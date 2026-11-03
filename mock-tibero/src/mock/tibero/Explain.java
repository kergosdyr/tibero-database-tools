package mock.tibero;

import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** EXPLAIN PLAN into PLAN_TABLE, shaped like a Tibero plan and derived from H2's own plan for the statement. */
final class Explain {
    private record Access(String owner,String table,String alias,String index,String condition) {}
    private record Row(int id,Integer parent,int depth,int position,String operation,String options,String owner,String object,String type,
                       long cost,long rows,String access,String filter) {}

    private static final Pattern SOURCE=Pattern.compile("(?m)^\\s*(?:FROM|(?:INNER|LEFT OUTER|RIGHT OUTER|LEFT|RIGHT|CROSS)?\\s*JOIN)\\s+\"(\\w+)\"\\.\"(\\w+)\"(?:\\s+\"(\\w+)\")?\\s*\\n\\s*/\\* ([^*]*?) \\*/");
    private static final Pattern WHERE=Pattern.compile("(?s)\\nWHERE (.*?)(?:\\n(?:GROUP BY|ORDER BY|HAVING|FETCH|OFFSET|FOR UPDATE)\\b|$)");

    static void plan(Session s,String statementId,String sql) throws SQLException {
        String plan;
        try(var st=s.h2.createStatement(); var rs=st.executeQuery("EXPLAIN " + sql)) { rs.next(); plan=rs.getString(1); }
        catch(SQLException e) { throw Session.translate(e,sql); }
        var sources=new ArrayList<Access>();
        var m=SOURCE.matcher(plan);
        while(m.find()) {
            String comment=m.group(4), index=null, condition=null;
            if(!comment.endsWith(".tableScan")) {
                int colon=comment.indexOf(':');
                index=(colon<0 ? comment : comment.substring(0,colon)).replaceFirst("^\\w+\\.","").trim();
                condition=colon<0 ? null : comment.substring(colon+1).trim();
            }
            sources.add(new Access(m.group(1),m.group(2),m.group(3),index,condition));
        }
        String upper=sql.trim().toUpperCase(Locale.ROOT);
        String statement=upper.startsWith("INSERT") ? "INSERT" : upper.startsWith("UPDATE") ? "UPDATE" : upper.startsWith("DELETE") ? "DELETE" : upper.startsWith("MERGE") ? "MERGE" : "SELECT";
        var where=WHERE.matcher(plan);
        String filter=where.find() ? where.group(1).replaceAll("\\s+"," ").replace("\"","").trim() : null;

        var rows=new ArrayList<Row>();
        int[] next={0};
        long total=0;
        var cards=new HashMap<String,Long>();
        for(var a:sources) { long c=count(s,a); cards.put(a.owner() + "." + a.table() + "." + a.alias(),c); total+=c; }
        rows.add(new Row(next[0]++,null,0,1,statement + " STATEMENT",null,null,null,null,1 + total/10,Math.max(1,total),null,null));
        int parent=0, depth=1;
        if(!statement.equals("SELECT")) { rows.add(new Row(next[0]++,parent,depth,1,statement,null,sources.isEmpty() ? null : sources.get(0).owner(),sources.isEmpty() ? null : sources.get(0).table(),null,1 + total/10,1,null,null)); parent=next[0]-1; depth++; }
        if(upper.contains("ORDER BY")) { rows.add(new Row(next[0]++,parent,depth,1,"SORT","ORDER BY",null,null,null,2 + total/10,Math.max(1,total),null,null)); parent=next[0]-1; depth++; }
        if(upper.contains("GROUP BY")) { rows.add(new Row(next[0]++,parent,depth,1,"HASH","GROUP BY",null,null,null,2 + total/10,Math.max(1,total/2),null,null)); parent=next[0]-1; depth++; }
        else if(Pattern.compile("(?i)\\b(COUNT|SUM|AVG|MIN|MAX)\\s*\\(").matcher(sql).find() && statement.equals("SELECT")) { rows.add(new Row(next[0]++,parent,depth,1,"SORT","AGGREGATE",null,null,null,1 + total/10,1,null,null)); parent=next[0]-1; depth++; }
        if(sources.isEmpty()) rows.add(new Row(next[0]++,parent,depth,1,"FAST DUAL",null,null,null,null,1,1,null,null));
        else access(s,rows,next,parent,depth,sources,sources.size()-1,cards,filter);

        String id=statementId==null ? "" : statementId;
        try(var del=s.h2.prepareStatement("DELETE FROM SYS.PLAN_TABLE WHERE STATEMENT_ID = ?")) { del.setString(1,id); del.executeUpdate(); }
        try(var ins=s.h2.prepareStatement("INSERT INTO SYS.PLAN_TABLE(STATEMENT_ID, PLAN_ID, \"TIMESTAMP\", ID, PARENT_ID, DEPTH, POSITION, OPERATION, OPTIONS, OBJECT_OWNER, OBJECT_NAME, OBJECT_TYPE, COST, CARDINALITY, BYTES, ACCESS_PREDICATES, FILTER_PREDICATES)"
                + " VALUES(?, 1, LOCALTIMESTAMP(0), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for(var r:rows) {
                ins.setString(1,id); ins.setInt(2,r.id()); ins.setObject(3,r.parent()); ins.setInt(4,r.depth()); ins.setInt(5,r.position());
                ins.setString(6,r.operation()); ins.setString(7,r.options()); ins.setString(8,r.owner()); ins.setString(9,r.object()); ins.setString(10,r.type());
                ins.setLong(11,r.cost()); ins.setLong(12,r.rows()); ins.setLong(13,r.rows()*40); ins.setString(14,r.access()); ins.setString(15,r.filter());
                ins.executeUpdate();
            }
        }
    }
    /** Left-deep join tree: join(join(t1, t2), t3) ... */
    private static void access(Session s,List<Row> rows,int[] next,int parent,int depth,List<Access> sources,int last,Map<String,Long> cards,String filter) {
        if(last==0) { table(rows,next,parent,depth,1,sources.get(0),cards,filter); return; }
        var right=sources.get(last);
        long card=cards.values().stream().mapToLong(Long::longValue).sum();
        boolean nested=right.index()!=null;
        rows.add(new Row(next[0]++,parent,depth,1,nested ? "NESTED LOOPS" : "HASH JOIN",null,null,null,null,3 + card/5,Math.max(1,card/2),nested ? null : right.condition(),null));
        int join=next[0]-1;
        access(s,rows,next,join,depth+1,sources,last-1,cards,last==1 ? filter : null);
        table(rows,next,join,depth+1,2,right,cards,null);
    }
    private static void table(List<Row> rows,int[] next,int parent,int depth,int position,Access a,Map<String,Long> cards,String filter) {
        long card=cards.getOrDefault(a.owner() + "." + a.table() + "." + a.alias(),1L);
        if(a.index()==null) { rows.add(new Row(next[0]++,parent,depth,position,"TABLE ACCESS","FULL",a.owner(),a.table(),"TABLE",1 + card/10,Math.max(1,card),null,filter)); return; }
        boolean unique=a.index().startsWith("PRIMARY_KEY") || a.index().contains("_PK") || a.index().startsWith("PK_");
        rows.add(new Row(next[0]++,parent,depth,position,"TABLE ACCESS","BY ROWID",a.owner(),a.table(),"TABLE",2,1,null,filter));
        rows.add(new Row(next[0]++,next[0]-2,depth+1,1,"INDEX",unique ? "UNIQUE SCAN" : "RANGE SCAN",a.owner(),a.index(),unique ? "INDEX (UNIQUE)" : "INDEX",1,1,a.condition()==null ? null : a.condition().replace("\"",""),null));
    }
    private static long count(Session s,Access a) {
        try(var st=s.h2.createStatement(); var rs=st.executeQuery("SELECT COUNT(*) FROM \"" + a.owner() + "\".\"" + a.table() + "\"")) { rs.next(); return rs.getLong(1); }
        catch(SQLException e) { return 1; }
    }
}
