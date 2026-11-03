package mock.tibero;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * DatabaseMetaData answers built from the SQL inside the real Tibero 6 JDBC driver (read at runtime from
 * com.tmax.tibero.jdbc.util.TbDatabaseMetaQuery, never copied). Parameters are bound exactly as TbDatabaseMetaData binds
 * them, so driver defects such as the broken getFunctionColumns SQL reproduce against the mock.
 */
final class DriverSql {
    static final Object DELEGATE=new Object();
    private static final String ESCAPE="/";
    private static Class<?> queries;

    private static String sql(String field) throws SQLException {
        try { var f=queries().getDeclaredField(field); f.setAccessible(true); return (String)f.get(null); }
        catch(ReflectiveOperationException e) { throw new SQLException("[mock] TbDatabaseMetaQuery." + field + " unavailable: " + e); }
    }
    private static String sql(String method,Class<?>[] types,Object... args) throws SQLException {
        try { var m=queries().getDeclaredMethod(method,types); m.setAccessible(true); return (String)m.invoke(null,args); }
        catch(ReflectiveOperationException e) { throw new SQLException("[mock] TbDatabaseMetaQuery." + method + " unavailable: " + e); }
    }
    private static synchronized Class<?> queries() throws SQLException {
        if(queries==null) {
            try { queries=Class.forName("com.tmax.tibero.jdbc.util.TbDatabaseMetaQuery",true,DriverSql.class.getClassLoader()); }
            catch(ClassNotFoundException e) {
                throw new SQLException("[mock] Put the Tibero 6 JDBC driver JAR (tibero6-jdbc.jar) next to the mock on the driver classpath; its metadata SQL is reused.");
            }
        }
        return queries;
    }

