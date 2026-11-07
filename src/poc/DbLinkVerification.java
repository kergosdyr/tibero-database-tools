package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.OraRoot;
import com.intellij.database.dataSource.*;
import com.intellij.database.psi.*;
import com.intellij.database.model.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.sql.psi.*;
import java.util.*;

/** Real SQL PSI, model indexes and IDE XML serialization, including simulated process restart. */
final class DbLinkVerification {
    static void persistenceProcess(String phase) throws Exception {
        var store=TiberoDbLinkStore.getInstance();
        var link=new TiberoDbLinks.Entry("APP","RESTART_LINK","REMOTE_APP","remote_alias",null);
        if(phase.equals("write")) {
            store.update("restart-verification","APP",List.of(link));
            store.map("restart-verification",link,new TiberoDbLinkStore.Mapping("remote-id","REMOTE_APP"));
            var app=com.intellij.openapi.application.ApplicationManager.getApplication();
            ((com.intellij.openapi.application.ex.ApplicationEx)app).setSaveAllowed(true);
            Class.forName("com.intellij.configurationStore.StoreUtil").getMethod("saveSettings",com.intellij.openapi.components.ComponentManager.class,boolean.class).invoke(null,app,true);
        } else if(phase.equals("read")) {
            check(store.entries("restart-verification","APP").equals(List.of(link)),"Catalogue restored in fresh IDE process");
            check(store.mapping("restart-verification",link)!=null && "remote-id".equals(store.mapping("restart-verification",link).sourceId()) && "REMOTE_APP".equals(store.mapping("restart-verification",link).schema()),"Mapping restored in fresh IDE process");
        } else throw new IllegalArgumentException(phase);
    }
    static void run(Project ignored) throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("tibero-link-project-");
        var project=com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().newProject("DB link verification",directory.toString(),false,false);
        if(project==null) throw new AssertionError("Could not create isolated project");
        try { runProject(project); }
        finally { com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(()->com.intellij.openapi.util.Disposer.dispose(project)); }
    }
    private static void runProject(Project project) throws Exception {
        var store=TiberoDbLinkStore.getInstance(); var original=store.getState();
        var manager=LocalDataSourceManager.getInstance(project);
        var local=fixture("DB link local", "APP", "ORDERS", "LOCAL_ONLY");
        var remote=fixture("DB link remote", "REMOTE_APP", "ORDERS", "ID", "AMOUNT");
        var disposable=com.intellij.openapi.util.Disposer.newDisposable();
        try {
            var localModel=local.getModel(); var remoteModel=remote.getModel();
            manager.addDataSource(local); manager.addDataSource(remote);
            local.setModel(localModel); remote.setModel(remoteModel);
            local.getInfo().setDbms(GenericDbms.TIBERO); remote.getInfo().setDbms(GenericDbms.TIBERO);
            var db=new DbDataSourceImpl(project,local,(DataSourceManager)manager);
            var remoteDb=new DbDataSourceImpl(project,remote,(DataSourceManager)manager);
            DbPsiFacade.getInstance(project).clearCaches();
            var target=DbPsiFacade.getInstance(project).findDataSource(remote.getUniqueId());
            check(target!=null,"Mapped data source exists");
            var link=new TiberoDbLinks.Entry("APP","REMOTE","REMOTE_APP","remote_alias",null);
            store.update(local.getUniqueId(),"APP",List.of(link));
            store.map(local.getUniqueId(),link,new TiberoDbLinkStore.Mapping(remote.getUniqueId(),"REMOTE_APP"));
            // Persist actual IDE XML to disk, instantiate a new store and reload it.
            var path=java.nio.file.Files.createTempFile("tibero-links-", ".xml");
            try {
                com.intellij.openapi.util.JDOMUtil.write(com.intellij.util.xmlb.XmlSerializer.serialize(store.getState()),path);
                store.loadState(new TiberoDbLinkStore.State());
                var restored=new TiberoDbLinkStore();
                restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.openapi.util.JDOMUtil.load(path),TiberoDbLinkStore.State.class));
                check(restored.entries(local.getUniqueId(),"APP").equals(List.of(link)),"Catalogue survives disk round trip");
                check(remote.getUniqueId().equals(restored.mapping(local.getUniqueId(),link).sourceId()),"Remote mapping survives disk round trip");
                check(restored.entries("another-source","APP").isEmpty(),"Data sources are isolated");
                store.loadState(restored.getState());
            } finally { java.nio.file.Files.delete(path); }
            check(store.mapping(local.getUniqueId(),new TiberoDbLinks.Entry("APP","REMOTE","REMOTE_APP","changed_host",null))==null,"Redefined link must not use an old target mapping");
            var publicLink=new TiberoDbLinks.Entry("PUBLIC","SHARED",null,"public_host",null);
            store.update(local.getUniqueId(),"OTHER",List.of(publicLink));
            check(store.entries(local.getUniqueId(),"APP").contains(publicLink),"Public links shared across schemas");
            store.update(local.getUniqueId(),"OTHER",List.of());
            check(!store.entries(local.getUniqueId(),"APP").contains(publicLink),"Dropped public link cannot be resurrected by stale schema");
            try {
                store.update(local.getUniqueId(),"APP",TiberoDbLinks.snapshot("APP",Collections.singletonList(new String[]{"OTHER","BAD",null,null,null})));
                throw new AssertionError("Malformed refresh accepted");
            } catch(IllegalArgumentException expected) { check(store.entries(local.getUniqueId(),"APP").equals(List.of(link)),"Failed refresh retains last successful catalogue"); }
            var files=new HashSet<PsiFile>();
            com.intellij.sql.psi.impl.DataSourceProvider.EP_NAME.getPoint().registerExtension(f->files.contains(f)?List.of(db):List.of(),disposable);
            var sql=file(project,files,"SELECT r.ID FROM ORDERS@REMOTE r");
            var refs=com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(sql,SqlReferenceExpression.class);
            var linkReference=refs.stream().filter(ref->ref.getText().equals("REMOTE")).findFirst().orElseThrow();
            check(linkReference.resolve() instanceof TiberoDbLinkElement,"DB link token remains unresolved/red: "+linkReference.getText());
            var linkElement=(TiberoDbLinkElement)linkReference.resolve();
            check(linkElement.getKind()==ObjectKind.DB_LINK && linkElement.canNavigateToSource(),"DB link definition supports navigation");
            linkElement.navigate(true);
            check(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor().getDocument().getText().contains("CREATE DATABASE LINK \"REMOTE\""),"SQL link navigation opens DDL");
            int resolvedCount=0;
            for(var ref:refs) if(ref.getText().equals("ORDERS@REMOTE") || ref.getText().equals("r.ID")) {
                resolvedCount++;
                var resolved=ref.getReference().resolve();
                check(resolved instanceof DbElement element && element.getDataSource().getUniqueId().equals(remote.getUniqueId()),"Remote reference resolves to mapped source: "+ref.getText()+" -> "+resolved);
            }
            check(resolvedCount==2,"Both remote table and column references were exercised");
            for(String query:List.of("SELECT * FROM NOT_A_REMOTE_TABLE@REMOTE", "SELECT r.NOT_A_COLUMN FROM ORDERS@REMOTE r")) {
                var bad=file(project,files,query);
                for(var ref:com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(bad,SqlReferenceExpression.class))
                    if(ref.getText().equals("NOT_A_REMOTE_TABLE@REMOTE") || ref.getText().equals("r.NOT_A_COLUMN"))
                        check(!(ref.resolve() instanceof DbElement),"Missing remote object must stay unresolved: "+ref.getText());
            }
            var unknown=file(project,files,"SELECT * FROM ORDERS@MISSING");
            var missing=com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(unknown,SqlReferenceExpression.class).stream().filter(r->r.getText().equals("ORDERS@MISSING")).findFirst().orElseThrow();
            PsiElement missingTarget=missing.resolve();
            var visited=new HashSet<PsiElement>();
            while(missingTarget instanceof SqlReferenceExpression expression && visited.add(missingTarget)) missingTarget=expression.resolve();
            check(!(missingTarget instanceof DbElement),"Unknown link does not resolve to a database object: "+missingTarget);
            check(complete(project,files,"SELECT * FROM ORDERS@RE|",new TiberoDbLinkCompletion()).contains("REMOTE"),"@link completion after disk restore");
            check(complete(project,files,"SELECT * FROM OR|@REMOTE",new TiberoDbLinkCompletion()).contains("ORDERS"),"Remote table completion after disk restore");
            var nativeCompletion=com.intellij.codeInsight.completion.CompletionContributor.forLanguage(com.intellij.sql.dialects.oracle.OraDialect.INSTANCE).stream().filter(c->c.getClass().getName().equals("com.intellij.sql.completion.SqlCompletionContributor")).findFirst().orElseThrow();
            check(complete(project,files,"SELECT * FROM OR|",nativeCompletion).contains("ORDERS"),"Native completion: local tables");
            var columns=complete(project,files,"SELECT r.| FROM ORDERS@REMOTE r",nativeCompletion);
            check(columns.contains("ID") && columns.contains("AMOUNT") && !columns.contains("LOCAL_ONLY"),"Native remote column completion: "+columns);
            String ddl=TiberoDbLinkActions.ddl(link);
            check(ddl.contains("CREATE DATABASE LINK \"REMOTE\"") && ddl.contains("USING 'remote_alias'") && ddl.contains("<PASSWORD_REQUIRED>"),"DDL reflects catalogue and missing password");
            TiberoDbLinkActions.open(project,link);
            var opened=com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            check(opened!=null && opened.getDocument().getText().equals(ddl),"DDL action opens the SQL editor with the link definition");
            String publicDdl=TiberoDbLinkActions.ddl(new TiberoDbLinks.Entry("PUBLIC","R\"X",null,"a'b",null));
            check(publicDdl.contains("CREATE PUBLIC DATABASE LINK \"R\"\"X\"") && publicDdl.contains("'a''b'") && !publicDdl.contains("CONNECT TO"),"PUBLIC/connected-user DDL and escaping");
            store.update(local.getUniqueId(),"APP",List.of());
            check(TiberoDbLinkResolve.links(db).isEmpty(),"Successful empty refresh removes dropped links");
            System.out.println("PASS: DB link catalogue/mapping disk restore, source isolation, remote table and column resolution, @link/table completion, DDL and dropped-link refresh");
        } finally { store.loadState(original); com.intellij.openapi.util.Disposer.dispose(disposable); manager.removeDataSource(local); manager.removeDataSource(remote); }
    }
    private static LocalDataSource fixture(String name,String schema,String table,String... columns) {
        var model=com.intellij.database.model.ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
        model.modify(OraRoot.class,r->{var database=r; database.setCurrent(true); var s=database.getSchemas().createOrGet(schema); s.setCurrent(true); var t=s.getTables().createOrGet(table); for(String c:columns) t.getColumns().createOrGet(c);});
        var source=LocalDataSource.temporary(); source.setName(name); source.setAutoSynchronize(false);
        source.setDatabaseDriver(DatabaseDriverManager.getInstance().getDriver("tibero")); source.setModel(model);
        source.setIntrospectionScope(com.intellij.database.util.TreePatternUtils.parse(GenericDbms.TIBERO,"*:*")); return source;
    }
    private static PsiFile file(Project project,Set<PsiFile> files,String sql) {
        var file=PsiFileFactory.getInstance(project).createFileFromText("db-link.sql",com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,sql); files.add(file); return file;
    }
    private static Set<String> complete(Project project,Set<PsiFile> files,String marked,com.intellij.codeInsight.completion.CompletionContributor contributor) throws Exception {
        int offset=marked.indexOf('|'); String text=marked.replace("|",""); var file=file(project,files,text);
        var completionFile=file(project,files,text.substring(0,offset)+"IntellijIdeaRulezzz"+text.substring(offset));
        var factory=com.intellij.openapi.editor.EditorFactory.getInstance(); var editor=factory.createEditor(factory.createDocument(text),project);
        try {
            var params=new com.intellij.codeInsight.completion.CompletionParameters(completionFile.findElementAt(offset),file,com.intellij.codeInsight.completion.CompletionType.BASIC,offset,1,editor,()->false);
            var items=new HashSet<String>(); com.intellij.util.Consumer<com.intellij.codeInsight.completion.CompletionResult> consumer=r->items.add(r.getLookupElement().getLookupString());
            var method=com.intellij.codeInsight.completion.CompletionService.class.getDeclaredMethod("createResultSet",com.intellij.codeInsight.completion.CompletionParameters.class,com.intellij.util.Consumer.class,com.intellij.codeInsight.completion.CompletionContributor.class,com.intellij.codeInsight.completion.PrefixMatcher.class);
            method.setAccessible(true);
            var result=(com.intellij.codeInsight.completion.CompletionResultSet)method.invoke(com.intellij.codeInsight.completion.CompletionService.getCompletionService(),params,consumer,contributor,com.intellij.codeInsight.completion.PrefixMatcher.ALWAYS_TRUE);
            contributor.fillCompletionVariants(params,result); return items;
        } finally { factory.releaseEditor(editor); }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
