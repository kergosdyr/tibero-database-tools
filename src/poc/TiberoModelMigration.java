package poc;

import com.intellij.database.dataSource.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.OraModel;
import com.intellij.database.model.*;
import com.intellij.database.model.serialization.*;
import com.intellij.database.util.TreePattern;
import com.intellij.database.util.TreePatternNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import org.jdom.Element;
import java.nio.file.*;
import java.util.*;

/** Migrates the old Generic cache before the public model loader reads it; keeps the original file. */
public final class TiberoModelMigration implements DataSourceModelStorage.Listener {
    @Override public void started(Project project) {}
    @Override public void finished(Project project) {}
    @Override public void failed(Project project,LocalDataSource source,Throwable failure) {}
    @Override public void started(Project project,LocalDataSource source) {
        if(source.getDbms()!=GenericDbms.TIBERO) return;
        var directory=DataSourceStorage.getStorageDir(project);
        if(directory==null || !source.getUniqueId().matches("[a-zA-Z0-9-]+")) return;
        try {
            migrateFile(Path.of(directory,source.getUniqueId()+".xml"),source.getName());
            var scope=source.getIntrospectionScope();
            var migrated=scope(scope);
            if(migrated!=scope) source.setIntrospectionScope(migrated);
        }
        catch(Exception failure) { Logger.getInstance(TiberoModelMigration.class).warn("Tibero cached model migration failed; original cache retained",failure); }
    }
    static TreePattern scope(TreePattern scope) {
        if(scope==null || scope.root.getGroup(ObjectKind.DATABASE)==null) return scope;
        var groups=new LinkedHashMap<ObjectKind,List<TreePatternNode>>();
        for(var group:scope.root.groups) {
            if(group.kind!=ObjectKind.DATABASE) groups.computeIfAbsent(group.kind,k -> new ArrayList<>()).addAll(List.of(group.children));
            else for(var database:group.children) {
                if(database.groups.length==0)
                    groups.computeIfAbsent(ObjectKind.SCHEMA,k -> new ArrayList<>()).add(new TreePatternNode(TreePatternNode.NegativeNaming.WILDCARD,TreePatternNode.NO_GROUPS));
                else for(var child:database.groups)
                    groups.computeIfAbsent(child.kind,k -> new ArrayList<>()).addAll(List.of(child.children));
            }
        }
        return new TreePattern(new TreePatternNode(scope.root.naming,groups.entrySet().stream()
                .map(e -> new TreePatternNode.Group(e.getKey(),e.getValue().toArray(TreePatternNode[]::new))).toArray(TreePatternNode.Group[]::new)));
    }
    @Override public void finished(Project project,LocalDataSource source,DasModel model,ModelImporter importer) {
        if(source.getDbms()!=GenericDbms.TIBERO || !(model instanceof OraModel nativeModel)) return;
        var saved=new ArrayList<>(nativeModel.getRoot().getSchemas().jbi().toList());
        for(var schema:saved) {
            var entries=TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),schema.getName());
            if(!entries.isEmpty()) TiberoDatabaseTree.update(schema,entries);
        }
    }
    static boolean migrate(ModelMem model) {
        if(model.getDbms()!=GenericDbms.TIBERO) return false;
        var databases=model.getNodes().stream().filter(n -> "database".equalsIgnoreCase(n.getKind())).toList();
        if(databases.isEmpty()) return false;
        if(databases.size()!=1) throw new IllegalArgumentException("Expected one cached Tibero database; refusing a partial migration");
        var database=databases.get(0);
        var root=model.getNodes().stream().filter(n -> Objects.equals(n.getId(),database.getParentId())).findFirst().orElseThrow();
        if(root.getParentId()!=null) throw new IllegalArgumentException("Unexpected cached Tibero model hierarchy");
        var properties=new LinkedHashMap<String,MutableNameAndValue>();
        root.getProperties().forEach(p -> properties.put(p.getName(),p));
        database.getProperties().forEach(p -> properties.put(p.getName(),p));
        root.setProperties(new ArrayList<>(properties.values()));
        root.setName(database.getName());
        for(var node:model.getNodes()) if(Objects.equals(node.getParentId(),database.getId())) node.setParentId(root.getId());
        var nodes=new ArrayList<>(model.getNodes()); nodes.remove(database); model.setNodes(nodes);
        return true;
    }
    static boolean migrateFile(Path file,String sourceName) throws Exception {
        if(!Files.isRegularFile(file)) return false;
        var document=JDOMUtil.load(file);
        var xml=document.getName().equals("database-model") ? document : document.getChild("database-model");
        if(xml==null || !"TIBERO".equalsIgnoreCase(xml.getAttributeValue("dbms")) || xml.getChildren("database").isEmpty()) return false;
        var memory=readMemory(xml);
        if(!migrate(memory)) return false;
        // Validate every cached object using the public importer before touching the original file.
        new ModelImporter(ModelFactory.BLACK_HOLE).importModel(memory);
        var retained=new HashMap<String,Element>();
        for(var node:xml.getChildren()) if(node.getAttributeValue("id")!=null) retained.put(node.getAttributeValue("id"),node);
        var ids=new HashSet<String>();
        for(var node:memory.getNodes()) {
            String id=node.getId().toString(); ids.add(id);
            var element=retained.get(id);
            if(node.getParentId()==null) element.removeAttribute("parent"); else element.setAttribute("parent",node.getParentId().toString());
            if(node.getName()!=null) element.setAttribute("name",node.getName());
            element.removeContent();
            for(var property:node.getProperties()) element.addContent(new Element(property.getName()).setText(property.getValue()));
        }
        for(var entry:retained.entrySet()) if(!ids.contains(entry.getKey())) entry.getValue().detach();
        var temporary=Files.createTempFile(file.getParent(),"tibero-model-",".tmp");
        try {
            Files.writeString(temporary,JDOMUtil.writeElement(document));
            // A migration never replaces its recovery copy, including after an interrupted previous attempt.
            var backup=file.resolveSibling(file.getFileName()+".tibero-generic-backup");
            if(!Files.exists(backup)) Files.copy(file,backup);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }
    static ModelMem readMemory(Element xml) {
        var nodes=new ArrayList<ModelElementMem>();
        for(var element:xml.getChildren()) {
            String id=element.getAttributeValue("id");
            if(id==null) throw new IllegalArgumentException("Unsupported nested cache format; original retained");
            var properties=new ArrayList<MutableNameAndValue>();
            for(var property:element.getChildren()) {
                if(property.getAttributeValue("id")!=null) throw new IllegalArgumentException("Unsupported nested cache hierarchy; original retained");
                properties.add(new MutableNameAndValue(property.getName(),property.getText()));
            }
            String parent=element.getAttributeValue("parent");
            nodes.add(new ModelElementMem(Integer.valueOf(id),parent==null ? null : Integer.valueOf(parent),element.getName(),element.getAttributeValue("name"),properties));
        }
        return new ModelMem(GenericDbms.TIBERO,com.intellij.database.util.Version.parse(xml.getAttributeValue("format-version")),nodes,new HashMap<>());
    }

}
