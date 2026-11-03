package poc;

import java.util.*;
import java.sql.SQLException;
import com.intellij.database.plan.PlanRetrievalException;

final class ActualPlanCheck {
    static void run() throws Exception {
        for (String sql : List.of("SELECT 1 FROM DUAL", "/* lead */ WITH X AS (SELECT 1 N FROM DUAL) SELECT N FROM X; -- end",
                "SELECT 'UPDATE', q'[DELETE;]', \"INTO\" FROM DUAL")) ActualPlanSession.request(sql);
        for (String sql : List.of("DELETE FROM T", "UPDATE T SET X=1", "INSERT INTO T VALUES(1)",
                "WITH X AS (SELECT 1 FROM DUAL) DELETE FROM T", "SELECT * FROM T FOR UPDATE",
                "SELECT X INTO Y FROM T", "WITH FUNCTION F RETURN NUMBER IS BEGIN RETURN 1; END; SELECT F FROM DUAL",
                "SELECT 1 FROM DUAL; DELETE FROM T", "SELECT 'unterminated")) {
            try { ActualPlanSession.request(sql); throw new AssertionError("Accepted: " + sql); }
            catch (IllegalArgumentException expected) { }
        }
        var request = ActualPlanSession.request("SELECT 1 FROM DUAL");
        check(!request.marker().equals(ActualPlanSession.request("SELECT 1 FROM DUAL").marker()), "Request isolation");
        check(ActualPlanSession.cursorSql(request).contains("LIKE '/* " + request.marker() + " */%'"), "Anchored lookup");
        try { ActualPlanSession.planSql("x' OR 1=1", "0"); throw new AssertionError("Cursor injection accepted"); }
        catch (PlanRetrievalException expected) { }
        var columns = new HashMap<String,String>(Map.of("ID", "1", "OPERATION", "SELECT", "CARDINALITY", "10",
                "LAST_STARTS", "2", "LAST_OUTPUT_ROWS", "0", "LAST_ELAPSED_TIME", "1.25", "LAST_CR_BUFFER_GETS", "4"));
        var calls = new ArrayList<String>();
        var operations = new ActualPlanSession.Operations() {
            public void executeAndDrain(String sql) { calls.add("drain:" + sql); }
            public List<Map<String,String>> query(String sql) {
                calls.add(sql);
                if (sql.contains("1 = 0")) return List.of();
                if (sql.contains("FROM V$SQLTEXT_WITH_NEWLINES")) return List.of(Map.of("SQL_ID", "abc123", "CHILD_NUMBER", "42"));
                check(sql.contains("P.CHILD_NUMBER = 42") && sql.contains("P.SQL_ID = 'abc123'"), "Exact cursor");
                return List.of(columns);
            }
        };
        var plan = ActualPlanSession.fetch(operations, request);
        check(calls.size() == 5 && calls.get(2).equals("drain:" + request.sql()), "Preflight, full fetch, cursor, statistics order");
        var model = plan.model(); var root = model.getRoot().getChildren()[0];
        check(model.isActual() && root.getPlanNumRows().intValueExact() == 10 && root.getActualNumRows().signum() == 0,
                "Zero actual rows remain distinct from estimates");
        check(root.getActualTotalTime() == 1.25 && root.getRawDescription().contains("Starts (last execution)=2"), "Time in ms and starts");
        check(TiberoPlan.parse(plan.json()).model().isActual() && TiberoPlan.parse(plan.json()).rows().equals(plan.rows()), "Actual JSON round trip");
        columns.remove("LAST_STARTS");
        try { ActualPlanSession.fetch(operations, request); throw new AssertionError("Missing statistics accepted"); }
        catch (PlanRetrievalException expected) { check(expected.getMessage().contains("GATHER_SQL_PLAN_STAT"), "Setup instructions"); }
        for (int count : new int[]{0, 2}) {
            var ambiguous = new ActualPlanSession.Operations() {
                public void executeAndDrain(String sql) { }
                public List<Map<String,String>> query(String sql) {
                    if (sql.contains("1 = 0")) return List.of();
                    if (!sql.contains("FROM V$SQLTEXT_WITH_NEWLINES")) throw new AssertionError("Read ambiguous plan");
                    return Collections.nCopies(count, Map.of("SQL_ID", "abc", "CHILD_NUMBER", "1"));
                }
            };
            try { ActualPlanSession.fetch(ambiguous, request); throw new AssertionError("Ambiguous cursor accepted"); }
            catch (PlanRetrievalException expected) { }
        }
        var denied = new ActualPlanSession.Operations() {
            public void executeAndDrain(String sql) { throw new AssertionError("Executed after denied preflight"); }
            public List<Map<String,String>> query(String sql) throws SQLException { throw new SQLException("permission denied"); }
        };
        try { ActualPlanSession.fetch(denied, request); throw new AssertionError("Permission failure ignored"); }
        catch (SQLException expected) { }
        var failed = new ActualPlanSession.Operations() {
            public void executeAndDrain(String sql) throws SQLException { throw new SQLException("cancelled"); }
            public List<Map<String,String>> query(String sql) {
                if (!sql.contains("1 = 0")) throw new AssertionError("Read statistics after incomplete execution");
                return List.of();
            }
        };
        try { ActualPlanSession.fetch(failed, request); throw new AssertionError("Execution failure ignored"); }
        catch (SQLException expected) { check(expected.getMessage().equals("cancelled"), "Original execution failure"); }
        System.out.println("PASS: actual plan query boundaries, cursor isolation, complete-fetch ordering, metrics/JSON, missing statistics, permissions and execution failures");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
