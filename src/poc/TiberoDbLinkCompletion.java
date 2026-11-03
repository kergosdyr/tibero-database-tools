package poc;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.database.dialects.generic.GenericDbms;
import com.intellij.database.util.DasUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.sql.psi.SqlPsiFacade;
import java.util.*;
import java.util.regex.Pattern;

/** Completes @link and tables before @link using durable catalogues and mapped IDE metadata. */
public final class TiberoDbLinkCompletion extends CompletionContributor {
    private static final Pattern LINK_PREFIX=Pattern.compile("@\\s*([A-Za-z0-9_$#.]*)$");
    private static final Pattern TABLE_PREFIX=Pattern.compile("(?i)(?:\\bFROM|\\bJOIN|\\bUPDATE|\\bINTO)\\s+(?:("+TiberoDbLinkResolve.IDENT+")\\s*\\.\\s*)?([A-Za-z0-9_$#]*)$");
    private static final Pattern SUFFIX=Pattern.compile("^\\s*@\\s*("+TiberoDbLinkResolve.LINK+")(?![A-Za-z0-9_$#])");
    @Override public void fillCompletionVariants(CompletionParameters parameters,CompletionResultSet result) {
        var position=parameters.getPosition();
        for(PsiElement p=position;p!=null && !(p instanceof PsiFile);p=p.getParent())
            if(p instanceof PsiComment || p.getNode()!=null && p.getNode().getElementType().toString().matches("(?i).*(STRING|COMMENT).*")) return;
        int offset=parameters.getOffset(); String text=parameters.getEditor().getDocument().getText();
        String before=text.substring(Math.max(0,offset-2048),offset),after=text.substring(offset,Math.min(text.length(),offset+512));
        var linkPrefix=LINK_PREFIX.matcher(before); var tablePrefix=TABLE_PREFIX.matcher(before); var suffix=SUFFIX.matcher(after);
        boolean link=linkPrefix.find(),table=!link && tablePrefix.find() && suffix.find();
        if(!link && !table) return;
        String prefix=link?linkPrefix.group(1):tablePrefix.group(2);
        var output=result.withPrefixMatcher(prefix); var seen=new HashSet<String>(); int count=0;
        for(var source:SqlPsiFacade.getInstance(position.getProject()).getDataSources(parameters.getOriginalFile())) {
            if(!GenericDbms.TIBERO.eq(source.getDbms())) continue;
            if(link) {
                for(var entry:TiberoDbLinkResolve.links(source)) if(seen.add(entry.name())) {
                    output.addElement(LookupElementBuilder.create(entry.name()).withIcon(icons.DatabaseIcons.DatabaseLink).withTypeText("DB LINK",true));
                    if(++count>=500) return;
                }
            } else {
                String schema=tablePrefix.group(1)==null?null:TiberoDbLinkResolve.name(tablePrefix.group(1));
                var target=TiberoDbLinkResolve.target(source,TiberoDbLinkResolve.name(suffix.group(1)),schema);
                if(target==null) continue;
                for(String name:target.source().getNameIndex().getAllNames()) {
                    ProgressManager.checkCanceled();
                    if(!name.regionMatches(true,0,prefix,0,prefix.length())) continue;
                    for(var object:TiberoDbLinkResolve.objects(target,name)) if(seen.add(name)) {
                        var psi=target.source().findElement(object);
                        String insertion=name.matches("[A-Z_][A-Z0-9_$#]*")?name:TiberoDbLinkActions.quote(name);
                        output.addElement(LookupElementBuilder.create(psi==null?object:psi,insertion).withLookupString(name).withTypeText(target.schema()+" · "+target.source().getName(),true));
                        if(++count>=500) return;
                    }
                }
            }
        }
    }
}
