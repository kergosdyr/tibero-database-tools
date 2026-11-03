package mock.tibero;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

/**
 * Tibero data dictionary on top of H2. Table and column views read H2's INFORMATION_SCHEMA; declared column types
 * come from the CREATE TABLE text, so ALL_TAB_COLUMNS shows what Tibero 6 stores (TIMESTAMP(6) with precision 6, DATE with length 7).
 */
public final class Dictionary {
    static final String[] DDL={
        "CREATE SCHEMA IF NOT EXISTS SYS",
        "CREATE TABLE SYS.MOCK_COL_TYPES(OWNER VARCHAR(128), TABLE_NAME VARCHAR(128), COLUMN_NAME VARCHAR(128), DATA_TYPE VARCHAR(128), PRIMARY KEY(OWNER, TABLE_NAME, COLUMN_NAME))",
        "CREATE TABLE SYS.MOCK_VIEW_TEXT(OWNER VARCHAR(128), VIEW_NAME VARCHAR(128), TEXT CLOB, PRIMARY KEY(OWNER, VIEW_NAME))",
        "CREATE TABLE SYS.MOCK_OBJECTS(OBJECT_ID INT PRIMARY KEY, OWNER VARCHAR(128), OBJECT_NAME VARCHAR(128), OBJECT_TYPE VARCHAR(30), STATUS VARCHAR(10), CREATED TIMESTAMP(0), LAST_DDL_TIME TIMESTAMP(0))",
        "CREATE SEQUENCE SYS.MOCK_OBJECT_ID START WITH 70000",
        "CREATE TABLE SYS.ALL_SOURCE(OWNER VARCHAR(128), NAME VARCHAR(128), TYPE VARCHAR(30), LINE INT, TEXT VARCHAR(4000))",
        "CREATE TABLE SYS.ALL_PROCEDURES(OWNER VARCHAR(128), OBJECT_NAME VARCHAR(128), PROCEDURE_NAME VARCHAR(128), OBJECT_ID INT, MEMBER_NO INT, OVERLOAD VARCHAR(40), OBJECT_TYPE VARCHAR(30), FUNCTIONABLE VARCHAR(3))",
        "CREATE TABLE SYS.ALL_ARGUMENTS(OWNER VARCHAR(128), OBJECT_NAME VARCHAR(128), PACKAGE_NAME VARCHAR(128), OBJECT_ID INT, OVERLOAD VARCHAR(40), ARGUMENT_NAME VARCHAR(128), POSITION INT, SEQUENCE INT, DATA_TYPE VARCHAR(128), DEFAULTED VARCHAR(1), IN_OUT VARCHAR(9), DATA_LENGTH INT, DATA_PRECISION INT, DATA_SCALE INT, TYPE_OWNER VARCHAR(128), TYPE_NAME VARCHAR(128), TYPE_SUBNAME VARCHAR(128))",
        "CREATE TABLE SYS.ALL_ERRORS(OWNER VARCHAR(128), NAME VARCHAR(128), TYPE VARCHAR(30), SEQUENCE INT, LINE INT, POSITION INT, TEXT VARCHAR(4000), ATTRIBUTE VARCHAR(9), MESSAGE_NUMBER INT)",
        "CREATE TABLE SYS.ALL_DEPENDENCIES(OWNER VARCHAR(128), NAME VARCHAR(128), TYPE VARCHAR(30), REFERENCED_OWNER VARCHAR(128), REFERENCED_NAME VARCHAR(128), REFERENCED_TYPE VARCHAR(30), PARENT_OBJ_OWNER VARCHAR(128), PARENT_OBJ_NAME VARCHAR(128), PARENT_OBJ_TYPE VARCHAR(30))",
        "CREATE TABLE SYS.ALL_TRIGGERS(OWNER VARCHAR(128), TRIGGER_NAME VARCHAR(128), TRIGGER_TYPE VARCHAR(30), TRIGGERING_EVENT VARCHAR(100), TABLE_OWNER VARCHAR(128), TABLE_NAME VARCHAR(128), STATUS VARCHAR(10))",
        // Tibero names the synonym target ORG_OBJECT_OWNER/ORG_OBJECT_NAME (Oracle: TABLE_OWNER/TABLE_NAME); the driver's getColumns joins on them.
        "CREATE TABLE SYS.ALL_SYNONYMS(OWNER VARCHAR(128), SYNONYM_NAME VARCHAR(128), ORG_OBJECT_OWNER VARCHAR(128), ORG_OBJECT_NAME VARCHAR(128))",
        "CREATE TABLE SYS.ALL_DB_LINKS(OWNER VARCHAR(128), DB_LINK VARCHAR(128), USERNAME VARCHAR(128), HOST VARCHAR(2000), CREATED TIMESTAMP(0))",
        "CREATE TABLE SYS.ALL_TBL_PRIVS(GRANTOR VARCHAR(128), GRANTEE VARCHAR(128), OWNER VARCHAR(128), TABLE_NAME VARCHAR(128), PRIVILEGE VARCHAR(40), GRANTABLE VARCHAR(3))",
        "CREATE TABLE SYS.ALL_TYPES(OWNER VARCHAR(128), TYPE_NAME VARCHAR(128), TYPECODE VARCHAR(30))",
        "CREATE TABLE SYS.PLAN_TABLE(STATEMENT_ID VARCHAR(30), PLAN_ID INT, \"TIMESTAMP\" TIMESTAMP(0), ID INT, PARENT_ID INT, DEPTH INT, POSITION INT, OPERATION VARCHAR(30), OPTIONS VARCHAR(255), OBJECT_OWNER VARCHAR(128), OBJECT_NAME VARCHAR(128), OBJECT_TYPE VARCHAR(30), COST NUMBER, CARDINALITY NUMBER, BYTES NUMBER, ACCESS_PREDICATES VARCHAR(4000), FILTER_PREDICATES VARCHAR(4000))",
        "CREATE TABLE SYS.V$VERSION(BANNER VARCHAR(200))",
        "INSERT INTO SYS.V$VERSION VALUES('Tibero 6 (mock server on H2, for IDE plugin testing)')",
        // H2 names constraint indexes PRIMARY_KEY_n / X_INDEX_n; Tibero shows the constraint name and no FK index.
        "CREATE VIEW SYS.MOCK_INDEXES AS SELECT I.INDEX_SCHEMA, I.INDEX_NAME, COALESCE(C.CONSTRAINT_NAME, I.INDEX_NAME) AS SHOWN_NAME, I.TABLE_SCHEMA, I.TABLE_NAME, I.INDEX_TYPE_NAME"
            + " FROM INFORMATION_SCHEMA.INDEXES I LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS C ON C.INDEX_SCHEMA = I.INDEX_SCHEMA AND C.INDEX_NAME = I.INDEX_NAME AND C.CONSTRAINT_TYPE IN ('PRIMARY KEY','UNIQUE')"
            + " WHERE I.INDEX_SCHEMA NOT IN ('INFORMATION_SCHEMA','SYS') AND NOT (C.CONSTRAINT_NAME IS NULL AND I.INDEX_NAME LIKE '%\\_INDEX\\_%' ESCAPE '\\')",
        "CREATE ALIAS SYS.TB_TYPE FOR 'mock.tibero.Dictionary.type'",
        "CREATE ALIAS SYS.TB_SIZE FOR 'mock.tibero.Dictionary.size'",
        "CREATE ALIAS SYS.MONTHS_BETWEEN FOR 'mock.tibero.Dictionary.monthsBetween'",
        "CREATE ALIAS SYS.SYS_CONTEXT FOR 'mock.tibero.Dictionary.sysContext'",
        "CREATE VIEW SYS.ALL_USERS AS SELECT SCHEMA_NAME AS USERNAME, CAST(ROW_NUMBER() OVER (ORDER BY SCHEMA_NAME) AS INT) AS USER_ID, CAST(TIMESTAMP '2026-01-01 00:00:00' AS TIMESTAMP(0)) AS CREATED FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME NOT IN ('INFORMATION_SCHEMA','PUBLIC')",
        "CREATE VIEW SYS.DBA_USERS AS SELECT * FROM SYS.ALL_USERS",
        "CREATE VIEW SYS.ALL_OBJECTS AS"
            + " SELECT TABLE_SCHEMA AS OWNER, TABLE_NAME AS OBJECT_NAME, CAST(NULL AS VARCHAR(128)) AS SUBOBJECT_NAME, CAST(1000 + ROW_NUMBER() OVER (ORDER BY TABLE_SCHEMA, TABLE_NAME) AS INT) AS OBJECT_ID,"
            + " CASE WHEN TABLE_TYPE = 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS OBJECT_TYPE, CASE WHEN TABLE_TYPE = 'VIEW' THEN 3 ELSE 1 END AS OBJECT_TYPE_NO,"
            + " CAST(TIMESTAMP '2026-01-01 00:00:00' AS TIMESTAMP(0)) AS CREATED, CAST(TIMESTAMP '2026-01-01 00:00:00' AS TIMESTAMP(0)) AS LAST_DDL_TIME, 'VALID' AS STATUS, 'N' AS TEMPORARY"
            + " FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA' AND NOT (TABLE_SCHEMA = 'SYS' AND TABLE_NAME LIKE 'MOCK%')"
            + " UNION ALL SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, NULL, CAST(5000 + ROW_NUMBER() OVER (ORDER BY SEQUENCE_SCHEMA, SEQUENCE_NAME) AS INT), 'SEQUENCE', 6, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', 'VALID', 'N'"
            + " FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME NOT LIKE 'SYSTEM_SEQUENCE%' AND SEQUENCE_SCHEMA <> 'SYS'"
            + " UNION ALL SELECT INDEX_SCHEMA, SHOWN_NAME, NULL, CAST(9000 + ROW_NUMBER() OVER (ORDER BY INDEX_SCHEMA, SHOWN_NAME) AS INT), 'INDEX', 2, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', 'VALID', 'N'"
            + " FROM SYS.MOCK_INDEXES"
            + " UNION ALL SELECT OWNER, OBJECT_NAME, NULL, OBJECT_ID, OBJECT_TYPE, CASE OBJECT_TYPE WHEN 'PROCEDURE' THEN 7 WHEN 'FUNCTION' THEN 8 WHEN 'PACKAGE' THEN 9 WHEN 'PACKAGE BODY' THEN 11 ELSE 12 END,"
            + " CREATED, LAST_DDL_TIME, STATUS, 'N' FROM SYS.MOCK_OBJECTS",
        "CREATE VIEW SYS.ALL_TAB_COLUMNS AS SELECT C.TABLE_SCHEMA AS OWNER, C.TABLE_NAME, C.COLUMN_NAME,"
            + " SYS.TB_TYPE(M.DATA_TYPE, C.DATA_TYPE, C.CHARACTER_MAXIMUM_LENGTH, C.NUMERIC_PRECISION, C.NUMERIC_SCALE, C.DATETIME_PRECISION) AS DATA_TYPE,"
            + " CAST(NULL AS VARCHAR(128)) AS DATA_TYPE_OWNER,"
            + " SYS.TB_SIZE(M.DATA_TYPE, C.DATA_TYPE, C.CHARACTER_MAXIMUM_LENGTH, C.NUMERIC_PRECISION, C.NUMERIC_SCALE, C.DATETIME_PRECISION, 'LENGTH') AS DATA_LENGTH,"
            + " SYS.TB_SIZE(M.DATA_TYPE, C.DATA_TYPE, C.CHARACTER_MAXIMUM_LENGTH, C.NUMERIC_PRECISION, C.NUMERIC_SCALE, C.DATETIME_PRECISION, 'PRECISION') AS DATA_PRECISION,"
            + " SYS.TB_SIZE(M.DATA_TYPE, C.DATA_TYPE, C.CHARACTER_MAXIMUM_LENGTH, C.NUMERIC_PRECISION, C.NUMERIC_SCALE, C.DATETIME_PRECISION, 'SCALE') AS DATA_SCALE,"
            + " CASE C.IS_NULLABLE WHEN 'YES' THEN 'Y' ELSE 'N' END AS NULLABLE, CAST(C.ORDINAL_POSITION AS INT) AS COLUMN_ID, C.COLUMN_DEFAULT AS DATA_DEFAULT"
            + " FROM INFORMATION_SCHEMA.COLUMNS C LEFT JOIN SYS.MOCK_COL_TYPES M ON M.OWNER = C.TABLE_SCHEMA AND M.TABLE_NAME = C.TABLE_NAME AND M.COLUMN_NAME = C.COLUMN_NAME"
            + " WHERE C.TABLE_SCHEMA <> 'INFORMATION_SCHEMA'",
        "CREATE VIEW SYS.ALL_TAB_COMMENTS AS SELECT TABLE_SCHEMA AS OWNER, TABLE_NAME, CASE WHEN TABLE_TYPE = 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS TABLE_TYPE, REMARKS AS COMMENTS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'",
        "CREATE VIEW SYS.ALL_COL_COMMENTS AS SELECT TABLE_SCHEMA AS OWNER, TABLE_NAME, COLUMN_NAME, REMARKS AS COMMENTS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA <> 'INFORMATION_SCHEMA'",
        "CREATE VIEW SYS.ALL_TABLES AS SELECT TABLE_SCHEMA AS OWNER, TABLE_NAME, 'USR' AS TABLESPACE_NAME, ROW_COUNT_ESTIMATE AS NUM_ROWS, CAST(1 + ROW_COUNT_ESTIMATE / 50 AS INT) AS BLOCKS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA <> 'INFORMATION_SCHEMA'",
        "CREATE VIEW SYS.ALL_VIEWS AS SELECT V.TABLE_SCHEMA AS OWNER, V.TABLE_NAME AS VIEW_NAME, COALESCE(M.TEXT, V.VIEW_DEFINITION) AS TEXT FROM INFORMATION_SCHEMA.VIEWS V"
            + " LEFT JOIN SYS.MOCK_VIEW_TEXT M ON M.OWNER = V.TABLE_SCHEMA AND M.VIEW_NAME = V.TABLE_NAME WHERE V.TABLE_SCHEMA NOT IN ('INFORMATION_SCHEMA','SYS')",
        "CREATE VIEW SYS.ALL_CONSTRAINTS AS SELECT T.CONSTRAINT_SCHEMA AS OWNER, T.CONSTRAINT_NAME,"
            + " CASE T.CONSTRAINT_TYPE WHEN 'PRIMARY KEY' THEN 'PRIMARY KEY' WHEN 'FOREIGN KEY' THEN 'REFERENTIAL' WHEN 'UNIQUE' THEN 'UNIQUE' ELSE 'CHECK' END AS CON_TYPE,"
            + " CASE T.CONSTRAINT_TYPE WHEN 'PRIMARY KEY' THEN 'P' WHEN 'FOREIGN KEY' THEN 'R' WHEN 'UNIQUE' THEN 'U' ELSE 'C' END AS CONSTRAINT_TYPE,"
            + " T.TABLE_NAME, R.UNIQUE_CONSTRAINT_SCHEMA AS R_OWNER, R.UNIQUE_CONSTRAINT_NAME AS R_CONSTRAINT_NAME, R.DELETE_RULE, 'ENABLED' AS STATUS"
            + " FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS T LEFT JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS R"
            + " ON R.CONSTRAINT_SCHEMA = T.CONSTRAINT_SCHEMA AND R.CONSTRAINT_NAME = T.CONSTRAINT_NAME WHERE T.CONSTRAINT_SCHEMA <> 'INFORMATION_SCHEMA'",
        "CREATE VIEW SYS.ALL_CONS_COLUMNS AS SELECT CONSTRAINT_SCHEMA AS OWNER, CONSTRAINT_NAME, TABLE_NAME, COLUMN_NAME, CAST(ORDINAL_POSITION AS INT) AS POSITION FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE",
        "CREATE VIEW SYS.ALL_INDEXES AS SELECT INDEX_SCHEMA AS OWNER, SHOWN_NAME AS INDEX_NAME, 'NORMAL' AS INDEX_TYPE, TABLE_SCHEMA AS TABLE_OWNER, TABLE_NAME,"
            + " CASE WHEN INDEX_TYPE_NAME IN ('PRIMARY KEY','UNIQUE INDEX') THEN 'UNIQUE' ELSE 'NONUNIQUE' END AS UNIQUENESS, 'VALID' AS STATUS, 1 AS DISTINCT_KEYS, 1 AS LEAF_BLOCKS FROM SYS.MOCK_INDEXES",
        "CREATE VIEW SYS.ALL_IND_COLUMNS AS SELECT M.INDEX_SCHEMA AS INDEX_OWNER, M.SHOWN_NAME AS INDEX_NAME, M.TABLE_SCHEMA AS TABLE_OWNER, M.TABLE_NAME, X.COLUMN_NAME, CAST(X.ORDINAL_POSITION AS INT) AS COLUMN_POSITION,"
            + " CASE X.ORDERING_SPECIFICATION WHEN 'DESC' THEN 'DESC' ELSE 'ASC' END AS DESCEND FROM SYS.MOCK_INDEXES M JOIN INFORMATION_SCHEMA.INDEX_COLUMNS X ON X.INDEX_SCHEMA = M.INDEX_SCHEMA AND X.INDEX_NAME = M.INDEX_NAME",
        "CREATE VIEW SYS.ALL_SEQUENCES AS SELECT SEQUENCE_SCHEMA AS SEQUENCE_OWNER, SEQUENCE_NAME, MINIMUM_VALUE AS MIN_VALUE, MAXIMUM_VALUE AS MAX_VALUE, \"INCREMENT\" AS INCREMENT_BY,"
            + " CASE CYCLE_OPTION WHEN 'YES' THEN 'Y' ELSE 'N' END AS CYCLE_FLAG, 'N' AS ORDER_FLAG, CACHE AS CACHE_SIZE, BASE_VALUE AS LAST_NUMBER"
            + " FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME NOT LIKE 'SYSTEM_SEQUENCE%' AND SEQUENCE_SCHEMA <> 'SYS'",
        "CREATE VIEW SYS.USER_OBJECTS AS SELECT * FROM SYS.ALL_OBJECTS WHERE OWNER = USER",
        "CREATE VIEW SYS.USER_TAB_COLUMNS AS SELECT * FROM SYS.ALL_TAB_COLUMNS WHERE OWNER = USER",
        "CREATE VIEW SYS.USER_ERRORS AS SELECT * FROM SYS.ALL_ERRORS WHERE OWNER = USER",
        "CREATE VIEW SYS.USER_SOURCE AS SELECT * FROM SYS.ALL_SOURCE WHERE OWNER = USER",
    };

