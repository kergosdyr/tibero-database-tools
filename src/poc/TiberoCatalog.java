package poc;

import java.util.Set;

/** Read-only catalogue SQL. SELECT * tolerates additional version-specific columns. */
public final class TiberoCatalog {
    private static final Set<String> SOURCE_TYPES = Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "TYPE", "TYPE BODY");
    public enum Query {
        OBJECTS("Schema Stored Objects"), VIEW("View Definition"), COLUMN_TYPES("Column Types"), ARGUMENTS("Routine Arguments"), SOURCE("Object Source"), PACKAGE_BODY("Package Body"), ERRORS("Compilation Errors"),
        DEPENDENCIES("Dependencies"), DEPENDENTS("Dependent Objects"), TRIGGERS("Table Triggers"), SYNONYMS("Schema Synonyms"),
        SEQUENCES("Schema Sequences"), PRIVILEGES("Object Privileges");
        public final String title;
        Query(String title) { this.title=title; }
    }
    public static String literal(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("An exact schema/object name is required");
        return "'" + value.replace("'", "''") + "'";
    }
    public static String sourceSql(String schema, String name, String type) {
        if (!SOURCE_TYPES.contains(type)) throw new IllegalArgumentException("Unsupported source type: " + type);
        return "SELECT TEXT FROM ALL_SOURCE WHERE OWNER = " + literal(schema) + " AND NAME = " + literal(name)
                + " AND TYPE = " + literal(type) + " ORDER BY LINE";
    }
    public static String sql(Query query, String schema, String name) {
        String owner = literal(schema);
        return switch(query) {
            case OBJECTS -> "SELECT OWNER, OBJECT_NAME, OBJECT_TYPE, STATUS FROM ALL_OBJECTS WHERE OWNER = " + owner
                    + " AND OBJECT_TYPE IN ('VIEW', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'TRIGGER', 'TYPE', 'TYPE BODY') ORDER BY OBJECT_TYPE, OBJECT_NAME";
            case VIEW -> TiberoViewLoader.viewSql(schema,name);
            // Raw dictionary values behind the IDE's column and argument types; columns match the driver's own getColumns().
            case COLUMN_TYPES -> "SELECT COLUMN_ID, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE FROM ALL_TAB_COLUMNS WHERE OWNER = " + owner
                    + " AND TABLE_NAME = " + literal(name) + " ORDER BY COLUMN_ID";
            case ARGUMENTS -> "SELECT * FROM ALL_ARGUMENTS WHERE OWNER = " + owner + " AND (OBJECT_NAME = " + literal(name)
                    + " OR PACKAGE_NAME = " + literal(name) + ") ORDER BY PACKAGE_NAME, OBJECT_NAME, POSITION";
            case SOURCE -> "SELECT TYPE, LINE, TEXT FROM ALL_SOURCE WHERE OWNER = " + owner + " AND NAME = " + literal(name) + " ORDER BY TYPE, LINE";
            case PACKAGE_BODY -> sourceSql(schema,name,"PACKAGE BODY");
            case ERRORS -> "SELECT * FROM ALL_ERRORS WHERE OWNER = " + owner + " AND NAME = " + literal(name) + " ORDER BY TYPE, SEQUENCE";
            case DEPENDENCIES -> "SELECT * FROM ALL_DEPENDENCIES WHERE OWNER = " + owner + " AND NAME = " + literal(name);
            case DEPENDENTS -> "SELECT * FROM ALL_DEPENDENCIES WHERE PARENT_OBJ_OWNER = " + owner + " AND PARENT_OBJ_NAME = " + literal(name);
            case TRIGGERS -> "SELECT * FROM ALL_TRIGGERS WHERE TABLE_OWNER = " + owner + " AND TABLE_NAME = " + literal(name);
            case SYNONYMS -> "SELECT * FROM ALL_SYNONYMS WHERE OWNER IN (" + owner + ", 'PUBLIC') ORDER BY OWNER, SYNONYM_NAME";
            case SEQUENCES -> "SELECT * FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = " + owner + " ORDER BY SEQUENCE_NAME";
            case PRIVILEGES -> "SELECT * FROM ALL_TBL_PRIVS WHERE OWNER = " + owner + " AND TABLE_NAME = " + literal(name);
        };
    }
}
