package mock.tibero;

import java.util.*;

/** Lexer and parser for the PL/SQL subset the mock server runs. Expressions stay token lists; H2 evaluates them. */
final class PlSql {
    enum T { WORD, QUOTED, STRING, NUMBER, SYMBOL, PARAM, EOF }
    record Tok(T type,String text,int pos,int line,int col,int param) {
        boolean is(String s) { return (type==T.WORD || type==T.SYMBOL) && text.equalsIgnoreCase(s); }
        boolean name() { return type==T.WORD || type==T.QUOTED; }
        String id() { return type==T.QUOTED ? text : text.toUpperCase(Locale.ROOT); }
        @Override public String toString() { return type==T.EOF ? "end of input" : text; }
    }
    /** Compile error with a source position. */
    static final class Syntax extends RuntimeException {
        final int line,col;
        Syntax(String message,Tok at) { super(message); line=at.line; col=at.col; }
    }

    static List<Tok> lex(String s) {
        var out=new ArrayList<Tok>();
        int i=0,line=1,lineStart=0,params=0,n=s.length();
        while(i<n) {
            char c=s.charAt(i);
            if(c=='\n') { line++; lineStart=++i; continue; }
            if(Character.isWhitespace(c)) { i++; continue; }
            int start=i,col=i-lineStart+1;
            if(c=='-' && i+1<n && s.charAt(i+1)=='-') { while(i<n && s.charAt(i)!='\n') i++; continue; }
            if(c=='/' && i+1<n && s.charAt(i+1)=='*') {
                int end=s.indexOf("*/",i+2); end=end<0 ? n : end+2;
                for(int k=i;k<end;k++) if(s.charAt(k)=='\n') { line++; lineStart=k+1; }
                i=end; continue;
            }
            if(c=='\'') {
                var b=new StringBuilder(); i++;
                while(i<n) {
                    char d=s.charAt(i);
                    if(d=='\'') { if(i+1<n && s.charAt(i+1)=='\'') { b.append('\''); i+=2; continue; } i++; break; }
                    if(d=='\n') { line++; lineStart=i+1; }
                    b.append(d); i++;
                }
                out.add(new Tok(T.STRING,b.toString(),start,line,col,0)); continue;
            }
            if(c=='"') {
                int end=s.indexOf('"',i+1); if(end<0) end=n-1;
                out.add(new Tok(T.QUOTED,s.substring(i+1,end),start,line,col,0)); i=end+1; continue;
            }
            if(Character.isDigit(c) || c=='.' && i+1<n && Character.isDigit(s.charAt(i+1)) && !(i>0 && s.charAt(i-1)=='.')) {
                while(i<n && (Character.isDigit(s.charAt(i)) || s.charAt(i)=='.' && !(i+1<n && s.charAt(i+1)=='.'))) i++;
                if(i<n && (s.charAt(i)=='E' || s.charAt(i)=='e') && i+1<n && (Character.isDigit(s.charAt(i+1)) || "+-".indexOf(s.charAt(i+1))>=0)) {
                    i+=2; while(i<n && Character.isDigit(s.charAt(i))) i++;
                }
                out.add(new Tok(T.NUMBER,s.substring(start,i),start,line,col,0)); continue;
            }
            if(Character.isLetter(c) || c=='_') {
                while(i<n && (Character.isLetterOrDigit(s.charAt(i)) || "_$#".indexOf(s.charAt(i))>=0)) i++;
                out.add(new Tok(T.WORD,s.substring(start,i),start,line,col,0)); continue;
            }
            if(c=='?') { out.add(new Tok(T.PARAM,"?",start,line,col,++params)); i++; continue; }
            String two=i+1<n ? s.substring(i,i+2) : "";
            if(Set.of(":=","=>","..","||","<>","!=","<=",">=","**").contains(two)) { out.add(new Tok(T.SYMBOL,two,start,line,col,0)); i+=2; continue; }
            out.add(new Tok(T.SYMBOL,String.valueOf(c),start,line,col,0)); i++;
        }
        out.add(new Tok(T.EOF,"",n,line,n-lineStart+1,0));
        return out;
    }