    // ---- Tibero 6 column descriptors ----
    private static final Pattern DECLARED=Pattern.compile("(?i)\\s*([A-Z_0-9]+(?:\\s+[A-Z_]+)*)\\s*(?:\\(\\s*(\\d+|\\*)\\s*(?:(?:BYTE|CHAR)\\s*)?(?:,\\s*(-?\\d+)\\s*)?\\))?\\s*(.*)");

    /** DATA_TYPE as ALL_TAB_COLUMNS shows it: TIMESTAMP keeps its precision in the name, other types do not. */
    public static String type(String declared,String h2,Long chars,Integer precision,Integer scale,Integer fraction) {
        return describe(declared,h2,chars,precision,scale,fraction)[0];
    }
    public static Integer size(String declared,String h2,Long chars,Integer precision,Integer scale,Integer fraction,String part) {
        String v=describe(declared,h2,chars,precision,scale,fraction)[switch(part) { case "LENGTH" -> 1; case "PRECISION" -> 2; default -> 3; }];
        return v==null ? null : Integer.valueOf(v);
    }
    /** {type, length, precision, scale}; Tibero stores precision 0 for non-numeric types. */
    static String[] describe(String declared,String h2,Long chars,Integer precision,Integer scale,Integer fraction) {
        if(declared==null) declared=fromH2(h2,chars,precision,scale,fraction);
        String d=declared.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
        if(d.startsWith("TIMESTAMP")) {
            var m=Pattern.compile("TIMESTAMP(?:\\s*\\((\\d)\\))?(.*)").matcher(d); m.matches();
            String p=m.group(1)==null ? "6" : m.group(1), suffix=m.group(2).trim();
            return new String[]{"TIMESTAMP(" + p + ")" + (suffix.isEmpty() ? "" : " " + suffix),suffix.contains("TIME ZONE") && !suffix.contains("LOCAL") ? "13" : "11",p,p};
        }
        if(d.startsWith("INTERVAL")) {
            var m=Pattern.compile("INTERVAL (DAY|YEAR)(?:\\s*\\((\\d)\\))? TO (SECOND|MONTH)(?:\\s*\\((\\d)\\))?").matcher(d);
            if(m.matches()) {
                String lead=m.group(2)==null ? "2" : m.group(2), frac=m.group(4)==null ? (m.group(3).equals("SECOND") ? "6" : "0") : m.group(4);
                String name=m.group(1).equals("DAY") ? "INTERVAL DAY(" + lead + ") TO SECOND(" + frac + ")" : "INTERVAL YEAR(" + lead + ") TO MONTH";
                return new String[]{name,m.group(1).equals("DAY") ? "11" : "5",lead,frac};
            }
            return new String[]{d,"11","0","0"};
        }
        var m=DECLARED.matcher(d); m.matches();
        String base=m.group(1).trim(), a=m.group(2), b=m.group(3);
        return switch(base) {
            case "NUMBER","NUMERIC","DECIMAL","DEC" -> a==null ? new String[]{"NUMBER","22",null,null}
                    : new String[]{"NUMBER","22",a.equals("*") ? "38" : a,b==null ? "0" : b};
            case "INTEGER","INT","SMALLINT" -> new String[]{"NUMBER","22","38","0"};
            case "FLOAT" -> new String[]{"FLOAT","22",a==null ? "126" : a,null};
            case "DATE" -> new String[]{"DATE","7","0",null};
            case "VARCHAR2","VARCHAR","NVARCHAR2","NVARCHAR","CHAR","NCHAR","RAW" -> new String[]{base,a==null ? "1" : a,"0",null};
            case "CLOB","NCLOB","BLOB","BFILE","XMLTYPE" -> new String[]{base,"4000","0",null};
            case "LONG","LONG RAW" -> new String[]{base,"0","0",null};
            case "ROWID" -> new String[]{"ROWID","10","0",null};
            case "BINARY_FLOAT" -> new String[]{base,"4","0",null};
            case "BINARY_DOUBLE" -> new String[]{base,"8","0",null};
            default -> new String[]{base,a==null ? "0" : a,"0",b};
        };
    }
    private static String fromH2(String h2,Long chars,Integer precision,Integer scale,Integer fraction) {
        String t=h2==null ? "" : h2;
        return switch(t) {
            case "CHARACTER VARYING" -> "VARCHAR2(" + chars + ")";
            case "CHARACTER" -> "CHAR(" + chars + ")";
            case "NUMERIC" -> "NUMBER(" + precision + "," + scale + ")";
            case "DECFLOAT" -> "NUMBER";
            case "INTEGER","BIGINT","SMALLINT","TINYINT" -> "NUMBER(" + precision + ",0)";
            case "DOUBLE PRECISION" -> "BINARY_DOUBLE";
            case "REAL" -> "BINARY_FLOAT";
            case "TIMESTAMP" -> fraction!=null && fraction==0 ? "DATE" : "TIMESTAMP(" + fraction + ")";
            case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP(" + fraction + ") WITH TIME ZONE";
            case "DATE" -> "DATE";
            case "CHARACTER LARGE OBJECT" -> "CLOB";
            case "BINARY LARGE OBJECT" -> "BLOB";
            case "BINARY VARYING","BINARY" -> "RAW(" + chars + ")";
            case "BOOLEAN" -> "NUMBER(1)";
            default -> t.isEmpty() ? "VARCHAR2(4000)" : t;
        };
    }
    /** USERENV values the IDE plugin reads; CURRENT_SCHEMA follows ALTER SESSION SET CURRENT_SCHEMA (H2 SET SCHEMA). */
    public static String sysContext(Connection c,String namespace,String parameter) throws SQLException {
        if(!"USERENV".equalsIgnoreCase(namespace)) throw new SQLException("[mock] JDBC-11039: Invalid SYS_CONTEXT namespace.","22023",-11039);
        return switch(parameter==null ? "" : parameter.toUpperCase(Locale.ROOT)) {
            case "CURRENT_SCHEMA" -> c.getSchema();
            case "SESSION_USER", "CURRENT_USER" -> c.getMetaData().getUserName();
            default -> throw new SQLException("[mock] JDBC-11006: Invalid USERENV parameter.","22023",-11006);
        };
    }
    public static java.math.BigDecimal monthsBetween(Timestamp to,Timestamp from) {
        if(to==null || from==null) return null;
        var a=to.toLocalDateTime(); var b=from.toLocalDateTime();
        long months=(a.getYear()-b.getYear())*12L + a.getMonthValue()-b.getMonthValue();
        double days=a.getDayOfMonth()-b.getDayOfMonth() + (a.toLocalTime().toSecondOfDay()-b.toLocalTime().toSecondOfDay())/86400.0;
        return java.math.BigDecimal.valueOf(months + days/31.0).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }

