package poc;

import com.intellij.database.console.session.DatabaseSession;
import com.intellij.database.dataSource.DatabaseDriver;
import com.intellij.database.dataSource.DatabaseDriverImpl;
import com.intellij.database.dataSource.DatabaseDriverManager;
import com.intellij.database.console.session.DatabaseSessionStateListener;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.util.DbSqlUtilCore;
import com.intellij.openapi.application.ApplicationManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Attaches the Tibero console auditors to every Tibero session as it is published. */
public final class TiberoSessions {
    private static final AtomicBoolean INSTALLED=new AtomicBoolean();
    static final String ORACLE_DIALECT_SETTING="tibero.console.oracle.dialect";

    public static final class Startup implements com.intellij.ide.AppLifecycleListener {
        @Override public void appFrameCreated(List<String> args) { install(); }
    }
    static void install() {
        if(!INSTALLED.compareAndSet(false,true)) return;
        if(Boolean.parseBoolean(System.getProperty(ORACLE_DIALECT_SETTING,"true")))
            for(var driver:DatabaseDriverManager.getInstance().getDrivers()) preferOracleDialect(driver);
        var app=ApplicationManager.getApplication();
        app.getMessageBus().connect(app).subscribe(DatabaseSession.getTopic(),new DatabaseSessionStateListener.Adapter() {
            @Override public void stateChanged(DatabaseSessionStateListener.ChangeEvent event) {
                if(event.getType()==DatabaseSessionStateListener.ChangeEvent.EventType.PUBLISHED) attach(event.getSession());
            }
        });
    }
    /**
     * The bundled Tibero driver declares dialect="GenericSQL" and a console takes its language from the driver, so
     * PL/SQL blocks were split at every ';'. Runs before projects open (data sources cache the dialect) and changes only
     * drivers still on GenericSQL, like choosing Dialect: Oracle in the driver settings; a modified driver keeps it when
     * saved. -Dtibero.console.oracle.dialect=false turns this off.
     */
    static boolean preferOracleDialect(DatabaseDriver driver) {
        boolean tibero="tibero".equals(driver.getId()) || "com.tmax.tibero.jdbc.TbDriver".equals(driver.getDriverClass());
        if(!tibero || !(driver instanceof DatabaseDriverImpl impl) || !"GenericSQL".equals(impl.getSqlDialect())) return false;
        impl.setSqlDialect(DbSqlUtilCore.getSqlDialect(GenericDbms.TIBERO).getID());
        return true;
    }
    /** The session's DBMS_OUTPUT auditor, attaching both auditors on first use; null for other DBMSs. */
    static TiberoDbmsOutput attach(DatabaseSession session) {
        if(session==null || !GenericDbms.TIBERO.eq(session.getTarget().getDbms())) return null;
        synchronized(session) {
            var existing=session.getUserData(TiberoDbmsOutput.KEY);
            if(existing!=null) return existing;
            var output=new TiberoDbmsOutput(TiberoDbmsOutput.defaultEnabled());
            session.putUserData(TiberoDbmsOutput.KEY,output);
            session.getMessageBus().addAuditor(output);
            session.getMessageBus().addAuditor(new TiberoCompileErrors());
            return output;
        }
    }
}
