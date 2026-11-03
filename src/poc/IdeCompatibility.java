package poc;

import com.intellij.database.introspection.DBIntrospector;
import com.intellij.database.plan.PlanModel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Only the API seams that changed between the verified IDE releases. */
final class IdeCompatibility {
    static void setRawData(PlanModel model,String json) {
        Method method=optional(PlanModel.class,"setRawData",String.class);
        if(method!=null) invoke(method,model,json);
        // Older native models have no raw-data property; raw plan JSON still loads through TiberoPlan.
    }
    static void report(DBIntrospector introspector,String message,Throwable failure) {
        try {
            Method getter=DBIntrospector.class.getMethod("getErrorSink");
            Object sink=invoke(getter,introspector);
            Class<?> sinkType=getter.getReturnType();
            Method method=optional(sinkType,"accept",Object.class,Object.class);
            if(method==null) method=sinkType.getMethod("consume",Object.class,Object.class);
            invoke(method,sink,message,failure);
        } catch(ReflectiveOperationException e) { throw new IllegalStateException("Unsupported introspection error sink",e); }
    }
    static Method optional(Class<?> type,String name,Class<?>... parameters) {
        try { return type.getMethod(name,parameters); } catch(NoSuchMethodException absent) { return null; }
    }
    static Object invoke(Method method,Object target,Object... args) {
        try { return method.invoke(target,args); }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException runtime) throw runtime;
            if(e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(e.getCause());
        } catch(IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
