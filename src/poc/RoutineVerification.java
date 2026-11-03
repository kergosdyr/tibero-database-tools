package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.generic.model.*;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.*;
import com.intellij.openapi.project.Project;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class RoutineVerification {
    record Fixture(List<TiberoStoredObjects.ObjectInfo> objects,List<TiberoRoutineCatalog.Member> members,List<TiberoRoutineCatalog.Argument> arguments) {}
    static void run(Project project) throws Exception {
        Fixture fixture;
        try(var in=RoutineVerification.class.getResourceAsStream("/samples/routine-signatures.json")) {
            fixture=new com.google.gson.Gson().fromJson(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Fixture.class);
        }
        var snapshot=TiberoRoutineCatalog.snapshot("DEMO",fixture.objects(),fixture.members(),fixture.arguments());
        check(snapshot.size()==6,"Standalone function, overloaded functions and zero-argument procedure");
        var model=ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
        var target=new AtomicReference<GenericSchema>();
        model.modify(GenericRoot.class,root->target.set(root.getDatabases().createOrGet("TEST").getSchemas().createOrGet("DEMO")));
        var schema=target.get();
        model.modify(schema,GenericSchema.class,s->{TiberoStoredObjects.apply(s,fixture.objects()); TiberoRoutineCatalog.apply(s,snapshot);});
        var pkg=schema.getPackages().first();
        check(pkg.getRoutines().size()==5,"Native package family contains all overloads");
        var context=TiberoRoutineCompletion.context("SELECT DEMO.PKG.CA");
        check(context!=null && "DEMO".equals(context.schema()) && "PKG".equals(context.pkg()) && "CA".equals(context.prefix()),"Qualified prefix parsed");
        check(TiberoRoutineCompletion.context("SELECT 1") == null,"Unqualified SQL not intercepted");
        check(TiberoRoutineCompletion.candidates(pkg,new TiberoRoutineCompletion.Context("OTHER","PKG","")).isEmpty(),"Completion schema boundary");
        var candidates=TiberoRoutineCompletion.candidates(pkg,new TiberoRoutineCompletion.Context("DEMO","PKG",""));
        check(candidates.size()==5,"Completion candidates use the native package family");
        var calc=candidates.stream().filter(r->r.getName().equals("CALC")).toList();
        check(calc.size()==2,"Same-name overloads remain separate");
        for(var routine:calc) {
            check(routine.getReturnArgument()!=null && routine.getReturnArgument().getDasType().getSpecification().contains("NUMBER"),"NULL-position function return mapped");
            var presentation=new com.intellij.codeInsight.lookup.LookupElementPresentation();
            TiberoRoutineCompletion.lookup(routine).renderElement(presentation);
            check(presentation.getTailText().contains("VALUE IN") && presentation.getTypeText().contains("NUMBER"),"Native lookup shows signature and return type");
        }
        check(candidates.stream().filter(r->r.getName().equals("RESET")).count()==2,"Zero-argument overload survives without argument rows");
        var read=candidates.stream().filter(r->r.getName().equals("READ_ITEM")).findFirst().orElseThrow();
        check(read.getArguments().iterator().next().getDasType().getSpecification().contains("ITEM_T"),"Package-local named type retained");
        checkCompletionPipeline(project,model);
        var before=List.copyOf(candidates);
        model.modify(schema,GenericSchema.class,s->TiberoRoutineCatalog.apply(s,snapshot));
        check(TiberoRoutineCompletion.candidates(pkg,new TiberoRoutineCompletion.Context("DEMO","PKG","")).equals(before),"Repeated refresh preserves overload identity");
        var wrong=new ArrayList<>(fixture.arguments()); wrong.add(wrong.get(0));
        reject(()->TiberoRoutineCatalog.snapshot("DEMO",fixture.objects(),fixture.members(),wrong));
        check(pkg.getRoutines().size()==5,"Invalid snapshot does not change native model");
        var missing=new ArrayList<>(fixture.arguments()); missing.remove(0);
        reject(()->TiberoRoutineCatalog.snapshot("DEMO",fixture.objects(),fixture.members(),missing));
        var changed=snapshot.stream().filter(s->!s.key().name().equals("CALC") || s.overload().equals("8")).toList();
        model.modify(schema,GenericSchema.class,s->TiberoRoutineCatalog.apply(s,changed));
        check(pkg.getRoutines().size()==4,"Dropped overload removed");
        check(TiberoRoutineCatalog.argumentsSql("O'WNER").contains("'O''WNER'") && TiberoRoutineCatalog.argumentsSql("S").contains("DATA_LEVEL = 0"),"Bounded schema and top-level argument SQL");
        checkTransport(fixture,false); checkTransport(fixture,true);
        checkCurrentSchema();
        checkUnqualifiedResolve(project);
        checkReadFailure("rows"); checkReadFailure("size"); checkReadFailure("sql");
        var largeMembers=new ArrayList<TiberoRoutineCatalog.Member>();
        for(int i=0;i<5000;i++) largeMembers.add(new TiberoRoutineCatalog.Member("DEMO","PKG","PROC_"+i,""+i,"NO"));
        long start=System.nanoTime();
        var large=TiberoRoutineCatalog.snapshot("DEMO",List.of(fixture.objects().get(1)),largeMembers,List.of());
        model.modify(schema,GenericSchema.class,s->TiberoRoutineCatalog.apply(s,large));
        check(pkg.getRoutines().size()==5000,"5000-member snapshot applied");
        check(TiberoRoutineCompletion.candidates(pkg,new TiberoRoutineCompletion.Context("DEMO","PKG","PROC_4999")).size()==1,"Prefix filtering happens before completion cap");
        var quoted=TiberoRoutineCompletion.context("SELECT \"Demo\".\"Pkg\".C");
        check(quoted!=null && TiberoRoutineCompletion.matches(quoted.pkg(),"Pkg") && !TiberoRoutineCompletion.matches(quoted.pkg(),"PKG"),"Quoted identifier casing preserved");
        System.out.println("PASS: 5000 routine signatures normalized/applied in " + ((System.nanoTime()-start)/1_000_000) + " ms (local synthetic model; not DB latency)");
    }
    private static void checkCompletionPipeline(Project project,BasicModModel model) throws Exception {
        var language=com.intellij.sql.psi.SqlLanguage.INSTANCE;
        check(language!=null,"SQL language registered");
        var contributor=com.intellij.codeInsight.completion.CompletionContributor.forLanguage(language).stream()
                .filter(c->c instanceof TiberoRoutineCompletion).findFirst().orElseThrow();
        String text="SELECT DEMO.PKG.CA";
        var file=com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText("tibero-completion.sql",language,text);
        var source=com.intellij.database.dataSource.LocalDataSource.temporary();
        source.setDatabaseDriver(com.intellij.database.dataSource.DatabaseDriverManager.getInstance().getDriver("tibero"));
        source.setName("Tibero offline completion fixture"); source.setAutoSynchronize(false); source.setModel(model);
        var manager=com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project);
        var disposable=com.intellij.openapi.util.Disposer.newDisposable();
        com.intellij.openapi.editor.Editor editor=null;
        try {
            manager.addDataSource(source);
            source.setModel(model); source.getInfo().setDbms(GenericDbms.TIBERO);
            var db=new com.intellij.database.psi.DbDataSourceImpl(project,source,(com.intellij.database.psi.DataSourceManager)manager);
            check(db!=null,"Fixture data source exposed through native PSI");
            com.intellij.sql.psi.impl.DataSourceProvider.EP_NAME.getPoint().registerExtension(
                    f->f==file?List.of(db):List.of(),disposable);
            var document=com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(text);
            editor=com.intellij.openapi.editor.EditorFactory.getInstance().createEditor(document,project);
            var params=new com.intellij.codeInsight.completion.CompletionParameters(file.findElementAt(text.length()-1),file,
                    com.intellij.codeInsight.completion.CompletionType.BASIC,text.length(),1,editor,()->false);
            var items=new ArrayList<com.intellij.codeInsight.lookup.LookupElement>();
            com.intellij.util.Consumer<com.intellij.codeInsight.completion.CompletionResult> consumer=r->items.add(r.getLookupElement());
            var method=com.intellij.codeInsight.completion.CompletionService.class.getDeclaredMethod("createResultSet",
                    com.intellij.codeInsight.completion.CompletionParameters.class,com.intellij.util.Consumer.class,
                    com.intellij.codeInsight.completion.CompletionContributor.class,com.intellij.codeInsight.completion.PrefixMatcher.class);
            method.setAccessible(true);
            var result=(com.intellij.codeInsight.completion.CompletionResultSet)method.invoke(
                    com.intellij.codeInsight.completion.CompletionService.getCompletionService(),params,consumer,contributor,
                    com.intellij.codeInsight.completion.PrefixMatcher.ALWAYS_TRUE);
            check(com.intellij.sql.psi.SqlPsiFacade.getInstance(project).getDataSources(file).contains(db),"SQL PSI fixture data source binding");
            check(GenericDbms.TIBERO.eq(db.getDbms()),"Fixture resolved as Tibero");
            check(!db.getNameIndex().getObjectsByName("PKG").isEmpty(),"Package indexed by native model");
            contributor.fillCompletionVariants(params,result);
            check(items.size()==2 && items.stream().allMatch(i->i.getLookupString().equals("CALC")),
                    "Registered contributor resolves PSI data source, native name index and both overloads: " + items);
        } finally {
            if(editor!=null) com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(editor);
            com.intellij.openapi.util.Disposer.dispose(disposable); manager.removeDataSource(source);
        }
    }
    /** noDataLevel: the server rejects DATA_LEVEL (JDBC-8026) and the reader retries without it. */
    private static void checkTransport(Fixture f,boolean noDataLevel) throws Exception {
        var rows=new ArrayList<List<String[]>>();
        rows.add(f.members().stream().map(m->new String[]{m.owner(),m.object(),m.name(),m.member(),m.function()}).toList());
        rows.add(f.arguments().stream().map(a->new String[]{a.owner(),a.name(),a.pkg(),a.overload(),a.position(),a.level(),a.argument(),a.type(),a.direction(),a.typeOwner(),a.typeName(),a.typeSubname()}).toList());
        int[] calls={0},closed={0};
        var remote=proxy(com.intellij.database.remote.jdbc.RemoteConnection.class,(o,m,a)-> {
            if(!m.getName().equals("createStatement")) throw new AssertionError("Unexpected connection operation: "+m.getName());
            int query=calls[0]++; int[] index={-1};
            return proxy(com.intellij.database.remote.jdbc.RemoteStatement.class,(s,method,args)->switch(method.getName()) {
                case "setQueryTimeout" -> {check(args[0].equals(30),"Timeout");yield null;}
                case "setFetchSize" -> {check(args[0].equals(512),"Fetch size");yield null;}
                case "close" -> {closed[0]++;yield null;}
                case "executeQuery" -> {
                    check(args[0].equals(switch(query) {
                        case 0 -> TiberoRoutineCatalog.membersSql("DEMO");
                        case 1 -> TiberoRoutineCatalog.argumentsSql("DEMO");
                        default -> TiberoRoutineCatalog.argumentsWithoutLevelSql("DEMO");
                    }),"Exact scoped SELECT");
                    if(noDataLevel && query==1) throw new java.sql.SQLSyntaxErrorException("JDBC-8026: Invalid identifier 'DATA_LEVEL'.","42000",-8026);
                    var result=rows.get(Math.min(query,1));
                    yield proxy(com.intellij.database.remote.jdbc.RemoteResultSet.class,(r,rm,ra)->switch(rm.getName()) {
                        case "next" -> ++index[0]<result.size();
                        case "getString" -> result.get(index[0])[(Integer)ra[0]-1];
                        case "close" -> {closed[0]++;yield null;}
                        default -> throw new AssertionError(rm.getName());
                    });
                }
                default -> throw new AssertionError(method.getName());
            });
        });
        var connection=proxy(com.intellij.database.dataSource.DatabaseConnectionCore.class,(o,m,a)-> {
            if(m.getName().equals("getRemoteConnection")) return remote;
            throw new AssertionError("Transaction or unexpected connection operation: "+m.getName());
        });
        check(TiberoRoutineReader.read(connection,"DEMO",f.objects()).size()==6 && calls[0]==(noDataLevel?3:2) && closed[0]==(noDataLevel?5:4),
                noDataLevel ? "ALL_ARGUMENTS without DATA_LEVEL retried once; all resources closed" : "Two bulk queries and all resources closed");
    }
    private static void checkCurrentSchema() throws Exception {
        var model=ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
        var schemas=new HashMap<String,GenericSchema>();
        model.modify(GenericRoot.class,root->{
            var family=root.getDatabases().createOrGet("TEST").getSchemas();
            for(var name:List.of("APP_USER","ABC","HR")) schemas.put(name,family.createOrGet(name));
            schemas.get("APP_USER").setCurrent(true);
        });
        boolean[] applied={false};
        model.modify(GenericRoot.class,root->applied[0]=TiberoCurrentSchema.apply(root,"ABC"));
        check(applied[0] && schemas.get("ABC").isCurrent() && !schemas.get("APP_USER").isCurrent() && !schemas.get("HR").isCurrent(),"Session CURRENT_SCHEMA becomes the only current schema");
        model.modify(GenericRoot.class,root->applied[0]=TiberoCurrentSchema.apply(root,"MISSING"));
        check(!applied[0] && schemas.get("ABC").isCurrent(),"Unknown CURRENT_SCHEMA leaves the model unchanged");
        var connection=proxy(com.intellij.database.dataSource.DatabaseConnectionCore.class,(o,m,a)-> {
            if(!m.getName().equals("getRemoteConnection")) throw new AssertionError(m.getName());
            return proxy(com.intellij.database.remote.jdbc.RemoteConnection.class,(c,cm,ca)-> {
                if(!cm.getName().equals("createStatement")) throw new AssertionError(cm.getName());
                return proxy(com.intellij.database.remote.jdbc.RemoteStatement.class,(s,sm,sa)->switch(sm.getName()) {
                    case "setQueryTimeout", "setFetchSize", "close" -> null;
                    case "executeQuery" -> {
                        if(((String)sa[0]).contains("SYS_CONTEXT")) throw new java.sql.SQLException("JDBC-8036: Specified procedure or function was not found.","42000",-8036);
                        boolean[] done={false};
                        yield proxy(com.intellij.database.remote.jdbc.RemoteResultSet.class,(r,rm,ra)->switch(rm.getName()) {
                            case "next" -> { boolean first=!done[0]; done[0]=true; yield first; }
                            case "getString" -> "APP_USER";
                            case "close" -> null;
                            default -> throw new AssertionError(rm.getName());
                        });
                    }
                    default -> throw new AssertionError(sm.getName());
                });
            });
        });
        check("APP_USER".equals(TiberoCurrentSchema.read(connection)),"Without SYS_CONTEXT the upper-case USER is the current schema");
    }
    /** Oracle-dialect consoles resolve unqualified names in the model's current schema when the session reports none. */
    private static void checkUnqualifiedResolve(Project project) throws Exception {
        var model=ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
        model.modify(GenericRoot.class,root->{
            var database=root.getDatabases().createOrGet("TEST"); database.setCurrent(true);
            var login=database.getSchemas().createOrGet("APP_USER"); login.setCurrent(true);
            var abc=database.getSchemas().createOrGet("ABC"); abc.getTables().createOrGet("ORDERS"); abc.getTables().createOrGet("ITEMS");
            login.getTables().createOrGet("ITEMS");
        });
        var source=com.intellij.database.dataSource.LocalDataSource.temporary();
        source.setDatabaseDriver(com.intellij.database.dataSource.DatabaseDriverManager.getInstance().getDriver("tibero"));
        source.setName("Tibero current schema fixture"); source.setAutoSynchronize(false);
        // Real data sources resolve only inside the introspection scope; the fixture's ABC must be in it like a selected schema.
        source.setIntrospectionScope(com.intellij.database.util.TreePatternUtils.parse(GenericDbms.TIBERO,"*:*"));
        var manager=com.intellij.database.dataSource.LocalDataSourceManager.getInstance(project);
        var disposable=com.intellij.openapi.util.Disposer.newDisposable();
        try {
            manager.addDataSource(source);
            source.setModel(model); source.getInfo().setDbms(GenericDbms.TIBERO);
            var db=new com.intellij.database.psi.DbDataSourceImpl(project,source,(com.intellij.database.psi.DataSourceManager)manager);
            var files=new HashSet<com.intellij.psi.PsiFile>();
            com.intellij.sql.psi.impl.DataSourceProvider.EP_NAME.getPoint().registerExtension(f->files.contains(f)?List.of(db):List.of(),disposable);
            check(resolve(project,files,"SELECT * FROM ABC.ORDERS")!=null,"Qualified name resolves with the login schema current");
            check(resolve(project,files,"SELECT * FROM ORDERS")==null,"Unqualified name does not resolve while the login schema is current");
            // PUBLIC synonyms ORDERS -> ABC.ORDERS and ITEMS -> ABC.ITEMS; the login schema has its own ITEMS.
            DbLinkVerification.run(project);
            var synonyms=TiberoSynonyms.getInstance();
            synonyms.update(source.getUniqueId(),Map.of("ORDERS",new TiberoSynonyms.Target("ABC","ORDERS"),"ITEMS",new TiberoSynonyms.Target("ABC","ITEMS")));
            try {
                check(in(resolve(project,files,"SELECT * FROM ORDERS"),"ABC","ORDERS"),"Unqualified name resolves through its PUBLIC synonym to ABC.ORDERS");
                check(in(resolve(project,files,"SELECT * FROM ITEMS","ITEMS"),"APP_USER","ITEMS"),"An object in the current schema hides the PUBLIC synonym");
                check(resolve(project,files,"SELECT * FROM \"orders\"","orders")==null,"Quoted lower-case name does not match the synonym");
            } finally { synonyms.update(source.getUniqueId(),Map.of()); }
            model.modify(GenericRoot.class,root->TiberoCurrentSchema.apply(root,"ABC"));
            var target=resolve(project,files,"SELECT * FROM ORDERS");
            check(in(target,"ABC","ORDERS"),"Unqualified name resolves in the session CURRENT_SCHEMA: " + target);
            System.out.println("PASS: Oracle-dialect console resolves unqualified names through PUBLIC synonyms and in CURRENT_SCHEMA (ABC); local objects win; DATA_LEVEL-less ALL_ARGUMENTS retried");
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable); manager.removeDataSource(source);
        }
    }
    private static com.intellij.psi.PsiElement resolve(Project project,Set<com.intellij.psi.PsiFile> files,String sql) { return resolve(project,files,sql,"ORDERS"); }
    private static com.intellij.psi.PsiElement resolve(Project project,Set<com.intellij.psi.PsiFile> files,String sql,String name) {
        var file=com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText("tibero-resolve.sql",com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,sql);
        files.add(file);
        var reference=com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file,com.intellij.sql.psi.SqlReferenceExpression.class).stream()
                .filter(r->name.equals(r.getName())).findFirst().orElseThrow();
        return reference.getReference().resolve();
    }
    private static boolean in(com.intellij.psi.PsiElement target,String schema,String name) {
        return target instanceof com.intellij.database.model.DasObject t && name.equals(t.getName()) && schema.equals(com.intellij.database.util.DasUtil.getSchema(t));
    }
    private static void checkReadFailure(String mode) throws Exception {
        int[] closed={0};
        String value=mode.equals("size")?"x".repeat(1024*1024):"x";
        var rs=proxy(com.intellij.database.remote.jdbc.RemoteResultSet.class,(o,m,a)->switch(m.getName()) {
            case "next" -> true;
            case "getString" -> {if(mode.equals("sql")) throw new java.sql.SQLException("read failed");yield value;}
            case "close" -> {closed[0]++;yield null;}
            default -> throw new AssertionError(m.getName());
        });
        var statement=proxy(com.intellij.database.remote.jdbc.RemoteStatement.class,(o,m,a)->switch(m.getName()) {
            case "setQueryTimeout", "setFetchSize" -> null;
            case "executeQuery" -> rs;
            case "close" -> {closed[0]++;yield null;}
            default -> throw new AssertionError(m.getName());
        });
        var remote=proxy(com.intellij.database.remote.jdbc.RemoteConnection.class,(o,m,a)-> {
            if(m.getName().equals("createStatement")) return statement; throw new AssertionError(m.getName());
        });
        var connection=proxy(com.intellij.database.dataSource.DatabaseConnectionCore.class,(o,m,a)-> {
            if(m.getName().equals("getRemoteConnection")) return remote; throw new AssertionError(m.getName());
        });
        try {TiberoRoutineReader.query(connection,TiberoRoutineCatalog.membersSql("DEMO"),1);throw new AssertionError("Partial read accepted");}
        catch(java.sql.SQLException expected) {check(closed[0]==2,"Resources closed after " + mode + " failure");}
    }
    private static <T> T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler) { return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler)); }
    private static void reject(Runnable r) { try {r.run();} catch(IllegalArgumentException expected) {return;} throw new AssertionError("Invalid metadata accepted"); }
    private static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message);}
}
