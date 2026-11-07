package poc;

import com.intellij.database.dataSource.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.*;
import com.intellij.database.dialects.base.introspector.jdbc.JdbcIntrospector;
import com.intellij.database.introspection.*;
import com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper;
import com.intellij.database.model.ModelFactory;
import com.intellij.database.model.basic.*;
import com.intellij.database.remote.jdbc.*;
import com.intellij.database.util.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import java.lang.reflect.*;
import java.util.*;

/** Drives the IDE's real JDBC pruning code and the registered public lifecycle listener. Verification artifact only. */
final class MetadataRefreshVerification {
    static void run(Project project) throws Exception {
        checkModelPersistence(project);
        for(boolean present:List.of(false,true)) {
            var rows=new java.util.ArrayDeque<DatabaseMetaDataWrapper.Schema>();
            rows.add(new DatabaseMetaDataWrapper.Schema("APP",null));
            if(present) rows.add(new DatabaseMetaDataWrapper.Schema("PUBLIC",null));
            var closed=new java.util.concurrent.atomic.AtomicBoolean();
            try(var schemas=TiberoMetadataWrapper.withPublicSchema(new com.intellij.database.jdbc.wrappers.ClosableIt<DatabaseMetaDataWrapper.Schema>() {
                @Override protected DatabaseMetaDataWrapper.Schema nextImpl() { return rows.isEmpty() ? stop() : rows.remove(); }
                @Override public void close() { closed.set(true); }
            })) {
                var names=new ArrayList<String>();
                while(schemas.hasNext()) names.add(schemas.next().schema);
                check(names.equals(List.of("APP","PUBLIC")),"Public schema survives namespace refresh without duplicate rows");
            }
            check(closed.get(),"Schema metadata cursor is closed");
        }
        var source=LocalDataSource.temporary();
        source.getInfo().setDbms(GenericDbms.TIBERO);
        source.setDatabaseDriver(DatabaseDriverManager.getInstance().getDriver("tibero"));
        var manager=LocalDataSourceManager.getInstance(project);
        ApplicationManager.getApplication().invokeAndWait(() -> manager.addDataSource(source));
        var model=com.intellij.database.dataSource.srcStorage.DbSrcModelStorageService.getInstance()
                .createFactory(project,source.getUniqueId()).createModel(GenericDbms.TIBERO);
        try(var ignored=((com.intellij.database.dataSource.srcStorage.DbSrcModelStorage)model.getTextStorage())
                .startWriteSession(project,model,ModalityState.nonModal(),false,true)) {
          model.modify(OraRoot.class,root -> {
            var schema=root.getSchemas().createOrGet("MixedCase");
            schema.getRoutines().createOrGet("Fn");
            schema.getPackages().createOrGet("Pkg").getRoutines().createOrGet("RUN");
            schema.getPackages().createOrGet("Empty");
            root.getSchemas().createOrGet("OTHER").getRoutines().createOrGet("KEEP");
          });
        }
        source.setModel(model);
        var session=new DatabaseIntrospectionSession(LoaderContext.selectNothing(project,source),new EmptyProgressIndicator(),ModalityState.nonModal()) {
            @Override protected void performDbSpecificOperation(ObjectPath path,boolean reconnect,
                    com.intellij.util.ThrowableConsumer<DatabaseConnection,Exception> operation) { throw new AssertionError("Unexpected new connection"); }
        };
        var introspector=session.getIntrospector();
        check(introspector instanceof JdbcIntrospector && introspector.getClass().getName().startsWith("com.intellij.database."),"Native JDBC factory selected");
        var sources=Arrays.stream(DBIntrospectionOptions.SourceLoading.values()).filter(s -> s!=DBIntrospectionOptions.SourceLoading.NO_SOURCES).findFirst().orElseThrow();
        introspector.setOptions(new DBIntrospectionOptions(sources,TreePattern.EMPTY));
        var actual=(OraModel)introspector.getModel();
        var schema=((OraRoot)actual.getRoot()).getSchemas().find(s -> s.getName().equals("MixedCase"));
        var originalPackage=schema.getPackages().find(p -> p.getName().equals("Empty"));
        var fixture=new Fixture(source,project);
        var listener=ApplicationManager.getApplication().getMessageBus().syncPublisher(DatabaseModelLoader.TOPIC);
        for(int pass=0;pass<4;pass++) {
            fixture.failed=pass==1;
            fixture.empty=pass>=2;
            if(pass==0) source.getInfo().setDbms(com.intellij.database.Dbms.UNKNOWN);
            listener.started(session);
            source.getInfo().setDbms(GenericDbms.TIBERO);
            // The real connection runner changes workers after publishing started().
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
              try(var ignored=((com.intellij.database.dataSource.srcStorage.DbSrcModelStorage)actual.getTextStorage())
                      .startWriteSession(project,actual,ModalityState.nonModal(),false,true)) {
                var wrapper=new TiberoMetadataWrapper(fixture.connection,fixture.metadata);
                actual.modify(schema,OraSchema.class,mod -> {
                    invokeJdbc(introspector,wrapper,mod,"introspectRoutinesInSchema");
                    invokeJdbc(introspector,wrapper,mod,"introspectPrototypesInSchema");
                });
              }
            }).get();
            listener.finished(session,pass==2 ? DatabaseModelLoader.FinishedState.CANCELLED : fixture.failed ? DatabaseModelLoader.FinishedState.WARN : DatabaseModelLoader.FinishedState.OK);
            if(pass<3) {
                check(schema.getPackages().find(p -> p.getName().equals("Empty"))==originalPackage,"Empty package identity survives JDBC pruning and catalogue failure");
                check(schema.getRoutines().size()==1 && schema.getPackages().size()==2,"Routines and packages retained on refresh/failure/cancellation");
                var fn=schema.getRoutines().first();
                check(fn.getReturnArgument()!=null,"Function return signature retained");
                check(schema.getPackages().find(p -> p.getName().equals("Pkg")).getRoutines().first().getName().equals("RUN"),"Package member retained");
                check(schema.getSequences().size()==1,"Sequence snapshot committed");
                check(TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),"MixedCase").size()==1,"DB link catalogue persisted by refresh");
                check(actual.loadSourceText(fn)!=null,"Stored source available after connection lifecycle ends");
            } else {
                check(schema.getRoutines().isEmpty() && schema.getPackages().isEmpty(),"Successful empty snapshot removes dropped stored objects");
                check(schema.getSequences().isEmpty() && TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),"MixedCase").isEmpty(),"Successful empty snapshot clears dropped sequences and links");
            }
        }
        check(((OraRoot)actual.getRoot()).getSchemas().find(s -> s.getName().equals("OTHER")).getRoutines().size()==1,"Unvisited schema retained");
        // Metadata calls outside an introspection session must not read extra catalogues or retain a connection.
        fixture.failed=true;
        int before=fixture.queries;
        new TiberoMetadataWrapper(fixture.connection,fixture.metadata);
        check(fixture.queries==before,"Lifecycle context cleared");
        fixture.audit.close();
        ApplicationManager.getApplication().invokeAndWait(() -> manager.removeDataSource(source));
        System.out.println("PASS: public metadata/listener pipeline across connection workers, native source storage and JDBC pruning, stored objects/signatures/sources, DB links/sequences, failure/cancellation/empty snapshots");
    }
    private static void checkModelPersistence(Project project) throws Exception {
        var old=com.intellij.database.dialects.generic.model.GenericMetaModel.MODEL.newModel();
        old.modify(root -> {
            var schema=root.getDatabases().createOrGet("TEST").getSchemas().createOrGet("SAVED");
            schema.setCurrent(true);
            schema.getTables().createOrGet("EMP").getColumns().createOrGet("ID");
            schema.getPackages().createOrGet("API").getRoutines().createOrGet("RUN");
        });
        var exporter=new com.intellij.database.model.serialization.ModelExporter();
        var previous=exporter.exportModel(old,null);
        previous.setDbms(GenericDbms.TIBERO);
        for(var n:previous.getNodes()) for(var p:n.getProperties()) if(p.getName().equals("Dbms")) p.setValue("TIBERO");
        checkMigrationFile(project,previous);
        check(TiberoModelMigration.migrate(previous),"Old Generic hierarchy needs migration");
        check(!TiberoModelMigration.migrate(previous),"Migration is idempotent");
        var restored=new com.intellij.database.model.serialization.ModelImporter(ModelFactory.BLACK_HOLE).importModel(previous);
        check(restored instanceof OraModel,"Old Generic cache migrates to the Tibero DB-link model");
        var schema=((OraModel)restored).getRoot().getSchemas().find(s -> s.getName().equals("SAVED"));
        check(schema!=null && schema.isCurrent() && schema.getTables().first().getColumns().size()==1 && schema.getPackages().first().getRoutines().size()==1,"Old cached schema, table, column, package and routine survive migration");
        var oldSchema=old.getRoot().getDatabases().first().getSchemas().first();
        var oldScope=com.intellij.database.dataSource.DataSourceSchemaMapping.asPattern(ObjectPaths.of(oldSchema));
        check(com.intellij.database.dataSource.DataSourceSchemaMapping.matches(TiberoModelMigration.scope(oldScope),schema),"Old schema selection survives migration");
        check(TiberoModelMigration.scope(TreePattern.EMPTY)==TreePattern.EMPTY,"Empty schema selection stays empty");
        TiberoDatabaseTree.update(schema,List.of(new TiberoDbLinks.Entry("SAVED","REMOTE","HR","remote",null),new TiberoDbLinks.Entry("PUBLIC","SHARED","HR","shared",null)));
        var roundTrip=(OraModel)new com.intellij.database.model.serialization.ModelImporter(ModelFactory.BLACK_HOLE).importModel(exporter.exportModel(restored,null));
        var saved=roundTrip.getRoot().getSchemas().find(s -> s.getName().equals("SAVED"));
        check(saved.getDbLinks().size()==1 && "remote".equals(saved.getDbLinks().first().getRemoteDatabaseString()),"Native DB link survives model serialization");
        check(roundTrip.getRoot().getSchemas().find(s -> s.getName().equals("PUBLIC")).getDbLinks().size()==1,"Public DB link survives model serialization");
        check(com.intellij.database.dialects.generic.model.GenericMetaModel.MODEL.newModel().getRoot().getDatabases().isEmpty(),"Shared Generic model unchanged");
        check(com.intellij.database.dialects.oracle.model.OraMetaModel.MODEL.newModel().getRoot().getSchemas().isEmpty(),"Shared Oracle model unchanged");
        System.out.println("PASS: old Generic model cache migration and native DB-link serialization, without changing other DBMS models");
    }
    private static void checkMigrationFile(Project project,com.intellij.database.model.serialization.ModelMem previous) throws Exception {
        var xml=new org.jdom.Element("database-model").setAttribute("serializer","dbm").setAttribute("dbms","TIBERO").setAttribute("family-id","TIBERO").setAttribute("format-version",previous.getVersion().toString());
        for(var node:previous.getNodes()) {
            var element=new org.jdom.Element(node.getKind()).setAttribute("id",node.getId().toString());
            if(node.getParentId()!=null) element.setAttribute("parent",node.getParentId().toString());
            if(node.getName()!=null) element.setAttribute("name",node.getName());
            for(var property:node.getProperties()) element.addContent(new org.jdom.Element(property.getName()).setText(property.getValue()));
            xml.addContent(element);
        }
        var source=LocalDataSource.temporary(); source.getInfo().setDbms(GenericDbms.TIBERO);
        var file=java.nio.file.Path.of(DataSourceStorage.getStorageDir(project),source.getUniqueId()+".xml");
        java.nio.file.Files.createDirectories(file.getParent());
        String original=com.intellij.openapi.util.JDOMUtil.writeElement(new org.jdom.Element("dataSource").addContent(xml));
        java.nio.file.Files.writeString(file,original);
        var listener=ApplicationManager.getApplication().getMessageBus().syncPublisher(DataSourceModelStorage.TOPIC);
        listener.started(project,source);
        var backup=file.resolveSibling(file.getFileName()+".tibero-generic-backup");
        check(java.nio.file.Files.readString(backup).equals(original),"Migration retains an exact original cache backup");
        var changed=com.intellij.openapi.util.JDOMUtil.load(file).getChild("database-model");
        var loaded=(OraModel)new com.intellij.database.model.serialization.ModelImporter(ModelFactory.BLACK_HOLE).importModel(TiberoModelMigration.readMemory(changed));
        check(loaded.getRoot().getSchemas().first().getTables().size()==1,"Registered pre-load listener migrates a real cache file");
        check(!TiberoModelMigration.migrateFile(file,"test"),"File migration runs only once");
        var link=new TiberoDbLinks.Entry("SAVED","FROM_OLD_STORE","HR","remote",null);
        TiberoDbLinkStore.getInstance().update(source.getUniqueId(),"SAVED",List.of(link));
        listener.finished(project,source,loaded,null);
        check(loaded.getRoot().getSchemas().first().getDbLinks().size()==1,"Stored DB-link catalogue repopulates the migrated native model before display");
    }
    private static void invokeJdbc(DBIntrospector introspector,DatabaseMetaDataWrapper metadata,BasicModSchema schema,String name) {
        try {
            // Reaches the real JDBC call site without starting a remote process in this contract test.
            var method=JdbcIntrospector.class.getDeclaredMethod(name,DatabaseMetaDataWrapper.class,BasicModSchema.class);
            method.setAccessible(true); method.invoke(introspector,metadata,schema);
        } catch(InvocationTargetException e) { throw new AssertionError("Native JDBC refresh failed",e.getCause()); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static final class Fixture {
        boolean failed,empty;
        int queries;
        final RemoteDatabaseMetaData metadata;
        final DatabaseConnectionCore connection;
        final com.intellij.database.dataSource.connection.audit.AdvancedAuditService audit;
        Fixture(LocalDataSource source,Project project) {
            metadata=proxy(RemoteDatabaseMetaData.class,(o,m,a) -> switch(m.getName()) {
                case "getCatalogTerm" -> "";
                case "getSchemaTerm" -> "schema";
                case "getIdentifierQuoteString" -> "\"";
                case "getSearchStringEscape" -> "\\";
                case "getProcedures","getFunctions" -> {
                    if(failed) throw new java.sql.SQLException("JDBC routine catalogue unavailable");
                    yield StoredObjectsVerification.rows(new String[]{"PROCEDURE_CAT","PROCEDURE_SCHEM","PROCEDURE_NAME","RESERVED1","RESERVED2","RESERVED3","REMARKS","PROCEDURE_TYPE","SPECIFIC_NAME"},List.of());
                }
                case "getProcedureColumns" -> {
                    if(failed) throw new java.sql.SQLException("JDBC argument catalogue unavailable");
                    yield StoredObjectsVerification.rows(new String[]{"PROCEDURE_CAT","PROCEDURE_SCHEM","PROCEDURE_NAME","COLUMN_NAME","COLUMN_TYPE","DATA_TYPE","TYPE_NAME","PRECISION","LENGTH","SCALE","RADIX","NULLABLE","REMARKS","COLUMN_DEF","SQL_DATA_TYPE","SQL_DATETIME_SUB","CHAR_OCTET_LENGTH","ORDINAL_POSITION","IS_NULLABLE","SPECIFIC_NAME"},List.of());
                }
                default -> { if(m.getReturnType()==boolean.class) yield false; throw new AssertionError("Unexpected metadata: " + m.getName()); }
            });
            var remote=proxy(RemoteConnection.class,(o,m,a) -> {
                if(m.getName().equals("createStatement")) return proxy(RemoteStatement.class,(so,sm,sa) -> switch(sm.getName()) {
                    case "setQueryTimeout","setFetchSize","close" -> null;
                    case "executeQuery" -> query((String)sa[0]);
                    default -> throw new AssertionError(sm.getName());
                });
                throw new AssertionError(m.getName());
            });
            var point=proxy(DatabaseConnectionPoint.class,(o,m,a) -> {
                if(m.getName().equals("getDataSource")) return source;
                throw new AssertionError(m.getName());
            });
            var auditRef=new java.util.concurrent.atomic.AtomicReference<com.intellij.database.dataSource.connection.audit.AdvancedAuditService>();
            connection=proxy(DatabaseConnectionCore.class,(o,m,a) -> switch(m.getName()) {
                case "getDbms" -> GenericDbms.TIBERO;
                case "getConnectionPoint" -> point;
                case "getRemoteConnection" -> remote;
                case "getRemoteMetaData" -> metadata;
                case "getVersion","getDriverVersion" -> Version.of(6);
                case "getAuditService" -> auditRef.get();
                default -> throw new AssertionError(m.getName());
            });
            audit=new com.intellij.database.dataSource.connection.audit.AdvancedAuditService(connection,
                    () -> com.intellij.database.dataSource.connection.audit.AuditService.compose(),project);
            auditRef.set(audit);
        }
        RemoteResultSet query(String sql) throws Exception {
            queries++;
            List<String[]> values;
            int count;
            if(sql.contains("SYS_CONTEXT") || sql.equals("SELECT USER FROM DUAL")) { count=1; values=List.<String[]>of(new String[]{"MixedCase"}); }
            else if(sql.contains("ALL_SYNONYMS")) { count=4; values=List.of(); }
            else {
                if(failed) throw new java.sql.SQLException("catalogue unavailable");
                if(sql.contains("ALL_DB_LINKS")) { count=5; values=List.<String[]>of(new String[]{"MixedCase","REMOTE","APP","TB6",null}); }
                else if(sql.contains("ALL_SEQUENCES")) { count=6; values=List.<String[]>of(new String[]{"MixedCase","SEQ","1","10","1","99999"}); }
                else if(sql.contains("ALL_OBJECTS")) { count=4; values=List.of(new String[]{"MixedCase","Fn","FUNCTION","VALID"},new String[]{"MixedCase","Pkg","PACKAGE","VALID"},new String[]{"MixedCase","Empty","PACKAGE","VALID"}); }
                else if(sql.contains("ALL_PROCEDURES")) { count=5; values=List.of(new String[]{"MixedCase","Fn",null,"1","YES"},new String[]{"MixedCase","Pkg","RUN","1","NO"}); }
                else if(sql.contains("ALL_ARGUMENTS")) { count=12; values=List.<String[]>of(new String[]{"MixedCase","Fn",null,null,"0","0",null,"NUMBER","OUT",null,null,null}); }
                else if(sql.contains("ALL_SOURCE")) { count=1; values=List.<String[]>of(new String[]{sql.contains("'FUNCTION'") ? "FUNCTION Fn RETURN NUMBER IS BEGIN RETURN 1; END;" : "PACKAGE Pkg AS PROCEDURE RUN; END;"}); }
                else throw new AssertionError(sql);
                if(empty) values=List.of();
            }
            var names=new String[count]; for(int i=0;i<count;i++) names[i]="C"+i;
            return StoredObjectsVerification.rows(names,new ArrayList<Object[]>(values));
        }
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args) -> switch(method.getName()) {
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object==args[0];
            case "toString" -> type.getSimpleName() + " fixture";
            default -> handler.invoke(object,method,args);
        }));
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