    // ---- declared types from DDL text ----
    private static final Pattern CREATE_TABLE=Pattern.compile("(?is)^\\s*CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+(\"[^\"]+\"|[\\w$#]+)(?:\\s*\\.\\s*(\"[^\"]+\"|[\\w$#]+))?\\s*\\((.*)\\)([^)]*)$");
    private static final Pattern ALTER_ADD=Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+(\"[^\"]+\"|[\\w$#]+)(?:\\s*\\.\\s*(\"[^\"]+\"|[\\w$#]+))?\\s+(?:ADD|MODIFY)\\s*(\\(.*\\)|.*)$");
    private static final Pattern CREATE_VIEW=Pattern.compile("(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NO)?FORCE\\s+)?VIEW\\s+(\"[^\"]+\"|[\\w$#]+)(?:\\s*\\.\\s*(\"[^\"]+\"|[\\w$#]+))?(?:\\s*\\([^)]*\\))?\\s+AS\\s+(.*)$");
    private static final Set<String> CONSTRAINT_START=Set.of("CONSTRAINT","PRIMARY","FOREIGN","UNIQUE","CHECK");
    private static final Set<String> TYPE_END=Set.of("DEFAULT","NOT","NULL","PRIMARY","UNIQUE","CHECK","REFERENCES","CONSTRAINT","GENERATED","ENABLE","DISABLE","IDENTITY");
    private static final Pattern PHYSICAL=Pattern.compile("(?is)\\s+(TABLESPACE\\s+\\S+|PCTFREE\\s+\\d+|PCTUSED\\s+\\d+|INITRANS\\s+\\d+|MAXTRANS\\s+\\d+|STORAGE\\s*\\([^)]*\\)|NOLOGGING|LOGGING|NOCOMPRESS|COMPRESS)");

