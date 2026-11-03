package poc;

import java.lang.reflect.*;
import kotlin.jvm.functions.*;
import com.intellij.database.DbmsExtension;
import com.intellij.openapi.Disposable;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.*;
import kotlin.sequences.Sequence;
import java.util.*;

/** Tibero-only native Database tree extension. No shared Generic/Oracle model is mutated. */
public final class TiberoDatabaseTree implements Disposable {
    static final String PREFIX=detect();
    private static String detect() {
        for(String p:List.of("com.intellij.database.explorer.structure.","com.intellij.database.view.structure.")) {
            try {
                for(var c:Class.forName(p+"DvFamilyHierarchyDescriptor").getConstructors()) if(c.getParameterCount()==6) return p;
            } catch(ClassNotFoundException ignored) {}
        }
        return null;
    }
    public TiberoDatabaseTree() {
        if(PREFIX==null) return;
        Object extension=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type("DvStructureExtension")},(p,m,a) -> switch(m.getName()) {
            case "getTransformDescriptors" -> descriptors(a==null || a.length==0 ? null : a[0]);
            case "clearCachesForDataSource" -> null;
            case "toString" -> "Tibero Database tree";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==a[0];
            default -> throw new UnsupportedOperationException(m.toString());
        });
        var bean=new DbmsExtension.Bean<Object>() { @Override public Object getInstance() { return extension; } };
        bean.dbmsStr="TIBERO"; bean.implementationClass=TiberoDatabaseTree.class.getName();
        com.intellij.openapi.extensions.ExtensionPointName.<DbmsExtension.Bean<Object>>create("com.intellij.database.databaseViewStructureExtension").getPoint().registerExtension(bean,this);
    }
    public static final class Startup implements com.intellij.ide.AppLifecycleListener {
        @Override public void appFrameCreated(List<String> args) { install(); }
    }
    @Override public void dispose() {}
    static void install() { com.intellij.openapi.application.ApplicationManager.getApplication().getService(TiberoDatabaseTree.class); }
    static Class<?> type(String name) { try { return Class.forName(PREFIX+name); } catch(ClassNotFoundException e) { throw new IllegalStateException(e); } }
    static Object construct(String name,Object... args) {
        for(var c:type(name).getConstructors()) if(c.getParameterCount()==args.length) {
            try { return c.newInstance(args); } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new IllegalStateException("Missing tree constructor: " + name);
    }
    private static final Map<BasicModel,Map<String,List<TiberoDbLinks.Entry>>> SNAPSHOTS=Collections.synchronizedMap(new WeakHashMap<>());

    static void update(RawDataSource source,BasicSchema schema,List<TiberoDbLinks.Entry> entries) {
        // Introspection may build a temporary model and merge it into a different live model.
        // Keep the successful catalogue associated with its data source across that merge.
        TiberoDbLinkStore.getInstance().update(source.getUniqueId(),schema.getName(),entries);
        update(schema,entries);
    }

    static void update(BasicSchema schema,List<TiberoDbLinks.Entry> entries) {
        synchronized(SNAPSHOTS) {
            SNAPSHOTS.computeIfAbsent(schema.getModel(),ignored -> new HashMap<>()).put(schema.getName(),List.copyOf(entries));
        }
    }
    static List<TiberoDbLinks.Entry> entries(BasicSchema schema) {
        var source=com.intellij.database.BasicModelRegistry.instance().getDataSource(schema.getModel());
        if(source!=null) {
            return TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),schema.getName());
        }
        synchronized(SNAPSHOTS) { return SNAPSHOTS.getOrDefault(schema.getModel(),Map.of()).getOrDefault(schema.getName(),List.of()); }
    }
    static Map<ObjectKind,Map<ObjectKind,Object>> descriptors(Object options) {
        Object hierarchy=construct("DvFamilyHierarchyDescriptor",ObjectKind.SCHEMA,
                (Function1<Family<? extends BasicElement>,Object>)(family -> sequence(namespaces(family,showAllNamespaces(options)))),
                (Function2<Family<?>,BasicNode,Object>)((family,node) -> sequence(children(node))),
                // A schema count of 0 makes the transform layer mark the schema as always-leaf, hiding its tables
                // whenever no link snapshot exists yet (every IDE start until refresh). Hidden groups stay hidden: fetch decides.
                (Function2<Family<?>,BasicNode,Integer>)((family,node) -> node instanceof BasicSchema ? Math.max(1,children(node).size()) : children(node).size()),
                (Function2<Family<?>,BasicNode,BasicNode>)((family,node) -> link(node)==null ? null : link(node).parent()),
                (Function1<BasicElement,String>)BasicElement::getDisplayName);
        Object relocation=construct("DvFamilyRelocationDescriptor",construct("HostFamilyKin",ObjectKind.DATABASE,ObjectKind.SCHEMA),hierarchy,
                (java.util.function.Predicate<HostFamily<?>>)(family -> true),(java.util.function.Predicate<BasicElement>)(element -> true));
        return Map.of(ObjectKind.DATABASE,Map.of(ObjectKind.SCHEMA,relocation),
                ObjectKind.SCHEMA,Map.of(ObjectKind.SCHEMA,hierarchy,ObjectKind.DATABASE,relocation));
    }
    // The hierarchy replaces the model layer's schema list, so repeat its introspection-scope filter here.
    static List<? extends BasicElement> namespaces(Family<? extends BasicElement> family,boolean showAll) {
        var all=family.jbi().toList();
        if(showAll || !(family instanceof HostFamily<?> host)) return all;
        var source=com.intellij.database.BasicModelRegistry.instance().getLocalDataSource(host.getHost().getModel());
        var scope=source==null ? null : source.getIntrospectionScope();
        if(scope==null) return all;
        if(scope.equals(com.intellij.database.util.TreePattern.EMPTY)) return List.of();
        return all.stream().filter(element -> com.intellij.database.dataSource.DataSourceSchemaMapping.matches(scope,element)).toList();
    }
    private static boolean showAllNamespaces(Object options) {
        if(options==null) return false;
        try { return (Boolean)type("DvViewOptions").getMethod("getShowAllNamespaces").invoke(options); }
        catch(ReflectiveOperationException e) { return false; }
    }
    static List<? extends BasicNode> children(BasicNode node) {
        // Schemas without visible links (or not refreshed yet) get no empty dblinks group.
        if(node instanceof BasicSchema schema) return entries(schema).isEmpty() ? List.of() : List.of(new Link(schema,null).node());
        var link=link(node);
        if(link!=null && link.entry==null) return entries(link.schema).stream().map(entry -> new Link(link.schema,entry).node()).toList();
        return List.of();
    }
    static Link link(Object node) {
        return node!=null && Proxy.isProxyClass(node.getClass()) && Proxy.getInvocationHandler(node) instanceof Link l ? l : null;
    }
    private static Object sequence(List<? extends BasicNode> nodes) {
        try {
            if(type("DvFamilyHierarchyDescriptor").getMethod("getDoFetchTopNodes").getGenericReturnType().getTypeName().contains("JBIterable")) return com.intellij.util.containers.JBIterable.from(nodes);
        } catch(NoSuchMethodException e) { throw new IllegalStateException(e); }
        return (Sequence<BasicNode>)(() -> new ArrayList<BasicNode>(nodes).iterator());
    }
    record Link(BasicSchema schema,TiberoDbLinks.Entry entry) implements InvocationHandler {
        BasicNode node() { return (BasicNode)Proxy.newProxyInstance(TiberoDatabaseTree.class.getClassLoader(),entry==null ? new Class<?>[]{type("DvExtraTreeNode")} : new Class<?>[]{type("DvExtraTreeNode"),TiberoDbLinkNode.class,com.intellij.pom.Navigatable.class},this); }
        BasicNode parent() { return entry==null ? schema : new Link(schema,null).node(); }
        // The view counts children only for model families, so the group carries its link count in its name.
        String name() { return entry==null ? ObjectKind.DB_LINK.getPluralPresentableName() + " (" + entries(schema).size() + ")" : entry.name() + ("PUBLIC".equals(entry.owner()) ? " [PUBLIC]" : ""); }
        public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
            return switch(method.getName()) {
                case "canNavigate", "canNavigateToSource" -> entry!=null;
                case "navigate" -> { TiberoDbLinkActions.open(this); yield null; }
                case "getModel" -> schema.getModel();
                case "getDisplayName", "toString" -> name();
                case "getFamily" -> schema.getParentFamily();
                case "getParentNode" -> parent();
                case "getTreeNodeRank" -> type("DvTreeNodeRank").getField(entry==null ? "TL_FAMILY" : "TL_ELEMENT").get(null);
                case "isAlwaysLeaf" -> entry!=null;
                case "isItselfMeaningful" -> true;
                case "getShowCount" -> entry==null;
                case "getHostKind" -> ObjectKind.DB_LINK;
                case "getIconItem" -> type("DvTreeIconItem").getField("GRAY_FOLDER_ICON").get(null);
                case "hashCode" -> hashCode();
                case "equals" -> equals(link(args[0]));
                default -> {
                    // Newer IDE navigation uses default interface methods before falling back to navigate().
                    if(method.isDefault()) yield InvocationHandler.invokeDefault(proxy,method,args==null?new Object[0]:args);
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        }
    }
}
