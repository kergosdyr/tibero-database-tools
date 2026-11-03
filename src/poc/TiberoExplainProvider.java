package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.base.plan.AbstractExplainPlanProvider;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.util.DdlBuilder;
import com.intellij.database.util.Version;
import java.util.UUID;

/** Native IDE plan tree and diagram pipeline backed by Tibero PLAN_TABLE. */
public final class TiberoExplainProvider extends AbstractExplainPlanProvider {
    public record PlanSql(String explain, String query, String cleanup) {}
    public static PlanSql planSql(String sql) {
        sql = ExplainSql.singleStatement(sql);
        String id = "IJ" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return new PlanSql("EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + sql,
                "SELECT * FROM PLAN_TABLE WHERE STATEMENT_ID = '" + id + "' ORDER BY ID",
                "DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + id + "'");
    }
    public TiberoExplainProvider() { super(GenericDbms.TIBERO); }

    @Override public boolean isSupported(Version version, boolean actual) { return true; }
    @Override public boolean isRawSupported(Version version, boolean actual) { return !actual; }

    @Override public TiberoPlanBuilder createBuilder(Version version, boolean actual) {
        return new TiberoPlanBuilder();
    }

    @Override public RawExplainTask createRawExplainTask(LocalDataSource source, boolean actual) {
        if (actual) throw new UnsupportedOperationException("Use Explain Analyse (tree) to execute and fully fetch the query; Raw supports estimated plans only");
        return new RawExplainTask() {
            @Override public DdlBuilder sqlExplainPlan(DdlBuilder builder, String sql) {
                var plan = planSql(sql);
                builder.plain(plan.explain()).newStatement();
                return builder.plain(plan.query());
            }
        };
    }
}
