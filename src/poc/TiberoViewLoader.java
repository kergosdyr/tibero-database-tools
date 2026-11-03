package poc;

import com.intellij.database.dataSource.DatabaseConnectionCore;
import com.intellij.database.jdbc.JdbcSourceLoader;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.DasRoutine;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.BasicModSourceAware;
import com.intellij.database.util.DasUtil;
import com.intellij.database.util.DbImplUtilCore.ConcatenationProps;
import com.intellij.openapi.util.Pair;

/** Loads sources through the existing IDE source-loader pipeline in the editable form of TiberoSourceText. */
public final class TiberoViewLoader extends JdbcSourceLoader {
    public TiberoViewLoader() { super(GenericDbms.TIBERO); }
    @Override public boolean supportsViewDefinition() { return true; }
    @Override public boolean supportsProcedureDefinition() { return true; }
    @Override public boolean supportsPackageDefinition() { return true; }

    public static String viewSql(String schema, String name) {
        if (schema == null || schema.isEmpty()) throw new IllegalArgumentException("Schema is required");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("View name is required");
        return "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = '" + schema.replace("'", "''")
                + "' AND VIEW_NAME = '" + name.replace("'", "''") + "'";
    }

    @Override public Pair<String, ConcatenationProps> sqlViewDefinition(DasObject view) {
        return Pair.create(viewSql(DasUtil.getSchema(view), view.getName()), ConcatenationProps.NO_CONCAT);
    }
    @Override public Pair<String, ConcatenationProps> sqlProcedureDefinition(DasRoutine routine) {
        return source(routine, routine.getRoutineKind().name());
    }
    @Override public Pair<String, ConcatenationProps> sqlPackageDefinition(DasObject object) { return source(object, "PACKAGE"); }
    @Override public Pair<String, ConcatenationProps> sqlPackageBodyDefinition(DasObject object) { return source(object, "PACKAGE BODY"); }
    @Override public void loadSource(BasicModSourceAware object, DatabaseConnectionCore connection) throws Exception {
        var parent=DasUtil.getKind(object.getParent());
        String schema=DasUtil.getSchema(object), text;
        if(parent==ObjectKind.SCHEMA && object.getKind()==ObjectKind.ROUTINE && object instanceof DasRoutine routine)
            text=TiberoSourceText.ddl(schema,read(connection,sqlProcedureDefinition(routine)));
        else if(parent==ObjectKind.SCHEMA && object.getKind()==ObjectKind.PACKAGE)
            text=TiberoSourceText.packageSource(TiberoSourceText.ddl(schema,read(connection,sqlPackageDefinition(object))),
                    TiberoSourceText.ddl(schema,read(connection,sqlPackageBodyDefinition(object))));
        else if(parent==ObjectKind.SCHEMA && object.getKind()==ObjectKind.VIEW)
            text=TiberoSourceText.view(schema,object.getName(),read(connection,sqlViewDefinition(object)));
        else { super.loadSource(object,connection); return; }
        applySourceText(object,text.isEmpty() ? null : text,false);
    }
    private static String read(DatabaseConnectionCore connection,Pair<String,ConcatenationProps> query) throws java.sql.SQLException,java.rmi.RemoteException {
        var text=new StringBuilder();
        for(var row:TiberoRoutineReader.query(connection,query.first,1)) if(row[0]!=null) text.append(row[0]);
        return text.toString();
    }
    private static Pair<String, ConcatenationProps> source(DasObject object, String type) {
        return Pair.create(TiberoCatalog.sourceSql(DasUtil.getSchema(object), object.getName(), type), ConcatenationProps.TIGHT);
    }
}
