package poc;

import com.intellij.database.util.Version;
import java.util.regex.Pattern;
import java.util.*;
import com.intellij.database.plan.PlanModel;
import com.intellij.database.plan.PlanRetrievalException;

public final class OfflineCheck {
    public static void main(String[] args) throws Exception {
        var provider = new TiberoExplainProvider();
        var loader = new TiberoViewLoader();
        for (int major : new int[]{6, 7}) {
            var version = Version.of(major);
            require(provider.isRawSupported(version, false), "Tibero " + major + " raw explain enabled");
            require(provider.isSupported(version, false), "Tibero " + major + " native tree enabled");
            require(!provider.isRawSupported(version, true) && provider.isSupported(version, true), "Actual tree supported; raw remains estimated for " + major);
        }
        require(loader.supportsViewDefinition(), "View source support");
        String view = TiberoViewLoader.viewSql("O'WNER", "Mixed'View");
        require(view.contains("'O''WNER'") && view.contains("'Mixed''View'"), "Literal escaping and case");
        var plan = TiberoExplainProvider.planSql("SELECT 1 FROM DUAL");
        var match = Pattern.compile("STATEMENT_ID = '(IJ[0-9a-f]{24})'").matcher(plan.explain());
        require(match.find(), "Unique bounded plan id");
        require(plan.query().contains(match.group(1)), "Retrieval uses same plan id");
        require(plan.cleanup().equals("DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = '" + match.group(1) + "'"), "Cleanup uses only generated plan id");
        require(plan.explain().contains("FOR SELECT 1 FROM DUAL"), "SQL forwarded to EXPLAIN");
        require(!plan.query().equals(TiberoExplainProvider.planSql("SELECT 1 FROM DUAL").query()), "Separate requests use separate ids");
        if(Boolean.getBoolean("tibero.verify.driver.required")) {
            Class.forName("com.tmax.tibero.jdbc.TbDriver");
            checkDriverMetadataQueries();
        } else System.out.println("SKIP: proprietary Tibero JDBC regression; set TIBERO_POC_DRIVER to enable it");
        checkColumnTypes();
        checkCurrentSchemaQueries();
        checkArgumentQueries();
        checkSynonyms();
        var sample = TiberoPlan.parse(VerifyStarter.samplePlan());
        var model = sample.model();
        require(model.getRoot().getChildren().length==1, "SELECT has one child");
        var statement=model.getRoot().getChildren()[0];
        require(statement.getType()==PlanModel.NodeType.SELECT,"Container keeps SQL root visible");
        var join=statement.getChildren()[0];
        require(join.getType()==PlanModel.NodeType.HASH_JOIN && join.getChildren().length==2, "Join has two input branches");
        require(join.getChildren()[1].getChildren()[0].getType()==PlanModel.NodeType.INDEX_SCAN, "Index attached below ROWID access");
        require(join.getChildren()[0].getParentNode()==join, "Native parent links initialized");
        require(join.getTotalCost()==18.0 && join.getPlanNumRows().intValueExact()==12, "Estimated metrics retained");
        require(join.getRawDescription().contains("Node ID=1\nParent ID=0"), "Native property panel separates node and parent IDs");
        require(!join.getRawDescription().contains("HASH JOIN"), "Native operation property is not duplicated");
        require(join.getRawDescription().contains("O.CUSTOMER_ID = C.ID"), "Access predicate retained");
        require(!model.isActual() && model.getUnsupported().contains(PlanModel.Feature.STARTUP_COST), "Unavailable metrics not invented");
        require(TiberoPlan.parse(sample.json()).rows().equals(sample.rows()), "JSON round trip");
        var shuffled=new ArrayList<>(sample.rows()); Collections.reverse(shuffled);
        require(new TiberoPlan(shuffled).model().dump().equals(model.dump()), "Row order does not determine tree");
        expectBad(() -> new TiberoPlan(List.of()), "Empty plan");
        var duplicate=new ArrayList<>(sample.rows()); duplicate.add(sample.rows().get(0));
        expectBad(() -> new TiberoPlan(duplicate), "Duplicate ids");
        var orphan=new ArrayList<>(sample.rows()); orphan.remove(1);
        expectBad(() -> new TiberoPlan(orphan), "Missing parent");
        expectBad(() -> TiberoPlan.parse("{\"format\":\"wrong\",\"rows\":[]}"), "Unknown format");
        expectBad(() -> TiberoPlan.parse("{\"format\":\"tibero-plan-table-v1\",\"rows\":[{\"ID\":0,\"PARENT_ID\":null,\"OPERATION\":\"SELECT\"},{\"ID\":1,\"PARENT_ID\":2,\"OPERATION\":\"A\"},{\"ID\":2,\"PARENT_ID\":1,\"OPERATION\":\"B\"}]}"), "Disconnected cycle");
        var unknown=TiberoPlan.row(Map.of("id","0","parent_id","0","operation","NEW OPERATOR"));
        var unknownModel=new TiberoPlan(List.of(unknown)).model();
        require(unknownModel.getRoot().getChildren()[0].getType()==PlanModel.NodeType.OPERATION, "Unknown operators preserved");
        require(unknownModel.getRoot().getChildren()[0].getTotalCost()==null && unknownModel.getUnsupported().contains(PlanModel.Feature.NUM_ROWS), "Missing metrics remain absent");
        for (int major : new int[]{6, 7}) {
            var builder=provider.createBuilder(Version.of(major),false);
            var raw=builder.createData(); raw.load(sample.json());
            require(builder.build(raw).dump().equals(model.dump()), "Tibero " + major + " native builder retains full sample tree");
        }
        checkExplainBoundaries();
        ActualPlanCheck.run();
        require(loader.supportsProcedureDefinition() && loader.supportsPackageDefinition(), "PSM sources enabled");
        require(TiberoSourceText.ddl("DEMO","PROCEDURE RAISE_SALARY(p IN NUMBER) IS\nBEGIN NULL; END;\n").equals("CREATE OR REPLACE PROCEDURE DEMO.RAISE_SALARY(p IN NUMBER) IS\nBEGIN NULL; END;"), "Routine source becomes qualified CREATE OR REPLACE");
        require(TiberoSourceText.ddl("Mixed","package body \"Pkg\" is end;").equals("CREATE OR REPLACE package body \"Mixed\".\"Pkg\" is end;"), "Quoted schema and object names");
        require(TiberoSourceText.ddl("DEMO","FUNCTION HR.F RETURN NUMBER IS BEGIN RETURN 1; END;").startsWith("CREATE OR REPLACE FUNCTION HR.F "), "Existing qualification kept");
        require(TiberoSourceText.ddl("DEMO","").isEmpty() && TiberoSourceText.view("DEMO","EMP_V"," SELECT 1 FROM DUAL\n").equals("CREATE OR REPLACE VIEW DEMO.EMP_V AS\nSELECT 1 FROM DUAL"), "Empty source and view definition");
        require(TiberoSourceText.packageSource("CREATE OR REPLACE PACKAGE P IS END;","").equals("CREATE OR REPLACE PACKAGE P IS END;"), "Package without body");
        require(TiberoSourceText.statements(TiberoSourceText.packageSource("CREATE OR REPLACE PACKAGE P IS END;","CREATE OR REPLACE PACKAGE BODY P IS x NUMBER := 4/2; END;"))
                .equals(List.of("CREATE OR REPLACE PACKAGE P IS END;","CREATE OR REPLACE PACKAGE BODY P IS x NUMBER := 4/2; END;")), "Package splits only on lines holding '/'");
        for (var q:TiberoCatalog.Query.values()) {
            String query=TiberoCatalog.sql(q,"O'WNER","N'AME");
            require(query.startsWith("SELECT ") && query.contains("O''WNER"), "Read-only escaped catalogue query: " + q);
        }
        require(TiberoCatalog.sourceSql("S","P","PACKAGE BODY").endsWith("ORDER BY LINE"), "Source line order");
        require(TiberoCatalog.sql(TiberoCatalog.Query.PRIVILEGES,"S","T").contains("ALL_TBL_PRIVS WHERE OWNER"), "Tibero privilege catalogue, not Oracle spelling");
        require(TiberoCatalog.sql(TiberoCatalog.Query.DEPENDENTS,"S","T").contains("PARENT_OBJ_OWNER"), "Tibero dependency column names");
        var calls=new ArrayList<String>();
        var operations=new PlanSession.Operations() {
            public void execute(String sql) { calls.add(sql); }
            public TiberoPlan read(String sql) { calls.add(sql); return sample; }
        };
        require(PlanSession.fetch(operations,plan)==sample,"Transport returns complete plan");
        require(calls.equals(List.of(plan.explain(),plan.query(),plan.cleanup())),"Explain/read/targeted cleanup order");
        calls.clear();
        var failedRead=new PlanSession.Operations() {
            public void execute(String sql) { calls.add(sql); }
            public TiberoPlan read(String sql) throws java.sql.SQLException { throw new java.sql.SQLException("read failed"); }
        };
        try { PlanSession.fetch(failedRead,plan); throw new AssertionError("Expected failure"); }
        catch(java.sql.SQLException e) { require(e.getMessage().equals("read failed"),"Original SQL failure preserved"); }
        require(calls.equals(List.of(plan.explain(),plan.cleanup())),"Cleanup after result failure");
        var twoFailures=new PlanSession.Operations() {
            public void execute(String sql) throws java.sql.SQLException { throw new java.sql.SQLException(sql.equals(plan.cleanup())?"cleanup":"explain"); }
            public TiberoPlan read(String sql) { throw new AssertionError("Read after failed EXPLAIN"); }
        };
        try { PlanSession.fetch(twoFailures,plan); throw new AssertionError("Expected failure"); }
        catch(java.sql.SQLException e) { require(e.getMessage().equals("explain") && e.getSuppressed().length==1,"Cleanup failure never masks primary error"); }
        System.out.println("PASS: plan request lifecycle and targeted cleanup on success/failure");
        System.out.println("PASS: editable CREATE OR REPLACE source text and statement split; native plan model, branches/metrics/predicates, malformed plan rejection, JSON round trip, source/catalogue SQL");
        System.out.println("PASS: provider/source-loader construction, SQL generation and feature guards");
        System.out.println("NOT TESTED: IDE extension registry/UI, remote JDBC, Tibero 6/7 SQL execution and Tibero 7 driver loading");
    }
    private static void checkExplainBoundaries() {
        for (String sql : List.of("SELECT 1 FROM DUAL; -- final comment", "/* hint */ SELECT ';' FROM DUAL",
                "SELECT 'it''s;ok', \"semi;column\" FROM T", "SELECT q'[a';b]' FROM DUAL",
                "SELECT q'!a';b!' FROM DUAL", "WITH X AS (SELECT 1 N FROM DUAL) SELECT * FROM X",
                "INSERT INTO T SELECT 1 FROM DUAL", "UPDATE T SET X=1", "DELETE FROM T")) {
            require(TiberoExplainProvider.planSql(sql).explain().startsWith("EXPLAIN PLAN SET STATEMENT_ID"), "Statement always wrapped in EXPLAIN");
        }
        require(ExplainSql.singleStatement("SELECT 1 FROM DUAL; -- tail").equals("SELECT 1 FROM DUAL"), "Strip final delimiter");
        for (String sql : List.of("", "-- empty", "DROP TABLE T", "TRUNCATE TABLE T", "COMMIT", "BEGIN NULL; END;",
                "SELECT 1 FROM DUAL; DELETE FROM T", "SELECT 1 FROM DUAL; /* comment */ DROP TABLE T",
                "SELECT 1 FROM DUAL;;", "SELECT 'unclosed", "SELECT q'[unclosed' FROM DUAL", "SELECT 1 /* unclosed",
                "SELECT 'x';\n/\nDELETE FROM T", "SELECT abcq'[a'; DELETE FROM T; -- ]'")) {
            try { TiberoExplainProvider.planSql(sql); } catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("Unsafe or malformed script accepted: " + sql);
        }
        System.out.println("PASS: Tibero 6/7 provider eligibility and shared sample plan contract; single-statement explain guard");
    }
    /** The supplied Tibero 6 JAR still builds the malformed function-column SQL that the metadata wrapper avoids. */
    private static void checkDriverMetadataQueries() throws Exception {
        var queries=Class.forName("com.tmax.tibero.jdbc.util.TbDatabaseMetaQuery");
        for(boolean mapDateToTimestamp:new boolean[]{false,true}) {
            String function=(String)queries.getMethod("getFunctionColumnsQuery",boolean.class).invoke(null,mapDateToTimestamp);
            String procedure=(String)queries.getMethod("getProcedureColumnsQuery",boolean.class).invoke(null,mapDateToTimestamp);
            require(function.matches("(?s).*'DATE',\\s*\\)9[13].*"),"Driver getFunctionColumns SQL defect reproduced");
            require(!procedure.matches("(?s).*,\\s*\\).*") && procedure.contains("FROM ALL_ARGUMENTS"),"Driver getProcedureColumns SQL is well formed");
        }
        System.out.println("PASS: Tibero 6 driver getFunctionColumns emits \"'DATE', )93\"; getProcedureColumns is well formed");
    }
    /** Tibero 6 JDBC has no getSchema(), so the session schema comes from SQL; USER covers servers without SYS_CONTEXT. */
    private static void checkCurrentSchemaQueries() throws Exception {
        var asked=new ArrayList<String>();
        require("ABC".equals(TiberoCurrentSchema.read(sql -> { asked.add(sql); return " ABC "; })) && asked.size()==1 && asked.get(0).contains("SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')"),
                "CURRENT_SCHEMA from SYS_CONTEXT, trimmed, one query");
        asked.clear();
        require("APP_USER".equals(TiberoCurrentSchema.read(sql -> { asked.add(sql); if(sql.contains("SYS_CONTEXT")) throw new java.sql.SQLException("JDBC-8036"); return "APP_USER"; }))
                && asked.equals(TiberoCurrentSchema.QUERIES),"SYS_CONTEXT failure falls back to USER");
        require("APP_USER".equals(TiberoCurrentSchema.read(sql -> sql.contains("SYS_CONTEXT") ? null : "APP_USER")),"Blank SYS_CONTEXT falls back to USER");
        require(TiberoCurrentSchema.read(sql -> " ")==null,"All blank answers keep the IDE's own guess");
        try { TiberoCurrentSchema.read(sql -> { throw new java.sql.SQLException(sql); }); throw new AssertionError("Expected failure"); }
        catch(java.sql.SQLException e) { require(e.getMessage().contains("SYS_CONTEXT") && e.getSuppressed().length==1,"First failure reported, later ones suppressed"); }
        for(var sql:TiberoCurrentSchema.QUERIES) require(sql.startsWith("SELECT ") && sql.endsWith(" FROM DUAL"),"Read-only current schema query");
        System.out.println("PASS: session CURRENT_SCHEMA lookup with SYS_CONTEXT, USER fallback and failure reporting");
    }
    private static void checkSynonyms() {
        String sql=TiberoSynonyms.sql("APP'USER");
        require(sql.startsWith("SELECT ") && sql.contains("ORG_OBJECT_OWNER, ORG_OBJECT_NAME FROM ALL_SYNONYMS") && sql.contains("OWNER IN ('APP''USER', 'PUBLIC')"),"Tibero synonym columns, session schema and PUBLIC");
        require(TiberoSynonyms.sql(null).contains("OWNER IN ('PUBLIC')"),"PUBLIC synonyms without a session schema");
        var map=TiberoSynonyms.snapshot("APP",List.of(new String[]{"PUBLIC","ORDERS","ABC","ORDERS"},new String[]{"PUBLIC","ITEMS","ABC","ITEMS"},new String[]{"APP","ITEMS","XYZ","ITEMS2"}));
        require(map.get("ORDERS").equals(new TiberoSynonyms.Target("ABC","ORDERS")) && map.get("ITEMS").equals(new TiberoSynonyms.Target("XYZ","ITEMS2")),"Session schema synonyms hide PUBLIC ones");
        require(TiberoSynonyms.decode(TiberoSynonyms.encode(map)).equals(map) && TiberoSynonyms.decode(null).isEmpty(),"Stored synonym map round trip");
        for(var bad:List.<String[]>of(new String[]{"OTHER","X","ABC","X"},new String[]{"PUBLIC",null,"ABC","X"},new String[]{"PUBLIC","X",null,"X"})) {
            try { TiberoSynonyms.snapshot("APP",java.util.Collections.singletonList(bad)); throw new AssertionError("Invalid synonym row accepted"); }
            catch(IllegalArgumentException expected) {}
        }
        System.out.println("PASS: synonym catalogue SQL, precedence, validation and storage");
    }
    private static void checkArgumentQueries() {
        String levels=TiberoRoutineCatalog.argumentsSql("O'WNER"), plain=TiberoRoutineCatalog.argumentsWithoutLevelSql("O'WNER");
        require(levels.contains("AND DATA_LEVEL = 0") && levels.contains("'O''WNER'"),"Top-level argument SQL");
        require(!plain.contains("DATA_LEVEL =") && plain.contains("0 AS DATA_LEVEL") && plain.contains("'O''WNER'"),"Argument SQL without the DATA_LEVEL column");
        require(levels.replace(", DATA_LEVEL,",", 0 AS DATA_LEVEL,").replace(" AND DATA_LEVEL = 0","").equals(plain),"Both argument queries return the same columns and order");
        System.out.println("PASS: ALL_ARGUMENTS query with and without DATA_LEVEL");
    }
    private static void checkColumnTypes() {
        Object[][] cases={
                {"TIMESTAMP(6)",6,6,"TIMESTAMP",6,0,null}, {"TIMESTAMP(6)",11,6,"TIMESTAMP",6,0,null}, {"TIMESTAMP",0,6,"TIMESTAMP",6,0,null},
                {"TIMESTAMP",0,0,"TIMESTAMP",-1,0,null}, {"TIMESTAMP(0)",0,0,"TIMESTAMP",0,0,null},
                {"timestamp(3)  with  local time zone",11,3,"TIMESTAMP",3,0,"WITH LOCAL TIME ZONE"},
                {"INTERVAL DAY(2) TO SECOND(6)",2,6,"INTERVAL DAY(2) TO SECOND(6)",-1,0,null},
                {"DATE",7,0,"DATE",-1,0,null}, {"CLOB",4000,0,"CLOB",-1,0,null},
                {"NUMBER",10,2,"NUMBER",10,2,null}, {"NUMBER",0,0,"NUMBER",-1,0,null}, {"VARCHAR2",20,0,"VARCHAR2",20,0,null}};
        for(var c:cases) {
            var type=new com.intellij.database.remote.jdbc.helpers.JdbcHelper.Type();
            type.typeName=(String)c[0]; type.length=(Integer)c[1]; type.scale=(Integer)c[2];
            TiberoColumnTypes.normalizeColumn(type);
            require(type.typeName.equals(c[3]) && type.length==(Integer)c[4] && type.scale==(Integer)c[5] && Objects.equals(type.typeSuffix,c[6]),
                    "Column type " + c[0] + "/" + c[1] + "/" + c[2] + " -> " + type.typeName + "/" + type.length + "/" + type.scale + "/" + type.typeSuffix);
        }
        var argument=new com.intellij.database.remote.jdbc.helpers.JdbcHelper.Type();
        argument.typeName="VARCHAR2"; argument.length=32767; argument.scale=0;
        TiberoColumnTypes.normalizeArgument(argument);
        require(argument.length==-1 && argument.scale==0,"PL/SQL argument sizes dropped");
        System.out.println("PASS: Tibero column/argument type normalization rules");
    }
    private static void expectBad(Runnable work,String label) { try { work.run(); } catch(PlanRetrievalException e) { return; } throw new AssertionError(label); }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