    // ---- AST ----
    record Expr(List<Tok> toks) {
        Tok first() { return toks.isEmpty() ? null : toks.get(0); }
        @Override public String toString() { return text(toks); }
    }
    record Var(String name,String type,Expr init,Tok at,Expr cursor) {}
    record Handler(Set<String> names,List<Stmt> body) {}
    record Arg(String named,Expr value) {}
    interface Stmt { Tok at(); }
    record Block(List<Var> vars,List<Stmt> body,List<Handler> handlers,Tok at) implements Stmt {}
    record Assign(Tok at,List<Tok> target,Expr value) implements Stmt {}
    record Call(Tok at,List<Tok> name,List<Arg> args) implements Stmt {}
    record If(Tok at,List<Expr> conds,List<List<Stmt>> branches,List<Stmt> otherwise) implements Stmt {}
    record ForRange(Tok at,String var,Expr lo,Expr hi,boolean reverse,List<Stmt> body) implements Stmt {}
    record ForQuery(Tok at,String var,Expr query,String cursor,List<Stmt> body) implements Stmt {}
    record While(Tok at,Expr cond,List<Stmt> body) implements Stmt {}
    record Loop(Tok at,List<Stmt> body) implements Stmt {}
    record Exit(Tok at,Expr when) implements Stmt {}
    record Return(Tok at,Expr value) implements Stmt {}
    record Sql(Tok at,Expr sql,List<List<Tok>> into) implements Stmt {}
    record Raise(Tok at,String name) implements Stmt {}
    record Immediate(Tok at,Expr sql) implements Stmt {}
    record Null(Tok at) implements Stmt {}

    record Param(String name,String mode,String type,Expr dflt) {}
    record Routine(String name,boolean function,List<Param> params,String returnType,Block body,Tok at) {}
    /** Parsed CREATE statement; {@code source} is ALL_SOURCE text starting at the object keyword. */
    record Unit(String owner,String name,String type,String source,int firstLine,int firstCol,
                Routine routine,List<Routine> members,List<Var> globals,Block init) {}

    static final Set<String> STOP_STATEMENT=Set.of(";");
    private final List<Tok> t;
    private int i;
    PlSql(List<Tok> tokens) { t=tokens; }

    Tok peek() { return t.get(i); }
    Tok peek(int k) { return t.get(Math.min(i+k,t.size()-1)); }
    Tok next() { return t.get(i++); }
    boolean at(String s) { return peek().is(s); }
    boolean accept(String s) { if(at(s)) { i++; return true; } return false; }
    Tok expect(String s) {
        if(!at(s)) throw new Syntax("expected '" + s + "' but found '" + peek() + "'",peek());
        return next();
    }
    Tok name() {
        if(!peek().name()) throw new Syntax("expected an identifier but found '" + peek() + "'",peek());
        return next();
    }
    boolean eof() { return peek().type==T.EOF || peek().is("/") && peek(1).type==T.EOF; }