    static Object answer(Session s,String name,Object[] a) throws SQLException {
        return switch(name) {
            case "getDatabaseProductName" -> "Tibero";
            case "getDatabaseProductVersion" -> "Tibero 6 (mock on H2)";
            case "getDatabaseMajorVersion" -> 6;
            case "getDatabaseMinorVersion" -> 0;
            case "getDriverName" -> "Tibero JDBC Driver (mock)";
            case "getDriverVersion" -> "6.0";
            case "getDriverMajorVersion" -> 6;
            case "getDriverMinorVersion" -> 0;
            case "getCatalogTerm","getCatalogSeparator" -> "";
            case "getSchemaTerm" -> "schema";
            case "getProcedureTerm" -> "procedure";
            case "getSearchStringEscape" -> ESCAPE;
            case "getIdentifierQuoteString" -> "\"";
            case "getExtraNameCharacters" -> "$#";
            case "supportsCatalogsInTableDefinitions","supportsCatalogsInDataManipulation","supportsCatalogsInProcedureCalls","supportsCatalogsInIndexDefinitions","supportsCatalogsInPrivilegeDefinitions" -> false;
            case "getCatalogs" -> query(s,sql("QUERY_CATALOGS"));
            case "getSchemas" -> a==null || a.length==0 ? query(s,sql("QUERY_SCHEMAS_01") + sql("QUERY_SCHEMAS_02"))
                    : query(s,sql("QUERY_SCHEMAS_01") + " WHERE USERNAME LIKE ? ESCAPE '/' " + sql("QUERY_SCHEMAS_02"),a[1]==null ? "%" : a[1]);
            case "getTableTypes" -> query(s,sql("QUERY_TABLETYPES"));
            case "getTables" -> {
                var b=new StringBuilder(sql("QUERY_TABLES"));
                String[] types=(String[])a[3];
                if(types!=null) { b.append(" AND o.object_type IN ( "); for(int i=0;i<types.length;i++) b.append(i==0 ? "'" : ", '").append(types[i]).append("'"); b.append(") "); }
                else b.append(" AND o.object_type IN ('TABLE', 'SYNONYM', 'VIEW') ");
                b.append(" ORDER BY TABLE_TYPE, TABLE_SCHEM, TABLE_NAME");
                yield query(s,b.toString(),or(a[1]),or(a[2]));
            }
            case "getColumns" -> query(s,sql("getTableColumnsQuery",new Class<?>[]{boolean.class,boolean.class},false,false),91,or(a[1]),or(a[2]),or(a[3]));
            case "getPrimaryKeys" -> query(s,sql("QUERY_PRIMARYKEY"),a[2],or(a[1]));
            case "getIndexInfo" -> {
                String schema=(String)a[1];
                boolean owner=schema!=null && !schema.isEmpty();
                var b=new StringBuilder(sql("QUERY_INDEXINFO01"));
                if(owner) b.append(" and owner = ? ");
                b.append(" union ").append(sql("QUERY_INDEXINFO02"));
                if(owner) b.append(" and i.owner = ? ");
                if((Boolean)a[3]) b.append(" and i.uniqueness = 'UNIQUE' ");
                b.append(sql("QUERY_INDEXINFO03"));
                yield owner ? query(s,b.toString(),a[2],schema,a[2],schema) : query(s,b.toString(),a[2],a[2]);
            }
            case "getImportedKeys" -> related(s,null,null,(String)a[1],(String)a[2],"ORDER BY pktable_schem, pktable_name, key_seq");
            case "getExportedKeys" -> related(s,(String)a[1],(String)a[2],null,null,"ORDER BY fktable_schem, fktable_name, key_seq");
            case "getCrossReference" -> related(s,(String)a[1],(String)a[2],(String)a[4],(String)a[5],"ORDER BY fktable_schem, fktable_name, key_seq");
            case "getProcedures" -> procedures(s,(String)a[0],qualifiedSchema(s,(String)a[1]),(String)a[2]);
            case "getProcedureColumns" -> procedureColumns(s,(String)a[0],qualifiedSchema(s,(String)a[1]),(String)a[2],(String)a[3]);
            case "getFunctions" -> functions(s,(String)a[0],(String)a[1],(String)a[2]);
            case "getFunctionColumns" -> functionColumns(s,(String)a[0],(String)a[1],(String)a[2],(String)a[3]);
            default -> DELEGATE;
        };
    }
    private static String or(Object v) { return v==null ? "%" : (String)v; }
    private static String qualifiedSchema(Session s,String schema) { return schema==null ? "%" : schema.isEmpty() ? s.user : schema; }
    private static ResultSet related(Session s,String pkSchema,String pkTable,String fkSchema,String fkTable,String order) throws SQLException {
        var b=new StringBuilder(sql("QUERY_RELATEDKEYS")); var args=new ArrayList<Object>();
        if(pkTable!=null) { b.append(" AND p.table_name \t= ? "); args.add(pkTable); }
        if(fkTable!=null) { b.append(" AND f.table_name \t= ? "); args.add(fkTable); }
        if(pkSchema!=null && !pkSchema.isEmpty()) { b.append(" AND p.owner \t= ? "); args.add(pkSchema); }
        if(fkSchema!=null && !fkSchema.isEmpty()) { b.append(" AND f.owner \t= ? "); args.add(fkSchema); }
        b.append(order);
        return query(s,b.toString(),args.toArray());
    }
    private static ResultSet procedures(Session s,String catalog,String schema,String name) throws SQLException {
        String object=name==null ? "%" : name;
        var b=new StringBuilder(sql("QUERY_PROCEDURES"));
        if(catalog==null) {
            b.append("   AND (OBJECT_NAME LIKE ? ESCAPE '/' OR        PROCEDURE_NAME LIKE ? ESCAPE '/') ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME");
            return query(s,b.toString(),schema,object,object);
        }
        if(catalog.isEmpty()) { b.append("   AND OBJECT_NAME LIKE ? ESCAPE '/'   AND PROCEDURE_NAME IS NULL"); return query(s,b.toString(),schema,object); }
        b.append("   AND OBJECT_NAME LIKE ? ESCAPE '/'   AND PROCEDURE_NAME LIKE ? ESCAPE '/' ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME");
        return query(s,b.toString(),schema,catalog,object);
    }
    private static ResultSet procedureColumns(Session s,String catalog,String schema,String name,String column) throws SQLException {
        var b=new StringBuilder(sql("getProcedureColumnsQuery",new Class<?>[]{boolean.class},false));
        String object=name==null ? "%" : name, col=column==null ? "%" : column;
        if(catalog!=null && catalog.isEmpty()) b.append("   AND PACKAGE_NAME IS NULL");
        else if(catalog!=null) b.append("   AND PACKAGE_NAME LIKE ? ESCAPE '/'");
        b.append("   AND OBJECT_NAME LIKE ? ESCAPE '/'");
        if(col.equals("%")) b.append("   AND (ARGUMENT_NAME LIKE ? ESCAPE '/'         OR (ARGUMENT_NAME IS NULL AND DATA_TYPE IS NOT NULL))");
        else b.append("   AND ARGUMENT_NAME LIKE ? ESCAPE '/' ");
        b.append("   ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME");
        return catalog!=null && !catalog.isEmpty() ? query(s,b.toString(),schema,catalog,object,col) : query(s,b.toString(),schema,object,col);
    }
    private static ResultSet functions(Session s,String catalog,String schema,String name) throws SQLException {
        String owner=schema==null ? "%" : schema.isEmpty() ? s.user : schema, object=name==null ? "%" : name;
        String order="ORDER BY FUNCTION_SCHEM, FUNCTION_NAME\n";
        if(catalog==null) return query(s,sql("QUERY_FUNCTIONS_STANDALONE") + "UNION ALL " + sql("QUERY_FUNCTIONS_PKG") + "  AND PACKAGE_NAME IS NOT NULL\n"
                + "  AND OWNER LIKE ? ESCAPE '/'\n  AND OBJECT_NAME LIKE ? ESCAPE '/'\n" + order,owner,object,owner,object);
        if(catalog.isEmpty()) return query(s,sql("QUERY_FUNCTIONS_STANDALONE"),owner,object);
        return query(s,sql("QUERY_FUNCTIONS_PKG") + "  AND PACKAGE_NAME LIKE ? ESCAPE '/'\n  AND OWNER LIKE ? ESCAPE '/'\n  AND OBJECT_NAME LIKE ? ESCAPE '/'\n" + order,catalog,owner,object);
    }
    private static ResultSet functionColumns(Session s,String catalog,String schema,String name,String column) throws SQLException {
        String owner=schema==null ? "%" : schema.isEmpty() ? s.user : schema, object=name==null ? "%" : name;
        var filter=new StringBuilder(); String col=column;
        if(column!=null && !column.equals("%")) filter.append("  AND ARG.ARGUMENT_NAME LIKE ? ESCAPE '/'\n");
        else { col="%"; filter.append("  AND (ARG.ARGUMENT_NAME LIKE ? ESCAPE '/'\n       OR (ARG.ARGUMENT_NAME IS NULL\n           AND ARG.DATA_TYPE IS NOT NULL))\n"); }
        String base=sql("getFunctionColumnsQuery",new Class<?>[]{boolean.class},false), order="ORDER BY FUNCTION_SCHEM, FUNCTION_NAME\n";
        if(catalog==null) return query(s,base + filter + order,owner,object,col);
        if(catalog.isEmpty()) return query(s,base + "  AND ARG.PACKAGE_NAME IS NULL\n" + filter + order,owner,object,col);
        return query(s,base + "  AND ARG.PACKAGE_NAME LIKE ? ESCAPE '/'\n" + filter + order,owner,object,catalog,col);
    }

