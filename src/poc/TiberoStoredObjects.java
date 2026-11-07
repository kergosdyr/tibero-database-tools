package poc;

import com.intellij.database.model.DasRoutine;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.*;
import com.intellij.database.model.families.ModFamily;
import java.util.*;
import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.openapi.progress.ProgressManager;

/** Complete per-schema catalogue snapshot; validation precedes all model mutation. */
public final class TiberoStoredObjects {
    public record Entry(String owner, String name, String type, String status) {}
    public record ObjectInfo(String name, String type, boolean invalid) {}
    public static String sql(String schema) {
        if(schema==null || schema.isBlank()) throw new IllegalArgumentException("Schema is required");
        return "SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM ALL_OBJECTS WHERE OWNER = '"
                + schema.replace("'","''") + "' AND OBJECT_TYPE IN ('FUNCTION','PROCEDURE','PACKAGE','PACKAGE BODY') ORDER BY OBJECT_NAME, OBJECT_TYPE";
    }
    public static List<ObjectInfo> snapshot(String schema, List<Entry> rows) {
        var result=new LinkedHashMap<String,ObjectInfo>();
        for(var row: rows) {
            if(!schema.equals(row.owner()) || row.name()==null || row.name().isBlank()
                    || row.type()==null || row.status()==null
                    || !Set.of("FUNCTION","PROCEDURE","PACKAGE","PACKAGE BODY").contains(row.type())
                    || !Set.of("VALID","INVALID").contains(row.status()))
                throw new IllegalArgumentException("Unexpected ALL_OBJECTS row for schema " + schema);
            String type=row.type().equals("PACKAGE BODY") ? "PACKAGE" : row.type();
            var old=result.get(row.name());
            if(old!=null && !old.type().equals(type)) throw new IllegalArgumentException("Conflicting stored object: " + row.name());
            result.put(row.name(),new ObjectInfo(row.name(),type,"INVALID".equals(row.status()) || old!=null && old.invalid()));
        }
        return List.copyOf(result.values());
    }
    public static void apply(BasicModSchema schema, List<ObjectInfo> snapshot) {
        var routines=requireFamily(schema,ObjectKind.ROUTINE);
        var packages=requireFamily(schema,ObjectKind.PACKAGE);
        var expectedRoutines=new LinkedHashMap<String,ObjectInfo>();
        var expectedPackages=new LinkedHashMap<String,ObjectInfo>();
        for(var item:snapshot) (item.type().equals("PACKAGE") ? expectedPackages : expectedRoutines).put(item.name(),item);
        reconcile(routines,expectedRoutines,true);
        reconcile(packages,expectedPackages,false);
    }
    private static ModFamily<? extends BasicModElement> requireFamily(BasicModSchema schema,ObjectKind kind) {
        var family=schema.familyOf(kind);
        if(family==null) throw new IllegalStateException("IDE model does not support " + kind);
        return family;
    }
    private static void reconcile(ModFamily<? extends BasicModElement> family,Map<String,ObjectInfo> expected,boolean routine) {
        var retained=new HashSet<String>();
        // Remove dropped objects and duplicate JDBC entries, retaining existing identities and arguments.
        family.remove(element -> !(element instanceof BasicModNamedElement named)
                || !expected.containsKey(named.getName()) || !retained.add(named.getName()));
        var byName=new HashMap<String,BasicModElement>();
        for(var e:family) if(e instanceof BasicNamedElement n) byName.put(n.getName(),e);
        for(var item:expected.values()) {
            var existing=byName.get(item.name());
            var object=(BasicModNamedElement)(existing==null ? family.createNewOne() : existing);
            object.setName(item.name());
            object.setSyncPending(false);
            if(routine) ((BasicModRoutine)object).setRoutineKind(DasRoutine.Kind.valueOf(item.type()));
            if(object instanceof BasicModSourceAware source) source.setInvalid(item.invalid());

        }
    }
    @FunctionalInterface interface SnapshotReader {
        List<TiberoStoredObjects.Entry> read() throws java.sql.SQLException,java.rmi.RemoteException;
    }
    static void refresh(BasicModSchema schema,SnapshotReader reader) throws java.sql.SQLException,java.rmi.RemoteException {
        var snapshot=TiberoStoredObjects.snapshot(schema.getName(),reader.read());
        ProgressManager.checkCanceled();
        schema.getModel().modify(schema,BasicModSchema.class,mod -> TiberoStoredObjects.apply(mod,snapshot));
    }
    static List<TiberoStoredObjects.Entry> readCatalog(DatabaseConnectionCore connection,String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        var statement=connection.getRemoteConnection().createStatement();
        Throwable failure=null;
        try {
            statement.setQueryTimeout(60);
            var rs=statement.executeQuery(TiberoStoredObjects.sql(schema));
            Throwable rowFailure=null;
            try {
                var rows=new ArrayList<TiberoStoredObjects.Entry>();
                while(rs.next()) {
                    ProgressManager.checkCanceled();
                    if(rows.size()>=100000) throw new java.sql.SQLException("Stored object snapshot exceeds 100000 rows; refusing partial refresh");
                    rows.add(new TiberoStoredObjects.Entry(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)));
                }
                return rows;
            } catch(java.sql.SQLException | java.rmi.RemoteException | RuntimeException | Error e) { rowFailure=e; throw e; }
            finally {
                try { rs.close(); } catch(java.sql.SQLException | java.rmi.RemoteException close) { if(rowFailure!=null) rowFailure.addSuppressed(close); else throw close; }
            }
        } catch(java.sql.SQLException | java.rmi.RemoteException | RuntimeException | Error e) { failure=e; throw e; }
        finally {
            try { statement.close(); } catch(java.sql.SQLException | java.rmi.RemoteException close) { if(failure!=null) failure.addSuppressed(close); else throw close; }
        }
    }
}
