package mock.tibero;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** One client connection: H2 connection, current schema, DBMS_OUTPUT buffer and package state. */
final class Session {
    enum Kind { BLOCK, CREATE_UNIT, DROP_UNIT, EXPLAIN, CALL, ALTER_SESSION, SQL }
    record Outcome(long count,SQLWarning warning,Map<Integer,Object> outs) {}
    static final class Output {
        private boolean enabled;
        private final ArrayDeque<String> lines=new ArrayDeque<>();
        private final StringBuilder partial=new StringBuilder();
        void enable() { enabled=true; }
        void disable() { enabled=false; lines.clear(); partial.setLength(0); }
        void put(String s) { if(enabled) partial.append(s); }
        void putLine(String s) { if(enabled) { lines.add(partial + s); partial.setLength(0); } }
        String getLine() { return lines.poll(); }
    }

    final Server server; final String user; final Connection h2;
    final Output output=new Output();
    final Map<Server.Pkg,Interpreter.Scope> packages=new HashMap<>();
    String schema;
    long rowCount;
    Session(Server server,String user,Connection h2) { this.server=server; this.user=user; this.h2=h2; schema=user; }

    private static final Pattern LEADING=Pattern.compile("(?s)^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*");
    private static final Pattern UNIT=Pattern.compile("(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)\\b.*");
    private static final Pattern DROP=Pattern.compile("(?is)^DROP\\s+(PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE\\s+BODY|TYPE)\\s+(\\S+?)\\s*;?\\s*$");
    private static final Pattern EXPLAIN=Pattern.compile("(?is)^EXPLAIN\\s+PLAN\\s+(?:SET\\s+STATEMENT_ID\\s*=\\s*'([^']*)'\\s+)?(?:INTO\\s+\\S+\\s+)?FOR\\s+(.*)$");
    private static final Pattern ESCAPE_CALL=Pattern.compile("(?is)^\\{\\s*(\\?\\s*=\\s*)?call\\s+(.*?)\\s*}$");
    private static final Pattern EXEC=Pattern.compile("(?is)^(?:EXEC|EXECUTE|CALL)\\s+(?!IMMEDIATE)(.*?);?$");
    private static final Pattern CURRENT_SCHEMA=Pattern.compile("(?is)^ALTER\\s+SESSION\\s+SET\\s+CURRENT_SCHEMA\\s*=\\s*\"?([\\w$#]+)\"?\\s*;?$");

    static String strip(String sql) { return LEADING.matcher(sql).replaceFirst(""); }
    static Kind classify(String sql) {
        String s=strip(sql), u=s.toUpperCase(Locale.ROOT);
        if(u.startsWith("BEGIN") || u.startsWith("DECLARE")) return Kind.BLOCK;
        if(UNIT.matcher(s).matches()) return Kind.CREATE_UNIT;
        if(DROP.matcher(s).matches()) return Kind.DROP_UNIT;
        if(u.startsWith("EXPLAIN")) return Kind.EXPLAIN;
        if(ESCAPE_CALL.matcher(s).matches() || EXEC.matcher(s).matches()) return Kind.CALL;
        if(u.startsWith("ALTER SESSION")) return Kind.ALTER_SESSION;
        return Kind.SQL;
    }

