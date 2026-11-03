package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.ModFamily;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.*;

/**
 * The session's CURRENT_SCHEMA as the IDE's current schema. Tibero 6 JDBC cannot report it (no getSchema(), getCatalog()
 * is null), so the IDE falls back to the login name as typed and unqualified names do not resolve when it differs.
 */
final class TiberoCurrentSchema {
    // SYS_CONTEXT follows ALTER SESSION SET CURRENT_SCHEMA (logon triggers, startup scripts); USER is the upper-case login schema.
    static final List<String> QUERIES=List.of("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL","SELECT USER FROM DUAL");
    @FunctionalInterface interface Reader {
        String read(String sql) throws SQLException,RemoteException;
    }
    static String read(DatabaseConnectionCore connection) throws SQLException,RemoteException {
        return read(sql -> {
            var rows=TiberoRoutineReader.query(connection,sql,1);
            return rows.size()==1 ? rows.get(0)[0] : null;
        });
    }
    /** First non-blank answer; null when every query answered blank, the first failure when none answered. */
    static String read(Reader reader) throws SQLException,RemoteException {
        SQLException failure=null;
        boolean answered=false;
        for(var sql:QUERIES) {
            try {
                String name=reader.read(sql);
                answered=true;
                if(name!=null && !name.isBlank()) return name.trim();
            } catch(SQLException e) {
                if(failure==null) failure=e; else failure.addSuppressed(e);
            }
        }
        if(!answered && failure!=null) throw failure;
        return null;
    }
    /** Marks the named schema current and clears its siblings; leaves the model alone when no schema has that name. */
    static boolean apply(BasicModElement root,String name) {
        var families=new ArrayList<ModFamily<? extends BasicModElement>>();
        var direct=root.familyOf(ObjectKind.SCHEMA);
        if(direct!=null) families.add(direct);
        var databases=root.familyOf(ObjectKind.DATABASE);
        if(databases!=null) for(var database:databases) {
            var schemas=database.familyOf(ObjectKind.SCHEMA);
            if(schemas!=null) families.add(schemas);
        }
        boolean found=false;
        for(var family:families) {
            boolean here=false;
            for(var e:family) if(e instanceof BasicNamedElement n && name.equals(n.getName())) here=true;
            if(!here) continue;
            found=true;
            for(var e:family) if(e instanceof BasicModNamespace namespace && e instanceof BasicNamedElement n) namespace.setCurrent(name.equals(n.getName()));
        }
        return found;
    }
}
