package poc;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.database.console.JdbcConsole;
import com.intellij.database.editor.DatabaseEditorHelper;
import com.intellij.database.model.DasDataSource;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.psi.DbElement;
import com.intellij.database.util.DasUtil;
import com.intellij.database.util.DbImplUtil;
import java.util.Arrays;

/** Context menu actions open a fresh, attached native query console; execution remains explicit. */
public final class CatalogActions extends ActionGroup implements DumbAware {
    public CatalogActions() { super("Tibero", true); }
    static DbElement object(AnActionEvent e) {
        var element=e.getData(CommonDataKeys.PSI_ELEMENT);
        return element instanceof DbElement db && GenericDbms.TIBERO.eq(DbImplUtil.guessDbms(db)) ? db : null;
    }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent e) {
        var db=object(e);
        e.getPresentation().setEnabledAndVisible(db!=null && DasUtil.getSchema(db)!=null && e.getProject()!=null);
    }
    @Override public AnAction[] getChildren(AnActionEvent e) {
        var queries=Arrays.stream(TiberoCatalog.Query.values()).map(CatalogAction::new);
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(new ExecuteRoutine(),Separator.getInstance()),queries).toArray(AnAction[]::new);
    }
    /** Prepares an editable PL/SQL block in a new console; nothing runs until the user executes it. */
    static final class ExecuteRoutine extends DumbAwareAction {
        ExecuteRoutine() { super("Execute Routine…"); }
        static com.intellij.database.model.DasRoutine routine(AnActionEvent e) {
            var db=object(e);
            return db!=null && db.getDelegate() instanceof com.intellij.database.model.DasRoutine routine ? routine : null;
        }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(AnActionEvent e) { e.getPresentation().setEnabled(routine(e)!=null); }
        @Override public void actionPerformed(AnActionEvent e) {
            var routine=routine(e); var db=object(e); var project=e.getProject();
            if(routine==null || project==null) return;
            open(project,db.getDataSource(),TiberoRoutineScript.block(routine));
        }
    }
    private static final class CatalogAction extends DumbAwareAction {
        private final TiberoCatalog.Query query;
        CatalogAction(TiberoCatalog.Query query) { super(query.title + "…"); this.query=query; }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
        @Override public void update(AnActionEvent e) { e.getPresentation().setEnabled(object(e)!=null); }
        @Override public void actionPerformed(AnActionEvent e) {
            var db=object(e); var project=e.getProject();
            if(db==null || project==null) return;
            String sql=TiberoCatalog.sql(query,DasUtil.getSchema(db),db.getName());
            open(project,db.getDataSource(),query,sql);
        }
    }
    public static void open(Project project, DasDataSource source, TiberoCatalog.Query query, String sql) {
        open(project,source,"-- Tibero: " + query.title + "\n" + sql + ";\n");
    }
    static void open(Project project, DasDataSource source, String text) {
        try {
            // Explicit new file: never replace an existing console's SQL.
            var file=DatabaseEditorHelper.createNewConsoleVirtualFile(source);
            var console=JdbcConsole.newConsole(project).fromDataSource(source).forFile(file).build();
            WriteCommandAction.runWriteCommandAction(project,(Runnable) () -> console.getDocument().setText(text));
            FileEditorManager.getInstance(project).openFile(file,true);
        } catch (Exception ex) { Messages.showErrorDialog(project,ex.getMessage(),"Tibero Catalogue Query"); }
    }
}