    /** Statements the mock answers itself (PL/SQL, EXPLAIN PLAN, ALTER SESSION). */
    Outcome executeMock(String sql,Map<Integer,Object> binds) throws SQLException {
        String s=strip(sql);
        try {
            switch(classify(s)) {
                case BLOCK -> {
                    var outs=new Interpreter(this).runAnonymous(PlSql.parseAnonymous(s),binds);
                    return new Outcome(1,null,outs);
                }
                case CALL -> {
                    var m=ESCAPE_CALL.matcher(s);
                    String block=m.matches() ? "BEGIN " + (m.group(1)!=null ? "? := " : "") + m.group(2) + "; END;" : "BEGIN " + EXEC.matcher(s).replaceFirst("$1") + "; END;";
                    var outs=new Interpreter(this).runAnonymous(PlSql.parseAnonymous(block),binds);
                    return new Outcome(0,null,outs);
                }
                case CREATE_UNIT -> {
                    var problems=server.create(schema,s);
                    String type=UNIT.matcher(s).replaceFirst("$1").toUpperCase(Locale.ROOT);
                    return new Outcome(0,problems.isEmpty() ? null : new SQLWarning("[mock] Warning: " + type + " created with compilation errors. See ALL_ERRORS.","01000",-15163),Map.of());
                }
                case DROP_UNIT -> {
                    var m=DROP.matcher(s); m.matches();
                    server.drop(schema,m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+"," "),m.group(2));
                    return new Outcome(0,null,Map.of());
                }
                case EXPLAIN -> {
                    var m=EXPLAIN.matcher(s.replaceFirst(";\\s*$",""));
                    if(!m.matches()) throw new SQLException("[mock] EXPLAIN PLAN syntax: EXPLAIN PLAN [SET STATEMENT_ID = 'id'] FOR statement","42000",-8001);
                    Explain.plan(this,m.group(1),toH2(m.group(2)));
                    return new Outcome(0,null,Map.of());
                }
                case ALTER_SESSION -> {
                    var m=CURRENT_SCHEMA.matcher(s);
                    if(m.matches()) {
                        String target=m.group(1).toUpperCase(Locale.ROOT);
                        try(var st=h2.createStatement()) { st.execute("SET SCHEMA " + target); st.execute("SET SCHEMA_SEARCH_PATH " + target + ", SYS"); }
                        schema=target;
                    }
                    return new Outcome(0,null,Map.of()); // NLS and other session parameters are accepted and ignored.
                }
                default -> throw new IllegalStateException();
            }
        } catch(PlSql.Syntax e) {
            throw new SQLSyntaxErrorException("[mock] PL/SQL syntax error at line " + e.line + ", column " + e.col + ": " + e.getMessage(),"42000",-15001);
        } catch(Interpreter.PlError e) {
            throw new SQLException(e.getMessage(),"72000",e.code);
        }
    }
    void executeNested(String sql) {
        try {
            if(classify(sql)!=Kind.SQL) { executeMock(sql,new HashMap<>()); return; }
            try(var st=h2.createStatement()) { st.execute(toH2(sql)); rowCount=Math.max(st.getUpdateCount(),0); }
            afterH2(sql);
        } catch(SQLException e) { throw new Interpreter.PlError("OTHERS",-e.getErrorCode(),translate(e,sql).getMessage()); }
    }
    /** Used for seed scripts. */
    void executeTop(String sql) throws SQLException {
        if(classify(sql)!=Kind.SQL) {
            var outcome=executeMock(sql.startsWith(Seed.INVALID) ? sql.substring(Seed.INVALID.length()) : sql,new HashMap<>());
            if(outcome.warning()!=null && !sql.startsWith(Seed.INVALID)) throw new SQLException("seed failed: " + outcome.warning().getMessage() + "\n" + sql);
            return;
        }
        try(var st=h2.createStatement()) { st.execute(toH2(sql)); }
        afterH2(sql);
    }

    // ---- Tibero SQL → H2 ----
    String toH2(String sql) {
        String s=strip(sql).replaceFirst("\\s*/\\s*$","").replaceFirst(";\\s*$","");
        s=Dictionary.stripPhysical(s);
        s=s.replaceAll("(?i)\\s+PURGE\\s*$","");
        return s;
    }
    void afterH2(String sql) throws SQLException {
        String s=strip(sql).replaceFirst("\\s*/\\s*$","").replaceFirst(";\\s*$","");
        String u=s.toUpperCase(Locale.ROOT);
        if(u.startsWith("CREATE") && u.matches("(?s)CREATE\\s+(GLOBAL\\s+TEMPORARY\\s+)?TABLE.*") || u.startsWith("ALTER TABLE")) Dictionary.recordTable(server.admin,schema,s);
        else if(u.matches("(?s)CREATE\\s+(OR\\s+REPLACE\\s+)?((NO)?FORCE\\s+)?VIEW.*")) Dictionary.recordView(server.admin,schema,s);
    }

    Interpreter.Scope packageScope(Server.Pkg pkg,Interpreter interpreter) {
        var scope=packages.get(pkg);
        if(scope!=null) return scope;
        scope=new Interpreter.Scope(null,pkg);
        packages.put(pkg,scope);
        var spec=server.unit(pkg.owner(),"PACKAGE",pkg.name()); var body=server.unit(pkg.owner(),"PACKAGE BODY",pkg.name());
        if(spec!=null) interpreter.declare(spec.globals(),scope);
        if(body!=null) { interpreter.declare(body.globals(),scope); if(body.init()!=null) interpreter.exec(body.init().body(),scope); }
        return scope;
    }

    /** H2 messages rephrased as Tibero-style errors; the H2 text stays for debugging. */
    static SQLException translate(SQLException e,String sql) {
        if(e.getMessage()!=null && e.getMessage().startsWith("[mock]")) return e;
        String h2=e.getMessage()==null ? "" : e.getMessage().replaceFirst("(?s); SQL statement:.*","");
        // Only the getFunctionColumns failure carries the real driver message; other texts are the mock's own.
        String text=switch(e.getErrorCode()) {
            case 42102,42103,42104 -> "[mock] table or view does not exist.";
            case 42122 -> "[mock] invalid column name.";
            case 23505 -> "[mock] unique constraint violated.";
            case 23502 -> "[mock] NOT NULL constraint violated.";
            case 23503,23506 -> "[mock] referential constraint violated.";
            case 22012 -> "[mock] divisor is zero.";
            case 42101,42111 -> "[mock] name is already used by an existing object.";
            case 42000,42001 -> sql!=null && Pattern.compile(",\\s*\\)\\s*\\d").matcher(sql).find() ? "JDBC-8012: Missing comma." : "[mock] syntax error.";
            default -> "[mock] error " + e.getErrorCode() + ".";
        };
        var out=new SQLException(text + " [mock, H2: " + h2 + "]",e.getSQLState(),e.getErrorCode()==42000 || e.getErrorCode()==42001 ? -8012 : -e.getErrorCode());
        out.initCause(e);
        return out;
    }
}
