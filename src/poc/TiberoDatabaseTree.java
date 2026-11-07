package poc;

import com.intellij.database.BasicModelRegistry;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.*;
import com.intellij.database.model.RawDataSource;
import com.intellij.database.model.basic.*;
import java.util.*;

/** DB links are native model objects; the Database tree renders its normal model families. */
public final class TiberoDatabaseTree {
    private TiberoDatabaseTree() {}

    static void update(RawDataSource source,BasicSchema schema,List<TiberoDbLinks.Entry> entries) {
        TiberoDbLinkStore.getInstance().update(source.getUniqueId(),schema.getName(),entries);
        update(schema,entries);
        // A loader may finish with a staging model after the live model has already been merged.
        var live=source.getModel();
        if(live instanceof BasicModel liveModel && live!=schema.getModel())
            for(var element:liveModel.basicTraverser())
                if(element instanceof BasicSchema target && target.getName().equals(schema.getName())) {
                    update(target,entries); break;
                }
    }
    static void update(BasicSchema schema,List<TiberoDbLinks.Entry> entries) {
        if(!(schema instanceof OraSchema) || !(schema.getModel() instanceof OraModel model))
            throw new IllegalStateException("Refresh this Tibero data source to load the DB-link model");
        model.modify(root -> {
            var target=root.getSchemas().createOrGet(schema.getName());
            reconcile(target,entries.stream().filter(e -> e.owner().equals(schema.getName())).toList());
            if(!"PUBLIC".equals(schema.getName())) {
                var shared=entries.stream().filter(e -> "PUBLIC".equals(e.owner())).toList();
                var publicSchema=root.getSchemas().find(s -> "PUBLIC".equals(s.getName()));
                if(publicSchema!=null || !shared.isEmpty()) reconcile(publicSchema!=null ? publicSchema : root.getSchemas().createOrGet("PUBLIC"),shared);
            }
        });
    }
    private static void reconcile(OraSchema schema,List<TiberoDbLinks.Entry> entries) {
        var expected=new LinkedHashMap<String,TiberoDbLinks.Entry>();
        entries.forEach(e -> expected.put(e.name(),e));
        schema.getDbLinks().remove(link -> !expected.containsKey(link.getName()));
        for(var entry:expected.values()) {
            var link=schema.getDbLinks().createOrGet(entry.name());
            link.setConnectUserName(entry.username());
            link.setRemoteDatabaseString(entry.host());
            link.setSyncPending(false);
        }
    }
    static List<TiberoDbLinks.Entry> entries(BasicSchema schema) {
        var source=BasicModelRegistry.instance().getDataSource(schema.getModel());
        if(source!=null) return TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),schema.getName());
        var result=new ArrayList<TiberoDbLinks.Entry>();
        if(schema.getModel() instanceof OraModel model)
            for(var owner:model.getRoot().getSchemas())
                if(owner.getName().equals(schema.getName()) || "PUBLIC".equals(owner.getName()))
                    for(var link:owner.getDbLinks()) result.add(entry(link));
        return List.copyOf(result);
    }
    private static TiberoDbLinks.Entry entry(OraDbLink node) {
        return new TiberoDbLinks.Entry(node.getSchema().getName(),node.getName(),node.getConnectUserName(),node.getRemoteDatabaseString(),null);
    }
    static Link link(Object node) {
        if(!(node instanceof OraDbLink link) || link.getModel().getDbms()!=GenericDbms.TIBERO) return null;
        var schema=link.getSchema();
        var entry=entries(schema).stream().filter(e -> e.owner().equals(schema.getName()) && e.name().equals(link.getName())).findFirst().orElseGet(() -> entry(link));
        return new Link(schema,entry);
    }
    record Link(BasicSchema schema,TiberoDbLinks.Entry entry) {}
}
