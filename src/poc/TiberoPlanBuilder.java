package poc;

import com.intellij.database.dialects.base.plan.AbstractPlanModelBuilder;
import com.intellij.database.plan.PlanModel;
import java.math.BigDecimal;
import java.util.EnumSet;

/** Uses a validated parent-ID tree rather than the base builder's recursive parser state. */
public final class TiberoPlanBuilder extends AbstractPlanModelBuilder<TiberoRawPlanData, TiberoPlan.Row> {
    public TiberoPlanBuilder() { super(EnumSet.of(PlanModel.Feature.STARTUP_COST)); }
    @Override public TiberoRawPlanData createData() { return new TiberoRawPlanData(); }
    @Override public PlanModel build(TiberoRawPlanData data) { myData=data; return data.plan().model(); }
    protected boolean hasRawData() { return myData != null; }
    protected String getRawData() { return myData.dump(); }
    @Override protected void parseData() { throw new UnsupportedOperationException("build handles the complete tree"); }
    @Override protected String parseRawDescription(TiberoPlan.Row r) { return r.operation(); }
    @Override protected String parseAccessRelation(TiberoPlan.Row r) { return r.object(); }
    @Override protected BigDecimal parsePlanNumRows(TiberoPlan.Row r) { return r.rows(); }
    @Override protected String parseAccessIndex(TiberoPlan.Row r) { return r.object(); }
    @Override protected void parsePlan(TiberoPlan.Row r) { throw new UnsupportedOperationException(); }
    @Override protected void parseSubPlans(TiberoPlan.Row r) { throw new UnsupportedOperationException(); }
    @Override protected void parseStatement(TiberoPlan.Row r) { throw new UnsupportedOperationException(); }
    @Override protected Double parseTotalCost(TiberoPlan.Row r) { return r.cost()==null?null:r.cost().doubleValue(); }
    @Override protected Double parseStartupCost(TiberoPlan.Row r) { return null; }
    @Override protected boolean parseSubqueryCorrelated(TiberoPlan.Row r) { return false; }
    @Override protected boolean parseSubqueryScalar(TiberoPlan.Row r) { return false; }
}
