package poc;

import com.intellij.database.console.JdbcConsoleProvider;
import com.intellij.database.console.JdbcEngine;
import com.intellij.database.console.session.DatabaseSession;
import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.datagrid.DataAuditor;
import com.intellij.database.datagrid.DataRequest;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.Key;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Consumer;
import javax.swing.Icon;

/**
 * DBMS_OUTPUT for Tibero sessions. IntelliJ's own toggle only knows Oracle and DB2, so the same
 * enable → statement → GET_LINE → disable cycle runs on every Tibero session (see TiberoSessions).
 */
public final class TiberoDbmsOutput implements DataAuditor {
    static final Key<TiberoDbmsOutput> KEY=Key.create("tibero.dbms.output");
    static final String SETTING="tibero.dbms.output.enabled";
    static final String ENABLE="BEGIN DBMS_OUTPUT.ENABLE(1000000); END;";
    static final String GET_LINE="BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;";
    static final String DISABLE="BEGIN DBMS_OUTPUT.DISABLE; END;";
    static final int MAX_LINES=100000;
    volatile boolean enabled;
    private volatile boolean active;

    TiberoDbmsOutput(boolean enabled) { this.enabled=enabled; }

    /** Remote JDBC calls, separated so the cycle can be checked without a server. */
    interface Calls {
        void execute(String sql) throws SQLException,RemoteException;
        /** One GET_LINE: the line, or null once status is non-zero. */
        String nextLine() throws SQLException,RemoteException;
    }

    static boolean defaultEnabled() { return PropertiesComponent.getInstance().getBoolean(SETTING,true); }
    @Override public void beforeStatement(DataRequest.Context context) {
        var calls=calls(context);
        if(calls!=null) begin(calls,context::error);
    }
    @Override public void requestFinished(DataRequest.Context context) {
        var calls=calls(context);
        if(calls!=null) finish(calls,line -> context.warn(line,true),context::error);
    }
    void begin(Calls calls,Consumer<String> error) {
        if(!enabled || active) return; // once per request, not once per script statement
        try { calls.execute(ENABLE); active=true; }
        catch(SQLException|RemoteException e) {
            // Missing package or privilege: say so once instead of failing every statement.
            enabled=false;
            error.accept("Tibero DBMS_OUTPUT turned off for this session: " + e.getMessage());
        }
    }
    void finish(Calls calls,Consumer<String> output,Consumer<String> error) {
        if(!active) return;
        active=false;
        try {
            for(int i=0;i<MAX_LINES;i++) {
                String line=calls.nextLine();
                if(line==null) break;
                output.accept(line);
            }
        } catch(SQLException|RemoteException e) { error.accept("Tibero DBMS_OUTPUT read failed: " + e.getMessage()); }
        finally {
            try { calls.execute(DISABLE); } catch(SQLException|RemoteException ignored) {}
        }
    }
    private static Calls calls(DataRequest.Context context) {
        var connection=context.producer instanceof JdbcEngine engine ? engine.getCurrentConnectionIfReady() : null;
        return connection==null ? null : remote(connection);
    }
    static Calls remote(DatabaseConnectionCore connection) {
        return new Calls() {
            @Override public void execute(String sql) throws SQLException,RemoteException {
                var call=connection.getRemoteConnection().prepareCall(sql);
                try { call.execute(); } finally { call.close(); }
            }
            @Override public String nextLine() throws SQLException,RemoteException {
                var call=connection.getRemoteConnection().prepareCall(GET_LINE);
                try {
                    call.registerOutParameter(1,Types.VARCHAR);
                    call.registerOutParameter(2,Types.INTEGER);
                    call.execute();
                    if(call.getInt(2)!=0) return null;
                    var line=call.getString(1);
                    return line==null ? "" : line;
                } finally { call.close(); }
            }
        };
    }

    /** Console toolbar toggle; the choice also becomes the default for new Tibero sessions. */
    public static final class Toggle extends ToggleAction implements DumbAware {
        public Toggle() {
            super("Tibero DBMS_OUTPUT","Show DBMS_OUTPUT.PUT_LINE output after each Tibero statement",icon());
        }
        private static Icon icon() {
            try { return (Icon)Class.forName("icons.DatabaseIcons").getField("DbmsOutput").get(null); }
            catch(ReflectiveOperationException absent) { return com.intellij.icons.AllIcons.Debugger.Console; } // 2024.1
        }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        private static DatabaseSession session(AnActionEvent e) {
            var project=e.getProject(); var file=e.getData(CommonDataKeys.VIRTUAL_FILE);
            var console=project==null || file==null ? null : JdbcConsoleProvider.getConsole(project,file);
            return console==null || !GenericDbms.TIBERO.eq(console.getTarget().getDbms()) ? null : console.getSession();
        }
        @Override public void update(AnActionEvent e) {
            var session=session(e);
            e.getPresentation().setEnabledAndVisible(session!=null);
            if(session!=null) super.update(e);
        }
        @Override public boolean isSelected(AnActionEvent e) {
            var output=TiberoSessions.attach(session(e));
            return output!=null && output.enabled;
        }
        @Override public void setSelected(AnActionEvent e,boolean state) {
            var output=TiberoSessions.attach(session(e));
            if(output==null) return;
            output.enabled=state;
            PropertiesComponent.getInstance().setValue(SETTING,state,true);
        }
    }
}
