package poc;

import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.psi.DbDataSource;
import com.intellij.database.util.DasUtil;
import com.intellij.psi.ResolveState;
import com.intellij.sql.psi.SqlReference;
import com.intellij.sql.psi.SqlScopeProcessor;
import com.intellij.sql.psi.impl.SqlResolveExtension;
import java.util.Locale;

/** Unqualified names the IDE cannot find resolve to their synonym target, e.g. ORDERS -> PUBLIC.ORDERS -> ABC.ORDERS. */
public final class TiberoSynonymResolve implements SqlResolveExtension {
    @Override public boolean process(SqlReference reference,SqlScopeProcessor processor) {
        // Objects visible without a synonym win, as on the server.
        if(!processor.isResultEmpty() || reference.getQualifier()!=null) return true;
        String name=reference.getReferenceName();
        if(name==null || name.isBlank()) return true;
        String key=reference.isQuoted() ? name : name.toUpperCase(Locale.ROOT);
        for(DbDataSource source:processor.getDataSources()) {
            if(!GenericDbms.TIBERO.eq(source.getDbms())) continue;
            var target=TiberoSynonyms.getInstance().find(source.getUniqueId(),key);
            if(target==null) continue;
            for(var object:source.getNameIndex().getObjectsByName(target.name())) {
                if(!target.owner().equals(DasUtil.getSchema(object)) || !processor.isExpected(object.getKind())) continue;
                var element=source.findElement(object);
                if(element!=null && !processor.execute(element,ResolveState.initial())) return false;
            }
        }
        return true;
    }
}
