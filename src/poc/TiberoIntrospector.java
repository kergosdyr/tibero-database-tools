package poc;

import com.intellij.database.Dbms;
import com.intellij.database.dataSource.*;
import com.intellij.database.dialects.base.introspector.jdbc.JdbcIntrospector;
import com.intellij.database.dialects.base.introspector.jdbc.wrappers.DatabaseMetaDataWrapper;
import com.intellij.database.introspection.*;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.ModFamily;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.containers.JBIterable;
import java.util.*;

/** Keeps the standard JDBC pipeline, then reconciles stored objects in visited schemas only. */
public final class TiberoIntrospector extends JdbcIntrospector {
    public static final class Factory implements DBIntrospector.Factory {
        @Override public DBIntrospector createIntrospector(DBIntrospectionContext context,Dbms dbms,ModelFactory models) {
            return new TiberoIntrospector(context,dbms,models);
        }
        @Override public boolean isSupported(com.intellij.database.util.Version version) { return true; }
        @Override public int getVersion(ObjectKind kind) { return 6; }
        @Override public boolean isOutdatedCheckSupported(BasicElement element) { return false; }
        @Override public boolean isNative() { return true; }
        @Override public boolean isIncremental() { return false; }
        @Override public boolean supportsMultilevelIntrospection() { return false; }
        @Override public boolean supportsFragmentIntrospection() { return false; }
    }
    private final Set<BasicModSchema> visited=new LinkedHashSet<>();
    private final Set<BasicModSchema> sequenceSchemas=new LinkedHashSet<>();
    private DatabaseConnectionCore connection;
    private int nesting;
    TiberoIntrospector(DBIntrospectionContext context,Dbms dbms,ModelFactory models) {
        super(context,new JdbcNature(),dbms,models);
    }
    @Override public void introspectTablesInSchema(DatabaseMetaDataWrapper metadata,BasicModSchema schema) {
        sequenceSchemas.add(schema);
        super.introspectTablesInSchema(metadata,schema);
    }
    @Override public void introspectColumnsInSchema(DatabaseMetaDataWrapper metadata,BasicModSchema schema) {
        sequenceSchemas.add(schema);
        super.introspectColumnsInSchema(metadata,schema);
    }
    @Override public JBIterable<ModFamily<?>> getRoutineFamilies(BasicModSchema schema) {
        visited.add(schema);
        sequenceSchemas.add(schema);
        return super.getRoutineFamilies(schema).transform(TiberoIntrospector::preserveUntilSnapshot);
    }
    static ModFamily<?> preserveUntilSnapshot(ModFamily<?> family) {
        // Keep iteration intact: JDBC's argument loader builds its routine lookup from these families.
        // Only routine/package deletion belongs to the later complete ALL_OBJECTS snapshot.
        return (ModFamily<?>)java.lang.reflect.Proxy.newProxyInstance(ModFamily.class.getClassLoader(),
                new Class<?>[]{ModFamily.class},(proxy,method,args) -> {
                    if(method.getName().equals("markChildrenAsSyncPending") || method.getName().equals("removeSyncPendingChildren")) return null;
                    try { return method.invoke(family,args); }
                    catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    // Final base task entry points dispatch here; nested auto/task calls reconcile once.
    private void run(Runnable work) {
        if (nesting++ > 0) {
            try { work.run(); } finally { nesting--; }
            return;
        }
        visited.clear();
        sequenceSchemas.clear();
        try {
            work.run();
            refreshSynonyms(markCurrentSchema());
            for(var schema:List.copyOf(sequenceSchemas)) {
                TiberoColumnTypes.repair(schema);
                refreshDbLinks(schema);
                ProgressManager.checkCanceled();
                try { TiberoSequences.refresh(schema,() -> TiberoSequences.readCatalog(connection,schema.getName())); }
                catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
                    IdeCompatibility.report(this,"Tibero sequences: " + schema.getName() + "; catalogue refresh failed",failure);
                }
            }
            for(var schema:List.copyOf(visited)) {
                ProgressManager.checkCanceled();
                try {
                    var objects=TiberoStoredObjects.snapshot(schema.getName(),read(schema.getName()));
                    // Read and validate both signature catalogues before changing the IDE model.
                    // They only refine overloads; the JDBC arguments already in the model stay if they fail.
                    List<TiberoRoutineCatalog.Signature> signatures=null;
                    if(Boolean.parseBoolean(System.getProperty("tibero.routine.details.enabled","true"))) {
                        try { signatures=TiberoRoutineReader.read(connection,schema.getName(),objects); }
                        catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
                            IdeCompatibility.report(this,"Tibero routine details: " + schema.getName() + "; catalogue refresh failed, JDBC arguments kept",failure);
                        }
                    }
                    ProgressManager.checkCanceled();
                    var details=signatures;
                    schema.getModel().modify(schema,BasicModSchema.class,mod -> {
                        TiberoStoredObjects.apply(mod,objects);
                        if(details!=null) TiberoRoutineCatalog.apply(mod,details);
                    });
                    loadStoredSources(schema);
                } catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
                    IdeCompatibility.report(this,"Tibero stored objects: " + schema.getName() + "; catalogue refresh failed, existing nodes retained",failure);
                }
            }
        } finally { visited.clear(); sequenceSchemas.clear(); nesting--; }
    }
    // Overrides JdbcIntrospector's login-name guess so unqualified console names resolve against the session schema.
    /** Returns the session schema, or null when it could not be read. */
    private String markCurrentSchema() {
        if(connection==null) return null;
        ProgressManager.checkCanceled();
        try {
            String name=TiberoCurrentSchema.read(connection);
            if(name!=null) getModel().modify(BasicModRoot.class,root -> TiberoCurrentSchema.apply(root,name));
            return name;
        } catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
            IdeCompatibility.report(this,"Tibero current schema: lookup failed, login name kept",failure);
            return null;
        }
    }
    // Once per refresh: PUBLIC synonyms do not belong to any refreshed schema. A failure keeps the previous map.
    private void refreshSynonyms(String schema) {
        if(connection==null) return;
        ProgressManager.checkCanceled();
        try { TiberoSynonyms.getInstance().update(connection.getConnectionPoint().getDataSource().getUniqueId(),TiberoSynonyms.read(connection,schema)); }
        catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
            IdeCompatibility.report(this,"Tibero synonyms: catalogue refresh failed, previous synonyms kept",failure);
        }
    }
    private void refreshDbLinks(BasicModSchema schema) {
        ProgressManager.checkCanceled();
        try { TiberoDatabaseTree.update(connection.getConnectionPoint().getDataSource(),schema,TiberoDbLinks.read(connection,schema.getName())); }
        catch(java.sql.SQLException | java.rmi.RemoteException | IllegalArgumentException failure) {
            IdeCompatibility.report(this,"Tibero DB links: " + schema.getName() + "; catalogue refresh failed",failure);
        }
    }
    private void loadStoredSources(BasicModSchema schema) {
        if(getOptions().getSources()==DBIntrospectionOptions.SourceLoading.NO_SOURCES) return;
        var loader=new TiberoViewLoader();
        var objects=new ArrayList<BasicModSourceAware>();
        for(var kind:List.of(ObjectKind.ROUTINE,ObjectKind.PACKAGE))
            for(var object:schema.familyOf(kind))
                if(object instanceof BasicModSourceAware source) objects.add(source);
        getModel().writeSources(() -> {
            for(var object:objects) {
                ProgressManager.checkCanceled();
                try { loader.loadSource(object,connection); }
                catch(com.intellij.openapi.progress.ProcessCanceledException canceled) { throw canceled; }
                catch(Exception failure) { IdeCompatibility.report(this,"Tibero source: " + schema.getName() + "." + object.getName(),failure); }
            }
        });
    }
    @FunctionalInterface interface SnapshotReader {
        List<TiberoStoredObjects.Entry> read() throws java.sql.SQLException,java.rmi.RemoteException;
    }
    static void refresh(BasicModSchema schema,SnapshotReader reader) throws java.sql.SQLException,java.rmi.RemoteException {
        var snapshot=TiberoStoredObjects.snapshot(schema.getName(),reader.read());
        ProgressManager.checkCanceled();
        schema.getModel().modify(schema,BasicModSchema.class,mod -> TiberoStoredObjects.apply(mod,snapshot));
    }
    List<TiberoStoredObjects.Entry> read(String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        if(connection==null) throw new IllegalStateException("Tibero introspector is not connected");
        return readCatalog(connection,schema);
    }
    static List<TiberoStoredObjects.Entry> readCatalog(DatabaseConnectionCore connection,String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        var statement=connection.getRemoteConnection().createStatement();
        Throwable failure=null;
        try {
            statement.setQueryTimeout(60);
            var rs=statement.executeQuery(TiberoStoredObjects.sql(schema));
            Throwable rowFailure=null;
            try {
                var rows=new ArrayList<TiberoStoredObjects.Entry>();
                while(rs.next()) {
                    ProgressManager.checkCanceled();
                    if(rows.size()>=100000) throw new java.sql.SQLException("Stored object snapshot exceeds 100000 rows; refusing partial refresh");
                    rows.add(new TiberoStoredObjects.Entry(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)));
                }
                return rows;
            } catch(java.sql.SQLException | java.rmi.RemoteException | RuntimeException | Error e) { rowFailure=e; throw e; }
            finally {
                try { rs.close(); } catch(java.sql.SQLException | java.rmi.RemoteException close) { if(rowFailure!=null) rowFailure.addSuppressed(close); else throw close; }
            }
        } catch(java.sql.SQLException | java.rmi.RemoteException | RuntimeException | Error e) { failure=e; throw e; }
        finally {
            try { statement.close(); } catch(java.sql.SQLException | java.rmi.RemoteException close) { if(failure!=null) failure.addSuppressed(close); else throw close; }
        }
    }
    @Override public void attachToDB(DatabaseConnectionCore value) { super.attachToDB(value); connection=value; }
    @Override public void detachFromDB() { try { super.detachFromDB(); } finally { connection=null; visited.clear(); sequenceSchemas.clear(); } }
    @Override protected void introspectInTran(com.intellij.database.layoutedQueries.DBTransaction transaction,IntrospectionTask task) {
        run(() -> super.introspectInTran(transaction,task));
    }
    @Override public void introspectAuto() { run(() -> super.introspectAuto()); }
    @Override public void introspectAuto(BasicNamespace[] areas) { run(() -> super.introspectAuto(areas)); }
    @Override public void introspectDatabaseSchemas(BasicDatabase... databases) { run(() -> super.introspectDatabaseSchemas(databases)); }
    @Override public void processDeferredTasks() { run(() -> super.processDeferredTasks()); }
}
