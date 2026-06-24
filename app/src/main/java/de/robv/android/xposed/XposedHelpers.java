package de.robv.android.xposed;
import java.lang.reflect.Method;
public class XposedHelpers {
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... paramsAndCallback) {
        try {
            Class<?>[] paramTypes = new Class<?>[paramsAndCallback.length - 1];
            XC_MethodHook callback = null;
            for (int i = 0; i < paramsAndCallback.length; i++) {
                if (paramsAndCallback[i] instanceof XC_MethodHook) callback = (XC_MethodHook) paramsAndCallback[i];
                else if (paramsAndCallback[i] instanceof Class) paramTypes[i] = (Class<?>) paramsAndCallback[i];
            }
            Method m = clazz.getDeclaredMethod(methodName, paramTypes);
            XposedBridge.hookMethod(m, callback != null ? callback : new XC_MethodHook() {});
        } catch (NoSuchMethodException e) {
            XposedBridge.log("Method " + methodName + " not found in " + clazz.getName());
        }
    }
    public static void findAndHookMethod(String cn, ClassLoader cl, String mn, Object... params) {
        try { findAndHookMethod(Class.forName(cn, false, cl), mn, params); }
        catch (Exception e) { XposedBridge.log("findAndHookMethod failed: " + cn + "." + mn); }
    }
}
