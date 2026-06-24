package de.robv.android.xposed.callbacks;
import de.robv.android.xposed.IXposedMod;
public class XC_LoadPackage extends XCallback {
    public static class LoadPackageParam {
        public String packageName; public String processName;
        public ClassLoader classLoader; public boolean isFirstApplication;
    }
    public XC_LoadPackage() { super(); }
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {}
    public static abstract class XCallback implements IXposedMod {
        public XCallback() {} public int getPriority() { return 50; }
    }
}
