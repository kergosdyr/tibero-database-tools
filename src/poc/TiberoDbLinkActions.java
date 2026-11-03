package poc;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.database.util.DasUtil;
import com.intellij.database.view.DatabaseView;
import com.intellij.testFramework.LightVirtualFile;
import java.util.*;

/** Uses the Database view's native Navigatable dispatch, including double-click and Enter. */
public final class TiberoDbLinkActions extends DumbAwareAction {
    static String ddl(TiberoDbLinks.Entry link) {
        String header="-- Reconstructed from ALL_DB_LINKS; original password is not available.\n"
                +"-- Owner: "+link.owner().replace('\n',' ').replace('\r',' ')+"\n";
        return header+"CREATE "+("PUBLIC".equals(link.owner())?"PUBLIC ":"")+"DATABASE LINK "+quote(link.name())
                +(link.username()==null || link.username().isBlank()?"":"\n  CONNECT TO "+quote(link.username())+" IDENTIFIED BY <PASSWORD_REQUIRED>")
                +"\n  USING "+(link.host()==null?"<CONNECT_STRING_REQUIRED>":TiberoCatalog.literal(link.host()))+";\n";
    }
    static String quote(String name) { return "\""+name.replace("\"","\"\"")+"\""; }
    static void open(TiberoDatabaseTree.Link link) {
        var source=com.intellij.database.BasicModelRegistry.instance().getDataSource(link.schema().getModel());
        if(source==null || link.entry()==null) return;
        // The focused Database view identifies the project even for application-wide data sources.
        var project=CommonDataKeys.PROJECT.getData(com.intellij.ide.DataManager.getInstance().getDataContext());
        if(project!=null && DbPsiFacade.getInstance(project).findDataSource(source.getUniqueId())==null) project=null;
        if(project==null) for(var candidate:ProjectManager.getInstance().getOpenProjects())
            if(DbPsiFacade.getInstance(candidate).findDataSource(source.getUniqueId())!=null) { project=candidate; break; }
        if(project!=null && !project.isDisposed()) open(project,link.entry());
    }
    static void open(Project project,TiberoDbLinks.Entry entry) {
        var file=new LightVirtualFile(entry.name().replaceAll("[/\\\\]","_")+".sql",com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,ddl(entry));
        FileEditorManager.getInstance(project).openFile(file,true);
    }
    static TiberoDatabaseTree.Link selected(AnActionEvent event) {
        var nodes=event.getData(DatabaseView.DATABASE_NODES);
        return nodes!=null && nodes.length==1 ? TiberoDatabaseTree.link(nodes[0]) : null;
    }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent event) {
        var link=selected(event); event.getPresentation().setEnabledAndVisible(event.getProject()!=null && link!=null && link.entry()!=null);
    }
    @Override public void actionPerformed(AnActionEvent event) {
        var link=selected(event); var project=event.getProject();
        if(link==null || link.entry()==null || project==null) return;
        var source=com.intellij.database.BasicModelRegistry.instance().getDataSource(link.schema().getModel());
        if(source==null) return;
        var targets=DbPsiFacade.getInstance(project).getDataSources();
        var labels=new ArrayList<String>(); labels.add("Remove mapping");
        for(var target:targets) labels.add(target.getName());
        int choice=Messages.showChooseDialog(project,"Select the data source whose metadata describes this link's remote database.","DB Link Completion",Messages.getQuestionIcon(),labels.toArray(String[]::new),labels.get(0));
        if(choice<0) return;
        var store=TiberoDbLinkStore.getInstance();
        if(choice==0) { store.map(source.getUniqueId(),link.entry(),null); com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches(); return; }
        var target=targets.get(choice-1);
        String[] schemas=DasUtil.getSchemas(target).map(s->s.getName()).toList().stream().distinct().sorted().toArray(String[]::new);
        if(schemas.length==0) { Messages.showInfoMessage(project,"Refresh the remote data source to load its schemas first.","DB Link Completion"); return; }
        String initial=Arrays.asList(schemas).contains(link.entry().username())?link.entry().username():schemas[0];
        int schema=Messages.showChooseDialog(project,"Default remote schema for unqualified table names:","DB Link Completion",Messages.getQuestionIcon(),schemas,initial);
        if(schema>=0) store.map(source.getUniqueId(),link.entry(),new TiberoDbLinkStore.Mapping(target.getUniqueId(),schemas[schema]));
        com.intellij.psi.PsiManager.getInstance(project).dropPsiCaches();
    }
}
