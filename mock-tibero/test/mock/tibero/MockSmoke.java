package mock.tibero;

import java.sql.*;
import java.util.*;

/** Plain-JDBC checks of the mock: driver metadata SQL, DBMS_OUTPUT, ALL_ERRORS, routines with OUT values, EXPLAIN PLAN. */
public final class MockSmoke {
    static int failures;
    static void check(boolean ok,String what) {
        System.out.println((ok ? "PASS: " : "FAIL: ") + what);
        if(!ok) failures++;
    }
    static List<Map<String,String>> rows(ResultSet rs) throws SQLException {
        var out=new ArrayList<Map<String,String>>(); var md=rs.getMetaData();
        while(rs.next()) { var row=new LinkedHashMap<String,String>(); for(int i=1;i<=md.getColumnCount();i++) row.put(md.getColumnLabel(i).toUpperCase(Locale.ROOT),rs.getString(i)); out.add(row); }
        rs.close();
        return out;
    }
    static List<String> output(Connection c) throws SQLException {
        var lines=new ArrayList<String>();
        while(true) {
            try(var call=c.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;")) {
                call.registerOutParameter(1,Types.VARCHAR); call.registerOutParameter(2,Types.INTEGER);
                call.execute();
                if(call.getInt(2)!=0) return lines;
                lines.add(call.getString(1));
            }
        }
    }
    static void run(Connection c,String sql) throws SQLException { try(var st=c.createStatement()) { st.execute(sql); } }
    static String value(Connection c,String sql) throws SQLException {
        try(var st=c.createStatement(); var rs=st.executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
    }

    public static void main(String[] argv) throws Exception {
        Class.forName("mock.tibero.MockTiberoDriver");
        var props=new Properties(); props.setProperty("user","demo"); props.setProperty("password","any");
        try(var c=DriverManager.getConnection("jdbc:tibero:thin:@localhost:8629:tibero",props)) {
            var md=c.getMetaData();
            check(md.getDatabaseProductName().equals("Tibero") && md.getUserName().equals("DEMO"),"product Tibero, user DEMO");
            var schemas=rows(md.getSchemas());
            check(schemas.stream().anyMatch(r -> "DEMO".equals(r.get("TABLE_SCHEM"))) && schemas.stream().anyMatch(r -> "HR".equals(r.get("TABLE_SCHEM"))),"getSchemas (driver QUERY_SCHEMAS) lists DEMO and HR: " + schemas.size());
            var tables=rows(md.getTables(null,"DEMO","%",new String[]{"TABLE","VIEW"}));
            check(tables.stream().map(r -> r.get("TABLE_NAME")).toList().containsAll(List.of("DEPT","EMP","TYPE_SAMPLES","EMP_V")),"getTables (driver QUERY_TABLES with (+) join) " + tables.stream().map(r -> r.get("TABLE_NAME") + ":" + r.get("TABLE_TYPE") + ":" + r.get("REMARKS")).toList());
            var columns=new LinkedHashMap<String,Map<String,String>>();
            for(var r:rows(md.getColumns(null,"DEMO","TYPE_SAMPLES","%"))) columns.put(r.get("COLUMN_NAME"),r);
            for(var r:rows(md.getColumns(null,"DEMO","EMP","%"))) columns.put("EMP." + r.get("COLUMN_NAME"),r);
            columns.forEach((k,r) -> System.out.println("  getColumns " + k + " TYPE_NAME=" + r.get("TYPE_NAME") + " DATA_TYPE=" + r.get("DATA_TYPE") + " COLUMN_SIZE=" + r.get("COLUMN_SIZE") + " DECIMAL_DIGITS=" + r.get("DECIMAL_DIGITS")));
            check(Objects.equals(columns.get("TS").get("TYPE_NAME"),"TIMESTAMP(6)") && "6".equals(columns.get("TS").get("COLUMN_SIZE")) && "6".equals(columns.get("TS").get("DECIMAL_DIGITS")) && "93".equals(columns.get("TS").get("DATA_TYPE")),
                    "TIMESTAMP(6) column reported as TYPE_NAME TIMESTAMP(6), size 6, digits 6 (the TIMESTAMP(6,6) trigger)");
            check("DATE".equals(columns.get("D").get("TYPE_NAME")) && "7".equals(columns.get("D").get("COLUMN_SIZE")),"DATE reported with COLUMN_SIZE 7 (the DATE(7) trigger)");
            check(columns.get("N").get("COLUMN_SIZE")==null && columns.get("N").get("DECIMAL_DIGITS")==null,"unconstrained NUMBER has NULL size and digits");
            check("INTERVAL DAY(2) TO SECOND(6)".equals(columns.get("IDS").get("TYPE_NAME")),"INTERVAL keeps its name");
            check("TIMESTAMP(3) WITH TIME ZONE".equals(columns.get("TS_TZ").get("TYPE_NAME")),"TIMESTAMP WITH TIME ZONE keeps its name");
            check("VARCHAR2".equals(columns.get("V").get("TYPE_NAME")) && "100".equals(columns.get("V").get("COLUMN_SIZE")),"VARCHAR2(100)");
            var pk=rows(md.getPrimaryKeys(null,"DEMO","EMP"));
            check(pk.size()==1 && "EMPNO".equals(pk.get(0).get("COLUMN_NAME")) && "EMP_PK".equals(pk.get(0).get("PK_NAME")),"getPrimaryKeys " + pk);
            var fk=rows(md.getImportedKeys(null,"DEMO","EMP"));
            check(fk.size()==1 && "DEPT".equals(fk.get(0).get("PKTABLE_NAME")) && "EMP_DEPT_FK".equals(fk.get(0).get("FK_NAME")),"getImportedKeys " + fk);
            var ix=rows(md.getIndexInfo(null,"DEMO","EMP",false,true));
            check(ix.stream().anyMatch(r -> "EMP_DEPT_IX".equals(r.get("INDEX_NAME"))) && ix.stream().anyMatch(r -> "EMP_PK".equals(r.get("INDEX_NAME"))) && ix.stream().noneMatch(r -> String.valueOf(r.get("INDEX_NAME")).contains("_INDEX_")),"getIndexInfo " + ix.stream().map(r -> r.get("INDEX_NAME")).toList());
            var procs=rows(md.getProcedures(null,"DEMO","%"));
            check(procs.stream().map(r -> r.get("PROCEDURE_NAME")).toList().containsAll(List.of("FN_YEARS","RAISE_SALARY","HEADCOUNT","HIRE")),"getProcedures " + procs.stream().map(r -> r.get("PROCEDURE_CAT") + "." + r.get("PROCEDURE_NAME") + ":" + r.get("PROCEDURE_TYPE")).toList());
            var args=rows(md.getProcedureColumns(null,"DEMO","%","%"));
            args.forEach(r -> System.out.println("  getProcedureColumns " + r.get("PROCEDURE_CAT") + "." + r.get("PROCEDURE_NAME") + " " + r.get("COLUMN_NAME") + " COLUMN_TYPE=" + r.get("COLUMN_TYPE") + " TYPE_NAME=" + r.get("TYPE_NAME") + " PRECISION=" + r.get("PRECISION") + " SCALE=" + r.get("SCALE")));
            check(args.stream().anyMatch(r -> "FN_YEARS".equals(r.get("PROCEDURE_NAME")) && r.get("COLUMN_NAME")==null && "5".equals(r.get("COLUMN_TYPE"))),"function return comes back as COLUMN_TYPE 5");
            check(args.stream().anyMatch(r -> "HIRE".equals(r.get("PROCEDURE_NAME")) && "P_HIRED_AT".equals(r.get("COLUMN_NAME")) && "2".equals(r.get("COLUMN_TYPE"))),"IN OUT argument comes back as COLUMN_TYPE 2");
            try { rows(md.getFunctionColumns(null,"DEMO","%","%")); check(false,"getFunctionColumns should fail like the real Tibero 6 driver"); }
            catch(SQLException e) { check(e.getMessage().startsWith("JDBC-8012: Missing comma."),"getFunctionColumns reproduces the driver bug: " + e.getMessage().lines().findFirst().orElse("")); }

            try(var st=c.createStatement(); var rs=st.executeQuery("SELECT EMPNO, ENAME, SAL, HIREDATE, CREATED_AT FROM EMP ORDER BY EMPNO")) {
                var m=rs.getMetaData();
                check(m.getColumnTypeName(3).equals("NUMBER") && m.getColumnTypeName(4).equals("DATE") && m.getColumnTypeName(5).equals("TIMESTAMP"),"result set type names NUMBER/DATE/TIMESTAMP");
            }
            run(c,"BEGIN DBMS_OUTPUT.ENABLE(1000000); END;");
            run(c,"BEGIN REPORT_DEPT(30); END;");
            var lines=output(c);
            run(c,"BEGIN DBMS_OUTPUT.DISABLE; END;");
            System.out.println("  DBMS_OUTPUT " + lines);
            check(lines.size()==3 && lines.get(0).startsWith("PARK") && lines.get(2).startsWith("TOTAL"),"DBMS_OUTPUT from a cursor FOR loop via GET_LINE(?, ?)");
            run(c,"BEGIN DBMS_OUTPUT.PUT_LINE('lost'); END;");
            run(c,"BEGIN DBMS_OUTPUT.ENABLE(1000000); END;");
            check(output(c).isEmpty(),"PUT_LINE while disabled is discarded");

            try(var st=c.createStatement()) {
                st.execute("CREATE OR REPLACE PROCEDURE BAD_PROC(P_X IN NUMBER) IS\n  v_a NUMBER;\nBEGIN\n  v_b := P_X;\nEND;");
                check(st.getWarnings()!=null && st.getWarnings().getMessage().contains("compilation errors"),"CREATE with errors succeeds with a warning");
            }
            var errors=rows(c.createStatement().executeQuery("SELECT LINE, POSITION, TEXT FROM ALL_ERRORS WHERE OWNER = USER AND NAME = 'BAD_PROC' AND TYPE = 'PROCEDURE' ORDER BY LINE, POSITION"));
            check(errors.size()==1 && "4".equals(errors.get(0).get("LINE")) && "3".equals(errors.get(0).get("POSITION")),"ALL_ERRORS line/column relative to the source: " + errors);
            var status=rows(c.createStatement().executeQuery("SELECT OBJECT_NAME, STATUS FROM ALL_OBJECTS WHERE OWNER = 'DEMO' AND OBJECT_TYPE = 'PROCEDURE' ORDER BY OBJECT_NAME"));
            check(status.contains(Map.of("OBJECT_NAME","BAD_PROC","STATUS","INVALID")) && status.contains(Map.of("OBJECT_NAME","CALC_BONUS","STATUS","INVALID")),"invalid procedures are INVALID in ALL_OBJECTS: " + status);
            try { run(c,"BEGIN BAD_PROC(1); END;"); check(false,"calling an invalid procedure fails"); }
            catch(SQLException e) { check(e.getMessage().contains("invalid"),"calling an invalid procedure fails: " + e.getMessage()); }
            run(c,"DROP PROCEDURE BAD_PROC");
            check(rows(c.createStatement().executeQuery("SELECT * FROM ALL_ERRORS WHERE NAME = 'BAD_PROC'")).isEmpty(),"DROP PROCEDURE clears ALL_ERRORS");
            var source=rows(c.createStatement().executeQuery("SELECT TEXT FROM ALL_SOURCE WHERE OWNER = 'DEMO' AND NAME = 'FN_YEARS' AND TYPE = 'FUNCTION' ORDER BY LINE"));
            check(source.get(0).get("TEXT").startsWith("FUNCTION FN_YEARS(") && source.get(0).get("TEXT").endsWith("\n"),"ALL_SOURCE starts at the object keyword, lines keep newlines");

            // The block TiberoRoutineScript generates for "Execute Routine".
            run(c,"""
                -- Tibero: execute DEMO.FN_YEARS. Set the IN values, then run the whole block.
                DECLARE
                  v_result NUMBER;
                  v_p_from TIMESTAMP := NULL; -- IN P_FROM
                  v_p_to DATE := NULL; -- IN P_TO
                BEGIN
                  v_p_from := TIMESTAMP '2019-03-02 09:00:00';
                  v_p_to := DATE '2026-10-18';
                  v_result := DEMO.FN_YEARS(P_FROM => v_p_from, P_TO => v_p_to);
                  DBMS_OUTPUT.PUT_LINE('RETURN = ' || v_result);
                END;
                """);
            var ret=output(c);
            check(ret.equals(List.of("RETURN = 7")),"generated Execute Routine block runs: " + ret);
            run(c,"""
                DECLARE
                  v_p_empno NUMBER; -- OUT P_EMPNO
                  v_p_hired_at TIMESTAMP := NULL; -- INOUT P_HIRED_AT
                BEGIN
                  DEMO.HR_API.HIRE(P_NAME => 'YOON', P_DEPTNO => 20, P_EMPNO => v_p_empno, P_HIRED_AT => v_p_hired_at);
                  DBMS_OUTPUT.PUT_LINE('P_EMPNO = ' || v_p_empno);
                END;
                """);
            var hire=output(c);
            check(hire.size()==2 && hire.get(0).startsWith("hired YOON as 2000, headcount 3") && hire.get(1).equals("P_EMPNO = 2000"),"package procedure with OUT/IN OUT and nested function call: " + hire);
            try(var call=c.prepareCall("{call RAISE_SALARY(?, ?, ?)}")) {
                call.setInt(1,1005); call.setBigDecimal(2,new java.math.BigDecimal("10")); call.registerOutParameter(3,Types.NUMERIC);
                call.execute();
                check(call.getBigDecimal(3).compareTo(new java.math.BigDecimal("2750"))==0,"JDBC {call} with OUT parameter: " + call.getBigDecimal(3));
            }
            output(c);
            try { run(c,"BEGIN RAISE_SALARY(9999, 1, :x); END;"); } catch(SQLException e) { System.out.println("  (expected) " + e.getMessage()); }
            try { run(c,"DECLARE v NUMBER; BEGIN RAISE_SALARY(9999, 1, v); END;"); check(false,"application error"); }
            catch(SQLException e) { check(e.getMessage().contains("-20001: No employee 9999"),"RAISE_APPLICATION_ERROR surfaces: " + e.getMessage()); }
            run(c,"BEGIN CALC_BONUS(1001); EXCEPTION WHEN OTHERS THEN DBMS_OUTPUT.PUT_LINE('caught: ' || SQLERRM); END;");
            check(output(c).get(0).startsWith("caught: [mock] PROCEDURE DEMO.CALC_BONUS is invalid"),"exception handler sees SQLERRM");

            run(c,"EXPLAIN PLAN SET STATEMENT_ID = 'S1' FOR SELECT e.ENAME, d.DNAME FROM EMP e JOIN DEPT d ON d.DEPTNO = e.DEPTNO WHERE e.SAL > 3000 ORDER BY e.ENAME");
            var plan=rows(c.createStatement().executeQuery("SELECT * FROM PLAN_TABLE WHERE STATEMENT_ID = 'S1' ORDER BY ID"));
            plan.forEach(r -> System.out.println("  PLAN " + r.get("ID") + " parent=" + r.get("PARENT_ID") + " " + r.get("OPERATION") + " " + Objects.toString(r.get("OPTIONS"),"") + " " + Objects.toString(r.get("OBJECT_NAME"),"") + " cost=" + r.get("COST") + " rows=" + r.get("CARDINALITY") + " access=" + r.get("ACCESS_PREDICATES") + " filter=" + r.get("FILTER_PREDICATES")));
            check(plan.size()>=5 && plan.get(0).get("OPERATION").equals("SELECT STATEMENT") && plan.stream().anyMatch(r -> "EMP".equals(r.get("OBJECT_NAME"))),"EXPLAIN PLAN fills PLAN_TABLE");
            run(c,"DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = 'S1'");
            try(var st=c.createStatement(); var rs=st.executeQuery("SELECT SYSDATE - 1 FROM DUAL")) { check(rs.next(),"SELECT ... FROM DUAL"); }
            try(var st=c.createStatement()) { st.execute("CREATE TABLE T_NEW (ID NUMBER(5), AT TIMESTAMP(6), NOTE VARCHAR2(20 BYTE)) TABLESPACE USR PCTFREE 10"); }
            var newCols=rows(md.getColumns(null,"DEMO","T_NEW","%"));
            check(newCols.size()==3 && "TIMESTAMP(6)".equals(newCols.get(1).get("TYPE_NAME")),"CREATE TABLE from the console updates the dictionary: " + newCols.stream().map(r -> r.get("TYPE_NAME")).toList());
            try { run(c,"SELECT * FROM NO_SUCH_TABLE"); } catch(SQLException e) { check(e.getMessage().startsWith("[mock] table or view does not exist"),"missing table error: " + e.getMessage()); }

            // The plugin's routine detail query, with and without DATA_LEVEL.
            try { run(c,"SELECT OWNER, DATA_LEVEL FROM ALL_ARGUMENTS WHERE OWNER = 'DEMO' AND DATA_LEVEL = 0"); check(false,"ALL_ARGUMENTS has no DATA_LEVEL column"); }
            catch(SQLException e) { check(e.getMessage().startsWith("[mock] invalid column name"),"ALL_ARGUMENTS.DATA_LEVEL rejected like the reported server: " + e.getMessage().lines().findFirst().orElse("")); }
            var plainArgs=rows(c.createStatement().executeQuery("SELECT OWNER, OBJECT_NAME, PACKAGE_NAME, OVERLOAD, POSITION, 0 AS DATA_LEVEL, ARGUMENT_NAME, DATA_TYPE, IN_OUT, TYPE_OWNER, TYPE_NAME, TYPE_SUBNAME FROM ALL_ARGUMENTS WHERE OWNER = 'DEMO' ORDER BY PACKAGE_NAME, OBJECT_NAME, OVERLOAD, POSITION"));
            check(plainArgs.stream().anyMatch(r -> "FN_YEARS".equals(r.get("OBJECT_NAME")) && "0".equals(r.get("DATA_LEVEL"))),"ALL_ARGUMENTS query without DATA_LEVEL: " + plainArgs.size() + " rows");

            // The plugin's synonym query (Tibero column names).
            var synonyms=rows(c.createStatement().executeQuery("SELECT OWNER, SYNONYM_NAME, ORG_OBJECT_OWNER, ORG_OBJECT_NAME FROM ALL_SYNONYMS WHERE OWNER IN ('HR', 'PUBLIC') AND ORG_OBJECT_OWNER IS NOT NULL AND ORG_OBJECT_NAME IS NOT NULL"));
            check(synonyms.contains(Map.of("OWNER","PUBLIC","SYNONYM_NAME","EMP","ORG_OBJECT_OWNER","DEMO","ORG_OBJECT_NAME","EMP")),"PUBLIC synonym EMP -> DEMO.EMP in ALL_SYNONYMS: " + synonyms);
            try { run(c,"SELECT TABLE_OWNER FROM ALL_SYNONYMS"); check(false,"Oracle synonym columns absent"); }
            catch(SQLException e) { check(e.getMessage().startsWith("[mock] invalid column name"),"Oracle TABLE_OWNER column absent like Tibero"); }

            // Tibero 6 JDBC cannot report the session schema; the plugin reads it with SQL.
            check(md.getUserName().equals("DEMO") && c.getCatalog()==null,"login user DEMO, no JDBC catalog");
            try { c.getSchema(); check(false,"getSchema() unsupported"); } catch(AbstractMethodError e) { check(true,"getSchema() unsupported like Tibero 6 JDBC"); }
            check("DEMO".equals(value(c,"SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) && "DEMO".equals(value(c,"SELECT USER FROM DUAL")),"CURRENT_SCHEMA and USER are the login schema");
            run(c,"ALTER SESSION SET CURRENT_SCHEMA = HR");
            check("HR".equals(value(c,"SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL")) && "DEMO".equals(value(c,"SELECT USER FROM DUAL")),"ALTER SESSION SET CURRENT_SCHEMA moves CURRENT_SCHEMA, not USER");
            run(c,"ALTER SESSION SET CURRENT_SCHEMA = DEMO");
            try { value(c,"SELECT SYS_CONTEXT('NOPE', 'CURRENT_SCHEMA') FROM DUAL"); check(false,"unknown SYS_CONTEXT namespace"); }
            catch(SQLException e) { check(e.getMessage().contains("JDBC-11039"),"unknown SYS_CONTEXT namespace: " + e.getMessage().lines().findFirst().orElse("")); }
        }
        try { DriverManager.getConnection("jdbc:tibero:thin:@localhost:8629:tibero","SCOTT","x"); check(false,"unknown user rejected"); }
        catch(SQLException e) { check(e.getMessage().contains("login failed"),"unknown user rejected"); }
        System.out.println(failures==0 ? "MOCK SMOKE: ALL PASS" : "MOCK SMOKE: " + failures + " FAILURES");
        if(failures>0) System.exit(1);
    }
}
