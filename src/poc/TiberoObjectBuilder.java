package poc;

import com.intellij.database.dialects.generic.model.GenericPackage;
import com.intellij.database.dialects.sql92.Sql92ObjectBuilder;
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
 * The generic SQL builder keeps routine and view statements as sources but drops package text.
 * An edited package source (specification, "/", body) must survive so it can be submitted.
 */
public final class TiberoObjectBuilder implements SqlObjectBuilder {
    private final SqlObjectBuilder generic=new Sql92ObjectBuilder();
    @Override public TextRange getCompactDefinition(ObjectPath path,CompositeText text,PsiObject object) { return generic.getCompactDefinition(path,text,object); }
    @Override public void build(SqlElement element,Context context) { generic.build(element,context); }
    @Override public void finalize(BasicModElement element) { generic.finalize(element); }
    @Override public void materializeInlineReferences(BasicHierarchicalObject object) { generic.materializeInlineReferences(object); }
    @Override public void build(BasicModElement element,DasObject object,Context context) {
        generic.build(element,object,context);
        if(element instanceof GenericPackage pkg && object instanceof PsiObject psi && !context.isStubBuilder()) {
            String text=packageSource(psi);
            if(text!=null) ((com.intellij.database.model.basic.BasicModSourceAware)pkg).setSourceText(new SimpleCompositeText(text,CompositeText.Kind.ORIGINAL_TEXT));
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
