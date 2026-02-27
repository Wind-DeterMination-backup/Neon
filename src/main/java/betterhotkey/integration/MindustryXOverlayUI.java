package betterhotkey.integration;

import arc.func.Prov;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;

import java.lang.reflect.Method;

/** Optional MindustryX OverlayUI integration via reflection (no hard dependency). */
public class MindustryXOverlayUI {
    private boolean initialized;
    private boolean installed;

    private Object instance;
    private Method registerWindow;

    private Method setAvailability;
    private Method getData;
    private Method setEnabled;
    private Method setPinned;
    private Method setResizable;
    private Method setAutoHeight;

    public boolean isInstalled() {
        if (initialized) return installed;
        initialized = true;

        try {
            installed = Vars.mods != null
                && (Vars.mods.locateMod("mindustryx") != null || Vars.mods.locateMod("mdtx") != null);
        } catch (Throwable ignored) {
            installed = false;
        }

        if (!installed) return false;

        try {
            Class<?> c = Class.forName("mindustryX.features.ui.OverlayUI");
            instance = c.getField("INSTANCE").get(null);
            registerWindow = c.getMethod("registerWindow", String.class, Table.class);
        } catch (Throwable t) {
            installed = false;
            Log.err("betterHotKey: MindustryX detected but OverlayUI reflection init failed.", t);
            return false;
        }

        return true;
    }

    public Object registerWindow(String name, Table table, Prov<Boolean> availability) {
        if (!isInstalled()) return null;
        try {
            Object window = registerWindow.invoke(instance, name, table);
            tryInitWindowAccessors(window);
            if (window != null && availability != null && setAvailability != null) {
                setAvailability.invoke(window, availability);
            }
            return window;
        } catch (Throwable t) {
            Log.err("betterHotKey: OverlayUI.registerWindow failed.", t);
            return null;
        }
    }

    public void tryConfigureWindow(Object window, boolean autoHeight, boolean resizable) {
        if (window == null) return;
        try {
            tryInitWindowAccessors(window);
            if (setAutoHeight != null) setAutoHeight.invoke(window, autoHeight);
            if (setResizable != null) setResizable.invoke(window, resizable);
        } catch (Throwable ignored) {
        }
    }

    public void setEnabledAndPinned(Object window, boolean enabled, boolean pinned) {
        if (window == null) return;
        try {
            tryInitWindowAccessors(window);
            if (getData == null) return;
            Object data = getData.invoke(window);
            if (data == null) return;
            if (setEnabled != null) setEnabled.invoke(data, enabled);
            if (setPinned != null) setPinned.invoke(data, pinned);
        } catch (Throwable ignored) {
        }
    }

    private void tryInitWindowAccessors(Object window) {
        if (window == null) return;
        if (getData != null || setAvailability != null) return;
        try {
            Class<?> wc = window.getClass();

            try {
                setAvailability = wc.getMethod("setAvailability", Prov.class);
            } catch (Throwable ignored) {
                setAvailability = null;
            }

            try {
                setResizable = wc.getMethod("setResizable", boolean.class);
            } catch (Throwable ignored) {
                setResizable = null;
            }

            try {
                setAutoHeight = wc.getMethod("setAutoHeight", boolean.class);
            } catch (Throwable ignored) {
                setAutoHeight = null;
            }

            getData = wc.getMethod("getData");

            Object data = getData.invoke(window);
            if (data != null) {
                Class<?> dc = data.getClass();
                try {
                    setEnabled = dc.getMethod("setEnabled", boolean.class);
                } catch (Throwable ignored) {
                    setEnabled = null;
                }
                try {
                    setPinned = dc.getMethod("setPinned", boolean.class);
                } catch (Throwable ignored) {
                    setPinned = null;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
