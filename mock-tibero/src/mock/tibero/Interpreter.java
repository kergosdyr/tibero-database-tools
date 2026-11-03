package mock.tibero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import mock.tibero.PlSql.*;

/** Runs parsed PL/SQL. Variables become SQL literals so H2 evaluates every expression and embedded statement. */
final class Interpreter {
    static final int LOOP_LIMIT=1_000_000;
    static final Set<String> NUMERIC=Set.of("NUMBER","INTEGER","INT","SMALLINT","PLS_INTEGER","BINARY_INTEGER","NATURAL","POSITIVE","FLOAT","DECIMAL","NUMERIC","BINARY_DOUBLE","BINARY_FLOAT","REAL","SIMPLE_INTEGER");
    static final Set<String> TEXT=Set.of("VARCHAR2","VARCHAR","NVARCHAR2","CHAR","NCHAR","CLOB","NCLOB","LONG","STRING");

    /** A PL/SQL exception in flight: predefined name, user exception name or application error. */
    static final class PlError extends RuntimeException {
        final String name; final int code;
        PlError(String name,int code,String message) { super(message,null,false,false); this.name=name; this.code=code; }
    }
    private static final class ExitSignal extends RuntimeException { ExitSignal() { super(null,null,false,false); } }
    private static final class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object value) { super(null,null,false,false); this.value=value; }
    }
    static final class Scope {
        final Scope parent; final Server.Pkg pkg;
        final Map<String,Object> values=new HashMap<>();
        final Map<String,String> types=new HashMap<>();
        final Map<String,Expr> cursors=new HashMap<>();
        PlError current;
        Scope(Scope parent,Server.Pkg pkg) { this.parent=parent; this.pkg=pkg; }
        Scope owner(String name) { for(var s=this;s!=null;s=s.parent) if(s.values.containsKey(name)) return s; return null; }
        Expr cursor(String name) { for(var s=this;s!=null;s=s.parent) if(s.cursors.containsKey(name)) return s.cursors.get(name); return null; }
        PlError currentError() { for(var s=this;s!=null;s=s.parent) if(s.current!=null) return s.current; return null; }
        void declare(String name,String type,Object value) { values.put(name,value); types.put(name,type); }
    }

    final Session s;
    Interpreter(Session s) { this.s=s; }

    /** Anonymous block; binds are positional JDBC parameters, returned with their final values. */
    Map<Integer,Object> runAnonymous(Block block,Map<Integer,Object> binds) {
        var root=new Scope(null,null);
        binds.forEach((k,v) -> root.declare("?" + k,null,v));
        try { runBlock(block,root); }
        catch(ReturnSignal ignored) {}
        catch(ExitSignal e) { throw new PlError("OTHERS",-6550,"EXIT must appear inside a loop"); }
        var out=new HashMap<Integer,Object>();
        root.values.forEach((k,v) -> { if(k.startsWith("?")) out.put(Integer.parseInt(k.substring(1)),v); });
        return out;
    }

    void runBlock(Block b,Scope outer) {
        var scope=new Scope(outer,outer.pkg);
        declare(b.vars(),scope);
        try { exec(b.body(),scope); }
        catch(PlError e) {
            for(var h:b.handlers()) if(h.names().contains(e.name) || h.names().contains("OTHERS")) {
                var handler=new Scope(scope,scope.pkg);
                handler.current=e;
                handler.declare("SQLERRM","VARCHAR2",e.getMessage());
                handler.declare("SQLCODE","NUMBER",BigDecimal.valueOf(e.code));
                exec(h.body(),handler);
                return;
            }
            throw e;
        }
    }
    void declare(List<Var> vars,Scope scope) {
        for(var v:vars) {
            if("CURSOR".equals(v.type())) scope.cursors.put(v.name(),v.cursor());
            else if("EXCEPTION".equals(v.type())) scope.declare(v.name(),"EXCEPTION",v.name());
            else scope.declare(v.name(),v.type(),v.init()==null ? null : coerce(eval(v.init(),scope),v.type()));
        }
    }
    void exec(List<Stmt> body,Scope scope) { for(var st:body) exec(st,scope); }
    private void exec(Stmt st,Scope scope) {
        if(st instanceof Null) return;
        if(st instanceof Block b) { runBlock(b,scope); return; }
        if(st instanceof Assign a) { assign(a.target(),eval(a.value(),scope),scope); return; }
        if(st instanceof Call c) { call(c.name(),c.args(),scope,false); return; }
        if(st instanceof If f) {
            for(int i=0;i<f.conds().size();i++) if(cond(f.conds().get(i),scope)) { exec(f.branches().get(i),new Scope(scope,scope.pkg)); return; }
            exec(f.otherwise(),new Scope(scope,scope.pkg));
            return;
        }
        if(st instanceof ForRange f) {
            int lo=number(eval(f.lo(),scope)).intValue(), hi=number(eval(f.hi(),scope)).intValue();
            var loop=new Scope(scope,scope.pkg);
            try {
                for(int i=0;i<=hi-lo;i++) {
                    guard(i);
                    loop.declare(f.var(),"PLS_INTEGER",BigDecimal.valueOf(f.reverse() ? hi-i : lo+i));
                    exec(f.body(),loop);
                }
            } catch(ExitSignal done) {}
            return;
        }
        if(st instanceof ForQuery f) {
            Expr query=f.query()!=null ? f.query() : scope.cursor(f.cursor());
            if(query==null) throw new PlError("OTHERS",-6550,"cursor '" + f.cursor() + "' must be declared");
            var loop=new Scope(scope,scope.pkg);
            try { for(var row:rows(render(query,scope))) { loop.declare(f.var(),"RECORD",row); exec(f.body(),loop); } }
            catch(ExitSignal done) {}
            return;
        }
        if(st instanceof While w) {
            try { for(int i=0;cond(w.cond(),scope);i++) { guard(i); exec(w.body(),new Scope(scope,scope.pkg)); } } catch(ExitSignal done) {}
            return;
        }
        if(st instanceof Loop l) {
            try { for(int i=0;;i++) { guard(i); exec(l.body(),new Scope(scope,scope.pkg)); } } catch(ExitSignal done) {}
            return;
        }
        if(st instanceof Exit e) { if(e.when()==null || cond(e.when(),scope)) throw new ExitSignal(); return; }
        if(st instanceof Return r) throw new ReturnSignal(r.value()==null ? null : eval(r.value(),scope));
        if(st instanceof Raise r) {
            if(r.name()==null) { var current=scope.currentError(); throw current!=null ? current : new PlError("OTHERS",-6550,"RAISE outside an exception handler"); }
            throw predefined(r.name());
        }
        if(st instanceof Immediate im) { s.executeNested(display(eval(im.sql(),scope))); return; }
        if(st instanceof Sql q) { sql(q,scope); return; }
        throw new IllegalStateException(st.toString());
    }
    private static void guard(int i) { if(i>=LOOP_LIMIT) throw new PlError("OTHERS",-6550,"[mock] loop limit of " + LOOP_LIMIT + " iterations reached"); }

    private void sql(Sql q,Scope scope) {
        String text=render(q.sql(),scope);
        String head=q.at().text().toUpperCase(Locale.ROOT);
        try {
            if(head.equals("COMMIT")) { if(!s.h2.getAutoCommit()) s.h2.commit(); return; }
            if(head.equals("ROLLBACK")) { if(!s.h2.getAutoCommit()) s.h2.rollback(); return; }
            if(!q.into().isEmpty()) {
                var found=rows(text);
                if(found.isEmpty()) throw new PlError("NO_DATA_FOUND",100,"[mock] NO_DATA_FOUND: no data found");
                if(found.size()>1) throw new PlError("TOO_MANY_ROWS",-1422,"[mock] TOO_MANY_ROWS: exact fetch returns more than requested number of rows");
                var values=new ArrayList<>(found.get(0).values());
                if(values.size()!=q.into().size()) throw new PlError("OTHERS",-6550,"[mock] INTO list has " + q.into().size() + " targets for " + values.size() + " columns");
                for(int i=0;i<values.size();i++) assign(q.into().get(i),values.get(i),scope);
                return;
            }
            try(var st=s.h2.createStatement()) { s.rowCount=st.executeUpdate(text); }
        } catch(SQLException e) { throw fromSql(e,text); }
    }
    List<LinkedHashMap<String,Object>> rows(String query) {
        try(var st=s.h2.createStatement(); var rs=st.executeQuery(query)) {
            var md=rs.getMetaData(); var out=new ArrayList<LinkedHashMap<String,Object>>();
            while(rs.next()) {
                guard(out.size());
                var row=new LinkedHashMap<String,Object>();
                for(int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i).toUpperCase(Locale.ROOT),normalize(rs.getObject(i)));
                out.add(row);
            }
            s.rowCount=out.size();
            return out;
        } catch(SQLException e) { throw fromSql(e,query); }
    }

    // ---- calls ----
    /** Statement call or function call inside an expression. */
    Object call(List<Tok> name,List<Arg> args,Scope scope,boolean function) {
        var parts=new ArrayList<String>(); for(var t:name) parts.add(t.id());
        if(parts.size()>1 && parts.get(0).equals("SYS")) parts.remove(0);
        String full=String.join(".",parts);
        switch(full) {
            case "DBMS_OUTPUT.PUT_LINE": s.output.putLine(args.isEmpty() ? "" : display(eval(args.get(0).value(),scope))); return null;
            case "DBMS_OUTPUT.PUT": s.output.put(display(eval(args.get(0).value(),scope))); return null;
            case "DBMS_OUTPUT.NEW_LINE": s.output.putLine(""); return null;
            case "DBMS_OUTPUT.ENABLE": s.output.enable(); return null;
            case "DBMS_OUTPUT.DISABLE": s.output.disable(); return null;
            case "DBMS_OUTPUT.GET_LINE": {
                String line=s.output.getLine();
                assignArg(args.get(0),line,scope);
                assignArg(args.get(1),BigDecimal.valueOf(line==null ? 1 : 0),scope);
                return null;
            }
            case "RAISE_APPLICATION_ERROR": {
                int code=number(eval(args.get(0).value(),scope)).intValue();
                throw new PlError("APPLICATION",code,"[mock] " + code + ": " + display(eval(args.get(1).value(),scope)));
            }
            case "DBMS_LOCK.SLEEP","DBMS_SESSION.SLEEP": {
                try { Thread.sleep((long)(number(eval(args.get(0).value(),scope)).doubleValue()*1000)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }
            default:
        }
        var target=s.server.resolve(s.schema,scope.pkg,parts,args.size());
        if(target==null) throw new PlError("OTHERS",-6550,"[mock] identifier '" + full + "' must be declared");
        if(function && !target.routine().function()) throw new PlError("OTHERS",-6550,"[mock] '" + full + "' is a procedure, not a function");
        return invoke(target,args,scope);
    }
    private Object invoke(Server.Target target,List<Arg> args,Scope caller) {
        var r=target.routine();
        if(r.body()==null) throw new PlError("OTHERS",-6508,"[mock] " + target.describe() + " has no body");
        var pkgScope=target.pkg()==null ? null : s.packageScope(target.pkg(),this);
        var scope=new Scope(pkgScope,target.pkg());
        var actual=new HashMap<String,Arg>();
        for(int i=0;i<args.size();i++) {
            var a=args.get(i);
            String formal=a.named()!=null ? a.named() : i<r.params().size() ? r.params().get(i).name() : null;
            if(formal==null || r.params().stream().noneMatch(p -> p.name().equals(formal)))
                throw new PlError("OTHERS",-6550,"[mock] wrong number or types of arguments in call to " + target.describe());
            actual.put(formal,a);
        }
        for(var p:r.params()) {
            var a=actual.get(p.name());
            Object v=null;
            if(!p.mode().equals("OUT")) v=a!=null ? eval(a.value(),caller) : p.dflt()!=null ? eval(p.dflt(),caller) : null;
            if(a==null && p.dflt()==null && !p.mode().equals("OUT")) throw new PlError("OTHERS",-6550,"[mock] missing value for " + p.name() + " in call to " + target.describe());
            scope.declare(p.name(),p.type(),coerce(v,p.type()));
        }
        Object result=null;
        try { runBlock(r.body(),scope); if(r.function()) throw new PlError("OTHERS",-6503,"[mock] function " + target.describe() + " returned without value"); }
        catch(ReturnSignal ret) { result=r.function() ? coerce(ret.value,r.returnType()) : null; }
        for(var p:r.params()) if(!p.mode().equals("IN") && actual.containsKey(p.name())) assignArg(actual.get(p.name()),scope.values.get(p.name()),caller);
        return result;
    }
    private void assignArg(Arg a,Object value,Scope scope) {
        var toks=a.value().toks();
        if(toks.isEmpty() || !(toks.get(0).name() || toks.get(0).type()==T.PARAM)) throw new PlError("OTHERS",-6550,"[mock] OUT argument must be a variable: " + a.value());
        var target=new ArrayList<Tok>();
        for(var t:toks) if(!t.is(".")) target.add(t);
        assign(target,value,scope);
    }
    private void assign(List<Tok> target,Object value,Scope scope) {
        Tok first=target.get(0);
        String name=first.type()==T.PARAM ? "?" + first.param() : first.id();
        var owner=scope.owner(name);
        if(owner==null) throw new PlError("OTHERS",-6550,"[mock] identifier '" + name + "' must be declared");
        if(target.size()>1) {
            if(!(owner.values.get(name) instanceof Map<?,?> record)) throw new PlError("OTHERS",-6550,"[mock] '" + name + "' is not a record");
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)record;
            row.put(target.get(1).id(),value);
            return;
        }
        owner.values.put(name,coerce(value,owner.types.get(name)));
    }

    // ---- expressions ----
    Object eval(Expr e,Scope scope) {
        String sql="SELECT " + render(e,scope) + " FROM DUAL";
        try(var st=s.h2.createStatement(); var rs=st.executeQuery(sql)) {
            rs.next();
            return normalize(rs.getObject(1));
        } catch(SQLException x) { throw fromSql(x,sql); }
    }
    boolean cond(Expr e,Scope scope) {
        var v=eval(new Expr(wrapCondition(e.toks())),scope);
        return v instanceof Number n && n.intValue()==1;
    }
    private static List<Tok> wrapCondition(List<Tok> toks) {
        var out=new ArrayList<Tok>();
        out.add(new Tok(T.WORD,"CASE WHEN (",0,0,0,0)); out.addAll(toks); out.add(new Tok(T.WORD,") THEN 1 ELSE 0 END",0,0,0,0));
        return out;
    }
    /** SQL text with PL/SQL variables, record fields, user functions and SQL% attributes replaced by literals. */
    String render(Expr e,Scope scope) {
        var toks=e.toks(); var b=new StringBuilder();
        for(int i=0;i<toks.size();i++) {
            Tok k=toks.get(i);
            boolean afterDot=i>0 && toks.get(i-1).is(".");
            if(k.type()==T.PARAM) { piece(b,literal(scope.owner("?" + k.param())==null ? null : scope.owner("?" + k.param()).values.get("?" + k.param()))); continue; }
            if(k.name() && !afterDot) {
                if(k.is("SQL") && i+2<toks.size() && toks.get(i+1).is("%")) {
                    String attr=toks.get(i+2).id(); i+=2;
                    piece(b,switch(attr) { case "ROWCOUNT" -> String.valueOf(s.rowCount); case "FOUND" -> s.rowCount>0 ? "TRUE" : "FALSE"; case "NOTFOUND" -> s.rowCount>0 ? "FALSE" : "TRUE"; default -> "NULL"; });
                    continue;
                }
                int end=i; var path=new ArrayList<Tok>(); path.add(k);
                while(end+2<toks.size() && toks.get(end+1).is(".") && toks.get(end+2).name()) { path.add(toks.get(end+2)); end+=2; }
                boolean called=end+1<toks.size() && toks.get(end+1).is("(");
                var owner=scope.owner(k.id());
                if(owner!=null && !called) {
                    Object v=owner.values.get(k.id());
                    if(path.size()>=2 && v instanceof Map<?,?> row) { piece(b,literal(row.get(path.get(1).id()))); i+=2; continue; }
                    if(path.size()==1 && !(i+1<toks.size() && toks.get(i+1).is("=>"))) { piece(b,literal(v)); continue; }
                }
                if(called && s.server.isFunction(s.schema,scope.pkg,ids(path))) {
                    int close=matching(toks,end+1);
                    var args=arguments(toks.subList(end+2,close));
                    piece(b,literal(call(path,args,scope,true)));
                    i=close; continue;
                }
            }
            // Tibero NUMBER division is exact; H2 divides integer literals as integers.
            piece(b,k.type()==T.SYMBOL && k.text().equals("/") ? "* 1.0 /" : PlSql.render(k));
        }
        return b.toString();
    }
    private static void piece(StringBuilder b,String text) {
        if(b.length()>0 && !text.equals(".") && b.charAt(b.length()-1)!='.') b.append(' ');
        b.append(text);
    }
    private static List<String> ids(List<Tok> path) { var out=new ArrayList<String>(); for(var t:path) out.add(t.id()); if(out.size()>1 && out.get(0).equals("SYS")) out.remove(0); return out; }
    private static int matching(List<Tok> toks,int open) {
        int depth=0;
        for(int i=open;i<toks.size();i++) { if(toks.get(i).is("(")) depth++; else if(toks.get(i).is(")") && --depth==0) return i; }
        throw new PlError("OTHERS",-6550,"[mock] unbalanced parentheses");
    }
    private static List<Arg> arguments(List<Tok> toks) {
        var out=new ArrayList<Arg>(); if(toks.isEmpty()) return out;
        int depth=0,start=0;
        for(int i=0;i<=toks.size();i++) {
            if(i<toks.size()) { var t=toks.get(i); if(t.is("(")) depth++; else if(t.is(")")) depth--; if(!(t.is(",") && depth==0)) continue; }
            var part=toks.subList(start,i);
            if(part.size()>2 && part.get(0).name() && part.get(1).is("=>")) out.add(new Arg(part.get(0).id(),new Expr(List.copyOf(part.subList(2,part.size())))));
            else out.add(new Arg(null,new Expr(List.copyOf(part))));
            start=i+1;
        }
        return out;
    }

    // ---- values ----
    static Object normalize(Object v) throws SQLException {
        if(v instanceof BigDecimal) return v;
        if(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) return BigDecimal.valueOf(((Number)v).longValue());
        if(v instanceof java.math.BigInteger bi) return new BigDecimal(bi);
        if(v instanceof Double || v instanceof Float) return BigDecimal.valueOf(((Number)v).doubleValue());
        if(v instanceof java.sql.Date d) return new Timestamp(d.getTime());
        if(v instanceof LocalDateTime t) return Timestamp.valueOf(t);
        if(v instanceof LocalDate d) return Timestamp.valueOf(d.atStartOfDay());
        if(v instanceof Clob c) return c.getSubString(1,(int)Math.min(c.length(),Integer.MAX_VALUE));
        return v;
    }
    static String literal(Object v) {
        if(v==null) return "NULL";
        if(v instanceof BigDecimal n) { String t=n.signum()==0 ? "0" : n.stripTrailingZeros().toPlainString(); return n.signum()<0 ? "(" + t + ")" : t; }
        if(v instanceof Number n) return literal(new BigDecimal(n.toString()));
        if(v instanceof Boolean f) return f ? "TRUE" : "FALSE";
        if(v instanceof Timestamp t) return "TIMESTAMP '" + t + "'";
        if(v instanceof OffsetDateTime t) return "TIMESTAMP WITH TIME ZONE '" + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxxx")) + "'";
        if(v instanceof byte[] bytes) { var b=new StringBuilder("X'"); for(byte x:bytes) b.append(String.format("%02X",x)); return b.append('\'').toString(); }
        if(v instanceof Map) throw new PlError("OTHERS",-6550,"[mock] a record cannot be used as a scalar value");
        return "'" + v.toString().replace("'","''") + "'";
    }
    static String display(Object v) {
        if(v==null) return "";
        if(v instanceof BigDecimal n) return n.signum()==0 ? "0" : n.stripTrailingZeros().toPlainString();
        if(v instanceof Timestamp t) {
            var l=t.toLocalDateTime();
            return l.format(DateTimeFormatter.ofPattern(l.getNano()==0 ? "yyyy/MM/dd HH:mm:ss" : "yyyy/MM/dd HH:mm:ss.SSSSSS"));
        }
        if(v instanceof Boolean f) return f ? "TRUE" : "FALSE";
        return v.toString();
    }
    static BigDecimal number(Object v) {
        if(v instanceof BigDecimal n) return n;
        if(v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(String.valueOf(v).trim()); }
        catch(NumberFormatException e) { throw new PlError("INVALID_NUMBER",-1722,"[mock] INVALID_NUMBER: invalid number '" + v + "'"); }
    }
    Object coerce(Object v,String type) {
        if(v==null || type==null || type.contains("%")) return v;
        var m=java.util.regex.Pattern.compile("([A-Z_ ]+?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(-?\\d+))?\\s*\\))?(\\s.*)?").matcher(type.trim());
        if(!m.matches()) return v;
        String base=m.group(1).trim();
        if(NUMERIC.contains(base)) {
            var n=number(v);
            if(base.equals("INTEGER") || base.equals("PLS_INTEGER") || base.equals("BINARY_INTEGER") || base.equals("INT")) return n.setScale(0,RoundingMode.HALF_UP);
            return m.group(3)!=null ? n.setScale(Integer.parseInt(m.group(3)),RoundingMode.HALF_UP) : m.group(2)!=null && base.equals("NUMBER") ? n.setScale(0,RoundingMode.HALF_UP) : n;
        }
        if(TEXT.contains(base)) {
            String text=v instanceof String str ? str : display(v);
            if(m.group(2)!=null && text.length()>Integer.parseInt(m.group(2)) && !base.endsWith("LOB"))
                throw new PlError("VALUE_ERROR",-6502,"[mock] VALUE_ERROR: character string buffer too small");
            return text;
        }
        if(base.equals("DATE") || base.startsWith("TIMESTAMP")) {
            if(v instanceof OffsetDateTime t && !type.contains("TIME ZONE")) return Timestamp.valueOf(t.toLocalDateTime());
            if(v instanceof Timestamp || v instanceof OffsetDateTime) return v;
            try(var st=s.h2.createStatement(); var rs=st.executeQuery("SELECT CAST(" + literal(v) + " AS TIMESTAMP) FROM DUAL")) { rs.next(); return normalize(rs.getObject(1)); }
            catch(SQLException e) { throw new PlError("VALUE_ERROR",-6502,"[mock] VALUE_ERROR: cannot convert '" + v + "' to " + type); }
        }
        if(base.equals("BOOLEAN")) {
            if(v instanceof Boolean) return v;
            throw new PlError("VALUE_ERROR",-6502,"[mock] VALUE_ERROR: expected BOOLEAN");
        }
        return v;
    }

    static PlError predefined(String name) {
        return switch(name) {
            case "NO_DATA_FOUND" -> new PlError(name,100,"[mock] NO_DATA_FOUND: no data found");
            case "TOO_MANY_ROWS" -> new PlError(name,-1422,"[mock] TOO_MANY_ROWS: exact fetch returns more than requested number of rows");
            case "ZERO_DIVIDE" -> new PlError(name,-1476,"[mock] ZERO_DIVIDE: divisor is equal to zero");
            case "VALUE_ERROR" -> new PlError(name,-6502,"[mock] VALUE_ERROR: numeric or value error");
            case "INVALID_NUMBER" -> new PlError(name,-1722,"[mock] INVALID_NUMBER: invalid number");
            case "DUP_VAL_ON_INDEX" -> new PlError(name,-1,"[mock] DUP_VAL_ON_INDEX: unique constraint violated");
            default -> new PlError(name,1,"[mock] User-Defined Exception " + name);
        };
    }
    PlError fromSql(SQLException e,String sql) {
        String message=Session.translate(e,sql).getMessage();
        return switch(e.getErrorCode()) {
            case 22012 -> new PlError("ZERO_DIVIDE",-1476,message);
            case 22018,22007 -> new PlError("INVALID_NUMBER",-1722,message);
            case 22001,22003 -> new PlError("VALUE_ERROR",-6502,message);
            case 23505 -> new PlError("DUP_VAL_ON_INDEX",-1,message);
            default -> new PlError("OTHERS",-e.getErrorCode(),message);
        };
    }
}