    /** Tibero physical clauses H2 does not know. */
    static String stripPhysical(String sql) {
        var m=CREATE_TABLE.matcher(sql);
        if(!m.matches()) return sql;
        return sql.substring(0,m.start(4)) + PHYSICAL.matcher(m.group(4)).replaceAll("");
    }
    static void recordTable(Connection admin,String user,String sql) throws SQLException {
        var m=CREATE_TABLE.matcher(sql);
        if(m.matches()) {
            String owner=m.group(2)==null ? user : id(m.group(1)), table=id(m.group(2)==null ? m.group(1) : m.group(2));
            try(var p=admin.prepareStatement("DELETE FROM SYS.MOCK_COL_TYPES WHERE OWNER = ? AND TABLE_NAME = ?")) { p.setString(1,owner); p.setString(2,table); p.executeUpdate(); }
            columns(admin,owner,table,m.group(3));
            return;
        }
        m=ALTER_ADD.matcher(sql);
        if(m.matches()) {
            String body=m.group(3).trim();
            if(body.startsWith("(")) body=body.substring(1,body.lastIndexOf(')'));
            columns(admin,m.group(2)==null ? user : id(m.group(1)),id(m.group(2)==null ? m.group(1) : m.group(2)),body);
        }
    }
    static void recordView(Connection admin,String user,String sql) throws SQLException {
        var m=CREATE_VIEW.matcher(sql);
        if(!m.matches()) return;
        try(var p=admin.prepareStatement("MERGE INTO SYS.MOCK_VIEW_TEXT KEY(OWNER, VIEW_NAME) VALUES(?, ?, ?)")) {
            p.setString(1,m.group(2)==null ? user : id(m.group(1))); p.setString(2,id(m.group(2)==null ? m.group(1) : m.group(2)));
            p.setString(3,m.group(3).trim()); p.executeUpdate();
        }
    }
    private static void columns(Connection admin,String owner,String table,String body) throws SQLException {
        for(var element:split(body)) {
            var words=element.trim().split("\\s+",2);
            if(words.length<2 || CONSTRAINT_START.contains(words[0].toUpperCase(Locale.ROOT))) continue;
            var type=new StringBuilder();
            int depth=0;
            for(var word:words[1].split("(?<=\\))|\\s+|(?=\\()")) {
                word=word.trim();
                if(word.isEmpty()) continue;
                if(depth==0 && TYPE_END.contains(word.toUpperCase(Locale.ROOT))) break;
                depth+=count(word,'(')-count(word,')');
                if(type.length()>0 && !word.startsWith("(")) type.append(' ');
                type.append(word);
            }
            try(var p=admin.prepareStatement("MERGE INTO SYS.MOCK_COL_TYPES KEY(OWNER, TABLE_NAME, COLUMN_NAME) VALUES(?, ?, ?, ?)")) {
                p.setString(1,owner); p.setString(2,table); p.setString(3,id(words[0])); p.setString(4,type.toString().toUpperCase(Locale.ROOT)); p.executeUpdate();
            }
        }
    }
    private static int count(String s,char c) { return (int)s.chars().filter(x -> x==c).count(); }
    static List<String> split(String body) {
        var out=new ArrayList<String>(); int depth=0,start=0; boolean quote=false;
        for(int i=0;i<body.length();i++) {
            char c=body.charAt(i);
            if(c=='\'') quote=!quote;
            if(quote) continue;
            if(c=='(') depth++;
            else if(c==')') depth--;
            else if(c==',' && depth==0) { out.add(body.substring(start,i)); start=i+1; }
        }
        out.add(body.substring(start));
        return out;
    }
    static String id(String token) {
        return token.startsWith("\"") ? token.substring(1,token.length()-1) : token.toUpperCase(Locale.ROOT);
    }
}
