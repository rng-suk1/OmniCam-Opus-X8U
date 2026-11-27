package local.jiege.hook.filterpanel;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterCore;
import local.jiege.hook.filters.FilterChoice;
import local.jiege.hook.filters.FilterChoices;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.CameraFilterPresets;
import local.jiege.settings.ModuleSettings;

/**
 * A swipe up (default) or a long press on the right-hand enter button, as set in the settings app
 * (ModuleSettings.FILTER_PANEL_TRIGGER), opens the stock filter strip (the panel tilt-shift mode
 * opens with a tap) where a tap opens the master preset cards or the palette. The tap keeps its
 * stock behaviour.
 *
 * The cards and the palette pages are one stock list, the "style" pages of FilterPresenter
 * (styleMode: master mode, or a mode with the palette): in master mode the presets (custom ones
 * first, then one per visible filter), with the palette the palette's filters. The strip lists
 * these pages and picks one exactly as a swipe of the cards does (the page indicator's message 3:
 * clamp the page, store it, apply its filter, sync the palette, sync the pager), so a page brings
 * its own adjusted parameters, a custom preset its saved ones, a palette filter its remembered
 * palette and soft light values. While the strip scrolls, the selections of one frame are applied
 * once, for the page it ends on.
 *
 * Opening: the long press opens the menu the way tilt-shift mode's entry does, as the beauty menu's
 * filter menu (BeautyUi.openMenu = FaceBeautyUIManager.t(3) -> FaceBeautyBaseMenu -> BaseMode ->
 * FilterPresenter.showMenu), so the menu state, the hidden controls below and closing with a tap on
 * the preview are the camera's own. Master mode has no beauty feature, so no beauty menu: the beauty
 * menu is what hides the controls below (FaceBeautyUIManager.t ends with CameraUIManager.F9 ->
 * CameraControlUI.Wg(true)) and what makes a tap on the preview close the strip (TouchEventManager
 * d8 revokes the effect panel only while EffectPanelUIContainer.isExpanded(), which only the beauty
 * menu answers true; the filter menu neither answers it nor handles the revoke). In master mode the
 * menu shows its strip, the effect panel is told the filter menu expanded (EffectPanelUi.expand:
 * mode bar and entry buttons hidden) and the controls are hidden by Wg(true) after their visibility
 * is recorded. While the strip is open the panel answers isExpanded() true and a revoke hides the
 * strip (FilterMenu.hideAnimated). When the strip ends, the controls that were visible come back
 * through their own setters (main shutter, thumbnail, switch camera, side shutter) and the panel
 * collapses (EffectPanelUi.collapse, mode bar back). Only inside showMenu the palette (FilterPresenter.palette) and
 * the master cards (com.oplus.feature.effect.style.support) answer false, which leaves the strip.
 * The presenter's palette list flag is set while the strip is open, so its items are the style
 * list's filters. With the palette these are the pages; in master mode the items are built from
 * the preset list: a custom preset shows its preset name, a built-in one its filter's item. Every
 * strip build goes through FilterHelper.setImageResourceHorizontal. Names are the cards': catalog
 * filters by their catalog title, 无 as 标准 (FilterPresenter p3).
 *
 * Strip callbacks (FilterStripHost): current page, range check and selection answer for the
 * pages while the strip is open from a long press. The strip ends when its container fades out
 * (BottomEffectContainer.setVisibilityState), or when the camera shows the menu itself (a tap
 * opens the cards or the palette over the strip).
 */
public final class LegacyFilterPanel {
    private static final String TAG = "LegacyFilterPanel";
    private static final String MASTER = "professional";
    private static final String CAPTURE_PARAMS = "com.oplus.camera.feature.capture_params";
    private static final String EFFECT_STYLE = "com.oplus.feature.effect.style.support";
    private static final String FILTER_BEAN = "com.oplus.camera.filter.FilterBean";
    private static final String CAMERA = "com.oplus.camera";
    private static final String CONTAINER = "com.oplus.camera.common.view.BottomEffectContainer";
    private static final Map<Object, WeakReference<Object>> presenters = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Object>> beautyUis = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String FILTER_MENU = "pref_filter_menu";
    /** The presenter whose strip is open from a long press, or null. */
    private static Object open;
    private static boolean openMaster;
    private static FilterChoices masterPages;
    private static boolean savedPaletteList;
    /** True while the long press runs the filter entry's tap. */
    private static boolean opening;
    /** True while FilterPresenter.showMenu runs for the long press. */
    private static boolean stripOnly;
    private static boolean shown;
    /** Custom preset items of the current strip, named by their preset names. */
    private static final java.util.Set<Object> ownItems = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int pendingPage = -1;

