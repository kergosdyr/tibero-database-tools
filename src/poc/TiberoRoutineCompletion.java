package poc;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.model.*;
import com.intellij.database.util.*;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.sql.psi.SqlPsiFacade;
import java.util.*;
import java.util.regex.Pattern;

/** Qualified package completion reads the IDE's model index, never the database. */
public final class TiberoRoutineCompletion extends CompletionContributor {
    private static final String IDENT="(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$#]*)";
    private static final Pattern QUALIFIED=Pattern.compile("(?<![A-Za-z0-9_$#\".])(?:([A-Za-z_][A-Za-z0-9_$#]*|\"(?:[^\"]|\"\")+\")\\s*\\.\\s*)?("+IDENT+")\\s*\\.\\s*([A-Za-z0-9_$#]*)$");
    record Context(String schema,String pkg,String prefix) {}
    static Context context(String text) {
        var m=QUALIFIED.matcher(text);
        if(!m.find()) return null;
        return new Context(m.group(1),m.group(2),m.group(3));
    }
    static boolean matches(String token,String name) {
        return token.startsWith("\"") ? token.substring(1,token.length()-1).replace("\"\"","\"").equals(name)
                : token.toUpperCase(Locale.ROOT).equals(name);
    }
    static List<DasRoutine> candidates(DasObject pkg,Context context) {
        if(DasUtil.getKind(pkg)!=ObjectKind.PACKAGE || !matches(context.pkg(),pkg.getName())
                || context.schema()!=null && !matches(context.schema(),DasUtil.getSchema(pkg))) return List.of();
        var result=new ArrayList<DasRoutine>();
        for(var child:pkg.getDasChildren(ObjectKind.ROUTINE)) {
            ProgressManager.checkCanceled();
            if(child instanceof DasRoutine routine && routine.getName().regionMatches(true,0,context.prefix(),0,context.prefix().length())) result.add(routine);
            if(result.size()>=500) break;
        }
        return result;
    }
    static LookupElementBuilder lookup(DasRoutine routine) {
        var params=new ArrayList<String>();
        String returns="";
        for(var a:routine.getArguments()) {
            String type=a.getDasType()==null?"?":a.getDasType().getSpecification();
            if(a.getArgumentDirection()==ArgumentDirection.RETURN) returns=type;
            else params.add(a.getName()+" "+a.getArgumentDirection()+" "+type);
        }
        String name=routine.getName();
        String insertion=name.matches("[A-Z_][A-Z0-9_$#]*")?name:"\""+name.replace("\"","\"\"")+"\"";
        return LookupElementBuilder.create(routine,insertion).withLookupString(name).withPresentableText(name)
                .withTailText("("+String.join(", ",params)+")",true)
                .withTypeText(returns.isEmpty()?"PROCEDURE":returns,true);
    }
    @Override public void fillCompletionVariants(CompletionParameters parameters,CompletionResultSet result) {
        var position=parameters.getPosition();
        for(PsiElement e=position;e!=null && !(e instanceof PsiFile);e=e.getParent()) {
            if(e instanceof PsiComment || e.getNode()!=null && e.getNode().getElementType().toString().matches("(?i).*(STRING|COMMENT).*")) return;
        }
        int offset=parameters.getOffset(); var document=parameters.getEditor().getDocument();
        String before=document.getCharsSequence().subSequence(Math.max(0,offset-2048),offset).toString();
        var context=context(before); if(context==null) return;
        var output=result.withPrefixMatcher(context.prefix()); int count=0;
        for(var source:SqlPsiFacade.getInstance(position.getProject()).getDataSources(parameters.getOriginalFile())) {
            if(!GenericDbms.TIBERO.eq(source.getDbms())) continue;
            String name=context.pkg().startsWith("\"")?context.pkg().substring(1,context.pkg().length()-1).replace("\"\"","\""):context.pkg().toUpperCase(Locale.ROOT);
            for(var object:source.getNameIndex().getObjectsByName(name))
                for(var routine:candidates(object,context)) {
                    output.addElement(lookup(routine)); if(++count>=500) return;
                }
        }
    }
}
