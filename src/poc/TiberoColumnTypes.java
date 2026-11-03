package poc;

import com.intellij.database.dialects.base.introspector.jdbc.JdbcIntrospectorHelper;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.*;
import com.intellij.database.remote.jdbc.helpers.JdbcHelper;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tibero 6 getColumns() returns ALL_TAB_COLUMNS.DATA_TYPE ("TIMESTAMP(6)") together with COLUMN_SIZE and
 * DECIMAL_DIGITS, and IntelliJ appends both again ("TIMESTAMP(6,6)"). Types are rebuilt from Tibero's type
 * grammar, so the result does not depend on which of those numbers the server fills in.
 */
final class TiberoColumnTypes {
    private static final Pattern TIMESTAMP=Pattern.compile("(?i)^TIMESTAMP(?:\\s*\\(\\s*([0-9])\\s*\\))?(\\s+WITH(?:\\s+LOCAL)?\\s+TIME\\s+ZONE)?$");
    // DDL never takes a size for these; JDBC COLUMN_SIZE would otherwise print DATE(7) or CLOB(4000).
    private static final Set<String> UNSIZED=Set.of("DATE","CLOB","NCLOB","BLOB","BFILE","LONG","LONG RAW","ROWID","UROWID",
            "BINARY_FLOAT","BINARY_DOUBLE","XMLTYPE");

    /** Raw JDBC column metadata: length is COLUMN_SIZE, scale is DECIMAL_DIGITS (both 0 when NULL). */
    static void normalizeColumn(JdbcHelper.Type type) {
        if(type.typeName==null || type.typeName.isBlank()) return;
        String name=type.typeName.trim();
        var timestamp=TIMESTAMP.matcher(name);
        if(timestamp.matches()) {
            // The name's own "(p)" wins; otherwise DECIMAL_DIGITS is the fractional-second precision.
            int precision=timestamp.group(1)!=null ? timestamp.group(1).charAt(0)-'0' : fractionalPrecision(type.length,type.scale);
            type.typeName="TIMESTAMP";
            if(timestamp.group(2)!=null) type.typeSuffix=timestamp.group(2).trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
            type.length=precision;
            type.scale=0;
        }
        else if(name.contains("(") || name.toUpperCase(Locale.ROOT).startsWith("INTERVAL") || UNSIZED.contains(name.toUpperCase(Locale.ROOT))) {
            // Self-describing names such as INTERVAL DAY(2) TO SECOND(6) carry their own precision.
            type.length=-1;
            type.scale=0;
        }
        else if(type.length==0 && type.scale==0) type.length=-1; // NULL COLUMN_SIZE: NUMBER, not NUMBER(0)
    }

    /** PL/SQL parameters and return values are unconstrained, so sizes from ALL_ARGUMENTS are display noise. */
    static void normalizeArgument(JdbcHelper.Type type) {
        type.length=-1;
        type.scale=0;
    }

    private static int fractionalPrecision(int size,int scale) {
        if(scale>=1 && scale<=9) return scale;
        if(size>=1 && size<=9) return size;
        return -1; // Tibero default precision (6)
    }

    /** Repairs the final model too: JDBC wrappers do not cover already cached column types. */
    static void repair(BasicModSchema schema) {
        schema.getModel().modify(schema,BasicModSchema.class,mod -> {
            for(var kind:new ObjectKind[]{ObjectKind.TABLE,ObjectKind.VIEW})
                for(var table:mod.familyOf(kind))
                    for(var child:table.familyOf(ObjectKind.COLUMN))
                        if(child instanceof BasicModTypedElement column) repair(column);
        });
    }

    /** Only the shapes the old JDBC path produced; a parsed model type has lost the raw JDBC numbers. */
    static void repair(BasicModTypedElement column) {
        var stored=column.getDataType();
        if(stored==null || stored.custom || stored.schemaName!=null && !stored.schemaName.isEmpty() || stored.typeName==null) return;
        String name=stored.typeName.trim().toUpperCase(Locale.ROOT);
        int size=stored.size, scale=Math.max(stored.scale,0);
        if(name.equals("TIMESTAMP") && scale>0) { size=fractionalPrecision(size,scale); scale=0; } // TIMESTAMP(6,6), (11,6), (0,6)
        else if(UNSIZED.contains(name) && size>=0) { size=-1; scale=0; }                          // DATE(7), CLOB(0)
        else if(name.equals("NUMBER") && size==0 && scale==0) size=-1;                             // NUMBER(0)
        else return;
        var fixed=JdbcIntrospectorHelper.makeDasType(stored.typeName,size,scale,stored.suffix,stored.jdbcType,column);
        if(!fixed.getSpecification().equals(stored.getSpecification())) column.setDataType(fixed.toDataType());
    }
}
