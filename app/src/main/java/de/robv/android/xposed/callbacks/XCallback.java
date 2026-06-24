package de.robv.android.xposed.callbacks;
import de.robv.android.xposed.IXposedMod;
public abstract class XCallback implements IXposedMod {
    public XCallback() {}
    public int getPriority() { return 50; }
}
