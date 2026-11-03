package mock.tibero;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mock.tibero.PlSql.*;

/** One in-memory Tibero instance per JVM: H2 database, data dictionary and compiled PL/SQL units. */
final class Server {
    record Pkg(String owner,String name) {}
    record Target(String owner,Pkg pkg,Routine routine) {
        String describe() { return owner + "." + (pkg==null ? "" : pkg.name() + ".") + routine.name(); }
    }
    record Problem(int line,int col,String text) {}
    private record Key(String owner,String type,String name) {}
    private record Stored(Unit unit,boolean valid) {}

    static final Set<String> USERS=Set.of("DEMO","HR");
    private static Server instance;
    final String url="jdbc:h2:mem:tibero_mock_" + Long.toHexString(System.nanoTime()) + ";MODE=Oracle;DB_CLOSE_DELAY=-1";
    final org.h2.Driver h2=new org.h2.Driver();
    final Connection admin;
    private final Map<Key,Stored> units=new ConcurrentHashMap<>();
    volatile java.nio.file.Path log;

    static synchronized Server get() throws SQLException {
        if(instance==null) { instance=new Server(); instance.seed(); }
        return instance;
    }
    private Server() throws SQLException {
        admin=h2.connect(url,props("SA"));
        try(var st=admin.createStatement()) {
            for(String user:USERS) {
                st.execute("CREATE USER IF NOT EXISTS " + user + " PASSWORD '' ADMIN");
                st.execute("CREATE SCHEMA IF NOT EXISTS " + user + " AUTHORIZATION " + user);
            }
            for(String ddl:Dictionary.DDL) st.execute(ddl);
        }
    }
    private static Properties props(String user) { var p=new Properties(); p.setProperty("user",user); p.setProperty("password",""); return p; }

