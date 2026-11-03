package poc;

import com.intellij.database.dialects.base.BaseExecutionEnvironmentHelper;
import com.intellij.sql.psi.SqlElement;

/** Keep-alive pings and console expression evaluation; the generic "SELECT expr" has no FROM clause. */
public final class TiberoExecutionEnvironment extends BaseExecutionEnvironmentHelper {
    @Override protected String generateSelectStatement(String text,SqlElement element) {
        return "SELECT " + text + " FROM DUAL";
    }
}