    private LegacyFilterPanel() {}

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "instances", () -> trackInstances(symbols));
        Log.guard(TAG, "expanded menu", () -> ExpandedFilterMenu.install(symbols));
        Log.guard(TAG, "long press", () -> hookButton(classLoader));
        Log.guard(TAG, "strip only", () -> hookStripOnly(symbols, classLoader));
        Log.guard(TAG, "strip items", () -> hookItems(symbols, classLoader));
        Log.guard(TAG, "strip callbacks", () -> hookStrip(symbols));
        Log.guard(TAG, "menu hide", () -> hookHide(symbols, classLoader));
        Log.guard(TAG, "strip input tone", () -> hookStripTone(classLoader));
        Log.guard(TAG, "strip draw window", () -> hookDrawWindow(symbols, classLoader));
    }

    /** The name of the activity's current mode ("professional" for master, "gr" for Ricoh), or null when unknown. */
    public static String modeName(Activity activity) {
        try {
            Object presenter = of(presenters, activity);
            return presenter == null ? null : (String) Symbols.get().get("FeaturePresenter.modeName", presenter);
        } catch (Throwable error) {
            return null;
        }
    }

    /** True while the activity's current mode is the master mode. */
    public static boolean masterMode(Activity activity) {
        return MASTER.equals(modeName(activity));
    }

    /** FilterPresenter and the beauty menu UI per activity. */
    private static void trackInstances(Symbols symbols) {
        trackByActivity(symbols.cls("FilterPresenter"), presenters);
        trackByActivity(symbols.cls("BeautyUi"), beautyUis);
    }

    private static void trackByActivity(Class<?> type, final Map<Object, WeakReference<Object>> instances) {
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // The beauty menu UI gets the application context and its presenter, which holds the activity.
                for (Object arg : param.args) {
                    Activity activity = arg instanceof Context ? activityOf((Context) arg) : null;
                    if (activity == null && arg != null && !(arg instanceof Context)) {
                        try {
                            activity = (Activity) Reflection.findFieldByType(arg, "android.app.Activity");
                        } catch (Throwable ignored) {
                            // not a presenter
                        }
                    }
                    if (activity != null) {
                        instances.put(activity, new WeakReference<>(param.thisObject));
                        return;
                    }
                }
            }
        });
    }

    private static Object of(Map<Object, WeakReference<Object>> instances, Activity activity) {
        WeakReference<Object> reference = activity == null ? null : instances.get(activity);
        return reference == null ? null : reference.get();
    }

    private static void hookButton(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.oplus.camera.ui.effectcontainer.MenuRightButton", classLoader, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                View button = (View) param.thisObject;
                if (button.getId() <= 0 || !"camera_menu_right_enter_button".equals(button.getResources().getResourceEntryName(button.getId()))) return;
                button.setOnLongClickListener(view -> trigger() == ModuleSettings.FILTER_PANEL_LONG_PRESS && open(view));
                button.setOnTouchListener(new SwipeUp());
            }
        });
    }

    /** How the strip is opened (ModuleSettings.FILTER_PANEL_TRIGGER). */
    private static int trigger() {
        return Settings.getInt(ModuleSettings.FILTER_PANEL_TRIGGER);
    }

    /** Swipe distance that opens the strip, in dp. */
    private static final float SWIPE_UP_DP = 24f;

    /**
     * A swipe toward the top of the phone along its long side, whatever way the screen is turned,
     * opens the strip. The button then gets a cancel, so neither its tap nor its long press
     * follows; touches that do not swipe that way reach the button unchanged.
     */
    private static final class SwipeUp implements View.OnTouchListener {
        private float downX, downY;
        private boolean opened;

        @Override public boolean onTouch(View button, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    opened = false;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (opened) return true;
                    if (towardTop(button, event.getRawX() - downX, event.getRawY() - downY) < SWIPE_UP_DP * button.getResources().getDisplayMetrics().density) return false;
                    if (trigger() != ModuleSettings.FILTER_PANEL_SWIPE_UP || !open(button)) return false;
                    opened = true;
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    button.onTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                default:
                    return opened;
            }
        }

        /** The movement toward the phone's top edge (its natural orientation), from a screen movement. */
        private static float towardTop(View button, float dx, float dy) {
            android.view.Display display = button.getDisplay();
            switch (display == null ? android.view.Surface.ROTATION_0 : display.getRotation()) {
                case android.view.Surface.ROTATION_90: return -dx;
                case android.view.Surface.ROTATION_180: return dy;
                case android.view.Surface.ROTATION_270: return dx;
                default: return -dy;
            }
        }
    }

    private static boolean open(View button) {
        try {
            Activity activity = activityOf(button.getContext());
            Object presenter = of(presenters, activity);
            Object beautyUi = of(beautyUis, activity);
            Object effectPanel = ExpandedFilterMenu.effectPanel(activity);
            if (presenter == null) return false;
            Symbols symbols = Symbols.get();
            boolean master = MASTER.equals(symbols.get("FeaturePresenter.modeName", presenter));
            // The beauty menu object of an earlier mode may outlive that mode: the path follows the mode.
            if (master) beautyUi = null;
            if (master ? effectPanel == null : beautyUi == null) {
                Log.i(TAG, "long press ignored: " + (master ? "effect panel" : "beauty menu") + " unknown");
                return false;
            }
            if (master && CameraFilterPresets.visibleCount() <= 0) return false;
            if (!master && !Boolean.TRUE.equals(symbols.call("FilterPresenter.palette", presenter))) return false;
            if (!Boolean.TRUE.equals(symbols.call("FilterPresenter.styleMode", presenter))) return false;
            if (!show(symbols, activity, presenter, beautyUi, master)) return false;
            button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "long press failed", error);
            return false;
        }
    }

    private static boolean show(Symbols symbols, Activity activity, Object presenter, Object beautyUi, boolean master) throws Throwable {
        if (open == null) savedPaletteList = (Boolean) symbols.get("FilterPresenter.paletteList", presenter);
        open = presenter;
        openMaster = master;
        masterPages = null;
        symbols.set("FilterPresenter.paletteList", presenter, Boolean.TRUE);
        shown = false;
        opening = true;
        try {
            if (!master) {
                // the filter entry's tap past its beauty checks: menu type 3, animated
                symbols.call("BeautyUi.openMenu", beautyUi, 3, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);
            } else {
                // master mode has no beauty menu: the strip, then the effect panel's own expand
                Object menu = menuOf(symbols, presenter);
                XposedHelpers.callMethod(menu, "setPaletteMode", Boolean.FALSE);
                symbols.call("FilterMenu.show", menu, "common", Boolean.TRUE);
                symbols.call("FilterPresenter.reloadStrip", presenter);
                ExpandedFilterMenu.expand(symbols, activity, FILTER_MENU, () -> hideStrip(symbols, presenter));
                shown = true;
            }
        } finally {
            opening = false;
        }
        if (!shown) {
            close(menuOf(symbols, presenter));
            Log.i(TAG, "strip not opened, the filter entry refused");
            return false;
        }
        Log.i(TAG, "strip opened (" + (master ? "master presets" : "palette filters") + ", "
            + symbols.call("FilterPresenter.styleCount", presenter) + " pages, current " + currentPage(symbols, presenter) + ")");
        return true;
    }

    /** Inside showMenu for the long press, neither the palette nor the master cards take the menu. */
    private static void hookStripOnly(Symbols symbols, ClassLoader classLoader) {
        XposedBridge.hookMethod(symbols.method("FilterPresenter.showMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (opening && param.thisObject == open) stripOnly = true;
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!stripOnly) return;
                stripOnly = false;
                shown = !param.hasThrowable();
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterPresenter.palette"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (stripOnly) param.setResult(Boolean.FALSE);
            }
        });
        XposedHelpers.findAndHookMethod("com.oplus.camera.configure.CameraConfig", classLoader, "getConfigBooleanValue", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (stripOnly && EFFECT_STYLE.equals(param.args[0])) param.setResult(Boolean.FALSE);
            }
        });
    }

    /**
     * The strip's items are drawn from the preview stream. In master mode that stream is the HDR
     * frame before the master look, so the items came out grey and every one took the menu SDK's
     * HDR path (MeisheFilterSdk.isHdrTexture) each frame. As the camera does for XPAN
     * (FilterPresenter's ImageCallback), the strip model asks for the basic tone conversion
     * (FilterMenuCanvas2D: the mode's basic tone LUT into a 2D texture) before the items are drawn.
     * GLModelParam is made only by the strip builds (FilterHelper.setImageResource*), before
     * imageReady hands it to the GL thread.
     */
    private static void hookStripTone(ClassLoader classLoader) {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass("com.oplus.camera.filter.GLModelParam", classLoader), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (open != null && openMaster) XposedHelpers.callMethod(param.thisObject, "setNeedBasicToneConvert", Boolean.TRUE);
            }
        });
    }

    /** Items drawn on each side of the centre item while the long-press strip is open. */
    private static final int DRAWN_AROUND_CENTRE = 4;

    /**
     * The menu SDK draws every strip item on every preview frame; with the long-press strip's 52
     * pages that cost the preview a third of its frames in master mode. The renderer can draw just
     * an index range (MeisheRender.drawFiltersFrame start / end; -1, -1 draws all) and the SDK
     * fills it from its own window (MeisheFilterSdk.mFilterItemDrawCount), but that window finds
     * the centre item along rect.top while this strip lays its items out along x with one top, so
     * it would stay at the first items. While the long-press strip is open the range is the items
     * around the one at the strip's scroll position (FilterStrip.indexAt of the position passed to
     * the SDK's draw). The list itself stays whole: the renderer keys its per-item caches and the
     * corner radius on it, and redraws everything whenever its content changes.
     */
    private static void hookDrawWindow(final Symbols symbols, ClassLoader classLoader) {
        final int[] window = new int[2];
        final ThreadLocal<int[]> pending = new ThreadLocal<>();
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.ipusdk.processunit.filter.vendorsdk.meicam.MeisheFilterSdk", classLoader),
            "drawFiltersFrame", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object presenter = open;
                    if (presenter == null || param.args.length < 5 || !(param.args[0] instanceof List) || !(param.args[4] instanceof Float)) return;
                    try {
                        int size = ((List<?>) param.args[0]).size();
                        if (size <= 2 * DRAWN_AROUND_CENTRE + 1) return;
                        Object strip = symbols.get("FilterMenu.strip", menuOf(symbols, presenter));
                        int centre = Math.max(0, Math.min(size - 1, (Integer) symbols.call("FilterStrip.indexAt", strip, param.args[4])));
                        window[0] = Math.max(0, centre - DRAWN_AROUND_CENTRE);
                        window[1] = Math.min(size - 1, centre + DRAWN_AROUND_CENTRE);
                        pending.set(window);
                    } catch (Throwable error) {
                        Log.e(TAG, "strip draw window failed", error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    pending.remove();
                }
            });
        for (java.lang.reflect.Method method : XposedHelpers.findClass("com.meicam.effect.oppo.MeisheRender", classLoader).getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            // drawFiltersFrame(items, in, out, width, height, padding, start, end, is2d); the 8-argument one calls it
            if (!"drawFiltersFrame".equals(method.getName()) || types.length != 9) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int[] range = pending.get();
                    if (range == null) return;
                    param.args[6] = range[0];
                    param.args[7] = range[1];
                }
            });
        }
    }

    /** A revoke (a tap on the preview) hides the strip as a tap above the menu does. */
    private static void hideStrip(Symbols symbols, Object presenter) {
        try {
            symbols.call("FilterMenu.hideAnimated", menuOf(symbols, presenter), Boolean.TRUE);
        } catch (Throwable error) {
            Log.e(TAG, "strip revoke failed", error);
        }
    }

    private static Object menuOf(Symbols symbols, Object presenter) throws Throwable {
        return symbols.call("FilterMenuHolder.menu", symbols.get("FilterPresenter.menus", presenter));
    }

    /** Ends with the strip's container fading out (every hide: the presenter's, a tap on the preview, ...). */
    private static void hookHide(Symbols symbols, ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "setVisibilityState", XposedHelpers.findClass(CONTAINER + "$VisibilityState", classLoader), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object presenter = open;
                if (presenter == null || opening) return;
                String state = String.valueOf(param.args[0]);
                if (!"FADING_OUT".equals(state) && !"HIDDEN".equals(state)) return;
                try {
                    Object menu = menuOf(Symbols.get(), presenter);
                    if (Reflection.findFieldByType(menu, CONTAINER) == param.thisObject) close(menu);
                } catch (Throwable error) {
                    Log.e(TAG, "strip close check failed", error);
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterMenu.show"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!opening) close(param.thisObject);
            }
        });
    }

    private static void close(Object menu) {
        Object presenter = open;
        if (presenter == null) return;
        try {
            if (menuOf(Symbols.get(), presenter) != menu) return;
            Symbols.get().set("FilterPresenter.paletteList", presenter, savedPaletteList);
        } catch (Throwable error) {
            Log.e(TAG, "strip close failed", error);
        }
        open = null;
        masterPages = null;
        pendingPage = -1;
        ExpandedFilterMenu.collapse(Symbols.get());
        Log.i(TAG, "strip closed");
    }

    /**
     * The strip's renderer redraws its name texture only when the current item changes
     * (FilterNameMarquee.updateMarqueeParams), so names of the list it showed before would stay.
     */
    private static void forgetNames(Symbols symbols, Object presenter) throws Throwable {
        Object strip = symbols.get("FilterMenu.strip", menuOf(symbols, presenter));
        Object renderer = Reflection.findFieldByType(strip, "com.oplus.camera.filter.GLProducerRender2DHorizontal");
        Object marquee = renderer == null ? null : XposedHelpers.getObjectField(renderer, "mFilterNameMarquee");
        if (marquee != null) XposedHelpers.setIntField(marquee, "mCurFilterIndex", -1);
    }

    /** The strip items: one per page. */
    private static void hookItems(final Symbols symbols, ClassLoader classLoader) {
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", classLoader), "setImageResourceHorizontal", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object presenter = open;
                if (presenter == null) return;
                try {
                    forgetNames(symbols, presenter);
                    int listArg = -1;
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof List) { listArg = i; break; }
                    }
                    if (listArg < 0 || !(param.args[2] instanceof Integer)) return;
                    List<?> items = (List<?>) param.args[listArg];
                    ArrayList<Object> pages;
                    if (openMaster) {
                        pages = presetItems(symbols, presenter, items, classLoader);
                        // the stock list arrives reversed where the strip runs right to left
                        if (Boolean.TRUE.equals(param.args[listArg - 1])) Collections.reverse(pages);
                    } else {
                        pages = new ArrayList<>(items);
                    }
                    cardNames(pages, openMaster ? FilterScope.MASTER : FilterScope.NORMAL, (android.content.res.Resources) param.args[0], classLoader);
                    param.args[listArg] = pages;
                    param.args[2] = currentPage(symbols, presenter);
                } catch (Throwable error) {
                    Log.e(TAG, "strip items failed", error);
                }
            }
        });
    }

    /** One item per preset page: custom presets by preset name, built-in ones as the filter's strip item. */
    private static ArrayList<Object> presetItems(Symbols symbols, Object presenter, List<?> filterItems, ClassLoader classLoader) throws Throwable {
        ownItems.clear();
        int total = (Integer) symbols.call("FilterPresenter.styleCount", presenter);
        masterPages = CameraFilterPresets.choices(total);
        Map<String, Object> byType = new HashMap<>();
        for (Object item : filterItems) byType.put((String) XposedHelpers.getObjectField(item, "mType"), item);
        ArrayList<Object> pages = new ArrayList<>(total);
        for (FilterChoice choice : masterPages.entries()) {
            String type = choice.filterType;
            Object item = byType.get(type);
            String name = choice.kind == FilterChoice.Kind.CUSTOM ? choice.title : null;
            if (name != null) {
                item = item(classLoader, type, name);
                ownItems.add(item);
            } else if (item == null) {
                item = item(classLoader, type, type);
            }
            pages.add(item);
        }
        return pages;
    }

    /**
     * The names the cards show: a catalog filter's title, 标准 (effect_style_standard) for 无
     * (camera_filter_none). Renamed items are copies; custom preset names are kept.
     */
    private static void cardNames(List<Object> items, FilterScope scope, android.content.res.Resources resources, ClassLoader classLoader) throws Throwable {
        int none = resources.getIdentifier("camera_filter_none", "string", CAMERA);
        int standard = resources.getIdentifier("effect_style_standard", "string", CAMERA);
        String noneName = none == 0 ? null : resources.getString(none);
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (ownItems.contains(item)) continue;
            String type = (String) XposedHelpers.getObjectField(item, "mType");
            String renamed = local.jiege.hook.filters.FilterLabels.titleFor(scope, type);
            String name = renamed != null ? renamed
                : standard != 0 && noneName != null && noneName.equals(XposedHelpers.getObjectField(item, "mName")) ? resources.getString(standard) : null;
            if (name != null) items.set(i, item(classLoader, type, name));
        }
    }

    private static Object item(ClassLoader classLoader, String type, Object name) throws Throwable {
        Object item = XposedHelpers.findClass(FILTER_BEAN, classLoader).getDeclaredConstructor().newInstance();
        XposedHelpers.setObjectField(item, "mType", type);
        XposedHelpers.setObjectField(item, "mName", name);
        return item;
    }

    private static void hookStrip(final Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("FilterStripHost.current"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object presenter = open;
                if (presenter != null) param.setResult(pendingPage >= 0 ? pendingPage : currentPage(symbols, presenter));
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterStripHost.inRange"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object presenter = open;
                if (presenter == null) return;
                int page = (Integer) param.args[0];
                param.setResult(page >= 0 && page < (Integer) symbols.call("FilterPresenter.styleCount", presenter));
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterStripHost.select"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                final Object presenter = open;
                if (presenter == null) return;
                param.setResult(null);
                boolean posted = pendingPage >= 0;
                pendingPage = (Integer) param.args[0];
                if (posted) return;
                main.post(() -> {
                    int page = pendingPage;
                    pendingPage = -1;
                    if (page < 0 || open != presenter) return;
                    try {
                        selectPage(symbols, presenter, page);
                    } catch (Throwable error) {
                        Log.e(TAG, "page selection failed", error);
                    }
                });
            }
        });
    }

    /** The current page; read with the palette answering as it is (styleCurrent asks it). */
    private static int currentPage(Symbols symbols, Object presenter) throws Throwable {
        boolean forced = stripOnly;
        stripOnly = false;
        try {
            int cameraIndex = (Integer) symbols.call("FilterPresenter.styleCurrent", presenter);
            // A hidden custom preset has no page; the strip then starts at 标准.
            return openMaster && masterPages != null ? Math.max(0, masterPages.pageOf(cameraIndex)) : cameraIndex;
        } finally {
            stripOnly = forced;
        }
    }

    /** The page indicator's selection (EffectStylePageIndicator, message 3), for one page. */
    private static void selectPage(Symbols symbols, Object presenter, int page) throws Throwable {
        if (!Boolean.TRUE.equals(symbols.call("FilterPresenter.styleMode", presenter))) return;
        if (Boolean.TRUE.equals(query(symbols, presenter, "isEffectMenuSeekBarOpen", Boolean.FALSE))) {
            Log.i(TAG, "page " + page + " not selected, adjustment bar open");
            return;
        }
        int total = (Integer) symbols.call("FilterPresenter.styleCount", presenter);
        int cameraIndex = openMaster && masterPages != null ? masterPages.at(page).cameraIndex : page;
        if (!Boolean.TRUE.equals(symbols.call("FilterPresenter.clampStyle", presenter, cameraIndex, total))) return;
        Object request = symbols.call("FilterPresenter.previewRequest", presenter);
        XposedHelpers.callMethod(request, "beginPresetSwitch");
        int target = (Integer) symbols.get("FilterPresenter.styleTarget", presenter);
        symbols.call("FilterPresenter.storeStyle", presenter, target);
        symbols.call("FilterPresenter.applyFilter", presenter, symbols.call("FilterPresenter.styleFilter", presenter));
        symbols.call("FilterPresenter.syncPalette", presenter);
        query(symbols, presenter, "onFilterScrolling", Boolean.FALSE);
        XposedHelpers.callMethod(request, "markPresetSwitchReady");
        symbols.call("StylePager.sync", symbols.get("FilterPresenter.pager", presenter), 1, target, total, Boolean.TRUE);
    }

    private static final Map<String, Object> keys = new HashMap<>();

    /** FeaturePresenter.feature on the capture parameter feature with the key of that name. */
    private static Object query(Symbols symbols, Object presenter, String keyName, Object fallback) throws Throwable {
        return symbols.call("FeaturePresenter.feature", presenter, CAPTURE_PARAMS, key(symbols, keyName), fallback);
    }

    private static Object key(Symbols symbols, String name) throws Throwable {
        synchronized (keys) {
            Object key = keys.get(name);
            if (key == null) {
                key = Reflection.findFeatureKey(symbols.cls("FeatureKeys"), symbols.cls("FeatureKey"), name);
                keys.put(name, key);
            }
            return key;
        }
    }

    private static Activity activityOf(Context context) {
        return ExpandedFilterMenu.activityOf(context);
    }
}
