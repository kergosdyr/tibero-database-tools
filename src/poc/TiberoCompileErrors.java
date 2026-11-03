package poc;

import com.intellij.database.console.JdbcEngine;
import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.datagrid.DataAuditor;
import com.intellij.database.datagrid.DataRequest;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * After CREATE [OR REPLACE] of a stored PL/SQL object, Tibero only reports "created with compilation errors".
 * Show the ALL_ERRORS rows in the console, as IntelliJ does for Oracle.
 */
public final class TiberoCompileErrors implements DataAuditor {
    record Target(String owner,String name,String type) {}
    interface Catalog {
        /** LINE, POSITION, TEXT rows; owner null means the current user. */
        List<String[]> errors(Target target) throws SQLException,RemoteException;
    }
    private static final String NAME="(\"(?:[^\"]|\"\")+\"|[A-Za-z][A-Za-z0-9_$#]*)";
    private static final Pattern CREATE=Pattern.compile("(?is)^\\s*(?:(?:--[^\\n]*\\n|/\\*.*?\\*/)\\s*)*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:FORCE\\s+|NOFORCE\\s+)?"
            + "(PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE\\s+BODY|TYPE|VIEW)\\s+" + NAME + "(?:\\s*\\.\\s*" + NAME + ")?");
    static final int MAX_ERRORS=200;
    private final Set<Target> pending=Collections.synchronizedSet(new LinkedHashSet<>());

    static Target target(String sql) {
        if(sql==null) return null;
        var m=CREATE.matcher(sql);
        if(!m.find()) return null;
        String type=m.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
        String first=name(m.group(2)), second=m.group(3)==null ? null : name(m.group(3));
        return second==null ? new Target(null,first,type) : new Target(first,second,type);
    }
    private static String name(String token) {
        return token.startsWith("\"") ? token.substring(1,token.length()-1).replace("\"\"","\"") : token.toUpperCase(Locale.ROOT);
    }
    static String sql(Target target) {
        String owner=target.owner()==null ? "USER" : TiberoCatalog.literal(target.owner());
        return "SELECT LINE, POSITION, TEXT FROM ALL_ERRORS WHERE OWNER = " + owner + " AND NAME = " + TiberoCatalog.literal(target.name())
                + " AND TYPE = " + TiberoCatalog.literal(target.type()) + " ORDER BY LINE, POSITION";
    }

    @Override public void beforeStatement(DataRequest.Context context) { remember(context); }
    @Override public void afterStatement(DataRequest.Context context) { remember(context); }
    private void remember(DataRequest.Context context) {
        for(var statement:context.statementContext) {
            var target=target(statement.getSql());
            if(target!=null) pending.add(target);
        }
    }
    @Override public void requestFinished(DataRequest.Context context) {
        var connection=context.producer instanceof JdbcEngine engine ? engine.getCurrentConnectionIfReady() : null;
        if(connection!=null) report(remote(connection),context::error);
        else pending.clear();
    }
    void record(String sql) { var target=target(sql); if(target!=null) pending.add(target); }
    void report(Catalog catalog,Consumer<String> error) {
        List<Target> targets;
        synchronized(pending) { targets=List.copyOf(pending); pending.clear(); }
        for(var target:targets) {
            List<String[]> rows;
            try { rows=catalog.errors(target); }
            catch(SQLException|RemoteException e) { continue; } // No ALL_ERRORS access: keep the console quiet.
            String object=target.type() + " " + (target.owner()==null ? "" : target.owner() + ".") + target.name();
            int shown=0;
            for(var row:rows) {
                if(shown++==MAX_ERRORS) { error.accept(object + ": more errors in ALL_ERRORS"); break; }
                error.accept(object + " compilation error at line " + row[0] + ", column " + row[1] + ": " + Objects.toString(row[2],"").trim());
            }
        }
    }
    static Catalog remote(DatabaseConnectionCore connection) {
        return target -> TiberoRoutineReader.query(connection,sql(target),3);
    }
}
