package poc;

import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.database.plan.ExplainPlanProvider;
import com.intellij.database.jdbc.JdbcSourceLoader;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.util.DdlBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.database.diagram.plan.QueryPlanDataModel;
import com.intellij.database.diagram.plan.QueryPlanProvider;
import com.intellij.database.plan.ui.PlanView;
import com.intellij.database.plan.PlanModel;
import com.intellij.openapi.util.Disposer;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit headless command for the isolated PoC sandbox only. */
public final class VerifyStarter implements ApplicationStarter {
    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }
    @Override public void main(List<String> args) {
        int status = 1;
        try {
            String persistencePhase=System.getenv("TIBERO_POC_PERSISTENCE_PHASE");
            if(persistencePhase!=null) {
                DbLinkVerification.persistenceProcess(persistencePhase);
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("tibero.poc.result")),"PASS: DB link IDE-process persistence "+persistencePhase+"\n");
                System.exit(0);
            }
            var provider = ExplainPlanProvider.EP.forDbms(GenericDbms.TIBERO);
            var loader = JdbcSourceLoader.EP.forDbms(GenericDbms.TIBERO);
            if (!(provider instanceof TiberoExplainProvider)) throw new AssertionError("Plan provider not registered");
            if (!(loader instanceof TiberoViewLoader)) throw new AssertionError("View loader not registered");
            var ddl = new DdlBuilder().splitStatements(true);
            provider.createRawExplainTask(null, false).sqlExplainPlan(ddl, "SELECT 1 FROM DUAL");
            if (ddl.getStatements().size() != 2) throw new AssertionError(ddl.getStatements());
            var failure=new AtomicReference<Throwable>();
            ApplicationManager.getApplication().invokeAndWait(() -> {
                com.intellij.openapi.project.Project project=null;
                try {
                    var directory=Files.createTempDirectory("tibero-ide-verification-");
                    project=com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().newProject("Tibero IDE verification",directory.toString(),false,false);
                    if(project==null) throw new AssertionError("Could not create verification project");
                    SourceEditVerification.run(project);
                    StoredObjectsVerification.run(project);
                    RoutineVerification.run(project);
                    var samplePlan=TiberoPlan.parse(samplePlan());
                    var actualRows=samplePlan.rows().stream().map(r -> new TiberoPlan.Row(r.id(), r.parent(), r.position(),
                        r.operation(), r.owner(), r.object(), r.cost(), r.rows(), r.access(), r.filter(),
                        new TiberoPlan.Metrics(java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE,
                            new java.math.BigDecimal("1.25"), java.math.BigDecimal.TEN))).toList();
                    var actualPlan=new TiberoPlan(actualRows,true);
                    if(!provider.isSupported(com.intellij.database.util.Version.of(7),true)) throw new AssertionError("Actual provider disabled");
                    var actualData=((TiberoExplainProvider)provider).createBuilder(com.intellij.database.util.Version.of(7),true).createData();
                    actualData.load(actualPlan.json());
                    var actualModel=((TiberoExplainProvider)provider).createBuilder(com.intellij.database.util.Version.of(7),true).build(actualData);
                    if(!actualModel.isActual() || actualModel.getRoot().getChildren()[0].getActualTotalTime()!=1.25)
                        throw new AssertionError("Actual metrics lost in registered builder");
                    var actualGraph=new QueryPlanDataModel(project,actualModel.getRoot(),new QueryPlanProvider());
                    try {
                        actualGraph.updateDataModel();
                        if(actualGraph.getNodes().size()!=5) throw new AssertionError("Actual diagram lost nodes");
                    } finally { actualGraph.dispose(); }
                    var model=samplePlan.model();
                    var graph=new QueryPlanDataModel(project,model.getRoot(),new QueryPlanProvider());
                    try {
                        graph.updateDataModel();
                        if(graph.getNodes().size()!=5 || graph.getEdges().size()!=4) throw new AssertionError("Diagram graph must contain 5 nodes and 4 edges");
                    } finally { graph.dispose(); }
                    var actions=ActionManager.getInstance();
                    for(String id:List.of("Tibero.Catalog","Console.Jdbc.ExplainPlan","ShowUmlDiagram"))
                        if(actions.getAction(id)==null) throw new AssertionError("Missing action: " + id);
                    for(String id:List.of("Tibero.Tools","Tibero.SamplePlan","Tibero.ImportPlan","Tibero.BrowseCatalog","Tibero.DbLinks"))
                        if(actions.getAction(id)!=null) throw new AssertionError("Standalone Tibero UI still registered: " + id);
                    requireMenu(actions,"DatabaseViewPopupMenu","Tibero.Catalog");
                    requireMenu(actions,"PlanViewGroup","ShowUmlDiagram");
                    if(((ActionGroup)actions.getAction("Tibero.Catalog")).getChildren(null).length!=TiberoCatalog.Query.values().length+2)
                        throw new AssertionError("Missing catalogue/execute menu entries");
                    requireMenu(actions,"Console.Jdbc.Left","Tibero.DbmsOutput");
                    var emptyContext=AnActionEvent.createFromAnAction(actions.getAction("Tibero.Catalog"),null,"test",DataContext.EMPTY_CONTEXT);
                    actions.getAction("Tibero.Catalog").update(emptyContext);
                    if(emptyContext.getPresentation().isVisible()) throw new AssertionError("Tibero context menu visible without a Tibero selection");
                    // Links JdbcConsole/DataRequest.Owner at runtime (Plugin Verifier flags a stale IDE-internal reference there).
                    Class.forName("com.intellij.database.datagrid.DataRequest$Owner").getMethods();
                    Class.forName("com.intellij.database.console.JdbcConsole").getMethods();
                    var outputContext=AnActionEvent.createFromAnAction(actions.getAction("Tibero.DbmsOutput"),null,"test",DataContext.EMPTY_CONTEXT);
                    actions.getAction("Tibero.DbmsOutput").update(outputContext);
                    if(outputContext.getPresentation().isVisible()) throw new AssertionError("Tibero DBMS_OUTPUT toggle visible outside a Tibero console");
                    if(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.getExtensionList().stream().anyMatch(ep -> List.of("Tibero Plans","dblinks").contains(ep.id)))
                        throw new AssertionError("Standalone Tibero tool window still registered");
                    var linkSql=TiberoDbLinks.sql("O'WNER");
                    if(!linkSql.contains("OWNER IN ('O''WNER', 'PUBLIC')") || linkSql.contains("PASSWORD"))
                        throw new AssertionError("DB link query scope/columns");
                    var view=PlanView.createView(project);
                    try {
                        showPlan(view, model);
                        if(!hasPopulatedTable(view.getComponent())) throw new AssertionError("Native plan view rejected model");
                        if(view.getComponent()==null) throw new AssertionError("Missing native plan component");
                        var component=view.getComponent(); component.setSize(1100,650); layout(component);
                        var image=new java.awt.image.BufferedImage(1100,650,java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics=image.createGraphics();
                        try { graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0,0,1100,650); component.printAll(graphics); }
                        finally { graphics.dispose(); }
                        javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("tibero.poc.result")).resolveSibling("native-plan-view.png").toFile());
                    } finally { Disposer.dispose(view); }
                } catch(Throwable e) { failure.set(e); }
                finally {
                    if(project!=null) {
                        var completedProject=project;
                        ApplicationManager.getApplication().runWriteAction(()->Disposer.dispose(completedProject));
                    }
                }
            });
            if(failure.get()!=null) throw new AssertionError("IDE UI-model verification failed",failure.get());
            Files.writeString(Path.of(System.getProperty("tibero.poc.result")),
                    "PASS: real IDE extensions, two-statement SQL, actual plan builder/metrics/view/diagram, native tree view, native diagram (5 nodes/4 edges), menu actions, no standalone Tibero windows/menus, stored-object introspector/model/refresh/source/remote-read contracts; source editor Submit (procedure, package spec/body, view); sequences, db link tree and schema scope, JDBC routine arguments without getFunctionColumns, Tibero getColumns() type shapes to DDL and cached type repair; package overloads/arguments/returns, completion lookup and bounded bulk-query contracts\n"
                    + "NOT TESTED: database execution, displayed-window interaction and remote JDBC\n");
            status = 0;
        } catch (Throwable e) { e.printStackTrace(); }
        System.exit(status);
    }
    static String samplePlan() {
        try (var in = VerifyStarter.class.getResourceAsStream("/samples/join-plan.json")) {
            if(in==null) throw new IllegalStateException("Sample plan is missing");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static void showPlan(PlanView view, PlanModel model) {
        try {
            java.lang.reflect.Method legacy = IdeCompatibility.optional(PlanView.class, "setModel", PlanModel.class);
            if (legacy != null) { IdeCompatibility.invoke(legacy, view, model); return; }
            Class<?> resultType=Class.forName("com.intellij.database.plan.ExplainPlanModel",true,PlanView.class.getClassLoader());
            Object result=resultType.getConstructor(String.class,boolean.class,PlanModel.class,boolean.class)
                    .newInstance("-- Imported/sample PLAN_TABLE; no SQL executed",false,model,false);
            IdeCompatibility.invoke(PlanView.class.getMethod("setResult",resultType),view,result);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported native plan view API",e); }
    }
    private static boolean hasPopulatedTable(java.awt.Component component) {
        if(component instanceof javax.swing.JTable table && table.getRowCount()>0) return true;
        if(component instanceof java.awt.Container container)
            for(var child:container.getComponents()) if(hasPopulatedTable(child)) return true;
        return false;
    }
    private static void layout(java.awt.Container c) { c.doLayout(); for(var child:c.getComponents()) if(child instanceof java.awt.Container nested) layout(nested); }
    private static void requireMenu(ActionManager manager,String group,String child) {
        var action=manager.getAction(group);
        if(!(action instanceof ActionGroup g) || java.util.Arrays.stream(g instanceof DefaultActionGroup defaults ? defaults.getChildActionsOrStubs() : g.getChildren(null)).noneMatch(a -> child.equals(manager.getId(a))))
            throw new AssertionError("Missing menu placement: " + group + " → " + child);
    }
}
