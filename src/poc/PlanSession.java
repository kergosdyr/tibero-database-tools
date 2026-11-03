package poc;

/** One plan request owns exactly one ID; cleanup also runs on execute/read/parse failure. */
public final class PlanSession {
    public interface Operations {
        void execute(String sql) throws java.sql.SQLException;
        TiberoPlan read(String sql) throws java.sql.SQLException;
    }
    public static TiberoPlan fetch(Operations operations, TiberoExplainProvider.PlanSql queries) throws java.sql.SQLException {
        Throwable failure=null;
        try {
            operations.execute(queries.explain());
            return operations.read(queries.query());
        } catch(Exception | Error e) { failure=e; throw e; }
        finally {
            try { operations.execute(queries.cleanup()); }
            catch(Exception cleanup) { if(failure!=null) failure.addSuppressed(cleanup); else throw cleanup; }
        }
    }
}
