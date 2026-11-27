package local.jiege.hook.palette;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.softlight.SoftLight;

/**
 * Gallery process: which photos the photo editor opens in palette mode.
 *
 * The gallery decides palette mode once for the whole app (isColorPaletteMode, a lazy value), and the
 * mode decides how a camera photo's filter is re-rendered from its src.image: palette mode runs a
 * palette stage (BasicTone with the palette sliders) before the filter stage, the filter mode runs the
 * filter stage alone. Palette mode goes to master photos, palette captures (filter.info version 5 or
 * later, isPalette or paletteAlgoUsed set) and images without filter.info; the camera's other filter
 * photos keep the stock filter editor. For a master photo the stage is skipped while the sliders are
 * untouched (the gallery's own pass-through), and its HDR transform data is withheld from the stage
 * (GalleryColorPalette).
 */
final class GalleryPaletteScope {
    private static final String TAG = "GalleryPaletteScope";
    private static final String EDITOR = "com.oplus.gallery.pictureeditorpage.PhotoEditorActivity";
    private static final String PALETTE_PARAMETER = "com.oplus.tbluniformeditor.plugins.colorpalette.ColorPaletteParameter";
    private static final String CONTAINER = "com.oplus.gallery.foundation.fileaccess.extension.FileExtendedContainer";
    private static final String OPEN_MODE = "com.oplus.gallery.foundation.fileaccess.extension.OpenFileMode";
    private static final String FILTER_INFO = "filter.info";
    // filter.info layout (little endian): version float at 0; from version 5, isPalette at 140 and paletteAlgoUsed at 144.
    private static final int IS_PALETTE = 140;
    private static final int PALETTE_ALGO_USED = 144;
    // filter.info: the recorded filter name, a NUL-terminated string of up to 100 bytes at offset 28.
    private static final int FILTER_NAME = 28;
    private static final int FILTER_NAME_LENGTH = 100;
    private static final String CAPTURE_MODE = "capture.mode";
    private static final ThreadLocal<Boolean> READING_MODE = new ThreadLocal<>();

    private static volatile boolean photoPalette = true;
    private static volatile boolean masterPhoto;

    private GalleryPaletteScope() {}

