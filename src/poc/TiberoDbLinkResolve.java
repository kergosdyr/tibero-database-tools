package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.model.*;
import com.intellij.database.model.basic.BasicNamespace;
import com.intellij.database.psi.*;
import com.intellij.database.util.DasUtil;
import com.intellij.psi.*;
import com.intellij.sql.psi.*;
import com.intellij.sql.psi.impl.SqlResolveExtension;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves remote objects against explicitly mapped, already introspected IDE metadata. No network on typing. */
public final class TiberoDbLinkResolve implements SqlResolveExtension {
    static final String IDENT="(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#]*)";
    static final String LINK="(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#]*(?:\\.[A-Za-z_][A-Za-z0-9_$#]*)*)";
    static final Pattern REMOTE=Pattern.compile("^(?:("+IDENT+")\\s*\\.\\s*)?("+IDENT+")\\s*@\\s*("+LINK+")$");
    record Remote(String schema,String table,String link) {}
    static String name(String token) { return token.startsWith("\"") ? token.substring(1,token.length()-1).replace("\"\"","\"") : token.toUpperCase(Locale.ROOT); }
    static Remote parse(String text) {
        var m=REMOTE.matcher(text.trim());
        return m.matches()?new Remote(m.group(1)==null?null:name(m.group(1)),name(m.group(2)),name(m.group(3))):null;
    }
    static List<TiberoDbLinks.Entry> links(DbDataSource source) {
        var own=new TreeMap<String,TiberoDbLinks.Entry>(); var shared=new TreeMap<String,TiberoDbLinks.Entry>();
        for(var schema:DasUtil.getSchemas(source)) {
            boolean current=schema instanceof BasicNamespace namespace && namespace.isCurrent();
            for(var link:TiberoDbLinkStore.getInstance().entries(source.getUniqueId(),schema.getName())) {
                if("PUBLIC".equals(link.owner())) shared.put(link.name(),link);
                else if(current) own.put(link.name(),link);
            }
        }
        shared.putAll(own); return List.copyOf(shared.values());
    }
    record Target(DbDataSource source,String schema) {}
    static Target target(DbDataSource source,String linkName,String explicitSchema) {
        if(!GenericDbms.TIBERO.eq(source.getDbms())) return null;
        var link=links(source).stream().filter(l->l.name().equals(linkName)).findFirst().orElse(null);
        if(link==null) return null;
        var mapping=TiberoDbLinkStore.getInstance().mapping(source.getUniqueId(),link);
        if(mapping==null || mapping.sourceId()==null || mapping.schema()==null) return null;
        var target=DbPsiFacade.getInstance(source.getProject()).findDataSource(mapping.sourceId());
        return target==null?null:new Target(target,explicitSchema==null?mapping.schema():explicitSchema);
    }
    static boolean table(DasObject object) { return object.getKind()==ObjectKind.TABLE || object.getKind()==ObjectKind.VIEW; }
    static List<DasObject> objects(Target target,String table) {
        if(target==null) return List.of();
        return target.source().getNameIndex().getObjectsByName(table).stream()
                .filter(o->table(o) && target.schema().equals(DasUtil.getSchema(o))).toList();
    }
    @Override public boolean process(SqlReference reference,SqlScopeProcessor processor) {
        if(!processor.isResultEmpty()) return true;
        PsiElement element=reference.getElement();
        // A qualified table reference sits inside the Oracle @link expression.
        Remote remote=null;
        for(PsiElement p=element;p instanceof SqlReferenceExpression;p=p.getParent()) {
            remote=parse(p.getText()); if(remote!=null) break;
        }
        if(remote==null) return true;
        if(processor.isExpected(ObjectKind.DB_LINK)) {
            for(var source:processor.getDataSources()) {
                if(!GenericDbms.TIBERO.eq(source.getDbms())) continue;
                for(var link:links(source)) if(link.name().equals(remote.link()))
                    if(!processor.execute(new TiberoDbLinkElement(source,link),ResolveState.initial())) return false;
            }
            return processor.getDataSources().stream().noneMatch(s->GenericDbms.TIBERO.eq(s.getDbms()));
        }
        for(var source:processor.getDataSources()) for(var object:objects(target(source,remote.link(),remote.schema()),remote.table())) {
            if(!processor.isExpected(object.getKind())) continue;
            var mapped=target(source,remote.link(),remote.schema());
            var psi=mapped.source().findElement(object);
            if(psi!=null && !processor.execute(psi,ResolveState.initial())) return false;
        }
        // Do not fall back to an unrelated local table for an explicit remote reference.
        return processor.getDataSources().stream().noneMatch(s->GenericDbms.TIBERO.eq(s.getDbms()));
    }
}
