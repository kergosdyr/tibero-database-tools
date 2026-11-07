package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.remote.jdbc.RemoteDatabaseMetaData;
import com.intellij.database.remote.jdbc.helpers.JdbcHelper;
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil;
import com.intellij.database.dialects.generic.introspector.jdbc.GenericMetadataWrapper;
import com.intellij.database.jdbc.wrappers.*;
import com.intellij.database.model.DasRoutine;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Function;
import java.sql.SQLException;
import java.util.function.Consumer;

/** Tibero-specific JDBC adaptation; leaves the driver JAR and user SQL unchanged. */
public final class TiberoMetadataWrapper extends GenericMetadataWrapper {
    public static final class Factory extends DatabaseMetaDataWrapper.MDFactory {
        @Override public DatabaseMetaDataWrapper create(DatabaseConnectionCore connection,RemoteDatabaseMetaData metadata) {
            return new TiberoMetadataWrapper(connection,metadata);
        }
    }
    public TiberoMetadataWrapper(DatabaseConnectionCore connection,RemoteDatabaseMetaData metadata) {
        super(connection,metadata);
        TiberoMetadataRefresh.connection(connection);
    }
    @Override public ClosableIt<Schema> schemas(com.intellij.util.containers.JBIterable<String> databases) throws SQLException {
        return withPublicSchema(super.schemas(databases));
    }
    static ClosableIt<Schema> withPublicSchema(ClosableIt<Schema> source) {
        // PUBLIC owns shared links but is absent from JDBC's ALL_USERS-based schema list.
        return new ClosableIt<>() {
            boolean publicSeen;
            @Override protected Schema nextImpl() {
                if(source.hasNext()) {
                    var schema=source.next();
                    if("PUBLIC".equals(schema.schema)) publicSeen=true;
                    return schema;
                }
                if(publicSeen) return stop();
                publicSeen=true;
                return new Schema("PUBLIC",null);
            }
            @Override public void close() { source.close(); }
        };
    }
    @Override public ClosableIt<? extends Table> tables(Schema schema,String name,String[] types) throws SQLException {
        TiberoMetadataRefresh.structure(getConnection(),schema);
        return super.tables(schema,name,types);
    }
    @Override public ClosableIt<Routine> routines(Schema schema,String pkg,String name) throws SQLException {
        TiberoMetadataRefresh.routines(getConnection(),schema,false);
        return super.routines(schema,pkg,name);
    }
    @Override public ClosableIt<RoutineArgument> routineArgumentsInner(Schema schema,String name,Function<Routine,DasRoutine.Kind> kinds) throws SQLException {
        TiberoMetadataRefresh.routines(getConnection(),schema,true);
        // The base appends getFunctionColumns(), whose Tibero 6 SQL contains "'DATE', )93" (JDBC-8012 Missing comma).
        // getProcedureColumns() reads the same ALL_ARGUMENTS rows for functions too (return value as COLUMN_TYPE 5),
        // so only the broken half is dropped and routine signatures keep their arguments.
        var metadata=getMetaData();
        boolean noCatalog=StringUtil.isEmpty(JdbcNativeUtil.computeRemote(metadata::getCatalogTerm));
        String catalog=noCatalog ? schema.database : StringUtil.notNullize(schema.database);
        try {
            var rs=JdbcNativeUtil.computeRemote(() -> metadata.getProcedureColumns(catalog,escape(schema.schema),StringUtil.notNullize(escape(name),"%"),null));
            if(rs==null) return ClosableIt.empty();
            return normalize(setUp(new RoutineArgumentIt(rs,getDbms(),schema,noCatalog,kinds,true)),TiberoColumnTypes::normalizeArgument);
        } catch(SQLException e) {
            if(isUnimplemented(e)) return ClosableIt.empty();
            throw e;
        }
    }
    @Override protected ClosableIt<TableColumn> getTableColumnsInner2(Schema schema,String table) throws SQLException {
        TiberoMetadataRefresh.structure(getConnection(),schema);
        return normalizeColumns(super.getTableColumnsInner2(schema,table));
    }
    static ClosableIt<TableColumn> normalizeColumns(ClosableIt<TableColumn> source) {
        return normalize(source,TiberoColumnTypes::normalizeColumn);
    }
    static <T extends JdbcHelper.Type> ClosableIt<T> normalize(ClosableIt<T> source,Consumer<? super T> fix) {
        return new ClosableIt<>() {
            @Override protected T nextImpl() {
                if(!source.hasNext()) return stop();
                var element=source.next(); fix.accept(element); return element;
            }
            @Override public void close() { source.close(); }
        };
    }
    // Same escaping as the private base helper used for its own metadata calls.
    private String escape(String pattern) {
        if(pattern==null) return null;
        String escape=getPatternEscape();
        return StringUtil.isEmpty(escape) ? pattern : pattern.replace("_",escape+"_").replace("%",escape+"%");
    }
}