    static void install(ClassLoader classLoader) {
        try {
            Symbols symbols = Symbols.get();
            if (!symbols.has("ColorPaletteMode.lazy")) {
                Log.i(TAG, "palette mode symbol missing");
                return;
            }
            Field field = symbols.field("ColorPaletteMode.lazy");
            Object base = field.get(null);
            Class<?> lazy = field.getType();
            InvocationHandler handler = (proxy, method, args) -> {
                Object value = method.invoke(base, args);
                if ("getValue".equals(method.getName())) return Boolean.TRUE.equals(value) && photoPalette;
                return value;
            };
            field.set(null, Proxy.newProxyInstance(lazy.getClassLoader(), new Class<?>[] {lazy}, handler));
            XposedHelpers.findAndHookMethod(EDITOR, classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    masterPhoto = false;
                    photoPalette = paletteCapture(activity, editedImage(activity.getIntent()), classLoader);
                }
            });
            XposedHelpers.findAndHookMethod(PALETTE_PARAMETER, classLoader, "shouldPassThroughAsOriginal", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!masterPhoto || Boolean.TRUE.equals(param.getResult())) return;
                    boolean untouched = (Integer) XposedHelpers.callMethod(param.thisObject, "getTone") == 0
                        && (Integer) XposedHelpers.callMethod(param.thisObject, "getFilterSaturation") == 0
                        && (Integer) XposedHelpers.callMethod(param.thisObject, "getSoftLight") == -1;
                    if (untouched) param.setResult(Boolean.TRUE);
                }
            });
            XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "getExtensionData", String.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!FILTER_INFO.equals(param.args[0]) || READING_MODE.get() != null) return;
                    byte[] info = (byte[]) param.getResult();
                    if (info == null || info.length < FILTER_NAME + FILTER_NAME_LENGTH) return;
                    READING_MODE.set(Boolean.TRUE);
                    try {
                        if (!masterCapture((byte[]) XposedHelpers.callMethod(param.thisObject, "getExtensionData", CAPTURE_MODE))) return;
                        byte[] mapped = masterFilterInfo(info);
                        if (mapped != null) param.setResult(mapped);
                    } finally {
                        READING_MODE.remove();
                    }
                }
            });
            Log.i(TAG, "installed");
        } catch (Throwable error) {
            Log.e(TAG, "palette scope hook failed", error);
        }
    }

    /** The image the editor opens (InputArgumentsVM): editor_image_uri when started from a photo, else the intent data. */
    private static Uri editedImage(Intent intent) {
        if (intent == null) return null;
        if (intent.getBooleanExtra("editor_from_photo", false)) {
            String uri = intent.getStringExtra("editor_image_uri");
            return uri == null ? null : Uri.parse(uri);
        }
        return intent.getData();
    }

    private static boolean paletteCapture(Activity activity, Uri uri, ClassLoader classLoader) {
        if (uri == null) return true;
        Object container = null;
        try {
            container = XposedHelpers.newInstance(XposedHelpers.findClass(CONTAINER, classLoader));
            Object read = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(OPEN_MODE, classLoader), "MODE_READ");
            if (!(Boolean) XposedHelpers.callMethod(container, "setDataSource", activity, uri, read)) return true;
            byte[] info = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", FILTER_INFO);
            byte[] mode = (byte[]) XposedHelpers.callMethod(container, "getExtensionData", "capture.mode");
            masterPhoto = masterCapture(mode);
            SoftLight.galleryPhoto(activity, uri, masterPhoto);
            boolean palette = info == null || paletteSemantics(info) || masterPhoto;
            Log.i(TAG, "editing " + uri + ": " + (palette ? "palette" : "filter") + " mode");
            return palette;
        } catch (Throwable error) {
            Log.e(TAG, "filter.info read failed for " + uri, error);
            return true;
        } finally {
            if (container != null) {
                try {
                    XposedHelpers.callMethod(container, "close");
                } catch (Throwable ignored) {
                    // closing a container that failed to open
                }
            }
        }
    }

    /** The photo being edited is a master capture: its src.image already carries the style's tone. */
    static boolean masterPhoto() {
        return masterPhoto;
    }

    private static boolean masterCapture(byte[] mode) {
        return mode != null && new String(mode, StandardCharsets.UTF_8).startsWith("master");
    }

    /**
     * The camera records a master photo's filter by the type the app uses ("800t.bin", "positive_normal.bin"),
     * the editor's master group lists the master files ("800t_master.bin", "positive_master.bin"); the
     * editor finds the photo's filter by that name. The record is answered with the master group's name.
     */
    private static byte[] masterFilterInfo(byte[] info) {
        int end = FILTER_NAME;
        while (end < FILTER_NAME + FILTER_NAME_LENGTH && info[end] != 0) end++;
        String name = new String(info, FILTER_NAME, end - FILTER_NAME, StandardCharsets.UTF_8);
        String mapped = masterType(name);
        if (mapped == null || mapped.equals(name) || mapped.length() >= FILTER_NAME_LENGTH) return null;
        byte[] copy = info.clone();
        java.util.Arrays.fill(copy, FILTER_NAME, FILTER_NAME + FILTER_NAME_LENGTH, (byte) 0);
        byte[] bytes = mapped.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, copy, FILTER_NAME, bytes.length);
        Log.i(TAG, "master filter " + name + " -> " + mapped);
        return copy;
    }

    /** The master group's type for a recorded app type, or null when the group has none (or the name is already one). */
    private static String masterType(String name) {
        java.util.Set<String> types = GalleryFilters.masterTypes();
        if (name.isEmpty() || types.contains(name)) return null;
        if ("gr.nega.rgba.bin".equals(name)) {
            return types.contains("JG_RICOH_NEGATIVE.cube.rgb.bin") ? "JG_RICOH_NEGATIVE.cube.rgb.bin" : "default";
        }
        if ("gr.posi.rgba.bin".equals(name)) {
            return types.contains("JG_RICOH_POSITIVE.cube.rgb.bin") ? "JG_RICOH_POSITIVE.cube.rgb.bin" : "default";
        }
        if (name.startsWith("gr.") && name.endsWith(".rgba.bin")) {
            return "default";
        }
        String stem = name.endsWith(".bin") ? name.substring(0, name.length() - 4) : name;
        int variant = stem.indexOf("_normal");
        if (variant < 0) variant = stem.indexOf("_sdr");
        if (variant < 0) variant = stem.indexOf("_hdr");
        if (variant >= 0) stem = stem.substring(0, variant);
        String candidate = stem + "_master.bin";
        return types.contains(candidate) ? candidate : null;
    }

    private static boolean paletteSemantics(byte[] info) {
        ByteBuffer buffer = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
        if (info.length < PALETTE_ALGO_USED + 4 || buffer.getFloat(0) < 5.0f) return false;
        return buffer.getInt(IS_PALETTE) == 1 || buffer.getInt(PALETTE_ALGO_USED) == 1;
    }
}
