package poc;

import com.intellij.database.view.DatabaseView;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.util.ui.tree.ExpandOnDoubleClick;
import javax.swing.JTree;
import java.util.function.Supplier;

/** Database Tools classifies every extra hierarchy node as expandable, including our link leaves. */
public final class TiberoDbLinkTreeNavigation implements StartupActivity.DumbAware {
    private static final String INSTALLED="tibero.db.link.navigation";
    @Override public void runActivity(Project project) {
        project.getMessageBus().connect(project).subscribe(ToolWindowManagerListener.TOPIC,new ToolWindowManagerListener() {
            @Override public void toolWindowShown(ToolWindow window) {
                if("Database".equals(window.getId())) attachLater(project);
            }
        });
        attachLater(project);
    }
    private static void attachLater(Project project) {
        ApplicationManager.getApplication().invokeLater(()-> {
            if(project.isDisposed()) return;
            var view=DatabaseView.getDatabaseViewIfVisible(project);
            var window=ToolWindowManager.getInstance(project).getToolWindow("Database");
            if(view==null || window==null) return;
            var tree=com.intellij.util.ui.UIUtil.findComponentOfType(window.getComponent(),JTree.class);
            if(tree==null || tree.getClientProperty(INSTALLED)!=null) return;
            install(tree,()-> {
                var nodes=view.getSelectedNodes().toList();
                return nodes.size()==1 ? TiberoDatabaseTree.link(nodes.get(0)) : null;
            },project);
        });
    }
    static void install(JTree tree,Supplier<TiberoDatabaseTree.Link> selected,Disposable parent) {
        tree.putClientProperty(INSTALLED,Boolean.TRUE);
        var original=ExpandOnDoubleClick.getBehavior(tree);
        Runnable update=()-> {
            var link=selected.get();
            (link!=null && link.entry()!=null ? ExpandOnDoubleClick.NEVER : original).installOn(tree);
        };
        javax.swing.event.TreeSelectionListener listener=e->update.run();
        tree.addTreeSelectionListener(listener); update.run();
        var open=new AnAction() {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) {
                var link=selected.get(); event.getPresentation().setEnabled(link!=null && link.entry()!=null);
            }
            @Override public void actionPerformed(AnActionEvent event) {
                var link=selected.get(); if(link!=null && link.entry()!=null) TiberoDbLinkActions.open(link);
            }
        };
        open.registerCustomShortcutSet(CommonShortcuts.ENTER,tree,parent);
        Disposer.register(parent,()-> {
            tree.removeTreeSelectionListener(listener); original.installOn(tree); tree.putClientProperty(INSTALLED,null);
        });
    }
}