    Session open(String user) throws SQLException {
        String u=user==null || user.isBlank() ? "DEMO" : user.toUpperCase(Locale.ROOT);
        if(!USERS.contains(u)) throw new SQLException("[mock] login failed: user " + u + " does not exist (use DEMO or HR)","28000",-17001);
        var c=h2.connect(url + ";SCHEMA=" + u,props(u));
        try(var st=c.createStatement()) { st.execute("SET SCHEMA_SEARCH_PATH " + u + ", SYS"); }
        return new Session(this,u,c);
    }
    private void seed() throws SQLException {
        var s=open("DEMO");
        try { for(String sql:Seed.DEMO) s.executeTop(sql); }
        finally { s.h2.close(); }
    }
    void log(String line) {
        var path=log;
        if(path==null) return;
        try { java.nio.file.Files.writeString(path,line + "\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND); }
        catch(java.io.IOException ignored) {}
    }

    // ---- PL/SQL objects ----
    private static final Pattern HEADER=Pattern.compile("(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
            + "(PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE\\s+BODY|TYPE)\\s+(\"[^\"]+\"|[\\w$#]+)(?:\\s*\\.\\s*(\"[^\"]+\"|[\\w$#]+))?");

    /** CREATE [OR REPLACE] of a stored unit; returns the compilation problems (empty when VALID). */
    synchronized List<Problem> create(String schema,String sql) throws SQLException {
        var h=HEADER.matcher(sql);
        if(!h.find()) throw new SQLException("[mock] unsupported CREATE statement","42000",-8001);
        String type=h.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
        String owner=h.group(3)==null ? schema : Dictionary.id(h.group(2)), name=Dictionary.id(h.group(3)==null ? h.group(2) : h.group(3));
        int kindAt=h.start(1);
        String source=sql.substring(kindAt).replaceFirst("\\s*/\\s*$","").stripTrailing();
        int firstLine=1+(int)sql.substring(0,kindAt).chars().filter(c -> c=='\n').count();
        int firstCol=kindAt-sql.lastIndexOf('\n',kindAt-1);
        Unit unit=null; var problems=new ArrayList<Problem>();
        try {
            unit=PlSql.parseCreate(sql,schema);
            new Checker(this,unit).run(problems);
        } catch(Syntax e) { problems.add(new Problem(e.line,e.col,"[mock] syntax error: " + e.getMessage())); }
        problems.replaceAll(p -> new Problem(p.line()-firstLine+1,p.line()==firstLine ? p.col()-firstCol+1 : p.col(),p.text()));
        store(new Key(owner,type,name),source,unit,problems);
        return problems;
    }
    synchronized void drop(String schema,String type,String rawName) throws SQLException {
        String owner=schema, name=rawName;
        if(rawName.contains(".")) { owner=Dictionary.id(rawName.substring(0,rawName.indexOf('.'))); name=rawName.substring(rawName.indexOf('.')+1); }
        name=Dictionary.id(name);
        var key=new Key(owner,type,name);
        if(!units.containsKey(key)) throw new SQLException("[mock] " + type + " " + owner + "." + name + " does not exist","42000",-8033);
        units.remove(key);
        clear(owner,type,name);
        if(type.equals("PACKAGE")) { units.remove(new Key(owner,"PACKAGE BODY",name)); clear(owner,"PACKAGE BODY",name); }
        exec("DELETE FROM SYS.MOCK_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE " + (type.equals("PACKAGE") ? "IN ('PACKAGE','PACKAGE BODY')" : "= ?"),
                type.equals("PACKAGE") ? new Object[]{owner,name} : new Object[]{owner,name,type});
    }
    private void clear(String owner,String type,String name) throws SQLException {
        exec("DELETE FROM SYS.ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND TYPE = ?",owner,name,type);
        exec("DELETE FROM SYS.ALL_ERRORS WHERE OWNER = ? AND NAME = ? AND TYPE = ?",owner,name,type);
        exec("DELETE FROM SYS.ALL_DEPENDENCIES WHERE OWNER = ? AND NAME = ? AND TYPE = ?",owner,name,type);
        if(!type.endsWith("BODY")) {
            exec("DELETE FROM SYS.ALL_PROCEDURES WHERE OWNER = ? AND OBJECT_NAME = ?",owner,name);
            exec("DELETE FROM SYS.ALL_ARGUMENTS WHERE OWNER = ? AND (PACKAGE_NAME = ? OR PACKAGE_NAME IS NULL AND OBJECT_NAME = ?)",owner,name,name);
        }
    }
    private void store(Key key,String source,Unit unit,List<Problem> problems) throws SQLException {
        boolean valid=problems.isEmpty();
        units.put(key,new Stored(unit,valid));
        clear(key.owner(),key.type(),key.name());
        Integer id=null;
        try(var p=admin.prepareStatement("SELECT OBJECT_ID FROM SYS.MOCK_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = ?")) {
            p.setString(1,key.owner()); p.setString(2,key.name()); p.setString(3,key.type());
            try(var rs=p.executeQuery()) { if(rs.next()) id=rs.getInt(1); }
        }
        if(id==null) {
            try(var st=admin.createStatement(); var rs=st.executeQuery("SELECT NEXT VALUE FOR SYS.MOCK_OBJECT_ID")) { rs.next(); id=rs.getInt(1); }
            exec("INSERT INTO SYS.MOCK_OBJECTS VALUES(?, ?, ?, ?, ?, LOCALTIMESTAMP(0), LOCALTIMESTAMP(0))",id,key.owner(),key.name(),key.type(),valid ? "VALID" : "INVALID");
        } else exec("UPDATE SYS.MOCK_OBJECTS SET STATUS = ?, LAST_DDL_TIME = LOCALTIMESTAMP(0) WHERE OBJECT_ID = ?",valid ? "VALID" : "INVALID",id);
        var lines=source.split("\n",-1);
        for(int i=0;i<lines.length;i++) exec("INSERT INTO SYS.ALL_SOURCE VALUES(?, ?, ?, ?, ?)",key.owner(),key.name(),key.type(),i+1,lines[i] + (i<lines.length-1 ? "\n" : ""));
        int seq=0;
        for(var p:problems) exec("INSERT INTO SYS.ALL_ERRORS VALUES(?, ?, ?, ?, ?, ?, ?, 'ERROR', 0)",key.owner(),key.name(),key.type(),++seq,p.line(),p.col(),p.text());
        if(unit==null) return;
        switch(key.type()) {
            case "PROCEDURE","FUNCTION" -> {
                exec("INSERT INTO SYS.ALL_PROCEDURES VALUES(?, ?, NULL, ?, 1, NULL, ?, ?)",key.owner(),key.name(),id,key.type(),unit.routine().function() ? "YES" : "NO");
                arguments(key.owner(),null,id,null,unit.routine());
            }
            case "PACKAGE" -> {
                exec("INSERT INTO SYS.ALL_PROCEDURES VALUES(?, ?, NULL, ?, 0, NULL, 'PACKAGE', NULL)",key.owner(),key.name(),id);
                var counts=new HashMap<String,Integer>();
                for(var m:unit.members()) counts.merge(m.name(),1,Integer::sum);
                var seen=new HashMap<String,Integer>(); int member=0;
                for(var m:unit.members()) {
                    String overload=counts.get(m.name())>1 ? String.valueOf(seen.merge(m.name(),1,Integer::sum)) : null;
                    exec("INSERT INTO SYS.ALL_PROCEDURES VALUES(?, ?, ?, ?, ?, ?, 'PACKAGE', ?)",key.owner(),key.name(),m.name(),id,++member,overload,m.function() ? "YES" : "NO");
                    arguments(key.owner(),key.name(),id,overload,m);
                }
            }
            default -> {}
        }
        dependencies(key,unit);
    }
    private void arguments(String owner,String pkg,int id,String overload,Routine r) throws SQLException {
        int seq=0;
        if(r.function()) argument(owner,pkg,r.name(),id,overload,null,0,++seq,r.returnType(),"OUT",false);
        int position=0;
        for(var p:r.params()) argument(owner,pkg,r.name(),id,overload,p.name(),++position,++seq,p.type(),p.mode(),p.dflt()!=null);
    }
    private void argument(String owner,String pkg,String object,int id,String overload,String name,int position,int seq,String type,String mode,boolean defaulted) throws SQLException {
        var fraction=Pattern.compile("TIMESTAMP\\s*\\((\\d)\\)").matcher(type.toUpperCase(Locale.ROOT));
        String base=type.toUpperCase(Locale.ROOT).replaceAll("\\s*\\(.*?\\)","").trim();
        if(base.startsWith("TIMESTAMP") || base.startsWith("INTERVAL")) base=base.replaceAll("\\s+"," ");
        if(base.equals("SYS_REFCURSOR")) base="REF CURSOR";
        // Tibero 6 / Oracle: ALL_ARGUMENTS.DATA_TYPE has no size; precision/length stay empty for unconstrained parameters.
        // Like the reported Tibero 6 server, there is no DATA_LEVEL column (JDBC-8026 Invalid identifier).
        exec("INSERT INTO SYS.ALL_ARGUMENTS VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)",
                owner,object,pkg,id,overload,name,position,seq,base,defaulted ? "Y" : "N",mode,
                base.equals("NUMBER") ? Integer.valueOf(22) : base.startsWith("TIMESTAMP") ? Integer.valueOf(11) : base.equals("DATE") ? Integer.valueOf(7) : null,null,
                base.startsWith("TIMESTAMP") ? Integer.valueOf(fraction.find() ? fraction.group(1) : "6") : null);
    }
    private void dependencies(Key key,Unit unit) throws SQLException {
        var names=new LinkedHashSet<String>();
        for(var t:PlSql.lex(unit.source())) if(t.name()) names.add(t.id());
        try(var p=admin.prepareStatement("SELECT OBJECT_NAME, OBJECT_TYPE FROM SYS.ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE IN ('TABLE','VIEW','SEQUENCE','PROCEDURE','FUNCTION','PACKAGE')")) {
            p.setString(1,key.owner());
            try(var rs=p.executeQuery()) {
                while(rs.next()) {
                    String n=rs.getString(1), t=rs.getString(2);
                    if(names.contains(n) && !n.equals(key.name()))
                        exec("INSERT INTO SYS.ALL_DEPENDENCIES VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",key.owner(),key.name(),key.type(),key.owner(),n,t,key.owner(),n,t);
                }
            }
        }
    }
    private void exec(String sql,Object... args) throws SQLException {
        try(var p=admin.prepareStatement(sql)) { for(int i=0;i<args.length;i++) p.setObject(i+1,args[i]); p.executeUpdate(); }
    }

    // ---- resolution ----
    Unit unit(String owner,String type,String name) { var s=units.get(new Key(owner,type,name)); return s==null ? null : s.unit(); }
    boolean valid(String owner,String type,String name) { var s=units.get(new Key(owner,type,name)); return s!=null && s.valid(); }
    Target resolve(String schema,Pkg current,List<String> parts,int args) {
        if(parts.size()==1 && current!=null) { var t=member(current,parts.get(0),args); if(t!=null) return t; }
        if(parts.size()==1) return standalone(schema,parts.get(0));
        if(parts.size()==2) {
            var t=member(new Pkg(schema,parts.get(0)),parts.get(1),args);
            return t!=null ? t : standalone(parts.get(0),parts.get(1));
        }
        if(parts.size()==3) return member(new Pkg(parts.get(0),parts.get(1)),parts.get(2),args);
        return null;
    }
    boolean isFunction(String schema,Pkg current,List<String> parts) {
        if(parts.size()==1 && current!=null) {
            for(String type:List.of("PACKAGE BODY","PACKAGE")) {
                var u=unit(current.owner(),type,current.name());
                if(u!=null && u.members().stream().anyMatch(m -> m.function() && m.name().equals(parts.get(0)))) return true;
            }
        }
        var t=resolve(schema,current,parts,-1);
        return t!=null && t.routine().function();
    }
    private Target standalone(String owner,String name) {
        for(String type:List.of("FUNCTION","PROCEDURE")) {
            var u=unit(owner,type,name);
            if(u==null) continue;
            if(!valid(owner,type,name)) throw new Interpreter.PlError("OTHERS",-6575,"[mock] " + type + " " + owner + "." + name + " is invalid; see ALL_ERRORS");
            return new Target(owner,null,u.routine());
        }
        return null;
    }
    private Target member(Pkg pkg,String name,int args) {
        var spec=unit(pkg.owner(),"PACKAGE",pkg.name());
        if(spec==null) return null;
        var body=unit(pkg.owner(),"PACKAGE BODY",pkg.name());
        if(body==null) throw new Interpreter.PlError("OTHERS",-4067,"[mock] package body " + pkg.owner() + "." + pkg.name() + " does not exist");
        if(!valid(pkg.owner(),"PACKAGE BODY",pkg.name())) throw new Interpreter.PlError("OTHERS",-4063,"[mock] package body " + pkg.owner() + "." + pkg.name() + " is invalid; see ALL_ERRORS");
        Routine fallback=null;
        for(var m:body.members()) {
            if(!m.name().equals(name)) continue;
            long required=m.params().stream().filter(p -> p.dflt()==null).count();
            if(args<0 || args>=required && args<=m.params().size()) return new Target(pkg.owner(),pkg,m);
            fallback=m;
        }
        return fallback==null ? null : new Target(pkg.owner(),pkg,fallback);
    }

    /** Compile-time checks: undeclared assignment targets, unknown local calls and undeclared v_/p_ names. */
    private static final class Checker {
        private static final Pattern VARIABLE=Pattern.compile("[VP]_\\w*");
        final Server server; final Unit unit; List<Problem> out;
        Checker(Server server,Unit unit) { this.server=server; this.unit=unit; }
        void run(List<Problem> problems) {
            out=problems;
            var base=new HashSet<String>();
            if(unit.type().equals("PACKAGE BODY")) {
                var spec=server.unit(unit.owner(),"PACKAGE",unit.name());
                if(spec!=null) spec.globals().forEach(v -> base.add(v.name()));
                unit.globals().forEach(v -> base.add(v.name()));
                var members=new HashSet<String>();
                unit.members().forEach(m -> members.add(m.name()));
                if(spec!=null) spec.members().forEach(m -> members.add(m.name()));
                for(var m:unit.members()) routine(m,base,members);
                if(unit.init()!=null) block(unit.init(),new HashSet<>(base),members);
            } else if(unit.routine()!=null) routine(unit.routine(),base,Set.of(unit.routine().name()));
        }
        private void routine(Routine r,Set<String> outer,Set<String> members) {
            var names=new HashSet<>(outer);
            r.params().forEach(p -> names.add(p.name()));
            block(r.body(),names,members);
        }
        private void block(Block b,Set<String> outer,Set<String> members) {
            var names=new HashSet<>(outer);
            for(var v:b.vars()) { if(v.init()!=null) expr(v.init(),names); names.add(v.name()); }
            statements(b.body(),names,members);
            for(var h:b.handlers()) { var hn=new HashSet<>(names); hn.add("SQLERRM"); hn.add("SQLCODE"); statements(h.body(),hn,members); }
        }
        private void statements(List<Stmt> body,Set<String> names,Set<String> members) {
            for(var st:body) {
                if(st instanceof Block b) block(b,names,members);
                else if(st instanceof Assign a) { target(a.target().get(0),names); expr(a.value(),names); }
                else if(st instanceof Sql q) for(var t:q.into()) target(t.get(0),names);
                else if(st instanceof Call c) {
                    if(c.name().size()==1 && c.name().get(0).type()!=T.PARAM) {
                        String n=c.name().get(0).id();
                        if(!n.equals("RAISE_APPLICATION_ERROR") && !members.contains(n) && server.unit(unit.owner(),"PROCEDURE",n)==null && server.unit(unit.owner(),"FUNCTION",n)==null)
                            out.add(new Problem(c.at().line(),c.at().col(),"[mock] identifier '" + n + "' must be declared"));
                    }
                    for(var a:c.args()) expr(a.value(),names);
                }
                else if(st instanceof If f) { f.conds().forEach(e -> expr(e,names)); f.branches().forEach(x -> statements(x,names,members)); statements(f.otherwise(),names,members); }
                else if(st instanceof ForRange f) { expr(f.lo(),names); expr(f.hi(),names); var n=new HashSet<>(names); n.add(f.var()); statements(f.body(),n,members); }
                else if(st instanceof ForQuery f) { var n=new HashSet<>(names); n.add(f.var()); statements(f.body(),n,members); }
                else if(st instanceof While w) { expr(w.cond(),names); statements(w.body(),names,members); }
                else if(st instanceof Loop l) statements(l.body(),names,members);
                else if(st instanceof Exit e && e.when()!=null) expr(e.when(),names);
                else if(st instanceof Return r && r.value()!=null) expr(r.value(),names);
            }
        }
        private void target(Tok t,Set<String> names) {
            if(t.type()!=T.PARAM && !names.contains(t.id())) out.add(new Problem(t.line(),t.col(),"[mock] identifier '" + t.id() + "' must be declared"));
        }
        private void expr(Expr e,Set<String> names) {
            var toks=e.toks();
            for(int i=0;i<toks.size();i++) {
                var t=toks.get(i);
                boolean afterDot=i>0 && toks.get(i-1).is("."), call=i+1<toks.size() && toks.get(i+1).is("("), named=i+1<toks.size() && toks.get(i+1).is("=>");
                if(t.type()==T.WORD && !afterDot && !call && !named && VARIABLE.matcher(t.id()).matches() && !names.contains(t.id()))
                    out.add(new Problem(t.line(),t.col(),"[mock] identifier '" + t.id() + "' must be declared"));
            }
        }
    }
}
