package poc;

import com.intellij.database.dialects.base.generator.AbstractScriptGenerator;
import com.intellij.database.dialects.base.generator.ScriptGeneratorHelper;
import com.intellij.database.dialects.base.generator.ScriptingContext;
import com.intellij.database.dialects.base.generator.producers.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.generic.generator.GenericScriptGeneratorHelper;
import com.intellij.database.dialects.generic.model.GenericPackage;
import com.intellij.database.dialects.generic.model.GenericRoutine;
import com.intellij.database.model.ElementAlteration;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.BasicElement;
import com.intellij.database.model.basic.BasicSourceAware;
import com.intellij.database.model.meta.BasicMetaId;
import com.intellij.database.model.properties.CompositeText;
import java.util.Set;
import kotlin.Unit;

/**
 * Generic scripting with Oracle-style source submission: an edited routine or package is replaced
 * by its new CREATE OR REPLACE text. Generic only emits "alter routine definition is not implemented".
 */
public final class TiberoScriptGenerator extends AbstractScriptGenerator {
    private final ScriptGeneratorHelper helper=new Helper();
    public TiberoScriptGenerator() { super(GenericDbms.TIBERO); }
    @Override public ScriptGeneratorHelper getGeneratorHelper() { return helper; }

    static final class Helper extends ScriptGeneratorHelper {
        private final GenericScriptGeneratorHelper generic=new GenericScriptGeneratorHelper(GenericDbms.TIBERO);
        Helper() { super(GenericDbms.TIBERO); }
        @Override protected Set<ObjectKind> getSupportsCreateOrReplace() {
            return Set.of(ObjectKind.ROUTINE,ObjectKind.PACKAGE,ObjectKind.BODY,ObjectKind.VIEW,ObjectKind.TRIGGER);
        }
        // PL/SQL ends with "END;", so statements are separated as in Oracle scripts.
        @Override public String getStatementDelimiterStr() { return "/"; }
        @Override public boolean getStatementDelimiterOwnsLine() { return true; }
        @Override public CreateProducer<?> createProducerImpl(ScriptingContext context,BasicElement element) {
            return inPackage(element) ? new Skip.Create(context,element) : generic.createProducerImpl(context,element);
        }
        @Override public DropProducer<?> createDropProducerImpl(ScriptingContext context,BasicElement element) {
            return inPackage(element) ? new Skip.Drop(context,element) : generic.createDropProducerImpl(context,element);
        }
        @SuppressWarnings({"unchecked","rawtypes"})
        @Override public AlterProducerBase<?> createAlterProducerImpl(ScriptingContext context,ElementAlteration<?> elements) {
            var source=elements.getSourceElement();
            if(inPackage(source)) return new Skip.Alter(context,elements);
            if(source instanceof GenericRoutine) return new ReplaceRoutine(context,(ElementAlteration)elements);
            if(source instanceof GenericPackage) return new ReplacePackage(context,(ElementAlteration)elements);
            return generic.createAlterProducerImpl(context,elements);
        }
    }

    /** Package members and their arguments only exist inside the package source; never script them alone. */
    static boolean inPackage(BasicElement element) {
        for(var e=element; e!=null; e=e.getParent()) if(e instanceof GenericPackage) return e!=element;
        return false;
    }
    static final class Skip {
        static final class Create extends CreateProducerBase<BasicElement> {
            Create(ScriptingContext context,BasicElement element) { super(context,element); }
            @Override public void produceCreate() {}
            @Override public void produceComment(boolean exists,String comment) {}
        }
        static final class Drop extends DropProducerBase<BasicElement> {
            Drop(ScriptingContext context,BasicElement element) { super(context,element); }
            @Override public void produce() {}
        }
        static final class Alter extends AlterProducerBase<BasicElement> {
            @SuppressWarnings("unchecked") Alter(ScriptingContext context,ElementAlteration<?> elements) { super(context,(ElementAlteration<? extends BasicElement>)elements); }
            @Override protected void computeFlags() {}
        }
    }
    static boolean sameSource(BasicSourceAware a,BasicSourceAware b) {
        var x=a.getSourceText(); var y=b.getSourceText();
        return y==null || x!=null && TiberoSourceText.statements(x.getText()).equals(TiberoSourceText.statements(y.getText()));
    }

    static final class ReplaceRoutine extends AlterRoutine<GenericRoutine> {
        ReplaceRoutine(ScriptingContext context,ElementAlteration<? extends GenericRoutine> elements) { super(context,elements); }
        @Override protected void produceAlterDefinition() { produceCreateOrReplace(true); }
    }

    static final class ReplacePackage extends AlterProducerBase<GenericPackage> {
        ReplacePackage(ScriptingContext context,ElementAlteration<? extends GenericPackage> elements) {
            super(context,elements);
            getFlags().add(SourceAwareProducersKt.getDEFINITION_FLAG_ID(),
                    !isAnyRename() && !sameSource((BasicSourceAware)getElement(),(BasicSourceAware)getTo()));
        }
        @Override public void produceFlag(BasicMetaId id,Set<BasicMetaId> produced) {
            if(!id.equals(SourceAwareProducersKt.getDEFINITION_FLAG_ID())) { super.produceFlag(id,produced); return; }
            // Specification and body are separate statements; the generic producer would send them as one.
            for(String statement:TiberoSourceText.statements(((BasicSourceAware)getTo()).getSourceText().getText()))
                newCoding(coding -> { coding.appendSimple(statement,CompositeText.Kind.ORIGINAL_TEXT,null); return Unit.INSTANCE; });
        }
    }
}
