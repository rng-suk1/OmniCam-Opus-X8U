package local.jiege.hook.watermark;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.LaunchModes;
import local.jiege.settings.ModuleSettings;
import org.json.JSONObject;
import java.lang.reflect.Method;

/** Camera preference, mode-switch and gallery-return adapters for WatermarkCore. */
public final class WatermarkHooks {
    private static final String TAG = "Watermark";
    private static final String STYLE_ACTION = "com.oplus.camera.ai.master.watermark.resource";
    private static final String EDIT_CONTEXT = "watermark.edit";
    private static final String EDITOR_ACTIVITY = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static CameraWatermarkStore store;
    /** The editor result stays with the group that opened it for this long after the camera is back in front. */
    private static final long EDITOR_RETURN_GRACE_MS = 15_000L;
    private static volatile String editorGroup;
    private static volatile long editorReturned;
    private static volatile String grStyle = "gr_style_1";
    private static final ThreadLocal<Boolean> grCapture = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> quickStatusGr = new ThreadLocal<>();
    private static final ThreadLocal<EditResult> editing = new ThreadLocal<>();
    private static final ThreadLocal<XC_MethodHook.MethodHookParam> loadingStyle = new ThreadLocal<>();
    private static volatile String policyKey;
    private static volatile WatermarkCore.Policy policy;

    private static final class EditResult {
        final String group;
        WatermarkCore.State state;
        boolean changed;

        EditResult(String group, WatermarkCore.State state) { this.group = group; this.state = state; }
    }

    private WatermarkHooks() {}

    public static void installCamera(ClassLoader loader) {
        Symbols symbols = Symbols.get();
        store = new CameraWatermarkStore(symbols, loader);
        Log.guard(TAG, "stored values", () -> hookStoredValues(symbols, loader));
        Log.guard(TAG, "quick status", () -> hookQuickStatus(symbols));
        Log.guard(TAG, "write ownership", () -> hookWrites(symbols));
        Log.guard(TAG, "mode memories", () -> hookModes(symbols));
        Log.guard(TAG, "capture state", () -> hookCapture(symbols));
        Log.guard(TAG, "settings summary", () -> hookSummary(symbols));
        Log.guard(TAG, "editor ownership", () -> hookEditor(symbols, loader));
        Log.guard(TAG, "style loading", () -> hookStyles(symbols, loader));
        Log.guard(TAG, "GR style rendering", () -> GrWatermarkRenderer.install(loader));
    }

    public static void installGallery(ClassLoader loader) {
        GrGalleryWatermark.install(loader);
        WatermarkFilterNames.installGallery(loader);
    }

    /** The modes chosen in 独立保存水印, plus XPAN while XPAN isolation is on. */
    static WatermarkCore.Policy policy() {
        boolean xpan = Settings.get(ModuleSettings.XPAN_WATERMARK_ISOLATION);
        String modes = Settings.getString(ModuleSettings.WATERMARK_MODES);
        String key = xpan + "|" + modes;
        if (!key.equals(policyKey)) {
            Set<String> photo = LaunchModes.watermarkModes(modes);
            if (xpan) photo.add(WatermarkCore.XPAN);
            policy = new WatermarkCore.Policy(photo, Collections.emptySet());
            policyKey = key;
        }
        return policy;
    }

    static boolean isGrMode() {
        try { return WatermarkCore.GR.equals(store.mode()); }
        catch (Throwable error) { throw new IllegalStateException("Camera watermark mode unavailable", error); }
    }

    static String grStyle() { return grStyle; }
    static void rememberGrStyle(String style) { grStyle = style; }
    static boolean editorIsGr() { return WatermarkCore.GR.equals(editorGroup()); }

    /** The group an editor result belongs to: the one that opened the editor while that request is open, else the current mode's. */
    private static String editorGroup() {
        String pinned = editorGroup;
        if (pinned != null) {
            long returned = editorReturned;
            if (returned == 0 || SystemClock.uptimeMillis() - returned <= EDITOR_RETURN_GRACE_MS) return pinned;
            editorGroup = null;
        }
        try { return policy().group(store.mode(), WatermarkCore.Channel.PHOTO); }
        catch (Throwable error) { throw new IllegalStateException("Camera watermark mode unavailable", error); }
    }

    private static void pinEditor() throws Throwable {
        editorGroup = policy().group(store.mode(), WatermarkCore.Channel.PHOTO);
        editorReturned = 0;
    }

    private static void closeEditorRequest() {
        editorGroup = null;
        editorReturned = 0;
    }
    static void receiveGrStyle(String style) throws Throwable {
        rememberGrStyle(style);
        if (editorIsGr()) store.grStyle(style);
    }

    private static boolean grContext() {
        EditResult edit = editing.get();
        return edit == null ? isGrMode() : WatermarkCore.GR.equals(edit.group);
    }

