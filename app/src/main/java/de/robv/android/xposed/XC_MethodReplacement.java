package de.robv.android.xposed;
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public XC_MethodReplacement() {}
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try { param.setResult(replaceHookedMethod(param)); }
        catch (Throwable t) { param.setThrowable(t); }
    }
    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
