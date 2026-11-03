package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synonym targets for unqualified names. Tibero's ALL_SYNONYMS names the target ORG_OBJECT_OWNER/ORG_OBJECT_NAME
 * (Oracle: TABLE_OWNER/TABLE_NAME). The IDE's Generic model has no synonym kind, so TiberoSynonymResolve uses this map.
 */
@State(name="TiberoSynonyms",storages=@Storage(value="tiberoSynonyms.xml",roamingType=RoamingType.DISABLED))
public final class TiberoSynonyms implements PersistentStateComponent<TiberoSynonyms.State> {
    public record Target(String owner,String name) {}
    public static final class State { public Map<String,String> sources=new TreeMap<>(); }

    static String sql(String schema) {
        return "SELECT OWNER, SYNONYM_NAME, ORG_OBJECT_OWNER, ORG_OBJECT_NAME FROM ALL_SYNONYMS WHERE OWNER IN ("
                + (schema==null ? "" : TiberoCatalog.literal(schema) + ", ") + "'PUBLIC') AND ORG_OBJECT_OWNER IS NOT NULL AND ORG_OBJECT_NAME IS NOT NULL";
    }
    static Map<String,Target> read(DatabaseConnectionCore connection,String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        return snapshot(schema,TiberoRoutineReader.query(connection,sql(schema),4));
    }
    /** Oracle name resolution order: the session schema's own synonyms hide PUBLIC ones. */
    static Map<String,Target> snapshot(String schema,List<String[]> rows) {
        var own=new HashMap<String,Target>(); var shared=new HashMap<String,Target>();
        for(var row:rows) {
            if(row.length!=4 || row[1]==null || row[1].isBlank() || row[2]==null || row[3]==null
                    || !("PUBLIC".equals(row[0]) || row[0]!=null && row[0].equals(schema)))
                throw new IllegalArgumentException("Invalid synonym catalogue for " + schema);
            ("PUBLIC".equals(row[0]) ? shared : own).put(row[1],new Target(row[2],row[3]));
        }
        var result=new HashMap<>(shared); result.putAll(own);
        return Map.copyOf(result);
    }

    private final Map<String,Map<String,Target>> cache=new ConcurrentHashMap<>();
    private State state=new State();
    static TiberoSynonyms getInstance() { return ApplicationManager.getApplication().getService(TiberoSynonyms.class); }
    Target find(String sourceId,String name) {
        var targets=cache.computeIfAbsent(sourceId,id -> decode(state.sources.get(id)));
        return targets.get(name);
    }
    synchronized void update(String sourceId,Map<String,Target> targets) {
        var copy=new TreeMap<>(state.sources); copy.put(sourceId,encode(targets));
        state.sources=copy; cache.put(sourceId,Map.copyOf(targets));
    }
    @Override public synchronized State getState() { return state; }
    @Override public synchronized void loadState(State loaded) { state=loaded; cache.clear(); }
    // One "NAME\tOWNER\tOBJECT" line per synonym; catalogue names never contain tabs or line breaks.
    static String encode(Map<String,Target> targets) {
        var text=new StringBuilder();
        for(var e:new TreeMap<>(targets).entrySet()) text.append(e.getKey()).append('\t').append(e.getValue().owner()).append('\t').append(e.getValue().name()).append('\n');
        return text.toString();
    }
    static Map<String,Target> decode(String text) {
        if(text==null || text.isEmpty()) return Map.of();
        var result=new HashMap<String,Target>();
        for(var line:text.split("\n")) {
            var parts=line.split("\t",-1);
            if(parts.length==3) result.put(parts[0],new Target(parts[1],parts[2]));
        }
        return Map.copyOf(result);
    }
}