    private static String writeGroup() throws Throwable {
        EditResult edit = editing.get();
        return edit == null ? policy().group(store.mode(), WatermarkCore.Channel.PHOTO) : edit.group;
    }

    private static String caller() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("local.jiege") || name.startsWith("de.robv") || name.startsWith("LSPHooker") || name.startsWith("java.")) continue;
            out.append(name).append('.').append(frame.getMethodName()).append(' ');
            if (out.length() > 400) break;
        }
        return out.toString();
    }

    private static boolean holds(Object key, String name) throws IllegalAccessException {
        return Reflection.hasStringField(key, name);
    }

    private static String photoPreference(Object key) throws IllegalAccessException {
        for (String name : CameraWatermarkStore.PHOTO_PREFS) if (holds(key, name)) return name;
        return null;
    }

    private static boolean grPreference(Object key) throws IllegalAccessException {
        for (String name : CameraWatermarkStore.GR_PREFS) if (holds(key, name)) return true;
        return false;
    }

    private static void hookStoredValues(Symbols symbols, ClassLoader loader) {
        XC_MethodHook read = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (CameraWatermarkStore.rawAccess() || param.args[0] == null) return;
                Object key = param.args[0];
                Boolean statusGr = quickStatusGr.get();
                if (Boolean.TRUE.equals(statusGr) && holds(key, CameraWatermarkStore.PHOTO_OPEN)) {
                    param.setResult(Boolean.FALSE);
                } else if (Boolean.TRUE.equals(statusGr) && holds(key, CameraWatermarkStore.PHOTO_SWITCH)) {
                    // GR's menu item carries the photo key; its state is the GR switch.
                    param.setResult(store.grOn() ? "on" : "off");
                } else if (Boolean.TRUE.equals(grCapture.get()) && holds(key, CameraWatermarkStore.PHOTO_OPEN)) {
                    param.setResult(store.grOn());
                } else if (holds(key, CameraWatermarkStore.GR_STYLE)) {
                    Object value = param.getResult();
                    boolean gr = value != null && String.valueOf(value).startsWith("gr_");
                    if (grContext()) param.setResult(store.grOn() ? (gr ? value : grStyle) : "");
                    else if (gr) {
                        String backup = store.value(CameraWatermarkStore.GR_BACKUP, "");
                        param.setResult(backup.startsWith("gr_") ? "" : backup);
                    }
                } else if (holds(key, CameraWatermarkStore.MODE_NAME)) {
                    if (grContext()) param.setResult("gr");
                    else if ("gr".equals(param.getResult())) param.setResult("");
                } else if (holds(key, CameraWatermarkStore.GR_OPEN)) {
                    if (statusGr != null) param.setResult(statusGr && store.grOn());
                    else if (grContext()) param.setResult(store.grOn());
                } else if (holds(key, CameraWatermarkStore.GR_SWITCH)) {
                    if ((param.getResult() == null || "".equals(param.getResult())) && grContext()) param.setResult("on");
                }
                EditResult edit = editing.get();
                if (edit != null && edit.state != null) {
                    String name = photoPreference(key);
                    if (name != null) param.setResult(stateValue(edit.state, name));
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.get"), read);
        XposedBridge.hookMethod(symbols.method("DataManager.getOr"), read);
    }

    private static void hookQuickStatus(Symbols symbols) {
        final Method click = symbols.method("WatermarkMenu.click");
        XC_MethodHook scope = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String key = (String) symbols.call("WatermarkMenu.key", param.thisObject);
                if (!CameraWatermarkStore.PHOTO_SWITCH.equals(key) && !CameraWatermarkStore.GR_SWITCH.equals(key)) return;
                if (param.method.equals(click) && isGrMode()) {
                    // GR's menu item carries the photo key; the stock toggle would flip the photo switch,
                    // which GR ignores. Flip the GR switch and redraw the item instead.
                    store.grSwitch(store.grOn() ? "off" : "on");
                    symbols.call("WatermarkMenu.render", param.thisObject);
                    param.setResult(null);
                    return;
                }
                param.setObjectExtra("watermark.quickStatus", Boolean.TRUE);
                param.setObjectExtra("watermark.previousQuickStatus", quickStatusGr.get());
                quickStatusGr.set(isGrMode());
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!Boolean.TRUE.equals(param.getObjectExtra("watermark.quickStatus"))) return;
                Boolean previous = (Boolean) param.getObjectExtra("watermark.previousQuickStatus");
                if (previous == null) quickStatusGr.remove(); else quickStatusGr.set(previous);
            }
        };
        XposedBridge.hookMethod(symbols.method("WatermarkMenu.click"), scope);
        XposedBridge.hookMethod(symbols.method("WatermarkMenu.render"), scope);
    }

    private static Object stateValue(WatermarkCore.State state, String name) {
        if (CameraWatermarkStore.PHOTO_STYLE.equals(name)) return state.style;
        if (CameraWatermarkStore.PHOTO_BACKUP.equals(name)) return state.backup;
        if (CameraWatermarkStore.PHOTO_OPEN.equals(name)) return state.open;
        return state.capture;
    }

    private static WatermarkCore.State withValue(WatermarkCore.State state, String name, Object value) {
        return new WatermarkCore.State(CameraWatermarkStore.PHOTO_STYLE.equals(name) ? (String) value : state.style,
            CameraWatermarkStore.PHOTO_BACKUP.equals(name) ? (String) value : state.backup,
            CameraWatermarkStore.PHOTO_OPEN.equals(name) ? (Boolean) value : state.open,
            CameraWatermarkStore.PHOTO_SWITCH.equals(name) ? (String) value : state.capture);
    }

    private static void hookWrites(Symbols symbols) {
        XC_MethodHook write = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (CameraWatermarkStore.rawAccess() || param.args[0] == null) return;
                Object key = param.args[0];
                String photo = photoPreference(key);
                boolean gr = grPreference(key);
                if (photo == null && !gr) return;
                boolean grOwner = WatermarkCore.GR.equals(writeGroup());
                if (photo != null) Log.i(TAG, "DBG write " + photo + "=" + (param.args.length > 1 ? param.args[1] : "<remove>")
                    + " group=" + writeGroup() + " owner=" + store.owner() + " mode=" + store.mode() + " editing=" + (editing.get() != null)
                    + " thread=" + Thread.currentThread().getName() + " " + caller());
                EditResult edit = editing.get();
                if (grOwner && photo != null) {
                    // When the user opens the watermark editor from GR mode and picks a non-GR
                    // photo watermark (e.g. Leica / Hasselblad), don't silently drop the edit:
                    // record it against the current photo owner instead.
                    if (edit == null) { param.setResult(null); return; }
                    if (edit.state == null) {
                        synchronized (store) {
                            edit.state = store.live();
                        }
                    }
                } else if (!grOwner && gr) {
                    param.setResult(null);
                    return;
                }
                if (photo != null && edit != null && edit.state != null) {
                    Object value = param.args.length > 1 ? param.args[1]
                        : CameraWatermarkStore.PHOTO_OPEN.equals(photo) ? Boolean.FALSE
                        : CameraWatermarkStore.PHOTO_SWITCH.equals(photo) ? "off" : "";
                    edit.state = withValue(edit.state, photo, value);
                    edit.changed = true;
                    param.setResult(null);
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.put"), write);
        XposedBridge.hookMethod(symbols.method("DataManager.putSync"), write);
        XposedBridge.hookMethod(symbols.method("DataManager.remove"), write);
    }

    private static void hookModes(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String to = (String) param.args[1];
                if (to == null || to.isEmpty() || WatermarkCore.GR.equals(to)) return;
                synchronized (store) {
                    Log.i(TAG, "DBG switch " + param.args[0] + "->" + to + " owner=" + store.owner() + " live=" + store.live().style);
                    WatermarkCore.activate(store, policy().group(to, WatermarkCore.Channel.PHOTO));
                }
            }
        });
    }

    private static void hookCapture(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("WatermarkModel.isOpen"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(Boolean) param.args[0] && (Boolean) param.args[1]
                        && ("gr".equals(param.args[2]) || isGrMode())) grCapture.set(Boolean.TRUE);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) { grCapture.remove(); }
        });
    }

    private static void hookSummary(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("Preference.assignment"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!holds(param.thisObject, "pref_watermark_setting_key")) return;
                Context context = (Context) Reflection.findFieldByType(param.thisObject, "android.content.Context");
                WatermarkCore.State photo = store.live();
                boolean on = isGrMode() ? store.grOn() : "on".equals(photo.capture) && !photo.style.isEmpty();
                int id = context.getResources().getIdentifier(on ? "camera_setting_slogan_on" : "camera_setting_switch_off",
                    "string", context.getPackageName());
                param.args[0] = context.getString(id);
            }
        });
    }

    private static void hookEditor(Symbols symbols, ClassLoader loader) {
        XposedBridge.hookMethod(symbols.method("Util.openEditor"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                pinEditor();
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                ComponentName target = ((Intent) param.args[0]).getComponent();
                if (target != null && EDITOR_ACTIVITY.equals(target.getClassName())) pinEditor();
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (editorGroup != null && editorReturned == 0) editorReturned = SystemClock.uptimeMillis();
            }
        });
        Class<?> binder = XposedHelpers.findClass(
            "com.oplus.camera.feature.aiMasterWatermark.GalleryConnectionService$1", loader);
        XC_MethodHook result = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                beginEdit();
            }
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                finishEdit(Integer.valueOf(0).equals(param.getResult()), true);
            }
        };
        XposedBridge.hookAllMethods(binder, "sendResData", result);
        XposedBridge.hookAllMethods(binder, "sendResData2", result);
        hookBroadcast(symbols);
    }

    private static void beginEdit() throws Throwable {
        synchronized (store) {
            String group = editorGroup();
            WatermarkCore.State live = store.live();
            store.retain(live);
            WatermarkCore.State state = null;
            if (!WatermarkCore.GR.equals(group)) {
                state = group.equals(store.owner()) ? live : store.read(group);
                if (state == null) state = new WatermarkCore.State("", "", false, "off");
            }
            Log.i(TAG, "DBG beginEdit group=" + group + " owner=" + store.owner() + " mode=" + store.mode() + " pinned=" + editorGroup + " live=" + live.style + " state=" + (state == null ? "null" : state.style));
            editing.set(new EditResult(group, state));
        }
    }

    private static void finishEdit(boolean success, boolean resourcesReady) throws Throwable {
        EditResult edit = editing.get();
        editing.remove();
        if (edit != null) closeEditorRequest();
        if (edit != null) Log.i(TAG, "DBG finishEdit group=" + edit.group + " success=" + success + " changed=" + edit.changed + " style=" + (edit.state == null ? "null" : edit.state.style));
        if (!success || edit == null) return;
        synchronized (store) {
            if (edit.changed) {
                String target = WatermarkCore.GR.equals(edit.group) ? store.owner() : edit.group;
                if (resourcesReady) store.save(target, edit.state);
                else store.saveMemory(target, edit.state);
                if (target.equals(store.owner())) store.apply(edit.state);
            }
            if (resourcesReady) store.available(store.live());
        }
    }

    private static void hookBroadcast(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("WatermarkEditor.receive"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[1];
                if (intent == null || !STYLE_ACTION.equals(intent.getAction())) return;
                beginEdit();
                param.setObjectExtra(EDIT_CONTEXT, editing.get());
            }
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getObjectExtra(EDIT_CONTEXT) != null) finishEdit(!param.hasThrowable(), false);
            }
        });
        java.lang.reflect.Method copy = symbols.method("WatermarkEditor.copy");
        XposedBridge.hookAllConstructors(copy.getDeclaringClass(), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                EditResult edit = editing.get();
                if (edit != null) XposedHelpers.setAdditionalInstanceField(param.thisObject, EDIT_CONTEXT, edit);
            }
        });
        XposedBridge.hookMethod(copy, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                EditResult edit = (EditResult) XposedHelpers.removeAdditionalInstanceField(param.thisObject, EDIT_CONTEXT);
                if (edit == null || param.hasThrowable()) return;
                synchronized (store) {
                    if (edit.changed) store.retain(edit.state);
                    store.available(store.live());
                }
            }
        });
    }

    private static void hookStyles(Symbols symbols, ClassLoader loader) {
        // The original camera owns deferred-capture JSON. Keep its saved snapshot intact.
        XposedBridge.hookMethod(symbols.method("WatermarkStyles.saved"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                MethodHookParam loading = loadingStyle.get();
                if (loading != null && param.getResult() != null) loading.setObjectExtra("watermark.saved", Boolean.TRUE);
            }
        });
        XposedBridge.hookMethod(symbols.method("WatermarkStyles.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { loadingStyle.set(param); }
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                loadingStyle.remove();
                if (Boolean.TRUE.equals(param.args[3]) || Boolean.TRUE.equals(param.getObjectExtra("watermark.saved"))) return;
                if ("gr".equals(param.args[0])) {
                    param.setResult(GrWatermarkRenderer.loadStyle(param.getResult(), symbols, loader));
                    return;
                }
                WatermarkCore.State selected;
                synchronized (store) {
                    String group = policy().group((String) param.args[0], WatermarkCore.Channel.PHOTO);
                    selected = group.equals(store.owner()) ? store.live() : store.read(group);
                }
                Object current = param.getResult();
                String shown = current instanceof JSONObject ? ((JSONObject) current).optString("styleId") : "";
                Log.i(TAG, "DBG load mode=" + param.args[0] + " shown=" + shown + " selected=" + (selected == null ? "null" : selected.style));
                if (selected == null || selected.style.isEmpty()) return;
                if (selected.style.equals(shown) || "pld".equals(shown)) return;
                WatermarkFiles.ensureFiles(selected.style);
                File json = WatermarkFiles.styleJson(WatermarkFiles.PHOTO_DIR, selected.style);
                if (json.isFile()) param.setResult(new JSONObject(new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8)));
            }
        });
    }
}
