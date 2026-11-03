package poc;

import com.intellij.database.model.*;
import com.intellij.database.util.DasUtil;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Anonymous PL/SQL block for "Execute Routine": one variable per argument, a named-notation call and
 * DBMS_OUTPUT lines for the result and OUT values. IntelliJ's generic generator has no EXECUTE script.
 */
final class TiberoRoutineScript {
    private static final Pattern PLAIN=Pattern.compile("[A-Z][A-Z0-9_$#]*");
    // PL/SQL variables need a size even though parameters do not.
    private static final Set<String> SIZED=Set.of("VARCHAR2","VARCHAR","NVARCHAR2","NVARCHAR","CHAR","NCHAR","RAW");
    private static final Set<String> PRINTABLE=Set.of("NUMBER","INTEGER","INT","SMALLINT","DECIMAL","NUMERIC","FLOAT","REAL","DOUBLE PRECISION",
            "BINARY_INTEGER","PLS_INTEGER","BINARY_FLOAT","BINARY_DOUBLE","VARCHAR2","VARCHAR","NVARCHAR2","NVARCHAR","CHAR","NCHAR",
            "CLOB","NCLOB","DATE","TIMESTAMP","LONG","ROWID");

    static String block(DasRoutine routine) {
        var call=new StringBuilder();
        var declare=new StringBuilder();
        var after=new StringBuilder();
        var names=new HashSet<String>();
        var arguments=new ArrayList<DasArgument>();
        for(var argument:routine.getArguments())
            if(argument.getArgumentDirection()!=ArgumentDirection.RETURN && argument.getArgumentDirection()!=ArgumentDirection.RESULT) arguments.add(argument);
        arguments.sort(Comparator.comparingInt(DasArgument::getPosition));
        var result=routine.getRoutineKind()==DasRoutine.Kind.FUNCTION ? routine.getReturnArgument() : null;
        String resultVariable=result==null ? null : variable("result",names);
        if(result!=null) declare.append("  ").append(resultVariable).append(' ').append(declaration(type(result))).append(";\n");
        var actuals=new ArrayList<String>();
        for(var argument:arguments) {
            String name=argument.getName()==null || argument.getName().isBlank() ? "P" + argument.getPosition() : argument.getName();
            String v=variable(name,names);
            String type=type(argument);
            var direction=argument.getArgumentDirection();
            declare.append("  ").append(v).append(' ').append(declaration(type));
            if(direction!=ArgumentDirection.OUT) declare.append(" := NULL");
            declare.append("; -- ").append(direction==null ? "IN" : direction.name()).append(' ').append(name).append('\n');
            actuals.add(identifier(name) + " => " + v);
            if(direction==ArgumentDirection.OUT || direction==ArgumentDirection.INOUT) after.append(print(name,v,type));
        }
        call.append("  ");
        if(resultVariable!=null) call.append(resultVariable).append(" := ");
        call.append(qualifiedName(routine));
        if(!actuals.isEmpty()) call.append('(').append(String.join(", ",actuals)).append(')');
        call.append(";\n");
        if(resultVariable!=null) after.insert(0,print("RETURN",resultVariable,type(result)));
        return "-- Tibero: execute " + qualifiedName(routine) + ". Set the IN values, then run the whole block.\n"
                + (declare.isEmpty() ? "" : "DECLARE\n" + declare)
                + "BEGIN\n" + call + after + "END;\n";
    }
    static String qualifiedName(DasRoutine routine) {
        var parts=new ArrayList<String>();
        String schema=DasUtil.getSchema(routine);
        if(schema!=null && !schema.isEmpty()) parts.add(identifier(schema));
        var parent=routine.getDasParent();
        if(parent!=null && parent.getKind()==ObjectKind.PACKAGE) parts.add(identifier(parent.getName()));
        parts.add(identifier(routine.getName()));
        return String.join(".",parts);
    }
    static String identifier(String name) {
        return PLAIN.matcher(name).matches() ? name : '"' + name.replace("\"","\"\"") + '"';
    }
    private static String variable(String name,Set<String> used) {
        String base="v_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]","_");
        if(base.length()>28) base=base.substring(0,28); // Tibero/Oracle identifier limit 30
        String candidate=base;
        for(int i=2;!used.add(candidate);i++) candidate=base.substring(0,Math.min(base.length(),26)) + i;
        return candidate;
    }
    private static String type(DasTypedObject typed) {
        var type=typed==null ? null : typed.getDasType();
        String spec=type==null ? null : type.getSpecification();
        return spec==null || spec.isBlank() ? null : spec.trim();
    }
    static String declaration(String type) {
        if(type==null) return "VARCHAR2(32767) /* type unknown */";
        String upper=type.toUpperCase(Locale.ROOT);
        if(upper.equals("REF CURSOR") || upper.equals("SYS_REFCURSOR")) return "SYS_REFCURSOR";
        if(upper.equals("PL/SQL BOOLEAN")) return "BOOLEAN";
        if(SIZED.contains(upper)) return type + "(32767)";
        return type;
    }
    private static String print(String name,String variable,String type) {
        String label=name.replace("'","''");
        String base=type==null ? "" : type.toUpperCase(Locale.ROOT).replaceAll("\\s*\\(.*$","");
        if(base.equals("BOOLEAN") || base.equals("PL/SQL BOOLEAN"))
            return "  DBMS_OUTPUT.PUT_LINE('" + label + " = ' || CASE WHEN " + variable + " THEN 'TRUE' WHEN NOT " + variable + " THEN 'FALSE' END);\n";
        if(PRINTABLE.contains(base) || base.startsWith("TIMESTAMP") || base.startsWith("INTERVAL"))
            return "  DBMS_OUTPUT.PUT_LINE('" + label + " = ' || " + variable + ");\n";
        return "  -- " + label + " (" + (type==null ? "unknown type" : type) + ") is in " + variable + "\n";
    }
}
