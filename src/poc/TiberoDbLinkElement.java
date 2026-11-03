package poc;

import com.intellij.database.model.*;
import com.intellij.database.psi.*;

/** A durable catalogue entry exposed to SQL PSI without changing the shared Generic model. */
final class TiberoDbLinkElement extends DbElementImpl<TiberoDbLinkElement.Definition> {
    record Definition(String sourceId,TiberoDbLinks.Entry entry) implements DasObject {
        @Override public String getName() { return entry.name(); }
        @Override public ObjectKind getKind() { return ObjectKind.DB_LINK; }
        @Override public boolean isQuoted() { return false; }
    }
    private final DbDataSourceImpl source;
    TiberoDbLinkElement(DbDataSource source,TiberoDbLinks.Entry entry) {
        super(new Definition(source.getUniqueId(),entry));
        this.source=(DbDataSourceImpl)source;
    }
    @Override public DbDataSourceImpl getDataSource() { return source; }
    @Override public com.intellij.database.Dbms getDbms() { return source.getDbms(); }
    @Override public DbElement getParent() { return source; }
    @Override public com.intellij.openapi.project.Project getProject() { return source.getProject(); }
    @Override public ObjectKind getKind() { return ObjectKind.DB_LINK; }
    @Override public String getName() { return getDelegate().entry().name(); }
    @Override public String getName(com.intellij.psi.PsiElement context) { return getName(); }
    @Override public String getComment() { return getDelegate().entry().host(); }
    @Override public boolean isQuoted() { return false; }
    @Override public boolean isWritable() { return false; }
    @Override public com.intellij.psi.PsiElement setName(String name) { throw new com.intellij.util.IncorrectOperationException("DB link definitions are read-only"); }
    @Override public com.intellij.lang.Language getLanguage() { return com.intellij.sql.dialects.oracle.OraDialect.INSTANCE; }
    @Override public com.intellij.psi.PsiElement getDeclaration() { return this; }
    @Override public com.intellij.util.containers.JBIterable<DbElement> getDasChildren(ObjectKind kind) { return com.intellij.util.containers.JBIterable.empty(); }
    @Override public com.intellij.psi.PsiElement[] getChildren() { return com.intellij.psi.PsiElement.EMPTY_ARRAY; }
    @Override public String getText() { return TiberoDbLinkActions.ddl(getDelegate().entry()); }
    @Override public com.intellij.openapi.vfs.VirtualFile getVirtualFile() { return null; }
    @Override public javax.swing.Icon getIcon(boolean open) { return icons.DatabaseIcons.DatabaseLink; }
    @Override public String getLocationString() { return source.getName(); }

    @Override public boolean canNavigate() { return isValid(); }
    @Override public boolean canNavigateToSource() { return isValid(); }
    @Override public void navigate(boolean requestFocus) { TiberoDbLinkActions.open(getProject(),getDelegate().entry()); }
    @Override public boolean isValid() {
        return getParent().isValid() && TiberoDbLinkResolve.links(getDataSource()).contains(getDelegate().entry());
    }

}
