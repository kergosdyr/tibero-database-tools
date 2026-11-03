package poc;

import java.lang.reflect.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.*;

final class NativeTreeVerification {
    static void run(Project project,BasicSchema schema) {
        TiberoDatabaseTree.install();
        if(TiberoDatabaseTree.PREFIX==null) {
            System.out.println("LIMITATION: this IDE has no extra-node Database tree API; DB links are not shown in this IDE");
            return;
        }
        Class<?> optionType=TiberoDatabaseTree.type("DvViewOptions");
        var options=Proxy.newProxyInstance(optionType.getClassLoader(),new Class<?>[]{optionType},(p,m,a) -> {
            if(m.getReturnType()==boolean.class) return m.getName().equals("getGroupSchemaObjects");
            if(m.getReturnType()==long.class) return 0L;
            if(m.getName().equals("getHiddenKinds")) return Set.of();
            throw new AssertionError(m.getName());
        });
        Object context;
        if(Arrays.stream(TiberoDatabaseTree.type("DvTreeContext").getConstructors()).anyMatch(c -> c.getParameterCount()==2)) context=TiberoDatabaseTree.construct("DvTreeContext",project,options);
        else context=TiberoDatabaseTree.construct("DvTreeContext",project,options,com.intellij.openapi.diagnostic.Logger.getInstance(NativeTreeVerification.class));
        var root=new Layer("DvTreeRootLayer",context);
        var model=new Layer("DvTreeModelLayer",root.value);
        Layer tree,layout;
        boolean layoutFirst=false;
        try {
            var t=TiberoDatabaseTree.construct("DvTreeTransformLayer");
            var l=TiberoDatabaseTree.construct("DvTreeLayoutLayer");
            layoutFirst=((Number)t.getClass().getMethod("getLevel").invoke(t)).intValue()>((Number)l.getClass().getMethod("getLevel").invoke(l)).intValue();
        } catch(NoSuchMethodException ignored) {} catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        if(layoutFirst) { layout=new Layer("DvTreeLayoutLayer",model.value); tree=new Layer("DvTreeTransformLayer",layout.value); }
        else { tree=new Layer("DvTreeTransformLayer",model.value); layout=new Layer("DvTreeLayoutLayer",tree.value); }
        var optimized=new Layer("DvTreeOptimizationLayer",layoutFirst ? tree.value : layout.value);
        com.intellij.database.BasicModelRegistry.instance();
        var source=com.intellij.database.dataSource.LocalDataSource.temporary(); source.setModel(schema.getModel());
        source.setName("Tibero tree verification");
        source.setDatabaseDriver(com.intellij.database.dataSource.DatabaseDriverManager.getInstance().getDriver("tibero"));
        var manager=com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project);
        manager.addDataSource(source);
        try {
            if(com.intellij.database.BasicModelRegistry.instance().getDataSource(schema.getModel())==null) throw new AssertionError("Live model registration");
            checkObjectsVisibleWithoutLinks(tree,optimized,schema,"before any db link refresh (IDE start)");
            var staging=com.intellij.database.model.ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
            var stagingSchema=new java.util.concurrent.atomic.AtomicReference<BasicSchema>();
            staging.modify(com.intellij.database.dialects.generic.model.GenericRoot.class,r -> stagingSchema.set(r.getDatabases().createOrGet("TEST").getSchemas().createOrGet(schema.getName())));
            TiberoDatabaseTree.update(source,stagingSchema.get(),List.of(new TiberoDbLinks.Entry(schema.getName(),"REMOTE","APP","TB6",null),new TiberoDbLinks.Entry("PUBLIC","REMOTE","PUB","TB7",null)));
            var children=list(tree.childrenOf(schema));
            var group=children.stream().filter(n -> n.getDisplayName().startsWith(com.intellij.database.model.ObjectKind.DB_LINK.getPluralPresentableName())).findFirst().orElseThrow(() -> new AssertionError("Native schema children omit db link: " + children));
            if(!group.getDisplayName().equals(com.intellij.database.model.ObjectKind.DB_LINK.getPluralPresentableName() + " (2)")) throw new AssertionError("db link group shows its link count: " + group.getDisplayName());
            if(tree.parentOf(group)!=schema) throw new AssertionError("db link must be beneath actual schema");
            var links=list(tree.childrenOf(group));
            checkDoubleClickPolicy(links.get(0),group);
            if(links.size()!=2 || !links.get(0).getDisplayName().equals("REMOTE") || !links.get(1).getDisplayName().equals("REMOTE [PUBLIC]")) throw new AssertionError("Private/public native link nodes: " + links);
            if(com.intellij.database.view.DbNodeDescriptorHelper.getNodeIcon(project,links.get(0))!=icons.DatabaseIcons.DatabaseLink) throw new AssertionError("db link node icon is not the IDE database link icon");
            try {
                var method=com.intellij.database.view.DbNodeDescriptor.class.getDeclaredMethod("getNavigatable",Project.class,BasicNode.class);
                method.setAccessible(true);
                var navigation=(com.intellij.pom.Navigatable)method.invoke(null,project,links.get(0));
                if(navigation==null || !navigation.canNavigateToSource()) throw new AssertionError("Native DB link source navigation missing");
                for(var api:com.intellij.pom.Navigatable.class.getMethods())
                    if(api.getName().equals("navigationRequest") && api.getParameterCount()==0) {
                        try {
                            var application=com.intellij.openapi.application.ApplicationManager.getApplication();
                            application.executeOnPooledThread(()->application.runReadAction(()-> {
                                try { api.invoke(navigation); } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                            })).get(10,java.util.concurrent.TimeUnit.SECONDS);
                        } catch(Exception failure) { throw new AssertionError(failure); }
                    }
            } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
            if(!tree.parentOf(links.get(0)).equals(group) || !list(tree.childrenOf(links.get(0))).isEmpty()) throw new AssertionError("Native link parent/children");
            if(list(optimized.childrenOf(schema)).stream().noneMatch(n -> n.equals(group)) || list(optimized.childrenOf(group)).size()!=2) throw new AssertionError("Layout/optimization hides native DB link nodes");
            if(list(tree.childrenOf(schema.getParent())).stream().noneMatch(n -> n instanceof com.intellij.database.model.families.Family)) throw new AssertionError("Schema hierarchy preserved");
            // Simulate loss of process-local caches, as happens on every IDE restart.
            for(String fieldName:List.of("SNAPSHOTS")) {
                try {
                    var field=TiberoDatabaseTree.class.getDeclaredField(fieldName); field.setAccessible(true);
                    ((Map<?,?>)field.get(null)).clear();
                } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
            if(list(tree.childrenOf(group)).size()!=2) throw new AssertionError("DB links disappear after process-local caches are cleared (IDE restart)");
            TiberoDatabaseTree.update(source,stagingSchema.get(),List.of());
            if(!list(tree.childrenOf(group)).isEmpty()) throw new AssertionError("Native tree retains dropped links");
            if(list(tree.childrenOf(schema)).stream().anyMatch(n -> n.getDisplayName().startsWith(com.intellij.database.model.ObjectKind.DB_LINK.getPluralPresentableName())))
                throw new AssertionError("Schema without links still shows an empty db link group");
            checkObjectsVisibleWithoutLinks(tree,optimized,schema,"after links were dropped");
            checkSchemaScope(tree,source,schema);
            System.out.println("PASS: actual Database tree layers expose schema → db link → private/public links; the group shows its link count and links use the database link icon; schemas without links have no group but keep their objects expandable; schema scope respected");
        } finally { manager.removeDataSource(source); optimized.dispose(); layout.dispose(); tree.dispose(); model.dispose(); root.dispose(); }
    }
    private static void checkDoubleClickPolicy(BasicNode link,BasicNode group) {
        var root=new javax.swing.tree.DefaultMutableTreeNode(group);
        var leaf=new javax.swing.tree.DefaultMutableTreeNode(link); root.add(leaf);
        // Matches the real transform layer: DvExtraTreeNode is considered expandable even without children.
        var tree=new javax.swing.JTree(new javax.swing.tree.DefaultTreeModel(root,true));
        var path=new javax.swing.tree.TreePath(leaf.getPath()); tree.setSelectionPath(path);
        if(!com.intellij.util.EditSourceOnDoubleClickHandler.isExpandPreferable(tree,path)) throw new AssertionError("Double-click regression fixture must reproduce expansion");
        var disposable=com.intellij.openapi.util.Disposer.newDisposable();
        try {
            TiberoDbLinkTreeNavigation.install(tree,()->TiberoDatabaseTree.link(com.intellij.util.ui.tree.TreeUtil.getLastUserObject(tree.getSelectionPath())),disposable);
            if(com.intellij.util.EditSourceOnDoubleClickHandler.isExpandPreferable(tree,path)) throw new AssertionError("DB link double-click still expands instead of opening DDL");
            var groupPath=new javax.swing.tree.TreePath(root.getPath()); tree.setSelectionPath(groupPath);
            if(!com.intellij.util.EditSourceOnDoubleClickHandler.isExpandPreferable(tree,groupPath)) throw new AssertionError("DB link navigation changed group expansion");
        } finally { com.intellij.openapi.util.Disposer.dispose(disposable); }
    }
    // Without a link snapshot (every IDE start until refresh) the schema must still expand to its tables.
    private static void checkObjectsVisibleWithoutLinks(Layer tree,Layer optimized,BasicSchema schema,String when) {
        for(var layer:List.of(tree,optimized)) {
            if((Boolean)layer.invoke("isAlwaysLeaf",new Class<?>[]{BasicNode.class},schema)) throw new AssertionError("Schema becomes a leaf " + when + "; its objects are hidden");
            if(list(layer.childrenOf(schema)).stream().noneMatch(n -> n instanceof com.intellij.database.model.families.Family)) throw new AssertionError("Schema objects missing " + when);
        }
    }
    // The db link hierarchy supplies the schema list itself; it must keep the data source's schema filter.
    private static void checkSchemaScope(Layer tree,com.intellij.database.dataSource.LocalDataSource source,BasicSchema schema) {
        var schemas=schema.getParentFamily();
        var other=schemas.jbi().filter(s -> !s.equals(schema)).first();
        if(other==null) throw new AssertionError("Schema scope fixture needs a second schema");
        var previous=source.getIntrospectionScope();
        try {
            source.setIntrospectionScope(com.intellij.database.dataSource.DataSourceSchemaMapping.asPattern(com.intellij.database.util.ObjectPaths.of(schema)));
            var visible=list(tree.childrenOf(schemas));
            if(!visible.contains(schema) || visible.contains(other)) throw new AssertionError("Schema filter bypassed by db link tree: " + visible);
            source.setIntrospectionScope(com.intellij.database.util.TreePattern.EMPTY);
            if(!list(tree.childrenOf(schemas)).isEmpty()) throw new AssertionError("Empty schema scope shows schemas");
        } finally { source.setIntrospectionScope(previous); }
    }
    private static final class Layer {
        final Object value;
        Layer(String name,Object base) {
            value=TiberoDatabaseTree.construct(name);
            for(var m:value.getClass().getMethods()) if(m.getName().startsWith("setup$") && m.getParameterCount()==1 && m.getParameterTypes()[0].isInstance(base)) { call(m,base); return; }
            throw new AssertionError("Missing tree setup: " + name);
        }
        Object call(Method m,Object... args) {
            try { return m.invoke(value,args); } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        Object invoke(String name,Class<?>[] types,Object... args) {
            try { return call(value.getClass().getMethod(name,types),args); } catch(NoSuchMethodException e) { throw new AssertionError(e); }
        }
        Object childrenOf(BasicNode node) { return invoke("childrenOf",new Class<?>[]{BasicNode.class},node); }
        BasicNode parentOf(BasicNode node) { return (BasicNode)invoke("parentOf",new Class<?>[]{BasicNode.class},node); }
        void dispose() { ((com.intellij.openapi.Disposable)value).dispose(); }
    }
    private static List<BasicNode> list(Object seq) {
        var result=new ArrayList<BasicNode>();
        Iterator<?> it=seq instanceof Iterable<?> iterable ? iterable.iterator() : ((kotlin.sequences.Sequence<?>)seq).iterator();
        it.forEachRemaining(n -> result.add((BasicNode)n)); return result;
    }
}
