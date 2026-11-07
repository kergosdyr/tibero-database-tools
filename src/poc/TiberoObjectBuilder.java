package poc;


import com.intellij.database.dialects.oracle.OraObjectBuilder;
import com.intellij.database.model.DasObject;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.PsiObject;
import com.intellij.database.model.SqlObjectBuilder;
import com.intellij.database.model.basic.BasicHierarchicalObject;
import com.intellij.database.model.basic.BasicModElement;
import com.intellij.database.model.properties.CompositeText;
import com.intellij.database.script.SimpleCompositeText;
import com.intellij.database.util.ObjectPath;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.sql.psi.SqlElement;
import com.intellij.sql.psi.SqlFile;
import com.intellij.sql.psi.SqlStatement;
import java.util.ArrayList;

/**
 * Retains complete editable Tibero statements while using the public Oracle-compatible model builder.
 */
public final class TiberoObjectBuilder implements SqlObjectBuilder {
    private final SqlObjectBuilder modelBuilder=new OraObjectBuilder();
    @Override public TextRange getCompactDefinition(ObjectPath path,CompositeText text,PsiObject object) { return modelBuilder.getCompactDefinition(path,text,object); }
    @Override public void build(SqlElement element,Context context) { modelBuilder.build(element,context); }
    @Override public void finalize(BasicModElement element) { modelBuilder.finalize(element); }
    @Override public void materializeInlineReferences(BasicHierarchicalObject object) { modelBuilder.materializeInlineReferences(object); }
    @Override public void build(BasicModElement element,DasObject object,Context context) {
        modelBuilder.build(element,object,context);
        if(element.getKind()==ObjectKind.PACKAGE && object instanceof PsiObject psi && !context.isStubBuilder()) {
            String text=packageSource(psi);
            if(text!=null) ((com.intellij.database.model.basic.BasicModSourceAware)element).setSourceText(new SimpleCompositeText(text,CompositeText.Kind.ORIGINAL_TEXT));
        }
        else if((element.getKind()==ObjectKind.ROUTINE || element.getKind()==ObjectKind.VIEW)
                && element instanceof com.intellij.database.model.basic.BasicModSourceAware source
                && object instanceof PsiObject psi && !context.isStubBuilder()) {
            var statement=PsiTreeUtil.getParentOfType(psi,SqlStatement.class,false);
            if(statement!=null) source.setSourceText(new SimpleCompositeText(statement.getText().strip(),CompositeText.Kind.ORIGINAL_TEXT));
        }
    }
    /** Specification and body statements of the same package in this file, joined as TiberoViewLoader loads them. */
    static String packageSource(PsiObject object) {
        if(!(object.getContainingFile() instanceof SqlFile file)) return null;
        var parts=new ArrayList<String>();
        for(var kind:new ObjectKind[]{ObjectKind.PACKAGE,ObjectKind.BODY})
            for(var ddl:file.getDdl())
                if(ddl instanceof DasObject das && das.getKind()==kind && object.getName().equals(das.getName())) {
                    var statement=PsiTreeUtil.getParentOfType(ddl,SqlStatement.class,false);
                    parts.add((statement==null ? ddl : statement).getText().strip());
                }
        return parts.isEmpty() ? null : String.join("\n/\n",parts);
    }
}
