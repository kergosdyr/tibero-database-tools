package poc;

import java.lang.reflect.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.*;

final class NativeTreeVerification {
    static void run(Project project,BasicSchema schema) {
        Class<?> optionType=type("DvViewOptions");
        var options=Proxy.newProxyInstance(optionType.getClassLoader(),new Class<?>[]{optionType},(p,m,a) -> {
            if(m.getReturnType()==boolean.class) return m.getName().equals("getGroupSchemaObjects");
            if(m.getReturnType()==long.class) return 0L;
            if(m.getName().equals("getHiddenKinds")) return Set.of();
            throw new AssertionError(m.getName());
        });
        Object context;
        if(Arrays.stream(type("DvTreeContext").getConstructors()).anyMatch(c -> c.getParameterCount()==2)) context=construct("DvTreeContext",project,options);
        else context=construct("DvTreeContext",project,options,com.intellij.openapi.diagnostic.Logger.getInstance(NativeTreeVerification.class));
        var root=new Layer("DvTreeRootLayer",context);
        var model=new Layer("DvTreeModelLayer",root.value);
        Layer tree,layout;
        boolean layoutFirst=false;
        try {
            var t=construct("DvTreeTransformLayer");
            var l=construct("DvTreeLayoutLayer");
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
            staging.modify(com.intellij.database.dialects.oracle.model.OraRoot.class,r -> stagingSchema.set(r.getSchemas().createOrGet(schema.getName())));
            TiberoDatabaseTree.update(source,stagingSchema.get(),List.of(new TiberoDbLinks.Entry(schema.getName(),"REMOTE","APP","TB6",null),new TiberoDbLinks.Entry("PUBLIC","REMOTE","PUB","TB7",null)));
            var children=list(tree.childrenOf(schema));
            var group=children.stream().filter(n -> n instanceof com.intellij.database.model.families.Family<?> f && f.getMetaObject().kind==com.intellij.database.model.ObjectKind.DB_LINK).findFirst().orElseThrow(() -> new AssertionError("Native schema children omit DB links: " + children));
            var links=list(tree.childrenOf(group));
            if(links.size()!=1 || !links.get(0).getDisplayName().equals("REMOTE")) throw new AssertionError("Private native link: " + links);
            if(!(links.get(0) instanceof com.intellij.database.dialects.oracle.model.OraDbLink)) throw new AssertionError("DB link must be a real persistent model object");
            if(!(((com.intellij.database.model.families.Family<?>)group).size()==1)) throw new AssertionError("DB link family count");
            if(com.intellij.database.view.DbNodeDescriptorHelper.getNodeIcon(project,links.get(0))!=icons.DatabaseIcons.DatabaseLink) throw new AssertionError("DB link icon");
            if(!tree.parentOf(links.get(0)).equals(group) || !list(tree.childrenOf(links.get(0))).isEmpty()) throw new AssertionError("Native link parent/children");
            if(list(optimized.childrenOf(schema)).stream().noneMatch(n -> n.equals(group)) || list(optimized.childrenOf(group)).size()!=1) throw new AssertionError("Layout hides native DB links");
            var privateLink=TiberoDatabaseTree.link(links.get(0));
            if(privateLink==null || !TiberoDbLinkActions.ddl(privateLink.entry()).contains("CREATE DATABASE LINK")) throw new AssertionError("Private DDL navigation");
            var publicSchema=((com.intellij.database.dialects.oracle.model.OraModel)schema.getModel()).getRoot().getSchemas().find(s -> s.getName().equals("PUBLIC"));
            if(publicSchema==null || publicSchema.getDbLinks().size()!=1) throw new AssertionError("PUBLIC links have their actual owner schema");
            var publicLink=TiberoDatabaseTree.link(publicSchema.getDbLinks().first());
            if(publicLink==null || !TiberoDbLinkActions.ddl(publicLink.entry()).contains("CREATE PUBLIC DATABASE LINK")) throw new AssertionError("Public DDL navigation");
            checkDoubleClickPolicy(links.get(0),group);
            var action=TiberoDbLinkTreeNavigation.openAction(() -> privateLink);
            var event=com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action,null,"test",id -> com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.is(id) ? project : null);
            action.update(event);
            if(!event.getPresentation().isEnabled()) throw new AssertionError("DB link DDL action disabled");
            TiberoDatabaseTree.update(source,stagingSchema.get(),List.of());
            if(!list(tree.childrenOf(group)).isEmpty() || !publicSchema.getDbLinks().isEmpty()) throw new AssertionError("Native tree retains dropped links");
            checkObjectsVisibleWithoutLinks(tree,optimized,schema,"after links were dropped");
            checkSchemaScope(tree,source,schema);
            System.out.println("PASS: native Database tree renders real DB_LINK model families, private/public owners, counts, icon, DDL navigation, dropped links and schema scope");
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
    // Test-only access to the actual tree layers; none of these implementation classes ship in the plugin.
    private static final String PREFIX=detect();
    private static String detect() {
        for(var prefix:List.of("com.intellij.database.explorer.structure.","com.intellij.database.view.structure."))
            try { Class.forName(prefix+"DvTreeModelLayer"); return prefix; } catch(ClassNotFoundException ignored) {}
        throw new AssertionError("Missing Database tree implementation");
    }
    private static Class<?> type(String name) {
        try { return Class.forName(PREFIX+name); } catch(ClassNotFoundException e) { throw new AssertionError(e); }
    }
    private static Object construct(String name,Object... args) {
        for(var c:type(name).getConstructors()) if(c.getParameterCount()==args.length)
            try { return c.newInstance(args); } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        throw new AssertionError("Missing tree constructor: " + name);
    }
    private static final class Layer {
        final Object value;
        Layer(String name,Object base) {
            value=construct(name);
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
