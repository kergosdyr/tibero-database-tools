package poc;

import com.intellij.database.plan.PlanModel;
import com.intellij.database.plan.PlanRetrievalException;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** PLAN_TABLE column names, not formatted DBMS_XPLAN indentation, define the tree. */
public final class TiberoPlan {
    public static final int MAX_NODES = 10000;
    public record Row(int id, Integer parent, int position, String operation, String owner,
                      String object, BigDecimal cost, BigDecimal rows, String access, String filter, Metrics actual) {
        public Row(int id, Integer parent, int position, String operation, String owner, String object,
                   BigDecimal cost, BigDecimal rows, String access, String filter) {
            this(id, parent, position, operation, owner, object, cost, rows, access, filter, null);
        }
    }
    public record Metrics(BigDecimal rows, BigDecimal starts, BigDecimal elapsedMs, BigDecimal bufferGets) {}
    private final List<Row> rows;
    private final boolean actual;
    public TiberoPlan(List<Row> rows) { this(rows, false); }
    public TiberoPlan(List<Row> rows, boolean actual) {
        this.rows = List.copyOf(rows); this.actual = actual; validate();
        if (actual && rows.stream().noneMatch(r -> r.parent == null && r.actual != null
                && r.actual.starts != null && r.actual.starts.signum() > 0 && r.actual.rows != null))
            throw bad("The query ran, but actual statistics are missing. Enable GATHER_SQL_PLAN_STAT and GATHER_SQL_EXEC_TIME on this connection before Explain Analyse.");
    }
    public List<Row> rows() { return rows; }

