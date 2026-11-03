package poc;

import com.intellij.database.plan.PlanRetrievalException;
import java.util.*;

/** Executes and fully fetches one uniquely tagged query on the caller's connection. */
final class ActualPlanSession {
    interface Operations {
        List<Map<String, String>> query(String sql) throws java.sql.SQLException;
        void executeAndDrain(String sql) throws java.sql.SQLException;
    }
    record Request(String sql, String marker) {}
    static Request request(String sql) {
        sql = ExplainSql.analysisStatement(sql);
        String marker = "IJAN" + UUID.randomUUID().toString().replace("-", "");
        return new Request("/* " + marker + " */\n" + sql, marker);
    }
    static String cursorSql(Request request) {
        // Prefix match prevents this lookup SQL from matching itself. Never guess a child cursor.
        return "SELECT DISTINCT SQL_ID, CHILD_NUMBER FROM V$SQLTEXT_WITH_NEWLINES WHERE PIECE = 0 AND SQL_TEXT LIKE '/* "
                + request.marker() + " */%'";
    }
    static String planSql(String sqlId, String child) {
        if (sqlId == null || !sqlId.matches("[A-Za-z0-9]{1,13}") || child == null || !child.matches("[0-9]{1,10}"))
            throw new PlanRetrievalException("Invalid SQL cursor identity");
        return "SELECT P.ID, CASE WHEN P.DEPTH = 1 THEN NULL ELSE P.PARENT_ID END AS PARENT_ID, "
                + "P.POSITION, P.OPERATION, P.OBJECT_OWNER, P.OBJECT_NAME, P.COST, P.CARDINALITY, "
                + "P.ACCESS_PREDICATES, P.FILTER_PREDICATES, S.LAST_STARTS, S.LAST_OUTPUT_ROWS, "
                + "S.LAST_ELAPSED_TIME, S.LAST_CR_BUFFER_GETS "
                + "FROM V$SQL_PLAN P LEFT JOIN V$SQL_PLAN_STATISTICS S ON P.SQL_ID = S.SQL_ID "
                + "AND P.CHILD_NUMBER = S.CHILD_NUMBER AND P.ID = S.ID AND P.PLAN_HASH_VALUE = S.PLAN_HASH_VALUE "
                + "WHERE P.SQL_ID = '" + sqlId + "' AND P.CHILD_NUMBER = " + child + " ORDER BY P.ID";
    }
    static TiberoPlan fetch(Operations operations, Request request) throws java.sql.SQLException {
        // Check catalogue access/columns before executing the user's query.
        operations.query(planSql("0", "0").replace(" ORDER BY P.ID", " AND 1 = 0"));
        operations.query("SELECT SQL_ID, CHILD_NUMBER FROM V$SQLTEXT_WITH_NEWLINES WHERE 1 = 0");
        operations.executeAndDrain(request.sql());
        var cursors = operations.query(cursorSql(request));
        if (cursors.size() != 1) throw new PlanRetrievalException(
                "Expected one executed cursor; found " + cursors.size() + ". The query already ran. Check V$SQLTEXT_WITH_NEWLINES access and plan cache availability.");
        var cursor = cursors.get(0);
        var rows = operations.query(planSql(cursor.get("SQL_ID"), cursor.get("CHILD_NUMBER")));
        if (rows.isEmpty()) throw new PlanRetrievalException("The query ran, but its execution plan is no longer available.");
        return new TiberoPlan(rows.stream().map(TiberoPlan::row).toList(), true);
    }
}
