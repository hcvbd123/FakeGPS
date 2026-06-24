package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XposedBridge {
    public static void log(String text) { android.util.Log.i("Xposed", text); }
    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {}
}