    // ---- execution ----
    private static final Pattern OUTER=Pattern.compile("(?i)\\s+AND\\s+(\\w+\\.\\w+)\\s*=\\s*(\\w+)\\.(\\w+)\\s*\\(\\+\\)");
    /** Tibero/Oracle SQL that H2 cannot parse: (+) outer joins become LEFT JOIN. */
    static String h2(String sql) {
        if(!sql.contains("(+)")) return sql;
        var m=OUTER.matcher(sql); var on=new LinkedHashMap<String,List<String>>();
        while(m.find()) on.computeIfAbsent(m.group(2),k -> new ArrayList<>()).add(m.group(1) + " = " + m.group(2) + "." + m.group(3));
        String out=OUTER.matcher(sql).replaceAll("");
        for(var e:on.entrySet()) {
            var join=Pattern.compile("(?i),\\s*(\\w+)\\s+" + e.getKey() + "\\b").matcher(out);
            if(join.find()) out=out.substring(0,join.start()) + " LEFT JOIN " + join.group(1) + " " + e.getKey() + " ON " + String.join(" AND ",e.getValue()) + out.substring(join.end());
        }
        return out;
    }
    static ResultSet query(Session s,String sql,Object... args) throws SQLException {
        try {
            var p=s.h2.prepareStatement(h2(sql));
            for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]);
            p.closeOnCompletion();
            return p.executeQuery();
        } catch(SQLException e) { throw Session.translate(e,sql); }
    }
}
