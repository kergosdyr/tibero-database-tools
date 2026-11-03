package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.dialects.base.plan.RawPlanData;
import com.intellij.database.plan.PlanRetrievalException;
import java.util.*;

public final class TiberoRawPlanData extends RawPlanData {
    private TiberoPlan plan;
    public TiberoPlan plan() { if (plan == null) throw new PlanRetrievalException("No plan loaded"); return plan; }
    @Override public void load(String text) { plan = TiberoPlan.parse(text); }
    @Override public String dump() { return plan().json(); }
    @Override public void load(DatabaseConnectionCore connection, String sql, boolean actual) {
        if (actual) {
            var request = ActualPlanSession.request(sql);
            useStatementWithPreserved(connection, statement -> {
                plan = ActualPlanSession.fetch(new ActualPlanSession.Operations() {
                    @Override public void executeAndDrain(String query) throws java.sql.SQLException {
                        statement.noisy().execute(query, processing(rs -> {
                            while (rs.next()) com.intellij.openapi.progress.ProgressManager.checkCanceled();
                        }));
                    }
                    @Override public List<Map<String, String>> query(String query) throws java.sql.SQLException {
                        var result = new ArrayList<Map<String, String>>();
                        statement.noisy().execute(query, processing(rs -> {
                            var md = rs.getMetaData();
                            while (rs.next()) {
                                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                                if (result.size() >= TiberoPlan.MAX_NODES) throw new PlanRetrievalException("Plan too large");
                                var row = new HashMap<String, String>();
                                for (int i = 1; i <= md.getColumnCount(); i++)
                                    row.put(md.getColumnLabel(i).toUpperCase(Locale.ROOT), rs.getString(i));
                                result.add(row);
                            }
                        }));
                        return result;
                    }
                }, request);
            });
            return;
        }
        var queries = TiberoExplainProvider.planSql(sql);
        useStatementWithPreserved(connection, statement -> {
            plan = PlanSession.fetch(new PlanSession.Operations() {
              @Override public void execute(String query) throws java.sql.SQLException { statement.barren().execute(query); }
              @Override public TiberoPlan read(String query) throws java.sql.SQLException {
                var rows = new ArrayList<TiberoPlan.Row>();
                statement.noisy().execute(query, processing(rs -> {
                    var md = rs.getMetaData();
                    var labels = new ArrayList<String>();
                    for (int i=1; i<=md.getColumnCount(); i++) labels.add(md.getColumnLabel(i));
                    if (labels.stream().noneMatch("PARENT_ID"::equalsIgnoreCase))
                        throw new PlanRetrievalException("PLAN_TABLE must expose ID and PARENT_ID");
                    while (rs.next()) {
                        if (rows.size() >= TiberoPlan.MAX_NODES) throw new PlanRetrievalException("Plan too large");
                        var values = new HashMap<String,String>();
                        for (int i=0; i<labels.size(); i++) values.put(labels.get(i), rs.getString(i+1));
                        rows.add(TiberoPlan.row(values));
                    }
                }));
                return new TiberoPlan(rows);
              }
            }, queries);
        });
    }
}
