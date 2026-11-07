package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.*;
import com.intellij.database.model.DasRoutine;
import com.intellij.database.model.MemoryTextStorage;
import com.intellij.database.model.ModelFactory;
import com.intellij.database.model.basic.BasicModSourceAware;
import com.intellij.database.model.properties.CompositeText;
import com.intellij.database.script.DbSrcMigration;
import com.intellij.database.script.SimpleCompositeText;
import com.intellij.database.script.generator.ScriptGenerators;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.function.Function;

/** Native source editor Submit path: edited text → model → TiberoScriptGenerator statements. */
final class SourceEditVerification {
    static void run(Project project) {
        if(!TiberoSourceEditing.supported()) {
            System.out.println("LIMITATION: source editor Submit is registered only on " + TiberoSourceEditing.VERIFIED_BASELINE + "+; this IDE keeps generic scripting");
            return;
        }
        TiberoSourceEditing.install();
        String proc="CREATE OR REPLACE PROCEDURE DEMO.RAISE_SALARY(p_id IN NUMBER) IS\nBEGIN\n  UPDATE EMP SET SAL = SAL * 1.1 WHERE EMPNO = p_id;\nEND;";
        check(submit(project,schema -> {
            var r=schema.getRoutines().createOrGet("RAISE_SALARY"); r.setRoutineKind(DasRoutine.Kind.PROCEDURE);
            var a=r.getArguments().createOrGet("p_id"); a.setPosition((short)1); a.setArgumentDirection(com.intellij.database.model.ArgumentDirection.IN);
            a.setDataType(new com.intellij.database.model.DataType(null,null,"NUMBER",null,false,java.sql.Types.NUMERIC));
            return r;
        },proc,proc.replace("1.1","1.2")).equals(List.of(proc.replace("1.1","1.2"))),"Edited procedure submits as one CREATE OR REPLACE");

        String spec="CREATE OR REPLACE PACKAGE DEMO.HR_API AS\n  FUNCTION HEADCOUNT RETURN NUMBER;\n  PROCEDURE HIRE(p_name VARCHAR2);\nEND HR_API;";
        String body="CREATE OR REPLACE PACKAGE BODY DEMO.HR_API AS\n  FUNCTION HEADCOUNT RETURN NUMBER IS BEGIN RETURN 1; END;\n  PROCEDURE HIRE(p_name VARCHAR2) IS BEGIN NULL; END;\nEND HR_API;";
        Function<OraSchema,BasicModSourceAware> pkg=schema -> {
            var p=schema.getPackages().createOrGet("HR_API");
            p.getRoutines().createOrGet("HEADCOUNT").setRoutineKind(DasRoutine.Kind.FUNCTION);
            p.getRoutines().createOrGet("HIRE").setRoutineKind(DasRoutine.Kind.PROCEDURE);
            return p;
        };
        String edited=spec.replace("  PROCEDURE HIRE(p_name VARCHAR2);\n","  FUNCTION RETIRED RETURN NUMBER;\n");
        String editedBody=body.replace("  PROCEDURE HIRE(p_name VARCHAR2) IS BEGIN NULL; END;\n","  FUNCTION RETIRED RETURN NUMBER IS BEGIN RETURN 0; END;\n");
        var statements=submit(project,pkg,spec+"\n/\n"+body,edited+"\n/\n"+editedBody);
        check(statements.equals(List.of(edited,editedBody)),"Package specification and body submit as two statements; members are not scripted alone: " + statements);
        check(submit(project,pkg,spec+"\n/\n"+body,spec+"\n/\n"+body.replace("RETURN 1","RETURN 2")).equals(List.of(spec,body.replace("RETURN 1","RETURN 2"))),"Body-only edit replaces the package");

        String view="CREATE OR REPLACE VIEW DEMO.EMP_V AS\nSELECT EMPNO, ENAME FROM EMP";
        check(submit(project,schema -> schema.getViews().createOrGet("EMP_V"),view,view.replace("ENAME","ENAME, SAL"))
                .equals(List.of(view.replace("ENAME","ENAME, SAL"))),"Edited view submits as CREATE OR REPLACE VIEW");
        checkLoader();
        System.out.println("PASS: source loader opens qualified CREATE OR REPLACE text (package with body); source editor Submit for procedure, package specification/body and view emits CREATE OR REPLACE statements only");
    }
    /** ALL_SOURCE/ALL_VIEWS rows through the registered loader become the text the source editor opens. */
    private static void checkLoader() {
        var catalogue=java.util.Map.of(
                TiberoCatalog.sourceSql("DEMO","RAISE_SALARY","PROCEDURE"),List.of("PROCEDURE RAISE_SALARY(p_id IN NUMBER) IS\n","BEGIN NULL; END;"),
                TiberoCatalog.sourceSql("DEMO","HR_API","PACKAGE"),List.of("PACKAGE HR_API IS\n","END;"),
                TiberoCatalog.sourceSql("DEMO","HR_API","PACKAGE BODY"),List.of("PACKAGE BODY HR_API IS\n","END;"),
                TiberoViewLoader.viewSql("DEMO","EMP_V"),List.of("SELECT EMPNO FROM EMP"));
        var remote=proxy(com.intellij.database.remote.jdbc.RemoteConnection.class,(o,m,args) -> {
            if(!m.getName().equals("createStatement")) throw new AssertionError("Unexpected connection call: " + m.getName());
            return proxy(com.intellij.database.remote.jdbc.RemoteStatement.class,(so,sm,sargs) -> switch(sm.getName()) {
                case "setQueryTimeout","setFetchSize","close" -> null;
                case "executeQuery" -> {
                    var rows=catalogue.get((String)sargs[0]); int[] row={-1};
                    if(rows==null) throw new AssertionError("Unexpected source query: " + sargs[0]);
                    yield proxy(com.intellij.database.remote.jdbc.RemoteResultSet.class,(ro,rm,rargs) -> switch(rm.getName()) {
                        case "next" -> ++row[0]<rows.size();
                        case "getString" -> rows.get(row[0]);
                        case "close" -> null;
                        default -> throw new AssertionError("Unexpected result-set call: " + rm.getName());
                    });
                }
                default -> throw new AssertionError("Unexpected statement call: " + sm.getName());
            });
        });
        var connection=proxy(com.intellij.database.dataSource.DatabaseConnectionCore.class,(o,m,args) -> switch(m.getName()) {
            case "getRemoteConnection" -> remote;
            case "getDbms" -> GenericDbms.TIBERO;
            default -> throw new AssertionError(m.getName());
        });
        var model=new ModelFactory(new MemoryTextStorage()).createModel(GenericDbms.TIBERO);
        var loaded=new java.util.LinkedHashMap<String,String>();
        model.modify(OraRoot.class,root -> {
            var schema=root.getSchemas().createOrGet("DEMO");
            var r=schema.getRoutines().createOrGet("RAISE_SALARY"); r.setRoutineKind(DasRoutine.Kind.PROCEDURE);
            for(BasicModSourceAware o:List.<BasicModSourceAware>of(r,schema.getPackages().createOrGet("HR_API"),schema.getViews().createOrGet("EMP_V"))) {
                try { new TiberoViewLoader().loadSource(o,connection); } catch(Exception e) { throw new AssertionError(e); }
                loaded.put(o.getName(),o.getSourceText()==null ? null : o.getSourceText().getText().toString());
            }
        });
        check(loaded.equals(java.util.Map.of(
                "RAISE_SALARY","CREATE OR REPLACE PROCEDURE DEMO.RAISE_SALARY(p_id IN NUMBER) IS\nBEGIN NULL; END;",
                "HR_API","CREATE OR REPLACE PACKAGE DEMO.HR_API IS\nEND;\n/\nCREATE OR REPLACE PACKAGE BODY DEMO.HR_API IS\nEND;",
                "EMP_V","CREATE OR REPLACE VIEW DEMO.EMP_V AS\nSELECT EMPNO FROM EMP")),"Loaded editable sources: " + loaded);
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    private static List<String> submit(Project project,Function<OraSchema,BasicModSourceAware> create,String original,String edited) {
        var model=new ModelFactory(new MemoryTextStorage()).createModel(GenericDbms.TIBERO);
        var object=new java.util.concurrent.atomic.AtomicReference<BasicModSourceAware>();
        model.modify(OraRoot.class,root -> {
            var o=create.apply(root.getSchemas().createOrGet("DEMO"));
            o.setSourceText(new SimpleCompositeText(original,CompositeText.Kind.ORIGINAL_TEXT));
            object.set(o);
        });
        var source=com.intellij.database.dataSource.LocalDataSource.temporary();
        source.setDatabaseDriver(com.intellij.database.dataSource.DatabaseDriverManager.getInstance().getDriver("tibero"));
        source.setAutoSynchronize(false); source.setModel(model);
        var manager=com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project);
        manager.addDataSource(source);
        try {
            source.setModel(model); source.getInfo().setDbms(GenericDbms.TIBERO);
            check(ScriptGenerators.INSTANCE.byModel(model) instanceof TiberoScriptGenerator,"Tibero script generator registered");
            check(DbSrcMigration.isSupported(object.get()),"Source editor Submit enabled for " + object.get());
            var db=new com.intellij.database.psi.DbDataSourceImpl(project,source,(com.intellij.database.psi.DataSourceManager)manager);
            var file=new com.intellij.testFramework.LightVirtualFile("edited.sql",com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,edited);
            var path=com.intellij.database.util.ObjectPaths.of(object.get());
            var task=DbSrcMigration.migrationTaskBuilder(project,db,List.of(com.intellij.openapi.util.Pair.create(path,(com.intellij.openapi.vfs.VirtualFile)file)),null);
            check(task!=null,"Migration task for " + path);
            var result=List.of(ScriptGenerators.INSTANCE.makeScript(project,task.build()).getScriptStatementsTexts()).stream().map(String::strip).toList();
            return result;
        } finally { manager.removeDataSource(source); }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
