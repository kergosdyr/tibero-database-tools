package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.model.*;
import com.intellij.database.introspection.*;
import com.intellij.database.model.ModelFactory;
import com.intellij.database.model.DasRoutine;
import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.remote.jdbc.*;
import com.intellij.openapi.project.Project;
import java.util.*;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.atomic.AtomicReference;

/** Offline contract checks run only by the explicit sandbox ApplicationStarter. */
final class StoredObjectsVerification {
    static void run(Project project) throws Exception {
        var introspector=DBIntrospectorFactory.createIntrospector(new DumbIntrospectionContext(project),ModelFactory.BLACK_HOLE,GenericDbms.TIBERO,false);
        check(introspector.getClass().getName().startsWith("com.intellij.database."),"IDE JDBC introspector handles Tibero without a private factory");
        var sinkType=DBIntrospector.class.getMethod("getErrorSink").getReturnType();
        var reported=new AtomicReference<String>();
        var error=new java.sql.SQLException("sample error");
        Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(o,m,args) -> {
            if(m.getName().equals("accept") || m.getName().equals("consume")) {
                check(args[1]==error,"Original introspection error retained"); reported.set((String)args[0]); return null;
            }
            throw new AssertionError("Unexpected error sink call " + m.getName());
        });
        DBIntrospector.class.getMethod("setErrorSink",sinkType).invoke(introspector,sink);
        IdeCompatibility.report(introspector,"catalogue failure",error);
        check("catalogue failure".equals(reported.get()),"Version-specific error sink receives failures");
        var model=ModelFactory.BLACK_HOLE.createModel(GenericDbms.TIBERO);
        var target=new AtomicReference<OraSchema>();
        var untouched=new AtomicReference<OraSchema>();
        model.modify(OraRoot.class,root -> {
            var db=root;
            var schema=db.getSchemas().createOrGet("MixedCase"); target.set(schema);
            schema.getTables().createOrGet("KEEP_TABLE");
            schema.getViews().createOrGet("KEEP_VIEW");
            untouched.set(db.getSchemas().createOrGet("OTHER"));
            untouched.get().getRoutines().createOrGet("KEEP_ROUTINE");
        });
        var schema=target.get();
        List<TiberoStoredObjects.Entry> fixture;
        try(var stream=StoredObjectsVerification.class.getResourceAsStream("/samples/stored-objects.json")) {
            if(stream==null) throw new AssertionError("Missing catalogue fixture");
            fixture=List.of(new com.google.gson.Gson().fromJson(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),TiberoStoredObjects.Entry[].class));
        }
        TiberoStoredObjects.refresh(schema,() -> fixture);
        check(schema.getRoutines().size()==2 && schema.getPackages().size()==2,"Functions, procedures and empty packages appear in native model");
        var fn=schema.getRoutines().find(r -> r.getName().equals("Fn"));
        var proc=schema.getRoutines().find(r -> r.getName().equals("Proc"));
        var pkg=schema.getPackages().find(p -> p.getName().equals("Pkg"));
        check(fn.getRoutineKind()==DasRoutine.Kind.FUNCTION && proc.getRoutineKind()==DasRoutine.Kind.PROCEDURE,"Routine kind retained");
        check(proc.isInvalid() && pkg.isInvalid(),"Invalid status retained in the native model");
        check(fn.getSchema().equals(schema) && pkg.getParent().equals(schema),"Native schema hierarchy");
        var loader=new TiberoViewLoader();
        check(loader.sqlProcedureDefinition(fn).first.contains("OWNER = 'MixedCase'") && loader.sqlProcedureDefinition(fn).first.contains("TYPE = 'FUNCTION'"),"Discovered function routes to source loader");
        check(loader.sqlProcedureDefinition(proc).first.contains("TYPE = 'PROCEDURE'"),"Procedure source routing");
        check(loader.sqlPackageDefinition(pkg).first.contains("TYPE = 'PACKAGE'") && loader.sqlPackageBodyDefinition(pkg).first.contains("TYPE = 'PACKAGE BODY'"),"Package specification and body routing");
        TiberoStoredObjects.refresh(schema,() -> fixture);
        check(schema.getRoutines().size()==2 && schema.getPackages().size()==2,"Refresh is idempotent");
        check(schema.getRoutines().find(r -> r.getName().equals("Fn")).equals(fn),"Refresh preserves existing object identity");
        try { TiberoStoredObjects.refresh(schema,() -> { throw new java.sql.SQLException("permission denied"); }); throw new AssertionError("Failure expected"); }
        catch(java.sql.SQLException expected) { check(schema.getRoutines().size()==2 && schema.getPackages().size()==2,"Query failure preserves existing nodes"); }
        try { TiberoStoredObjects.refresh(schema,() -> List.of(new TiberoStoredObjects.Entry("OTHER","BAD","FUNCTION","VALID"))); throw new AssertionError("Wrong schema accepted"); }
        catch(IllegalArgumentException expected) { check(schema.getRoutines().size()==2,"Validation failure preserves existing nodes"); }
        TiberoStoredObjects.refresh(schema,() -> List.of(entry("Fn","PROCEDURE","VALID"),entry("BodyOnly","PACKAGE BODY","VALID")));
        check(schema.getRoutines().size()==1 && schema.getRoutines().first().getRoutineKind()==DasRoutine.Kind.PROCEDURE,"Drop and kind change reflected");
        check(schema.getPackages().size()==1 && schema.getPackages().first().getName().equals("BodyOnly"),"Package/body coalescing and dropped package cleanup");
        TiberoStoredObjects.refresh(schema,List::of);
        check(schema.getRoutines().isEmpty() && schema.getPackages().isEmpty(),"Successful empty catalogue removes stale nodes");
        check(schema.getTables().size()==1 && schema.getViews().size()==1 && untouched.get().getRoutines().size()==1,"Tables/views and unselected schemas are untouched");
        check(TiberoStoredObjects.sql("O'WNER").contains("OWNER = 'O''WNER'"),"Schema literal escaped exactly");
        checkRemoteRead(fixture);
        checkSequences(schema,untouched.get());
        checkMetadata(project,schema);
        checkCachedColumnTypes(project,schema);
        checkRoutineExecution(project,schema);
        checkConsoleHelpers(project);
        checkDbLinks();
        NativeTreeVerification.run(project,schema);

    }
    private static void checkCachedColumnTypes(Project project,OraSchema schema) {
        // Types cached by the pre-fix JDBC path, as makeDasType built them from raw Tibero values; the rest must stay.
        Object[][] cases={
                {"TS","TIMESTAMP(6)",6,6,93,"TIMESTAMP(6,6)","TIMESTAMP(6)"}, {"TS_LEN","TIMESTAMP",11,6,93,"TIMESTAMP(11,6)","TIMESTAMP(6)"},
                {"TS_TZ","TIMESTAMP(3) WITH TIME ZONE",3,3,93,"TIMESTAMP(3,3) WITH TIME ZONE","TIMESTAMP(3) WITH TIME ZONE"},
                {"D","DATE",7,0,91,"DATE(7)","DATE"}, {"N0","NUMBER",0,0,2,"NUMBER(0)","NUMBER"},
                {"N","NUMBER",12,4,2,"NUMBER(12,4)","NUMBER(12,4)"}, {"TS3","TIMESTAMP",3,0,93,"TIMESTAMP(3)","TIMESTAMP(3)"},
                {"TS0","TIMESTAMP(0)",-1,0,93,"TIMESTAMP(0)","TIMESTAMP(0)"},
                {"IDS","INTERVAL DAY(2) TO SECOND(6)",-1,0,1111,"INTERVAL DAY(2) TO SECOND(6)","INTERVAL DAY(2) TO SECOND(6)"},
                {"V","VARCHAR2",20,0,12,"VARCHAR2(20)","VARCHAR2(20)"}};
        schema.getModel().modify(schema,OraSchema.class,mod -> {
            var table=mod.getTables().createOrGet("TYPE_REPAIR");
            for(var c:cases) {
                var column=table.getColumns().createOrGet((String)c[0]);
                column.setDataType(com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType((String)c[1],(Integer)c[2],(Integer)c[3],null,(Integer)c[4],column).toDataType());
                check(column.getDataType().getSpecification().equals(c[5]),"Cached fixture " + c[0] + ": " + column.getDataType().getSpecification());
            }
        });
        TiberoColumnTypes.repair(schema);
        var table=schema.getTables().find(t -> t.getName().equals("TYPE_REPAIR"));
        for(var c:cases) {
            String actual=table.getColumns().find(e -> e.getName().equals(c[0])).getDataType().getSpecification();
            check(actual.equals(c[6]),"Final model repair " + c[0] + ": " + c[5] + " -> " + actual);
        }
        String ddl=ddl(project,schema,table).toUpperCase(java.util.Locale.ROOT);
        check(ddl.contains("TIMESTAMP(6)") && !ddl.matches("(?s).*TIMESTAMP\\(\\d+\\s*,.*") && !ddl.contains("DATE("),"Actual DDL generator uses corrected types: " + ddl);
        System.out.println("PASS: actual CREATE TABLE DDL emits TIMESTAMP(6)/DATE after repairing cached TIMESTAMP(6,6)/DATE(7)");
        schema.getModel().modify(schema,OraSchema.class,mod -> table.drop());
    }
    private static void checkRoutineExecution(Project project,OraSchema schema) {
        schema.getModel().modify(schema,OraSchema.class,mod -> {
            var routines=mod.familyOf(com.intellij.database.model.ObjectKind.ROUTINE);
            var function=(com.intellij.database.model.basic.BasicModRoutine)routines.createNewOne();
            function.setName("FN_AGE"); function.setRoutineKind(DasRoutine.Kind.FUNCTION);
            var ret=function.createOrGetReturnArgument(); ret.setArgumentDirection(com.intellij.database.model.ArgumentDirection.RETURN); ret.setDataType(com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType("NUMBER",-1,0,null,java.sql.Types.OTHER,ret).toDataType());
            var from=function.getArguments().createNewOne(); from.setName("P_FROM"); from.setPosition((short)1); from.setArgumentDirection(com.intellij.database.model.ArgumentDirection.IN); from.setDataType(com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType("DATE",-1,0,null,java.sql.Types.OTHER,from).toDataType());
            var procedure=(com.intellij.database.model.basic.BasicModRoutine)routines.createNewOne();
            procedure.setName("DO_IT"); procedure.setRoutineKind(DasRoutine.Kind.PROCEDURE);
            var id=procedure.getArguments().createNewOne(); id.setName("P_ID"); id.setPosition((short)1); id.setArgumentDirection(com.intellij.database.model.ArgumentDirection.INOUT); id.setDataType(com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType("NUMBER",-1,0,null,java.sql.Types.OTHER,id).toDataType());
        });
        var function=schema.getRoutines().find(r -> r.getName().equals("FN_AGE"));
        var procedure=schema.getRoutines().find(r -> r.getName().equals("DO_IT"));
        String fn=TiberoRoutineScript.block(function), proc=TiberoRoutineScript.block(procedure);
        check(fn.contains("v_result NUMBER;") && fn.contains("v_p_from DATE := NULL; -- IN P_FROM")
                && fn.contains("v_result := MixedCase.FN_AGE(P_FROM => v_p_from);".replace("MixedCase","\"MixedCase\""))
                && fn.contains("DBMS_OUTPUT.PUT_LINE('RETURN = ' || v_result);"),"Function execute block: " + fn);
        check(proc.contains("v_p_id NUMBER := NULL; -- INOUT P_ID") && proc.contains("\"MixedCase\".DO_IT(P_ID => v_p_id);")
                && proc.contains("DBMS_OUTPUT.PUT_LINE('P_ID = ' || v_p_id);") && !proc.contains("v_result"),"Procedure execute block: " + proc);
        check(TiberoRoutineScript.declaration("VARCHAR2").equals("VARCHAR2(32767)") && TiberoRoutineScript.declaration("REF CURSOR").equals("SYS_REFCURSOR")
                && TiberoRoutineScript.identifier("Mixed\"Q").equals("\"Mixed\"\"Q\""),"PL/SQL variable declarations and quoting");
        var dialect=com.intellij.database.util.DbSqlUtilCore.getSqlDialect(GenericDbms.TIBERO);
        check(dialect==com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,"Tibero consoles default to the Oracle SQL/PL/SQL dialect: " + dialect);
        for(String block:List.of(fn,proc)) {
            var queries=com.intellij.database.util.DbSqlUtilCore.getQueries(block,project,dialect);
            check(queries.size()==1 && queries.get(0).contains("END"),"Execute block stays one statement: " + queries);
        }
        var generic=com.intellij.database.util.DbSqlUtilCore.getQueries(fn,project,com.intellij.database.util.DbSqlUtilCore.getSqlDialect(com.intellij.database.Dbms.UNKNOWN));
        check(generic.size()>1,"GenericSQL would have split the PL/SQL block: " + generic.size());
        System.out.println("PASS: Execute Routine PL/SQL block; Tibero consoles parse PL/SQL as one statement (GenericSQL split it into " + generic.size() + ")");
        // A console's language comes from its driver; the bundled Tibero driver declares GenericSQL.
        TiberoSessions.install();
        var driver=(com.intellij.database.dataSource.DatabaseDriverImpl)com.intellij.database.dataSource.DatabaseDriverManager.getInstance().getDriver("tibero");
        var source=com.intellij.database.dataSource.LocalDataSource.temporary();
        source.setDatabaseDriver(driver);
        check(com.intellij.database.util.DbSqlUtilCore.getSqlDialect(source)==com.intellij.sql.dialects.oracle.OraDialect.INSTANCE,
                "Console dialect of a data source on the bundled Tibero driver: " + driver.getSqlDialect());
        var bundled=driver.copy("tibero.check",false); bundled.setSqlDialect("GenericSQL");
        var chosen=driver.copy("tibero.chosen",false); chosen.setSqlDialect("PostgreSQL");
        var other=driver.copy("other.check",false); other.setDriverClass("org.example.Driver"); other.setSqlDialect("GenericSQL");
        check(TiberoSessions.preferOracleDialect(bundled) && "Oracle".equals(bundled.getSqlDialect())
                && !TiberoSessions.preferOracleDialect(chosen) && "PostgreSQL".equals(chosen.getSqlDialect())
                && !TiberoSessions.preferOracleDialect(other) && "GenericSQL".equals(other.getSqlDialect()),"Only GenericSQL Tibero drivers switch to Oracle");
        System.out.println("PASS: real console path (driver dialect) resolves Tibero data sources to Oracle; user-chosen dialects and other drivers untouched");
        schema.getModel().modify(schema,OraSchema.class,mod -> mod.familyOf(com.intellij.database.model.ObjectKind.ROUTINE)
                .remove(r -> r instanceof com.intellij.database.model.basic.BasicNamedElement n && List.of("FN_AGE","DO_IT").contains(n.getName())));
    }
    private static void checkConsoleHelpers(Project project) throws Exception {
        var helper=com.intellij.database.console.ExecutionEnvironmentHelper.EP.forDbms(GenericDbms.TIBERO);
        check(helper instanceof TiberoExecutionEnvironment,"Tibero execution environment selected: " + helper);
        String ping=helper.getKeepAliveQuery(project,com.intellij.database.dataSource.LocalDataSource.temporary());
        check(ping.toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+"," ").contains("'KEEP ALIVE' FROM DUAL"),"Keep-alive query has FROM DUAL: " + ping);
        // DBMS_OUTPUT cycle against a fake session.
        var executed=new ArrayList<String>(); var lines=new ArrayDeque<>(List.of("first","","third"));
        boolean[] failEnable={false};
        var calls=new TiberoDbmsOutput.Calls() {
            public void execute(String sql) throws java.sql.SQLException { if(failEnable[0] && sql.equals(TiberoDbmsOutput.ENABLE)) throw new java.sql.SQLException("PLS: DBMS_OUTPUT not granted"); executed.add(sql); }
            public String nextLine() { return lines.poll(); }
        };
        var output=new TiberoDbmsOutput(true); var printed=new ArrayList<String>(); var errors=new ArrayList<String>();
        output.begin(calls,errors::add); output.begin(calls,errors::add); output.finish(calls,printed::add,errors::add);
        check(executed.equals(List.of(TiberoDbmsOutput.ENABLE,TiberoDbmsOutput.DISABLE)),"One ENABLE per request, DISABLE after reading: " + executed);
        check(printed.equals(List.of("first","","third")) && errors.isEmpty(),"DBMS_OUTPUT lines (including empty) reach the console: " + printed);
        executed.clear(); output.finish(calls,printed::add,errors::add);
        check(executed.isEmpty(),"No DBMS_OUTPUT reads for requests that never enabled it");
        output.enabled=false; output.begin(calls,errors::add); output.finish(calls,printed::add,errors::add);
        check(executed.isEmpty(),"Toggle off issues no DBMS_OUTPUT calls");
        output.enabled=true; failEnable[0]=true; output.begin(calls,errors::add);
        check(!output.enabled && errors.size()==1 && errors.get(0).contains("not granted"),"Missing DBMS_OUTPUT turns itself off once: " + errors);
        // Remote JDBC mapping and statement cleanup.
        int[] status={0,0,1}; int[] row={-1}; var closed=new ArrayList<String>(); var registered=new ArrayList<Integer>();
        var connection=proxy(DatabaseConnectionCore.class,(o,m,args) -> {
            if(!m.getName().equals("getRemoteConnection")) throw new AssertionError(m.getName());
            return proxy(RemoteConnection.class,(c,cm,cargs) -> {
                if(!cm.getName().equals("prepareCall")) throw new AssertionError("Unexpected connection call: " + cm.getName());
                String sql=(String)cargs[0];
                return proxy(RemoteCallableStatement.class,(st,sm,sargs) -> switch(sm.getName()) {
                    case "registerOutParameter" -> { registered.add((Integer)sargs[1]); yield null; }
                    case "execute" -> { if(sql.equals(TiberoDbmsOutput.GET_LINE)) row[0]++; yield false; }
                    case "getInt" -> status[row[0]];
                    case "getString" -> row[0]==1 ? null : "line" + row[0];
                    case "close" -> { closed.add(sql); yield null; }
                    default -> throw new AssertionError("Unexpected statement call: " + sm.getName());
                });
            });
        });
        var remote=TiberoDbmsOutput.remote(connection);
        remote.execute(TiberoDbmsOutput.ENABLE);
        check("line0".equals(remote.nextLine()) && "".equals(remote.nextLine()) && remote.nextLine()==null,"GET_LINE status/line mapping");
        check(registered.equals(List.of(java.sql.Types.VARCHAR,java.sql.Types.INTEGER,java.sql.Types.VARCHAR,java.sql.Types.INTEGER,java.sql.Types.VARCHAR,java.sql.Types.INTEGER))
                && closed.size()==4,"Out parameters registered and every call closed: " + closed);
        System.out.println("PASS: keep-alive SELECT ... FROM DUAL; DBMS_OUTPUT enable/read/disable cycle, toggle, failure and remote call mapping");
        // Compilation errors after CREATE of stored PL/SQL objects.
        Object[][] creates={
                {"create or replace procedure do_it is begin null; end;",null,"DO_IT","PROCEDURE"},
                {"CREATE OR REPLACE EDITIONABLE PACKAGE BODY app.\"Pkg\" AS END;","APP","Pkg","PACKAGE BODY"},
                {"-- header\n/* note */ CREATE FUNCTION \"S\".\"F\"\"Q\" RETURN NUMBER IS BEGIN RETURN 1; END;","S","F\"Q","FUNCTION"},
                {"create or replace force view v1 as select 1 x from dual",null,"V1","VIEW"},
                {"CREATE TYPE BODY t_obj AS END;",null,"T_OBJ","TYPE BODY"},
                {"CREATE TABLE t (x NUMBER)",null,null,null}, {"SELECT 'CREATE PROCEDURE p' FROM DUAL",null,null,null}};
        for(var c:creates) {
            var target=TiberoCompileErrors.target((String)c[0]);
            check(c[2]==null ? target==null : target!=null && Objects.equals(target.owner(),c[1]) && target.name().equals(c[2]) && target.type().equals(c[3]),"CREATE target " + c[0] + " -> " + target);
        }
        check(TiberoCompileErrors.sql(TiberoCompileErrors.target("create procedure do_it is begin null; end;"))
                .equals("SELECT LINE, POSITION, TEXT FROM ALL_ERRORS WHERE OWNER = USER AND NAME = 'DO_IT' AND TYPE = 'PROCEDURE' ORDER BY LINE, POSITION"),"ALL_ERRORS query for current user");
        var compile=new TiberoCompileErrors(); var reported=new ArrayList<String>(); var asked=new ArrayList<String>();
        compile.record("create or replace procedure do_it is begin x := 1; end;");
        compile.record("create or replace procedure do_it is begin x := 1; end;");
        compile.record("create table t (x number)");
        compile.report(target -> { asked.add(target.name()); return List.<String[]>of(new String[]{"1","32","PSM-..: identifier 'X' must be declared"}); },reported::add);
        check(asked.equals(List.of("DO_IT")) && reported.equals(List.of("PROCEDURE DO_IT compilation error at line 1, column 32: PSM-..: identifier 'X' must be declared")),"Compilation errors reported once per object: " + reported);
        reported.clear(); compile.record("create function f return number is begin return 1; end;");
        compile.report(target -> { throw new java.sql.SQLException("no ALL_ERRORS"); },reported::add);
        compile.report(target -> { throw new AssertionError("Targets must be consumed"); },reported::add);
        check(reported.isEmpty(),"ALL_ERRORS failure stays quiet and pending targets are cleared");
        System.out.println("PASS: CREATE PROCEDURE/FUNCTION/PACKAGE [BODY]/TYPE [BODY]/TRIGGER/VIEW compilation errors from ALL_ERRORS");
    }
    private static void checkDbLinks() {
        var entries=TiberoDbLinks.snapshot("MixedCase",List.of(
                new String[]{"MixedCase","REMOTE","APP","TB6","2026-10-16"},
                new String[]{"PUBLIC","REMOTE","PUBLIC_APP","TB7",null}));
        check(entries.size()==2,"Private/public links with same name stay distinct");
        try { TiberoDbLinks.snapshot("MixedCase",java.util.Collections.singletonList(new String[]{"OTHER","BAD",null,null,null})); throw new AssertionError("Wrong owner accepted"); }
        catch(IllegalArgumentException expected) {}
    }
    private static void checkMetadata(Project project,OraSchema schema) throws Exception {
        var metadata=proxy(RemoteDatabaseMetaData.class,(o,m,args) -> { throw new AssertionError("Broken JDBC metadata path invoked: " + m.getName()); });
        var source=com.intellij.database.dataSource.LocalDataSource.temporary();
        var point=proxy(com.intellij.database.dataSource.DatabaseConnectionPoint.class,(o,m,args) -> {
            if(m.getName().equals("getDataSource")) return source;
            throw new AssertionError(m.getName());
        });
        var audit=new AtomicReference<com.intellij.database.dataSource.connection.audit.AdvancedAuditService>();
        var connection=proxy(DatabaseConnectionCore.class,(o,m,args) -> switch(m.getName()) {
            case "getDbms" -> GenericDbms.TIBERO;
            case "getConnectionPoint" -> point;
            case "getAuditService" -> audit.get();
            case "hashCode" -> System.identityHashCode(o);
            case "equals" -> o==args[0];
            default -> throw new AssertionError(m.getName());
        });
        audit.set(new com.intellij.database.dataSource.connection.audit.AdvancedAuditService(connection,
                () -> com.intellij.database.dataSource.connection.audit.AuditService.compose(),project));
        var factory=com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.EP.forDbms(GenericDbms.TIBERO);
        check(factory instanceof TiberoMetadataWrapper.Factory,"Tibero metadata adapter selected");
        var wrapper=com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.create(connection,metadata);
        check(wrapper instanceof TiberoMetadataWrapper,"Connection-based metadata dispatch selects Tibero adapter");
        checkArgumentPipeline(connection,schema);
        checkColumnPipeline(project,connection,schema);
        audit.get().close();
    }
    // Rows shaped exactly like Tibero 6 TbDatabaseMetaData.getColumns(): TYPE_NAME is ALL_TAB_COLUMNS.DATA_TYPE,
    // COLUMN_SIZE is DECODE(DATA_PRECISION, 0, DATA_LENGTH, DATA_PRECISION), DECIMAL_DIGITS is DATA_SCALE.
    private static final String[] COLUMN_NAMES={"TABLE_CAT","TABLE_SCHEM","TABLE_NAME","COLUMN_NAME","DATA_TYPE","TYPE_NAME","COLUMN_SIZE","BUFFER_LENGTH","DECIMAL_DIGITS","NUM_PREC_RADIX","NULLABLE","REMARKS","COLUMN_DEF","SQL_DATA_TYPE","SQL_DATETIME_SUB","CHAR_OCTET_LENGTH","ORDINAL_POSITION","IS_NULLABLE","SCOPE_CATALOG","SCOPE_SCHEMA","SCOPE_TABLE","SOURCE_DATA_TYPE","IS_AUTOINCREMENT"};
    private static Object[] column(int position,String name,int jdbc,String type,Integer size,Integer digits) {
        return new Object[]{null,"MixedCase","T",name,jdbc,type,size,0,digits,10,1,null,null,0,0,0,position,"YES",null,null,null,null,"NO"};
    }
    private static void checkColumnPipeline(Project project,DatabaseConnectionCore connection,OraSchema schema) throws Exception {
        var expected=new LinkedHashMap<String,String>();
        var rows=new ArrayList<Object[]>();
        Object[][] cases={
                {"TS6","TIMESTAMP(6)",93,6,6,"TIMESTAMP(6)"}, {"TS6_LEN","TIMESTAMP(6)",93,11,6,"TIMESTAMP(6)"},
                {"TS6_NOSIZE","TIMESTAMP(6)",93,null,6,"TIMESTAMP(6)"}, {"TS_BARE","TIMESTAMP",93,6,6,"TIMESTAMP(6)"},
                {"TS_DEFAULT","TIMESTAMP",93,null,null,"TIMESTAMP"}, {"TS0","TIMESTAMP(0)",93,0,0,"TIMESTAMP(0)"},
                {"TS3_TZ","TIMESTAMP(3) WITH TIME ZONE",93,13,3,"TIMESTAMP(3) WITH TIME ZONE"},
                {"TS9_LTZ","TIMESTAMP(9) WITH LOCAL TIME ZONE",93,11,9,"TIMESTAMP(9) WITH LOCAL TIME ZONE"},
                {"IDS","INTERVAL DAY(2) TO SECOND(6)",1111,2,6,"INTERVAL DAY(2) TO SECOND(6)"},
                {"IYM","INTERVAL YEAR(2) TO MONTH",1111,2,0,"INTERVAL YEAR(2) TO MONTH"},
                {"D7","DATE",91,7,null,"DATE"}, {"D_NULL","DATE",91,null,null,"DATE"}, {"C","CLOB",2005,4000,null,"CLOB"},
                {"N","NUMBER",2,10,2,"NUMBER(10,2)"}, {"N_PLAIN","NUMBER",2,null,null,"NUMBER"}, {"N22","NUMBER",2,22,0,"NUMBER(22)"},
                {"V","VARCHAR2",12,20,null,"VARCHAR2(20)"}, {"R","RAW",-3,16,null,"RAW(16)"}};
        for(var c:cases) {
            rows.add(column(rows.size()+1,(String)c[0],(Integer)c[2],(String)c[1],(Integer)c[3],(Integer)c[4]));
            expected.put((String)c[0],(String)c[5]);
        }
        var remote=rows(COLUMN_NAMES,rows);
        var metadata=proxy(RemoteDatabaseMetaData.class,(o,m,args) -> switch(m.getName()) {
            case "getColumns" -> remote;
            case "getSearchStringEscape" -> "/";
            default -> throw new AssertionError("Unexpected JDBC call: "+m.getName());
        });
        var wrapper=(TiberoMetadataWrapper)com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.create(connection,metadata);
        var read=new ArrayList<Object[]>();
        try(var columns=wrapper.getTableColumnsInner2(new com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.Schema("MixedCase",null),"T")) {
            while(columns.hasNext()) { var c=columns.next(); read.add(new Object[]{c.name,c.typeName,c.length,c.scale,c.typeSuffix,c.type}); }
        }
        var actual=new LinkedHashMap<String,String>();
        schema.getModel().modify(schema,OraSchema.class,mod -> {
            var table=mod.getTables().createOrGet("PIPELINE");
            for(var c:read) {
                var column=table.getColumns().createOrGet((String)c[0]);
                column.setDataType(com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType((String)c[1],(Integer)c[2],(Integer)c[3],(String)c[4],(Integer)c[5],column).toDataType());
                actual.put((String)c[0],column.getDataType().getSpecification());
            }
        });
        check(actual.equals(expected),"Tibero getColumns() rows to native types: " + actual);
        var table=schema.getTables().find(t -> t.getName().equals("PIPELINE"));
        String ddl=ddl(project,schema,table).toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+"," ");
        for(var e:expected.entrySet()) check(ddl.contains(e.getKey()+" "+e.getValue()),"Generated DDL keeps " + e.getKey() + " " + e.getValue() + ": " + ddl);
        check(!ddl.matches(".*TIMESTAMP\\(\\d+\\s*,.*") && !ddl.contains("DATE("),"Generated DDL has no doubled timestamp precision or sized DATE: " + ddl);
        System.out.println("PASS: Tibero getColumns() shapes -> native model -> CREATE TABLE DDL (" + expected.size() + " types)");
        schema.getModel().modify(schema,OraSchema.class,mod -> table.drop());
    }
    // Columns and NULLs of Tibero 6 TbDatabaseMetaQuery.QUERY_PROCEDURE_COLUMNS: ORDINAL_POSITION is always NULL and
    // the function result row has no ARGUMENT_NAME and COLUMN_TYPE 5.
    private static final String[] ARGUMENT_NAMES={"PROCEDURE_CAT","PROCEDURE_SCHEM","PROCEDURE_NAME","COLUMN_NAME","COLUMN_TYPE","DATA_TYPE","TYPE_NAME","PRECISION","LENGTH","SCALE","RADIX","NULLABLE","REMARKS","COLUMN_DEF","SQL_DATA_TYPE","SQL_DATETIME_SUB","CHAR_OCTET_LENGTH","ORDINAL_POSITION","IS_NULLABLE","SPECIFIC_NAME"};
    private static Object[] argument(String pkg,String routine,String name,int mode,int jdbc,String type,Integer precision,Integer length,Integer scale,Integer octets) {
        return new Object[]{pkg,"MixedCase",routine,name,mode,jdbc,type,precision,length,scale,10,1,null,null,null,null,octets,null,"YES",null};
    }
    private static void checkArgumentPipeline(DatabaseConnectionCore connection,OraSchema schema) throws Exception {
        var remote=rows(ARGUMENT_NAMES,List.of(
                argument(null,"FN_AGE",null,5,2,"NUMBER",22,22,null,null),
                argument(null,"FN_AGE","P_FROM",1,93,"TIMESTAMP",11,11,6,null),
                argument(null,"FN_AGE","P_NAME",1,12,"VARCHAR2",null,null,null,32767),
                argument("PKG","DO_IT","P_ID",2,2,"NUMBER",22,22,null,null),
                argument("PKG","DO_IT","P_AT",4,91,"DATE",7,7,null,null)));
        var calls=new ArrayList<String>();
        var metadata=proxy(RemoteDatabaseMetaData.class,(o,m,args) -> {
            calls.add(m.getName());
            return switch(m.getName()) {
                case "getCatalogTerm" -> "";
                case "getSearchStringEscape" -> "/";
                case "getProcedureColumns" -> { check(args[0]==null && "MixedCase".equals(args[1]) && "%".equals(args[2]),"Schema-wide procedure columns: " + Arrays.toString(args)); yield remote; }
                default -> throw new AssertionError("Broken or unexpected JDBC metadata path invoked: " + m.getName());
            };
        });
        var wrapper=com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.create(connection,metadata);
        var signatures=new LinkedHashMap<String,List<String>>();
        try(var arguments=wrapper.routineArguments(new com.intellij.database.jdbc.wrappers.DatabaseMetaDataWrapper.Schema("MixedCase",null),null,
                r -> r.name.equals("FN_AGE") ? DasRoutine.Kind.FUNCTION : DasRoutine.Kind.PROCEDURE)) {
            for(var routine:com.intellij.util.containers.JBIterable.once(arguments)) {
                var list=signatures.computeIfAbsent((routine.pkg==null?"":routine.pkg+".")+routine.name,k -> new ArrayList<>());
                var group=arguments.groupIt();
                while(group.hasNext()) {
                    var a=group.next();
                    var type=com.intellij.database.jdbc.JdbcIntrospectorHelper.makeDasType(a.typeName,a.length,a.scale,a.typeSuffix,a.type,schema).getSpecification();
                    list.add(a.direction + " " + (a.name==null?"":a.name+" ") + type);
                }
            }
        }
        var expected=Map.of(
                "FN_AGE",List.of("RETURN NUMBER","IN P_FROM TIMESTAMP","IN P_NAME VARCHAR2"),
                "PKG.DO_IT",List.of("INOUT P_ID NUMBER","OUT P_AT DATE"));
        check(signatures.keySet().equals(expected.keySet()),"Function and package routines keep arguments: " + signatures);
        for(var e:expected.entrySet()) check(new HashSet<>(signatures.get(e.getKey())).equals(new HashSet<>(e.getValue())),"Routine signature " + e.getKey() + ": " + signatures);
        check(!calls.contains("getFunctionColumns") && calls.contains("getProcedureColumns"),"Only valid ALL_ARGUMENTS driver SQL used: " + calls);
        System.out.println("PASS: JDBC routine arguments via getProcedureColumns only; getFunctionColumns (JDBC-8012) never called");
    }
    private static String ddl(Project project,OraSchema schema,Object table) {
        var task=new com.intellij.database.script.generator.ScriptingSingleModelTaskBuilder(schema.getModel(),com.intellij.database.script.generator.ScriptCategory.CREATE_DEFINITION);
        task.addElements(List.of((com.intellij.database.model.basic.BasicElement)table)); task.setReformat(false);
        return new com.intellij.database.dialects.generic.generator.GenericScriptGenerator(GenericDbms.TIBERO).makeScript(project,task.build()).getScriptText();
    }
    static RemoteResultSet rows(String[] names,List<Object[]> values) throws Exception {
        var rs=javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet();
        var meta=new javax.sql.rowset.RowSetMetaDataImpl();
        meta.setColumnCount(names.length);
        for(int i=0;i<names.length;i++) {
            int column=i;
            boolean numeric=values.stream().anyMatch(r -> r[column] instanceof Integer);
            meta.setColumnName(i+1,names[i]); meta.setColumnLabel(i+1,names[i]); meta.setColumnType(i+1,numeric?java.sql.Types.INTEGER:java.sql.Types.VARCHAR);
            meta.setNullable(i+1,java.sql.ResultSetMetaData.columnNullable);
        }
        rs.setMetaData(meta);
        for(var row:values) {
            rs.moveToInsertRow();
            for(int i=0;i<row.length;i++) { if(row[i]==null) rs.updateNull(i+1); else rs.updateObject(i+1,row[i]); }
            rs.insertRow();
        }
        rs.moveToCurrentRow(); rs.beforeFirst();
        var remoteMeta=proxy(RemoteResultSetMetaData.class,(o,m,args) -> {
            try { return java.sql.ResultSetMetaData.class.getMethod(m.getName(),m.getParameterTypes()).invoke(meta,args); }
            catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
        return proxy(RemoteResultSet.class,(o,m,args) -> {
            if(m.getName().equals("getMetaData")) return remoteMeta;
            if(m.getName().equals("getObjects")) {
                var result=new ArrayList<Object[]>();
                while(rs.next()) { var row=new Object[names.length]; for(int i=0;i<row.length;i++) row[i]=rs.getObject(i+1); result.add(row); }
                return result;
            }
            try { return java.sql.ResultSet.class.getMethod(m.getName(),m.getParameterTypes()).invoke(rs,args); }
            catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        });
    }
    private static void checkSequences(OraSchema schema,OraSchema other) throws Exception {
        var range=TiberoSequences.identity("1","2000","1","9999999999999999999999999999");
        var fixture=List.of(new TiberoSequences.Entry("MixedCase","Seq.Mixed",range),new TiberoSequences.Entry("MixedCase","UPPER_SEQ"));
        TiberoSequences.refresh(schema,() -> fixture);
        var first=schema.getSequences().find(e -> e.getName().equals("Seq.Mixed"));
        check(schema.getSequences().size()==2 && first.getSchema().equals(schema),"Sequences appear under native schema with exact names");
        check(range!=null && range.equals(first.getSequenceIdentity()) && TiberoSequences.identity("1","x","1","9")==null,"Sequence range from ALL_SEQUENCES: " + first.getSequenceIdentity());
        TiberoSequences.refresh(schema,() -> fixture);
        check(first.equals(schema.getSequences().find(e -> e.getName().equals("Seq.Mixed"))),"Sequence refresh preserves identity");
        try { TiberoSequences.refresh(schema,() -> { throw new java.sql.SQLException("denied"); }); throw new AssertionError("Failure expected"); }
        catch(java.sql.SQLException expected) { check(schema.getSequences().size()==2,"Sequence SQL failure does not apply a partial snapshot"); }
        for(var invalid:List.of(List.of(new TiberoSequences.Entry("OTHER","BAD")),List.of(fixture.get(0),fixture.get(0)))) {
            try { TiberoSequences.refresh(schema,() -> invalid); throw new AssertionError("Invalid sequence rows accepted"); }
            catch(IllegalArgumentException expected) { check(schema.getSequences().size()==2,"Invalid sequence snapshot leaves catalogue unchanged"); }
        }
        TiberoSequences.refresh(schema,() -> List.of(fixture.get(0)));
        check(schema.getSequences().size()==1 && schema.getTables().size()==1 && other.getSequences().isEmpty(),"Sequence removal is schema and kind scoped");
        TiberoSequences.refresh(schema,List::of);
        check(schema.getSequences().isEmpty(),"Successful empty sequence snapshot removes stale nodes");
        check(TiberoSequences.sql("O'WNER").contains("'O''WNER'"),"Sequence owner escaped");
        int[] row={-1}; boolean[] closed={false,false}; boolean[] fail={false}; boolean[] noRanges={false};
        var rs=proxy(RemoteResultSet.class,(o,m,args) -> switch(m.getName()) {
            case "next" -> { if(fail[0]) throw new java.sql.SQLException("read failed"); yield ++row[0]<fixture.size(); }
            case "getString" -> {
                var e=fixture.get(row[0]); var r=e.identity();
                yield switch((Integer)args[0]) { case 1 -> e.owner(); case 2 -> e.name();
                    case 3 -> r==null ? null : r.min.toString(); case 4 -> r==null ? null : r.next.toString();
                    case 5 -> r==null ? null : r.increment.toString(); default -> r==null ? null : r.max.toString(); };
            }
            case "close" -> { closed[0]=true; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        var statement=proxy(RemoteStatement.class,(o,m,args) -> switch(m.getName()) {
            case "setQueryTimeout" -> { check((Integer)args[0]==30,"Sequence query timeout"); yield null; }
            case "setFetchSize" -> { check((Integer)args[0]==512,"Sequence fetch hint"); yield null; }
            case "executeQuery" -> {
                check(args[0].equals(TiberoSequences.sql("MixedCase")) || args[0].equals(TiberoSequences.namesSql("MixedCase")),"Schema-scoped sequence SELECT");
                if(noRanges[0] && args[0].equals(TiberoSequences.sql("MixedCase"))) throw new java.sql.SQLException("invalid column name");
                row[0]=-1; yield rs;
            }
            case "close" -> { closed[1]=true; yield null; }
            default -> throw new AssertionError("Unexpected sequence statement call: " + m.getName());
        });
        var remote=proxy(RemoteConnection.class,(o,m,args) -> { if(m.getName().equals("createStatement")) return statement; throw new AssertionError("Unexpected transaction change: " + m.getName()); });
        var connection=proxy(DatabaseConnectionCore.class,(o,m,args) -> { if(m.getName().equals("getRemoteConnection")) return remote; throw new AssertionError(m.getName()); });
        check(TiberoSequences.readCatalog(connection,"MixedCase").equals(fixture),"Sequence remote column mapping");
        check(closed[0] && closed[1],"Sequence resources closed");
        noRanges[0]=true;
        check(TiberoSequences.readCatalog(connection,"MixedCase").equals(List.of(new TiberoSequences.Entry("MixedCase","Seq.Mixed"),new TiberoSequences.Entry("MixedCase","UPPER_SEQ"))),
                "Sequence names still read when range columns are missing");
        noRanges[0]=false;
        Arrays.fill(closed,false); fail[0]=true;
        try { TiberoSequences.readCatalog(connection,"MixedCase"); throw new AssertionError("Read failure expected"); }
        catch(java.sql.SQLException expected) { check(closed[0] && closed[1],"Sequence resources closed on read failure"); }
    }
    private static TiberoStoredObjects.Entry entry(String name,String type,String status) { return new TiberoStoredObjects.Entry("MixedCase",name,type,status); }
    private static <T> T proxy(Class<T> type,InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler)); }
    private static void checkRemoteRead(List<TiberoStoredObjects.Entry> fixture) throws Exception {
        int[] row={-1}; boolean[] closed={false,false};
        var rs=proxy(RemoteResultSet.class,(o,m,args) -> switch(m.getName()) {
            case "next" -> ++row[0]<fixture.size();
            case "getString" -> { var e=fixture.get(row[0]); yield new String[]{e.owner(),e.name(),e.type(),e.status()}[(Integer)args[0]-1]; }
            case "close" -> { closed[0]=true; yield null; }
            default -> throw new AssertionError("Unexpected result-set call: " + m.getName());
        });
        var statement=proxy(RemoteStatement.class,(o,m,args) -> switch(m.getName()) {
            case "setQueryTimeout" -> { check((Integer)args[0]==60,"Bounded catalogue query"); yield null; }
            case "executeQuery" -> { check(args[0].equals(TiberoStoredObjects.sql("MixedCase")),"Remote SELECT uses selected schema"); yield rs; }
            case "close" -> { closed[1]=true; yield null; }
            default -> throw new AssertionError("Unexpected statement call: " + m.getName());
        });
        var remote=proxy(RemoteConnection.class,(o,m,args) -> { if(m.getName().equals("createStatement")) return statement; throw new AssertionError("Unexpected connection mutation: " + m.getName()); });
        var connection=proxy(DatabaseConnectionCore.class,(o,m,args) -> { if(m.getName().equals("getRemoteConnection")) return remote; throw new AssertionError(m.getName()); });
        check(TiberoStoredObjects.readCatalog(connection,"MixedCase").equals(fixture),"Remote JDBC column mapping");
        check(closed[0] && closed[1],"Result set and statement closed without transaction changes");
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
