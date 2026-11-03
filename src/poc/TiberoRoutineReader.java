package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.openapi.progress.ProgressManager;
import java.sql.SQLException;
import java.rmi.RemoteException;
import java.util.*;

/** Two bounded SELECTs per visited schema, never one request per package or argument. */
final class TiberoRoutineReader {
    static final int MAX_ROWS=100000, MAX_CHARS=16*1024*1024;
    static List<TiberoRoutineCatalog.Signature> read(DatabaseConnectionCore connection,String schema,List<TiberoStoredObjects.ObjectInfo> objects)
            throws SQLException,RemoteException {
        var members=new ArrayList<TiberoRoutineCatalog.Member>();
        for(var r:query(connection,TiberoRoutineCatalog.membersSql(schema),5))
            members.add(new TiberoRoutineCatalog.Member(r[0],r[1],r[2],r[3],r[4]));
        var arguments=new ArrayList<TiberoRoutineCatalog.Argument>();
        for(var r:arguments(connection,schema))
            arguments.add(new TiberoRoutineCatalog.Argument(r[0],r[1],r[2],r[3],r[4],r[5],r[6],r[7],r[8],r[9],r[10],r[11]));
        return TiberoRoutineCatalog.snapshot(schema,objects,members,arguments);
    }
    private static List<String[]> arguments(DatabaseConnectionCore connection,String schema) throws SQLException,RemoteException {
        try { return query(connection,TiberoRoutineCatalog.argumentsSql(schema),12); }
        catch(SQLException levels) {
            // Some Tibero 6 servers have no DATA_LEVEL column; the rest of ALL_ARGUMENTS is the same.
            try { return query(connection,TiberoRoutineCatalog.argumentsWithoutLevelSql(schema),12); }
            catch(SQLException plain) { plain.addSuppressed(levels); throw plain; }
        }
    }
    static List<String[]> query(DatabaseConnectionCore connection,String sql,int columns) throws SQLException,RemoteException {
        var statement=connection.getRemoteConnection().createStatement(); Throwable failure=null;
        try {
            statement.setQueryTimeout(30);
            statement.setFetchSize(512);
            var result=statement.executeQuery(sql); Throwable rowFailure=null;
            try {
                var rows=new ArrayList<String[]>(); int chars=0;
                while(result.next()) {
                    ProgressManager.checkCanceled();
                    if(rows.size()>=MAX_ROWS) throw new SQLException("Catalogue metadata exceeds row limit; refusing partial refresh");
                    var row=new String[columns];
                    for(int i=0;i<columns;i++) {
                        row[i]=result.getString(i+1); chars+=row[i]==null?0:row[i].length();
                        if(chars>MAX_CHARS) throw new SQLException("Catalogue metadata exceeds size limit; refusing partial refresh");
                    }
                    rows.add(row);
                }
                return rows;
            } catch(SQLException|RemoteException|RuntimeException|Error e) { rowFailure=e; throw e; }
            finally { try { result.close(); } catch(SQLException|RemoteException e) { if(rowFailure!=null) rowFailure.addSuppressed(e); else throw e; } }
        } catch(SQLException|RemoteException|RuntimeException|Error e) { failure=e; throw e; }
        finally { try { statement.close(); } catch(SQLException|RemoteException e) { if(failure!=null) failure.addSuppressed(e); else throw e; } }
    }
}
