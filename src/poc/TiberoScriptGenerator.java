package poc;

import com.intellij.database.dialects.base.generator.AbstractScriptGenerator;
import com.intellij.database.dialects.base.generator.ScriptGeneratorHelper;
import com.intellij.database.dialects.base.generator.ScriptingContext;
import com.intellij.database.dialects.base.generator.producers.*;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.dialects.oracle.generator.OraScriptGeneratorHelper;

import com.intellij.database.model.basic.BasicRoutine;
import com.intellij.database.model.ElementAlteration;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.model.basic.BasicElement;
import com.intellij.database.model.basic.BasicSourceAware;
import com.intellij.database.model.meta.BasicMetaId;
import com.intellij.database.model.properties.CompositeText;
import java.util.Set;
import kotlin.Unit;

/**
 * Source submission keeps the complete CREATE OR REPLACE text for Tibero routines, packages and views.
 */
public final class TiberoScriptGenerator extends AbstractScriptGenerator {
    private final ScriptGeneratorHelper helper=new Helper();
    public TiberoScriptGenerator() { super(GenericDbms.TIBERO); }
    @Override public ScriptGeneratorHelper getGeneratorHelper() { return helper; }

    static final class Helper extends ScriptGeneratorHelper {
        private final OraScriptGeneratorHelper nativeHelper=OraScriptGeneratorHelper.INSTANCE;
        Helper() { super(GenericDbms.TIBERO); }
        @Override protected Set<ObjectKind> getSupportsCreateOrReplace() {
            return Set.of(ObjectKind.ROUTINE,ObjectKind.PACKAGE,ObjectKind.BODY,ObjectKind.VIEW,ObjectKind.TRIGGER);
        }
        // PL/SQL ends with "END;", so statements are separated as in Oracle scripts.
        @Override public String getStatementDelimiterStr() { return "/"; }
        @Override public boolean getStatementDelimiterOwnsLine() { return true; }
        @Override public CreateProducer<?> createProducerImpl(ScriptingContext context,BasicElement element) {
            var link=TiberoDatabaseTree.link(element);
            if(link!=null) return new CreateProducerBase<BasicElement>(context,element) {
                @Override public void produceCreate() {
                    newCoding(coding -> { coding.appendSimple(TiberoDbLinkActions.ddl(link.entry()),CompositeText.Kind.ORIGINAL_TEXT,null); return Unit.INSTANCE; });
                }
            };
            return isSourceMember(element) ? new Skip.Create(context,element) : nativeHelper.createProducerImpl(context,element);
        }
        @Override public DropProducer<?> createDropProducerImpl(ScriptingContext context,BasicElement element) {
            return isSourceMember(element) ? new Skip.Drop(context,element) : nativeHelper.createDropProducerImpl(context,element);
        }
        @SuppressWarnings({"unchecked","rawtypes"})
        @Override public AlterProducerBase<?> createAlterProducerImpl(ScriptingContext context,ElementAlteration<?> elements) {
            var source=elements.getSourceElement();
            if(isSourceMember(source)) return new Skip.Alter(context,elements);
            if(source instanceof BasicRoutine || source.getKind()==ObjectKind.VIEW) return new ReplaceSource(context,(ElementAlteration)elements);
            if(source.getKind()==ObjectKind.PACKAGE) return new ReplaceSource(context,(ElementAlteration)elements);
            return nativeHelper.createAlterProducerImpl(context,elements);
        }
    }

    /** Routine arguments, package members and view columns belong to their containing source statement. */
    static boolean isSourceMember(BasicElement element) {
        for(var e=element.getParent(); e!=null; e=e.getParent())
            if(e.getKind()==ObjectKind.PACKAGE || e.getKind()==ObjectKind.ROUTINE || e.getKind()==ObjectKind.VIEW) return true;
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

    static final class ReplaceSource extends AlterProducerBase<BasicElement> {
        ReplaceSource(ScriptingContext context,ElementAlteration<? extends BasicElement> elements) {
            super(context,elements);
            getFlags().add(SourceAwareProducersKt.getDEFINITION_FLAG_ID(),
                    !isAnyRename() && !sameSource((BasicSourceAware)getElement(),(BasicSourceAware)getTo()));
        }
        @Override public void produceFlag(BasicMetaId id,Set<BasicMetaId> produced) {
            if(!id.equals(SourceAwareProducersKt.getDEFINITION_FLAG_ID())) { super.produceFlag(id,produced); return; }
            // Specification and body are submitted as separate statements.
            for(String statement:TiberoSourceText.statements(((BasicSourceAware)getTo()).getSourceText().getText()))
                newCoding(coding -> { coding.appendSimple(statement,CompositeText.Kind.ORIGINAL_TEXT,null); return Unit.INSTANCE; });
        }
    }
}
