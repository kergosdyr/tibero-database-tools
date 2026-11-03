package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import java.util.*;

/** Validated DB link catalogue rows. Refresh errors never masquerade as empty results. */
public final class TiberoDbLinks {
    public record Entry(String owner,String name,String username,String host,String created) {}
    static String sql(String schema) {
        return "SELECT OWNER, DB_LINK, USERNAME, HOST, CREATED FROM ALL_DB_LINKS WHERE OWNER IN (" + TiberoCatalog.literal(schema) + ", 'PUBLIC') ORDER BY OWNER, DB_LINK";
    }
    static List<Entry> read(DatabaseConnectionCore connection,String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        return snapshot(schema,TiberoRoutineReader.query(connection,sql(schema),5));
    }
    static List<Entry> snapshot(String schema,List<String[]> rows) {
        var result=new ArrayList<Entry>(); var seen=new HashSet<String>();
        for(var row:rows) {
            if(row.length!=5 || !(schema.equals(row[0]) || "PUBLIC".equals(row[0])) || row[1]==null || row[1].isBlank()
                    || !seen.add(row[0]+"\u0000"+row[1])) throw new IllegalArgumentException("Invalid DB link catalogue for " + schema);
            result.add(new Entry(row[0],row[1],row[2],row[3],row[4]));
        }
        return List.copyOf(result);
    }
}
