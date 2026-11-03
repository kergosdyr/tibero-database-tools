package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.SequenceIdentity;
import com.intellij.database.model.basic.*;
import com.intellij.openapi.progress.ProgressManager;
import java.util.*;

/** Sequences from a complete, schema-scoped snapshot; never advances a sequence. */
public final class TiberoSequences {
    /** identity is null when only names could be read; the tree then shows no range. */
    public record Entry(String owner, String name, SequenceIdentity identity) {
        public Entry(String owner, String name) { this(owner,name,null); }
    }
    public static String sql(String schema) {
        return "SELECT SEQUENCE_OWNER, SEQUENCE_NAME, MIN_VALUE, LAST_NUMBER, INCREMENT_BY, MAX_VALUE FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = "
                + TiberoCatalog.literal(schema) + " ORDER BY SEQUENCE_NAME";
    }
    static String namesSql(String schema) {
        return "SELECT SEQUENCE_OWNER, SEQUENCE_NAME FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = "
                + TiberoCatalog.literal(schema) + " ORDER BY SEQUENCE_NAME";
    }
    static SequenceIdentity identity(String min,String next,String increment,String max) {
        try { return SequenceIdentity.of(integer(min),integer(next),integer(increment),integer(max)); }
        catch(NumberFormatException | ArithmeticException e) { return null; }
    }
    private static java.math.BigInteger integer(String value) {
        if(value==null || value.isBlank()) throw new NumberFormatException("missing");
        return new java.math.BigDecimal(value.trim()).toBigIntegerExact();
    }
    static Map<String,SequenceIdentity> snapshot(String schema, List<Entry> rows) {
        TiberoCatalog.literal(schema);
        var names=new LinkedHashMap<String,SequenceIdentity>();
        for(var row:rows) {
            if(!schema.equals(row.owner()) || row.name()==null || row.name().isBlank() || names.containsKey(row.name()))
                throw new IllegalArgumentException("Unexpected ALL_SEQUENCES row for schema " + schema);
            names.put(row.name(),row.identity());
        }
        return names;
    }
    @FunctionalInterface interface Reader {
        List<Entry> read() throws java.sql.SQLException,java.rmi.RemoteException;
    }
    static void refresh(BasicModSchema schema,Reader reader) throws java.sql.SQLException,java.rmi.RemoteException {
        var names=snapshot(schema.getName(),reader.read());
        ProgressManager.checkCanceled();
        schema.getModel().modify(schema,BasicModSchema.class,mod -> {
            var family=mod.familyOf(ObjectKind.SEQUENCE);
            if(family==null) throw new IllegalStateException("IDE model does not support sequences");
            var retained=new HashSet<String>();
            family.remove(e -> !(e instanceof BasicNamedElement n) || !names.containsKey(n.getName()) || !retained.add(n.getName()));
            for(var name:names.keySet()) if(!retained.contains(name)) {
                var sequence=(BasicModNamedElement)family.createNewOne();
                sequence.setName(name);
                sequence.setSyncPending(false);
            }
            for(var element:family)
                if(element instanceof BasicModSequence sequence && names.get(sequence.getName())!=null) sequence.setSequenceIdentity(names.get(sequence.getName()));
        });
    }
    static List<Entry> readCatalog(DatabaseConnectionCore connection,String schema) throws java.sql.SQLException,java.rmi.RemoteException {
        try { return read(connection,sql(schema),true); }
        catch(java.sql.SQLException ranges) {
            // A server without one of the range columns still lists the sequences.
            try { return read(connection,namesSql(schema),false); }
            catch(java.sql.SQLException names) { names.addSuppressed(ranges); throw names; }
        }
    }
    private static List<Entry> read(DatabaseConnectionCore connection,String sql,boolean ranges) throws java.sql.SQLException,java.rmi.RemoteException {
        var statement=connection.getRemoteConnection().createStatement();
        Throwable failure=null;
        try {
            statement.setQueryTimeout(30);
            statement.setFetchSize(512);
            var rs=statement.executeQuery(sql);
            Throwable rowFailure=null;
            try {
                var rows=new ArrayList<Entry>();
                long characters=0;
                while(rs.next()) {
                    ProgressManager.checkCanceled();
                    if(rows.size()>=100000) throw new java.sql.SQLException("Sequence snapshot exceeds 100000 rows; refusing partial refresh");
                    String owner=rs.getString(1),name=rs.getString(2);
                    characters+=(owner==null ? 0 : owner.length())+(name==null ? 0 : name.length());
                    if(characters>16L*1024*1024) throw new java.sql.SQLException("Sequence snapshot exceeds character limit; refusing partial refresh");
                    rows.add(new Entry(owner,name,ranges ? identity(rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)) : null));
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
