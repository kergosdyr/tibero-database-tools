package poc;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.google.gson.Gson;
import java.util.*;

/** Last successful catalogues and explicit remote-source mappings, persisted by the IDE. No credentials. */
@State(name="TiberoDbLinks",storages=@Storage(value="tiberoDbLinks.xml",roamingType=RoamingType.DISABLED))
public final class TiberoDbLinkStore implements PersistentStateComponent<TiberoDbLinkStore.State> {
    public static final class State {
        public Map<String,String> catalogues=new TreeMap<>();
        public Map<String,String> mappings=new TreeMap<>();
    }
    public record Mapping(String sourceId,String schema,String definition) {
        public Mapping(String sourceId,String schema) { this(sourceId,schema,null); }
    }
    private static final Gson JSON=new Gson();
    private State state=new State();
    static TiberoDbLinkStore getInstance() { return ApplicationManager.getApplication().getService(TiberoDbLinkStore.class); }
    private static String key(String... parts) { return JSON.toJson(parts); }
    synchronized void update(String sourceId,String schema,List<TiberoDbLinks.Entry> entries) {
        // An empty successful refresh is meaningful: it clears links which were dropped.
        if(!"PUBLIC".equals(schema)) state.catalogues.put(key(sourceId,schema),JSON.toJson(entries.stream().filter(e->!"PUBLIC".equals(e.owner())).toList()));
        // PUBLIC links are shared. A refresh of any schema replaces this one snapshot,
        // so a stale sibling schema cannot resurrect a dropped public link.
        state.catalogues.put(key(sourceId,"PUBLIC"),JSON.toJson(entries.stream().filter(e->"PUBLIC".equals(e.owner())).toList()));
    }
    synchronized List<TiberoDbLinks.Entry> entries(String sourceId,String schema) {
        var result=new ArrayList<>(decode(state.catalogues.get(key(sourceId,schema))));
        if(!"PUBLIC".equals(schema)) result.addAll(decode(state.catalogues.get(key(sourceId,"PUBLIC"))));
        return List.copyOf(result);
    }
    private static List<TiberoDbLinks.Entry> decode(String text) {
        if(text==null) return List.of();
        try {
            var entries=List.copyOf(Arrays.asList(JSON.fromJson(text,TiberoDbLinks.Entry[].class)));
            if(entries.stream().anyMatch(e->e.owner()==null || e.name()==null || e.name().isBlank())) return List.of();
            return entries;
        }
        catch(RuntimeException invalid) { return List.of(); }
    }
    synchronized void map(String sourceId,TiberoDbLinks.Entry link,Mapping mapping) {
        String key=key(sourceId,link.owner(),link.name());
        if(mapping==null) state.mappings.remove(key);
        else state.mappings.put(key,JSON.toJson(new Mapping(mapping.sourceId(),mapping.schema(),key(link.username(),link.host()))));
    }
    synchronized Mapping mapping(String sourceId,TiberoDbLinks.Entry link) {
        try {
            var mapping=JSON.fromJson(state.mappings.get(key(sourceId,link.owner(),link.name())),Mapping.class);
            return mapping!=null && key(link.username(),link.host()).equals(mapping.definition())?mapping:null;
        }
        catch(RuntimeException invalid) { return null; }
    }
    @Override public synchronized State getState() {
        var copy=new State(); copy.catalogues.putAll(state.catalogues); copy.mappings.putAll(state.mappings); return copy;
    }
    @Override public synchronized void loadState(State loaded) {
        var copy=new State();
        if(loaded.catalogues!=null) copy.catalogues.putAll(loaded.catalogues);
        if(loaded.mappings!=null) copy.mappings.putAll(loaded.mappings);
        state=copy;
    }
}