    public static Row row(Map<String, String> columns) {
        var c = new HashMap<String, String>();
        columns.forEach((k,v) -> c.put(k.toUpperCase(Locale.ROOT), v));
        int id = integer(required(c, "ID"));
        String p = c.get("PARENT_ID");
        Integer parent = blank(p) ? null : integer(p);
        // Tibero plan roots may have NULL, -1, or ID=0/PARENT_ID=0.
        if (parent != null && (parent == -1 || id == 0 && parent == 0)) parent = null;
        String op = required(c, "OPERATION");
        if (!blank(c.get("OPTIONS"))) op += " (" + c.get("OPTIONS") + ")";
        return new Row(id, parent, blank(c.get("POSITION")) ? id : integer(c.get("POSITION")), op,
                c.get("OBJECT_OWNER"), c.get("OBJECT_NAME"), number(c.get("COST")),
                number(c.get("CARDINALITY")), c.get("ACCESS_PREDICATES"), c.get("FILTER_PREDICATES"),
                c.keySet().stream().anyMatch(k -> k.startsWith("LAST_"))
                        ? new Metrics(number(c.get("LAST_OUTPUT_ROWS")), number(c.get("LAST_STARTS")),
                            number(c.get("LAST_ELAPSED_TIME")), number(c.get("LAST_CR_BUFFER_GETS"))) : null);
    }
    private void validate() {
        if (rows.isEmpty()) throw bad("Execution plan is empty. Check PLAN_TABLE and permissions.");
        if (rows.size() > MAX_NODES) throw bad("Plan exceeds " + MAX_NODES + " nodes");
        var byId = new HashMap<Integer, Row>();
        for (var r : rows) {
            if (r.id < 0 || blank(r.operation)) throw bad("Invalid plan node");
            if (byId.put(r.id, r) != null) throw bad("Duplicate plan ID: " + r.id);
            if (r.cost != null && (!Double.isFinite(r.cost.doubleValue()) || r.cost.signum() < 0)) throw bad("Invalid cost");
            if (r.rows != null && r.rows.signum() < 0) throw bad("Invalid cardinality");
            if (r.actual != null) for (BigDecimal value : new BigDecimal[]{r.actual.rows, r.actual.starts, r.actual.elapsedMs, r.actual.bufferGets})
                if (value != null && (value.signum() < 0 || !Double.isFinite(value.doubleValue()))) throw bad("Invalid actual statistic");
        }
        if (rows.stream().filter(r -> r.parent == null).count() != 1) throw bad("Plan must have exactly one root");
        for (var r : rows) {
            var path = new HashSet<Integer>();
            for (Row at = r; at != null;) {
                if (!path.add(at.id)) throw bad("Cycle at plan ID " + at.id);
                if (path.size() > 512) throw bad("Plan depth exceeds 512");
                if (at.parent == null) break;
                Row parent = byId.get(at.parent);
                if (parent == null) throw bad("Missing parent " + at.parent + " for " + at.id);
                at = parent;
            }
        }
    }
    public PlanModel model() {
        var nodes = new HashMap<Integer, PlanModel.GenericNode>();
        var children = new HashMap<Integer, List<Row>>();
        Row root = null;
        for (var r : rows) {
            var node = PlanModel.createNode(type(r.operation), r.operation);
            node.setTotalCost(r.cost == null ? null : r.cost.doubleValue());
            node.setPlanNumRows(r.rows);
            if (actual && r.actual != null) {
                node.setActualNumRows(r.actual.rows);
                node.setActualTotalTime(r.actual.elapsedMs == null ? null : r.actual.elapsedMs.doubleValue());
            }
            String object = blank(r.object) ? null : (blank(r.owner) ? r.object : r.owner + "." + r.object);
            if (node instanceof PlanModel.IndexScanNode i) i.setIndex(object);
            else if (node instanceof PlanModel.GenericAccessNode a) a.setRelation(object);
            node.setRawDescription("Node ID=" + r.id + (r.parent == null ? "" : "\nParent ID=" + r.parent)
                    + (object == null ? "" : "\nObject=" + object)
                    + (blank(r.access) ? "" : "\nAccess=" + r.access)
                    + (blank(r.filter) ? "" : "\nFilter=" + r.filter)
                    + (actual && r.actual != null ? "\nActual rows (last execution)=" + r.actual.rows
                        + "\nStarts (last execution)=" + r.actual.starts
                        + "\nElapsed (ms, last execution)=" + r.actual.elapsedMs
                        + "\nConsistent buffer gets (last execution)=" + r.actual.bufferGets : ""));
            nodes.put(r.id, node);
            if (r.parent == null) root = r;
            else children.computeIfAbsent(r.parent, k -> new ArrayList<>()).add(r);
        }
        children.forEach((parent, list) -> {
            list.sort(Comparator.comparingInt(Row::position).thenComparingInt(Row::id));
            nodes.get(parent).setChildren(list.stream().map(r -> nodes.get(r.id)).toArray(PlanModel.GenericNode[]::new));
        });
        var unsupported = EnumSet.of(PlanModel.Feature.STARTUP_COST);
        if (rows.stream().noneMatch(r -> r.cost != null)) unsupported.add(PlanModel.Feature.TOTAL_COST);
        if (rows.stream().noneMatch(r -> r.rows != null)) unsupported.add(PlanModel.Feature.NUM_ROWS);
        // JetBrains hides the model's container root in both tree and diagram views.
        var container = PlanModel.createNode(PlanModel.NodeType.ROOT, "Tibero Plan");
        container.setChildren(new PlanModel.GenericNode[]{nodes.get(Objects.requireNonNull(root).id)});
        var model = new PlanModel(container, actual, unsupported);
        IdeCompatibility.setRawData(model, json());
        return model;
    }
    public static PlanModel.NodeType type(String op) {
        String s = op.toUpperCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
        if (s.startsWith("SELECT")) return PlanModel.NodeType.SELECT;
        if (s.startsWith("INSERT")) return PlanModel.NodeType.INSERT;
        if (s.startsWith("UPDATE")) return PlanModel.NodeType.UPDATE;
        if (s.startsWith("DELETE")) return PlanModel.NodeType.DELETE;
        if (s.startsWith("HASH JOIN")) return PlanModel.NodeType.HASH_JOIN;
        if (s.startsWith("MERGE JOIN")) return PlanModel.NodeType.MERGE_JOIN;
        if (s.startsWith("NESTED LOOP")) return PlanModel.NodeType.NESTED_LOOPS;
        if (s.startsWith("INDEX JOIN")) return PlanModel.NodeType.JOIN;
        if (s.startsWith("TABLE ACCESS")) return s.contains("ROWID") ? PlanModel.NodeType.ROWID_ACCESS : s.contains("FULL") ? PlanModel.NodeType.SEQ_SCAN : PlanModel.NodeType.ACCESS;
        if (s.startsWith("INDEX")) return s.contains("UNIQUE") ? PlanModel.NodeType.UNIQUE_INDEX_SCAN : s.contains("FULL") ? PlanModel.NodeType.FULL_INDEX_SCAN : PlanModel.NodeType.INDEX_SCAN;
        if (s.startsWith("GROUP BY") || s.startsWith("HASH GROUP")) return PlanModel.NodeType.GROUP_BY;
        if (s.startsWith("ORDER BY")) return PlanModel.NodeType.ORDER_BY;
        if (s.startsWith("SORT")) return PlanModel.NodeType.SORT;
        if (s.startsWith("FILTER")) return PlanModel.NodeType.FILTER;
        if (s.startsWith("UNION ALL")) return PlanModel.NodeType.UNION_ALL;
        if (s.startsWith("UNION")) return PlanModel.NodeType.UNION;
        return PlanModel.NodeType.OPERATION;
    }
    public String json() {
        try {
            var array = new JsonArray();
            for (var r : rows) {
                var o = new JsonObject();
                o.addProperty("ID", r.id); o.addProperty("PARENT_ID", r.parent);
                o.addProperty("POSITION", r.position); o.addProperty("OPERATION", r.operation);
                put(o, "OBJECT_OWNER", r.owner); put(o, "OBJECT_NAME", r.object);
                put(o, "COST", r.cost); put(o, "CARDINALITY", r.rows);
                put(o, "ACCESS_PREDICATES", r.access); put(o, "FILTER_PREDICATES", r.filter);
                if (actual && r.actual != null) {
                    put(o, "LAST_OUTPUT_ROWS", r.actual.rows); put(o, "LAST_STARTS", r.actual.starts);
                    put(o, "LAST_ELAPSED_TIME", r.actual.elapsedMs); put(o, "LAST_CR_BUFFER_GETS", r.actual.bufferGets);
                }
                array.add(o);
            }
            var doc = new JsonObject(); doc.addProperty("format", actual ? "tibero-actual-plan-v1" : "tibero-plan-table-v1"); doc.add("rows", array);
            return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(doc);
        } catch (Exception e) { throw bad("Cannot serialize plan: " + e.getMessage()); }
    }
    public static TiberoPlan parse(String text) {
        if (text.length() > 10 * 1024 * 1024) throw bad("Plan input exceeds 10 MiB");
        try {
            var doc = JsonParser.parseString(text).getAsJsonObject();
            if (!doc.has("format") || !Set.of("tibero-plan-table-v1", "tibero-actual-plan-v1").contains(doc.get("format").getAsString())) throw bad("Expected Tibero plan JSON");
            boolean actual = "tibero-actual-plan-v1".equals(doc.get("format").getAsString());
            var array = doc.getAsJsonArray("rows");
            if (array.size() > MAX_NODES) throw bad("Too many plan nodes");
            var rows = new ArrayList<Row>();
            for (int i=0; i<array.size(); i++) {
                var o = array.get(i).getAsJsonObject(); var columns = new HashMap<String,String>();
                o.entrySet().forEach(e -> columns.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString()));
                rows.add(row(columns));
            }
            return new TiberoPlan(rows, actual);
        } catch (PlanRetrievalException e) { throw e; }
        catch (Exception e) { throw new PlanRetrievalException("Invalid plan JSON", e); }
    }
    private static void put(JsonObject o, String k, Object v) { if(v instanceof Number n) o.addProperty(k,n); else o.addProperty(k,v==null?null:v.toString()); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String required(Map<String,String> c, String key) { String v=c.get(key); if(blank(v)) throw bad("Missing " + key); return v; }
    private static int integer(String s) { try { return new BigDecimal(s).intValueExact(); } catch (RuntimeException e) { throw bad("Invalid integer: " + s); } }
    private static BigDecimal number(String s) { if(blank(s)) return null; try { return new BigDecimal(s); } catch(NumberFormatException e) { throw bad("Invalid numeric value: " + s); } }
    private static PlanRetrievalException bad(String s) { return new PlanRetrievalException(s); }
}
