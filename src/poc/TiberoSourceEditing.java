package poc;

import com.intellij.database.DbmsExtension;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import java.util.List;
import java.util.function.Supplier;

/**
 * Source editor Submit (TiberoScriptGenerator, TiberoObjectBuilder) relies on IDE-internal scripting classes.
 * They are registered only on the release line where that path was verified; older IDEs keep generic scripting.
 */
public final class TiberoSourceEditing implements Disposable {
    static final int VERIFIED_BASELINE=262;
    public TiberoSourceEditing() {
        if(!supported()) return;
        try {
            register("com.intellij.database.scriptGenerator",TiberoScriptGenerator::new);
            register("com.intellij.database.sqlObjectBuilder",TiberoObjectBuilder::new);
        } catch(LinkageError | RuntimeException failure) {
            Logger.getInstance(TiberoSourceEditing.class).warn("Tibero source editing disabled: scripting API differs",failure);
        }
    }
    public static final class Startup implements com.intellij.ide.AppLifecycleListener {
        @Override public void appFrameCreated(List<String> args) { install(); }
    }
    static void install() { ApplicationManager.getApplication().getService(TiberoSourceEditing.class); }
    static boolean supported() { return ApplicationInfo.getInstance().getBuild().getBaselineVersion()>=VERIFIED_BASELINE; }
    private <T> void register(String point,Supplier<T> factory) {
        T instance=factory.get();
        var bean=new DbmsExtension.Bean<Object>() { @Override public Object getInstance() { return instance; } };
        bean.dbmsStr="TIBERO"; bean.implementationClass=instance.getClass().getName();
        ExtensionPointName.<DbmsExtension.Bean<Object>>create(point).getPoint().registerExtension(bean,this);
    }
    @Override public void dispose() {}
}
