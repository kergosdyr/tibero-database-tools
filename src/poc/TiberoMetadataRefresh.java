package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.introspection.*;
import com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.ModFamily;
import com.intellij.openapi.progress.ProgressManager;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Collects catalogues through the JDBC metadata extension and commits them at the public load event. */
public final class TiberoMetadataRefresh implements DatabaseModelLoader.Listener {
    // Connection operations can run on a different worker from the lifecycle events.
    // A new source can also be UNKNOWN until that connection detects Tibero.
    private static final Map<DatabaseIntrospectionSession,Refresh> ACTIVE=new ConcurrentHashMap<>();

    @Override public void started(DatabaseIntrospectionSession session) {
        ACTIVE.put(session,new Refresh(session));
    }
    @Override public void finished(DatabaseIntrospectionSession session,DatabaseModelLoader.FinishedState state) {
        var refresh=ACTIVE.remove(session);
        if(refresh!=null && refresh.sessionRead && (state==DatabaseModelLoader.FinishedState.OK || state==DatabaseModelLoader.FinishedState.WARN))
            refresh.apply();
    }
    private static Refresh active(DatabaseConnectionCore connection) {
        String sourceId=connection.getConnectionPoint().getDataSource().getUniqueId();
        Refresh selected=null;
        for(var refresh:ACTIVE.values()) if(refresh.sourceId().equals(sourceId)) {
            // Never assign a catalogue to an unrelated concurrent refresh.
            if(selected!=null) return null;
            selected=refresh;
        }
        return selected;
    }
    static void connection(DatabaseConnectionCore connection) {
        var refresh=active(connection);
        if(refresh!=null) refresh.captureSession(connection);
    }
    static void structure(DatabaseConnectionCore connection,DatabaseMetaDataWrapper.Schema schema) {
        var refresh=active(connection);
        if(refresh!=null) refresh.captureStructure(connection,key(schema));
    }
    static void routines(DatabaseConnectionCore connection,DatabaseMetaDataWrapper.Schema schema,boolean arguments) {
        var refresh=active(connection);
        if(refresh==null) return;
        var key=key(schema);
        // JDBC marks the old families before asking its metadata wrapper for rows.
        // Their deletion belongs to our complete catalogue snapshot, including on JDBC failures.
        refresh.preserve(key,arguments);
        refresh.captureRoutines(connection,key);
    }
    private static Scope key(DatabaseMetaDataWrapper.Schema schema) { return new Scope(schema.database,schema.schema); }
    private record Scope(String database,String schema) {}
    private static final class Catalogue {
        boolean structureRead,routinesRead;
        List<TiberoSequences.Entry> sequences;
        List<TiberoDbLinks.Entry> links;
        List<TiberoStoredObjects.ObjectInfo> objects;
        List<TiberoRoutineCatalog.Signature> signatures;
        final Map<String,String> sources=new LinkedHashMap<>();
    }
    @FunctionalInterface private interface Read<T> { T get() throws SQLException,RemoteException; }
    private static final class Refresh {
        final DatabaseIntrospectionSession session;
        final Map<Scope,Catalogue> catalogues=new LinkedHashMap<>();
        boolean sessionRead;
        String currentSchema;
        Map<String,TiberoSynonyms.Target> synonyms;
        Refresh(DatabaseIntrospectionSession session) { this.session=session; }
        String sourceId() { return session.getLoaderContext().getDataSource().getUniqueId(); }
        BasicModModel model() { return session.getIntrospector().getModel(); }
        <T> T read(String label,Read<T> work) {
            ProgressManager.checkCanceled();
            try { return work.get(); }
            catch(SQLException|RemoteException|IllegalArgumentException failure) {
                IdeCompatibility.report(session.getIntrospector(),"Tibero " + label + "; previous catalogue retained",failure);
                return null;
            }
        }
        void captureSession(DatabaseConnectionCore connection) {
            if(sessionRead) return;
            sessionRead=true;
            currentSchema=read("current schema",() -> TiberoCurrentSchema.read(connection));
            synonyms=read("synonyms",() -> TiberoSynonyms.read(connection,currentSchema));
        }
        Catalogue captureStructure(DatabaseConnectionCore connection,Scope scope) {
            captureSession(connection);
            var data=catalogues.computeIfAbsent(scope,ignored -> new Catalogue());
            if(data.structureRead) return data;
            data.structureRead=true;
            data.links=read("DB links: " + scope.schema,() -> TiberoDbLinks.read(connection,scope.schema));
            data.sequences=read("sequences: " + scope.schema,() -> {
                var entries=TiberoSequences.readCatalog(connection,scope.schema);
                TiberoSequences.snapshot(scope.schema,entries);
                return entries;
            });
            return data;
        }
        void captureRoutines(DatabaseConnectionCore connection,Scope scope) {
            var data=captureStructure(connection,scope);
            if(data.routinesRead) return;
            data.routinesRead=true;
            data.objects=read("stored objects: " + scope.schema,() ->
                    TiberoStoredObjects.snapshot(scope.schema,TiberoStoredObjects.readCatalog(connection,scope.schema)));
            if(data.objects==null) return;
            if(Boolean.parseBoolean(System.getProperty("tibero.routine.details.enabled","true")))
                data.signatures=read("routine details: " + scope.schema,() -> TiberoRoutineReader.read(connection,scope.schema,data.objects));
            if(session.getIntrospector().getOptions().getSources()!=DBIntrospectionOptions.SourceLoading.NO_SOURCES) {
                for(var object:data.objects) {
                    var text=read("source: " + scope.schema + "." + object.name(),() -> TiberoViewLoader.readStoredSource(connection,scope.schema,object));
                    if(text!=null) data.sources.put(object.name(),text);
                }
            }
        }
        BasicSchema schema(Scope scope) {
            var model=model();
            if(model==null) return null;
            for(var element:model.basicTraverser()) {
                if(element instanceof BasicSchema schema && scope.schema.equals(schema.getName())) {
                    if(schema.getParent() instanceof BasicRoot || scope.database==null || scope.database.isEmpty()
                            || schema.getParent() instanceof BasicNamedElement parent && scope.database.equals(parent.getName())) return schema;
                }
            }
            return null;
        }
        void preserve(Scope scope,boolean arguments) {
            var schema=schema(scope);
            if(schema==null) return;
            model().modify(schema,BasicModSchema.class,mod -> {
                retain(mod.familyOf(ObjectKind.ROUTINE),arguments);
                var packages=mod.familyOf(ObjectKind.PACKAGE);
                retain(packages,false);
                if(packages!=null) for(var pkg:packages) retain(pkg.familyOf(ObjectKind.ROUTINE),arguments);
            });
        }
        void apply() {
            if(model()==null) return;
            if(model().getTextStorage() instanceof com.intellij.database.dataSource.srcStorage.DbSrcModelStorage storage) {
                try(var ignored=storage.startWriteSession(session.getLoaderContext().getProject(),model(),
                        com.intellij.openapi.application.ModalityState.nonModal(),false,true)) {
                    applyCatalogue();
                }
            } else applyCatalogue();
        }
        private void applyCatalogue() {
            ProgressManager.checkCanceled();
            if(currentSchema!=null) model().modify(BasicModRoot.class,root -> TiberoCurrentSchema.apply(root,currentSchema));
            if(synonyms!=null) TiberoSynonyms.getInstance().update(sourceId(),synonyms);
            for(var entry:catalogues.entrySet()) {
                ProgressManager.checkCanceled();
                var schema=schema(entry.getKey());
                if(schema==null) continue;
                var data=entry.getValue();
                model().modify(schema,BasicModSchema.class,mod -> {
                    TiberoColumnTypes.repair(mod);
                    if(data.links!=null) TiberoDatabaseTree.update(session.getLoaderContext().getDataSource(),mod,data.links);
                    if(data.sequences!=null) read("sequences: " + mod.getName(),() -> { TiberoSequences.refresh(mod,() -> data.sequences); return true; });
                    if(data.objects!=null) {
                        TiberoStoredObjects.apply(mod,data.objects);
                        if(data.signatures!=null) TiberoRoutineCatalog.apply(mod,data.signatures);
                    }
                    if(!data.sources.isEmpty()) model().writeSources(() -> {
                        for(var kind:List.of(ObjectKind.ROUTINE,ObjectKind.PACKAGE))
                            for(var object:mod.familyOf(kind))
                                if(object instanceof BasicModSourceAware source && object instanceof BasicNamedElement named
                                        && data.sources.containsKey(named.getName()))
                                    new TiberoViewLoader().applyStoredSource(source,data.sources.get(named.getName()));
                    });
                });
            }
        }
    }
    private static void retain(ModFamily<? extends BasicModElement> family,boolean arguments) {
        if(family==null) return;
        for(var element:family) {
            element.setSyncPending(false);
            if(arguments && element instanceof BasicModRoutine routine) {
                for(var argument:routine.getArguments()) argument.setSyncPending(false);
            }
        }
    }
}