    // ---- CREATE ----
    static Unit parseCreate(String sql,String user) {
        var p=new PlSql(lex(sql));
        p.expect("CREATE");
        if(p.accept("OR")) p.expect("REPLACE");
        if(!p.accept("EDITIONABLE")) p.accept("NONEDITIONABLE");
        Tok kindTok=p.next();
        String kind=kindTok.text.toUpperCase(Locale.ROOT);
        if((kind.equals("PACKAGE") || kind.equals("TYPE")) && p.accept("BODY")) kind+=" BODY";
        Tok first=p.name(); String owner=user, name=first.id();
        if(p.accept(".")) { owner=name; name=p.name().id(); }
        String source=sql.substring(kindTok.pos).replaceFirst("\\s*/\\s*$","").stripTrailing();
        var unit=switch(kind) {
            case "PROCEDURE","FUNCTION" -> {
                var r=p.routineTail(name,kind.equals("FUNCTION"),true);
                yield new Unit(owner,name,kind,source,kindTok.line,kindTok.col,r,List.of(),List.of(),null);
            }
            case "PACKAGE","PACKAGE BODY" -> p.packageTail(owner,name,kind,source,kindTok);
            default -> new Unit(owner,name,kind,source,kindTok.line,kindTok.col,null,List.of(),List.of(),null);
        };
        if(unit.routine()!=null || kind.startsWith("PACKAGE")) { p.accept(";"); if(!p.eof()) throw new Syntax("unexpected '" + p.peek() + "' after END",p.peek()); }
        return unit;
    }
    private Routine routineTail(String name,boolean function,boolean withBody) {
        Tok at=peek();
        var params=new ArrayList<Param>();
        if(accept("(")) {
            do {
                String pname=name().id(), mode="IN";
                if(accept("IN")) { if(accept("OUT")) mode="IN/OUT"; }
                else if(accept("OUT")) mode="OUT";
                accept("NOCOPY");
                var type=typeText(Set.of(",",")","DEFAULT",":="));
                Expr dflt=null;
                if(accept("DEFAULT") || accept(":=")) dflt=expr(Set.of(",",")"));
                params.add(new Param(pname,mode,type,dflt));
            } while(accept(","));
            expect(")");
        }
        String returns=null;
        if(function) { expect("RETURN"); returns=typeText(Set.of("IS","AS",";","DETERMINISTIC","PIPELINED","RESULT_CACHE")); }
        while(accept("DETERMINISTIC") || accept("PIPELINED") || accept("RESULT_CACHE")) {}
        if(!withBody) return new Routine(name,function,params,returns,null,at);
        if(!accept("IS")) expect("AS");
        var vars=declarations();
        var body=blockBody(at,vars);
        if(peek().name() && !at(";")) next(); // END name
        return new Routine(name,function,params,returns,body,at);
    }
    private Unit packageTail(String owner,String name,String kind,String source,Tok kindTok) {
        if(!accept("IS")) expect("AS");
        var members=new ArrayList<Routine>(); var globals=new ArrayList<Var>(); Block init=null;
        boolean body=kind.equals("PACKAGE BODY");
        while(!at("END") && !at("BEGIN")) {
            if(eof()) throw new Syntax("expected END for package " + name,peek());
            if(at("PROCEDURE") || at("FUNCTION")) {
                boolean function=next().is("FUNCTION");
                String member=name().id();
                if(body) {
                    var r=routineTail(member,function,true);
                    expect(";");
                    members.add(r);
                } else { members.add(routineTail(member,function,false)); expect(";"); }
            } else globals.addAll(declarations(true));
        }
        if(accept("BEGIN")) { init=new Block(List.of(),statements(Set.of("END","EXCEPTION")),List.of(),kindTok); }
        expect("END");
        if(peek().name()) next();
        return new Unit(owner,name,kind,source,kindTok.line,kindTok.col,null,members,globals,init);
    }

