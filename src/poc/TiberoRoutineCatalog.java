package poc;

import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.ModFamily;
import java.util.*;

/** Validated catalogue signatures; MEMBER_NO is deliberately never joined to OVERLOAD. */
final class TiberoRoutineCatalog {
    record Member(String owner,String object,String name,String member,String function) {}
    record Argument(String owner,String name,String pkg,String overload,String position,String level,
                    String argument,String type,String direction,String typeOwner,String typeName,String typeSubname) {}
    record Key(String pkg,String name) {}
    record Param(String name,int position,ArgumentDirection direction,DataType type) {}
    record Signature(Key key,String overload,DasRoutine.Kind kind,List<Param> parameters) {}
    static String membersSql(String schema) {
        return "SELECT OWNER, OBJECT_NAME, PROCEDURE_NAME, MEMBER_NO, FUNCTIONABLE FROM ALL_PROCEDURES WHERE OWNER = "
                + TiberoCatalog.literal(schema) + " ORDER BY OBJECT_NAME, PROCEDURE_NAME, MEMBER_NO";
    }
    static String argumentsSql(String schema) {
        return "SELECT OWNER, OBJECT_NAME, PACKAGE_NAME, OVERLOAD, POSITION, DATA_LEVEL, ARGUMENT_NAME, DATA_TYPE, IN_OUT, TYPE_OWNER, TYPE_NAME, TYPE_SUBNAME FROM ALL_ARGUMENTS WHERE OWNER = "
                + TiberoCatalog.literal(schema) + " AND DATA_LEVEL = 0 ORDER BY PACKAGE_NAME, OBJECT_NAME, OVERLOAD, POSITION";
    }
    /**
     * Tibero 6 servers without ALL_ARGUMENTS.DATA_LEVEL (JDBC-8026). Record attribute rows, if listed, restart POSITION at 1,
     * so snapshot() rejects them as duplicate parameters and the JDBC arguments stay.
     */
    static String argumentsWithoutLevelSql(String schema) {
        return "SELECT OWNER, OBJECT_NAME, PACKAGE_NAME, OVERLOAD, POSITION, 0 AS DATA_LEVEL, ARGUMENT_NAME, DATA_TYPE, IN_OUT, TYPE_OWNER, TYPE_NAME, TYPE_SUBNAME FROM ALL_ARGUMENTS WHERE OWNER = "
                + TiberoCatalog.literal(schema) + " ORDER BY PACKAGE_NAME, OBJECT_NAME, OVERLOAD, POSITION";
    }
    static List<Signature> snapshot(String schema,List<TiberoStoredObjects.ObjectInfo> objects,List<Member> members,List<Argument> arguments) {
        Map<String,String> kinds=new HashMap<>();
        for(var o:objects) kinds.put(o.name(),o.type());
        var declarations=new LinkedHashMap<Key,Map<String,Boolean>>();
        for(var m:members) {
            if(!schema.equals(m.owner())) throw bad("Cross-schema member");
            String type=kinds.get(m.object());
            if(type==null) continue; // Type methods and other catalogue objects are outside this extension.
            boolean pkg=type.equals("PACKAGE");
            if(pkg && blank(m.name())) continue; // Package header.
            var key=new Key(pkg?m.object():null,pkg?m.name():m.object());
            if(blank(key.name()) || blank(m.member())) throw bad("Missing member identity");
            boolean function=Set.of("YES","Y","TRUE","1").contains(String.valueOf(m.function()));
            if(!function && !Set.of("NO","N","FALSE","0").contains(String.valueOf(m.function()))) throw bad("Unknown FUNCTIONABLE");
            var map=declarations.computeIfAbsent(key,k->new LinkedHashMap<>());
            if(map.put(m.member(),function)!=null) throw bad("Duplicate member");
        }
        var grouped=new LinkedHashMap<Key,Map<String,List<Param>>>();
        for(var a:arguments) {
            if(!schema.equals(a.owner())) throw bad("Cross-schema argument");
            var key=new Key(blank(a.pkg())?null:a.pkg(),a.name());
            if(!declarations.containsKey(key)) {
                if(kinds.containsKey(key.pkg()==null?key.name():key.pkg())) throw bad("Argument without declaration");
                continue;
            }
            if(!"0".equals(a.level())) throw bad("Nested argument in top-level query");
            int position=blank(a.position())?0:Integer.parseInt(a.position());
            if(position<0 || position>Short.MAX_VALUE) throw bad("Invalid argument position");
            ArgumentDirection direction=position==0?ArgumentDirection.RETURN:switch(String.valueOf(a.direction())) {
                case "IN" -> ArgumentDirection.IN; case "OUT" -> ArgumentDirection.OUT;
                case "IN/OUT", "IN OUT", "INOUT" -> ArgumentDirection.INOUT;
                default -> throw bad("Unknown parameter direction");
            };
            if(position>0 && blank(a.argument())) throw bad("Missing parameter name");
            if(blank(a.type())) throw bad("Missing parameter type");
            DataType dataType=blank(a.typeName()) ? new DataType(null,null,a.type(),null,false,java.sql.Types.OTHER)
                    : new DataType(a.typeOwner(),blank(a.typeSubname())?null:a.typeName(),
                        blank(a.typeSubname())?a.typeName():a.typeSubname(),null,true,java.sql.Types.OTHER);
            grouped.computeIfAbsent(key,k->new LinkedHashMap<>()).computeIfAbsent(blank(a.overload())?"single":a.overload(),k->new ArrayList<>())
                    .add(new Param(position==0?"":a.argument(),position,direction,dataType));
        }
        var result=new ArrayList<Signature>();
        for(var entry:declarations.entrySet()) {
            var variants=grouped.getOrDefault(entry.getKey(),Map.of());
            int functions=0, procedures=0;
            for(var variant:variants.entrySet()) {
                var params=new ArrayList<>(variant.getValue()); params.sort(Comparator.comparingInt(Param::position));
                var positions=new HashSet<Integer>(); var names=new HashSet<String>();
                for(var p:params) if(!positions.add(p.position()) || p.position()>0 && !names.add(p.name())) throw bad("Duplicate parameter");
                boolean function=params.get(0).direction()==ArgumentDirection.RETURN;
                if(function) functions++; else procedures++;
                result.add(new Signature(entry.getKey(),variant.getKey(),function?DasRoutine.Kind.FUNCTION:DasRoutine.Kind.PROCEDURE,List.copyOf(params)));
            }
            long declaredFunctions=entry.getValue().values().stream().filter(Boolean::booleanValue).count();
            long declaredProcedures=entry.getValue().size()-declaredFunctions;
            if(functions!=declaredFunctions || procedures>declaredProcedures || declaredProcedures-procedures>1)
                throw bad("Ambiguous or incomplete overload metadata for " + entry.getKey());
            if(declaredProcedures-procedures==1)
                result.add(new Signature(entry.getKey(),"no-arguments",DasRoutine.Kind.PROCEDURE,List.of()));
        }
        // Do not silently erase argument metadata when standalone declarations are missing.
        for(var o:objects) if(!o.type().equals("PACKAGE") && !declarations.containsKey(new Key(null,o.name())))
            throw bad("Missing declaration: " + o.name());
        var identities=new HashSet<String>();
        for(var signature:result) {
            if(!identities.add(signature.key().pkg()+"|"+identity(signature))) throw bad("Duplicate overload signature");
            if(signature.key().pkg()==null && !signature.kind().name().equals(kinds.get(signature.key().name())))
                throw bad("Standalone routine kind disagrees with ALL_OBJECTS");
        }
        return List.copyOf(result);
    }
    static void apply(BasicModSchema schema,List<Signature> signatures) {
        var byPackage=new HashMap<String,List<Signature>>();
        for(var s:signatures) byPackage.computeIfAbsent(s.key().pkg()==null?"":s.key().pkg(),k->new ArrayList<>()).add(s);
        applyFamily(schema.familyOf(ObjectKind.ROUTINE),byPackage.getOrDefault("",List.of()));
        for(var pkg:schema.familyOf(ObjectKind.PACKAGE))
            applyFamily(((BasicModNamedElement)pkg).familyOf(ObjectKind.ROUTINE),byPackage.getOrDefault(((BasicNamedElement)pkg).getName(),List.of()));
    }
    private static void applyFamily(ModFamily<? extends BasicModElement> family,List<Signature> signatures) {
        if(family==null) throw new IllegalStateException("Routine family unavailable");
        var existing=new HashMap<String,BasicModRoutine>();
        var comments=new HashMap<String,String>();
        for(var e:family) if(e instanceof BasicModRoutine r) { existing.put(identity(r),r); if(r.getComment()!=null) comments.put(r.getName(),r.getComment()); }
        var keep=new HashSet<BasicModRoutine>();
        for(var signature:signatures) {
            String key=identity(signature);
            var routine=existing.get(key);
            if(routine==null) {
                routine=(BasicModRoutine)family.createNewOne(); routine.setName(signature.key().name());
                routine.setRoutineKind(signature.kind()); routine.setComment(comments.get(signature.key().name()));
                for(var p:signature.parameters()) {
                    var a=p.direction()==ArgumentDirection.RETURN?routine.createOrGetReturnArgument():routine.getArguments().createNewOne();
                    a.setName(p.name()); a.setPosition((short)p.position()); a.setArgumentDirection(p.direction());
                    a.setStoredType(com.intellij.database.types.DasTypeUtilsKt.getTypeSystem(a).createDasType(p.type()));
                    a.setSyncPending(false);
                }
            }
            routine.setSyncPending(false); keep.add(routine);
        }
        family.remove(e->!keep.contains(e));
    }
    private static String identity(Signature s) { return s.key().name()+"|"+s.kind()+"|"+s.parameters(); }
    private static String identity(BasicModRoutine r) {
        var params=new ArrayList<Param>();
        for(var a:r.getArguments()) params.add(new Param(a.getName(),a.getPosition(),a.getArgumentDirection(),a.getDasType()==null?DataType.UNKNOWN:a.getDasType().toDataType()));
        params.sort(Comparator.comparingInt(Param::position));
        return r.getName()+"|"+r.getRoutineKind()+"|"+params;
    }
    static boolean blank(String s) { return s==null || s.isBlank(); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