    // ---- blocks and statements ----
    static Block parseAnonymous(String sql) {
        var p=new PlSql(lex(sql));
        Tok at=p.peek();
        Block b;
        if(p.accept("DECLARE")) b=p.blockBody(at,p.declarations());
        else { p.expect("BEGIN"); b=p.blockBody(at,List.of(),true); }
        if(p.peek().name()) p.next();
        p.accept(";");
        if(!p.eof()) throw new Syntax("unexpected '" + p.peek() + "' after END",p.peek());
        return b;
    }
    private Block blockBody(Tok at,List<Var> vars) { expect("BEGIN"); return blockBody(at,vars,true); }
    private Block blockBody(Tok at,List<Var> vars,boolean begun) {
        var body=statements(Set.of("END","EXCEPTION"));
        var handlers=new ArrayList<Handler>();
        if(accept("EXCEPTION")) {
            while(accept("WHEN")) {
                var names=new LinkedHashSet<String>();
                do names.add(name().id()); while(accept("OR"));
                expect("THEN");
                handlers.add(new Handler(names,statements(Set.of("WHEN","END"))));
            }
        }
        expect("END");
        return new Block(vars,body,handlers,at);
    }
    private List<Var> declarations() { return declarations(false); }
    private List<Var> declarations(boolean single) {
        var vars=new ArrayList<Var>();
        while(!at("BEGIN") && !at("END") && !at("PROCEDURE") && !at("FUNCTION")) {
            if(eof()) throw new Syntax("expected BEGIN but found end of input",peek());
            Tok start=peek();
            if(accept("PRAGMA") || accept("TYPE") || accept("SUBTYPE")) { skipTo(";"); }
            else if(accept("CURSOR")) {
                String n=name().id();
                expect("IS");
                vars.add(new Var(n,"CURSOR",null,start,expr(STOP_STATEMENT)));
                expect(";");
            } else {
                Tok n=name();
                accept("CONSTANT");
                if(accept("EXCEPTION")) { vars.add(new Var(n.id(),"EXCEPTION",null,n,null)); expect(";"); }
                else {
                    String type=typeText(Set.of(":=","DEFAULT",";","NOT"));
                    if(accept("NOT")) expect("NULL");
                    Expr init=null;
                    if(accept(":=") || accept("DEFAULT")) init=expr(STOP_STATEMENT);
                    if(!at(";")) throw new Syntax("expected ';' after declaration of " + n.id() + " but found '" + peek() + "'",peek());
                    next();
                    vars.add(new Var(n.id(),type,init,n,null));
                }
            }
            if(single) break;
        }
        return vars;
    }
    private void skipTo(String s) { while(!at(s) && !eof()) next(); expect(s); }
    private String typeText(Set<String> stops) {
        var b=new ArrayList<Tok>(); int depth=0;
        while(!eof()) {
            Tok k=peek();
            if(depth==0 && stops.stream().anyMatch(k::is)) break;
            if(k.is("(")) depth++;
            if(k.is(")")) { if(depth==0) break; depth--; }
            b.add(next());
        }
        if(b.isEmpty()) throw new Syntax("expected a data type but found '" + peek() + "'",peek());
        return text(b).toUpperCase(Locale.ROOT);
    }
    List<Stmt> statements(Set<String> stops) {
        var list=new ArrayList<Stmt>();
        while(!eof() && stops.stream().noneMatch(this::at)) list.add(statement());
        if(eof()) throw new Syntax("expected END but found end of input",peek());
        return list;
    }
    private Stmt statement() {
        Tok k=peek();
        if(accept("NULL")) { expect(";"); return new Null(k); }
        if(at("BEGIN") || at("DECLARE")) {
            Block b=accept("DECLARE") ? blockBody(k,declarations()) : blockBody(k,List.of());
            if(peek().name()) next();
            expect(";");
            return b;
        }
        if(accept("IF")) {
            var conds=new ArrayList<Expr>(); var branches=new ArrayList<List<Stmt>>(); List<Stmt> otherwise=List.of();
            conds.add(expr(Set.of("THEN"))); expect("THEN");
            branches.add(statements(Set.of("ELSIF","ELSE","END")));
            while(accept("ELSIF")) { conds.add(expr(Set.of("THEN"))); expect("THEN"); branches.add(statements(Set.of("ELSIF","ELSE","END"))); }
            if(accept("ELSE")) otherwise=statements(Set.of("END"));
            expect("END"); expect("IF"); expect(";");
            return new If(k,conds,branches,otherwise);
        }
        if(accept("FOR")) {
            String v=name().id(); expect("IN");
            boolean reverse=accept("REVERSE");
            Stmt loop;
            if(at("(") && (peek(1).is("SELECT") || peek(1).is("WITH"))) {
                next(); var q=expr(Set.of(")")); expect(")");
                expect("LOOP"); loop=new ForQuery(k,v,q,null,loopBody());
            } else if(peek().name() && peek(1).is("LOOP")) {
                String cursor=next().id(); expect("LOOP"); loop=new ForQuery(k,v,null,cursor,loopBody());
            } else {
                var lo=expr(Set.of("..")); expect("..");
                var hi=expr(Set.of("LOOP")); expect("LOOP");
                loop=new ForRange(k,v,lo,hi,reverse,loopBody());
            }
            return loop;
        }
        if(accept("WHILE")) { var c=expr(Set.of("LOOP")); expect("LOOP"); return new While(k,c,loopBody()); }
        if(accept("LOOP")) return new Loop(k,loopBody());
        if(accept("EXIT")) { Expr when=accept("WHEN") ? expr(STOP_STATEMENT) : null; expect(";"); return new Exit(k,when); }
        if(accept("RETURN")) { Expr v=at(";") ? null : expr(STOP_STATEMENT); expect(";"); return new Return(k,v); }
        if(accept("RAISE")) { String n=at(";") ? null : name().id(); expect(";"); return new Raise(k,n); }
        if(at("EXECUTE") && peek(1).is("IMMEDIATE")) { next(); next(); var e=expr(STOP_STATEMENT); expect(";"); return new Immediate(k,e); }
        if(Set.of("SELECT","WITH","INSERT","UPDATE","DELETE","MERGE","COMMIT","ROLLBACK","SAVEPOINT","LOCK").stream().anyMatch(this::at)) return sql(k);
        if(!k.name() && k.type!=T.PARAM) throw new Syntax("unexpected '" + k + "'",k);
        var target=new ArrayList<Tok>();
        target.add(next());
        while(at(".") && peek(1).name()) { next(); target.add(next()); }
        if(accept(":=")) { var v=expr(STOP_STATEMENT); expectEnd(); return new Assign(k,target,v); }
        var args=new ArrayList<Arg>();
        if(accept("(")) {
            if(!at(")")) do {
                String named=null;
                if(peek().name() && peek(1).is("=>")) { named=next().id(); next(); }
                args.add(new Arg(named,expr(Set.of(",",")"))));
            } while(accept(","));
            expect(")");
        }
        expectEnd();
        return new Call(k,target,args);
    }
    private void expectEnd() {
        if(!at(";")) throw new Syntax("expected ';' but found '" + peek() + "'",peek());
        next();
    }
    private List<Stmt> loopBody() {
        var body=statements(Set.of("END"));
        expect("END"); expect("LOOP"); expect(";");
        return body;
    }
    private Stmt sql(Tok k) {
        var toks=new ArrayList<Tok>(); var into=new ArrayList<List<Tok>>(); int depth=0;
        while(!eof() && !(depth==0 && at(";"))) {
            if(at("(")) depth++;
            if(at(")")) depth--;
            if(depth==0 && at("INTO") && k.is("SELECT") && into.isEmpty()) {
                next();
                do {
                    var target=new ArrayList<Tok>(); target.add(name());
                    while(at(".") && peek(1).name()) { next(); target.add(next()); }
                    into.add(target);
                } while(accept(","));
                continue;
            }
            toks.add(next());
        }
        expectEnd();
        return new Sql(k,new Expr(toks),into);
    }
    /** Tokens up to a stop word at paren/CASE depth 0. */
    Expr expr(Set<String> stops) {
        var toks=new ArrayList<Tok>(); int depth=0,cases=0;
        while(!eof()) {
            Tok k=peek();
            if(depth==0 && cases==0 && stops.stream().anyMatch(k::is)) break;
            if(k.is("(")) depth++;
            else if(k.is(")")) { if(depth==0) break; depth--; }
            else if(k.is("CASE")) cases++;
            else if(k.is("END") && cases>0) cases--;
            else if(depth==0 && cases==0 && (k.is(";") || k.is("END") || k.is("THEN") || k.is("LOOP"))) break;
            toks.add(next());
        }
        if(toks.isEmpty()) throw new Syntax("expected an expression but found '" + peek() + "'",peek());
        return new Expr(toks);
    }

    static String text(List<Tok> toks) {
        var b=new StringBuilder(); Tok prev=null;
        for(var k:toks) {
            if(prev!=null && !(k.is(".") || prev.is(".") || k.is(",") || k.is(")") || prev.is("(") || k.is("(") && prev.name())) b.append(' ');
            b.append(render(k)); prev=k;
        }
        return b.toString();
    }
    static String render(Tok k) {
        return switch(k.type) {
            case STRING -> "'" + k.text.replace("'","''") + "'";
            case QUOTED -> '"' + k.text + '"';
            default -> k.text;
        };
    }
}
