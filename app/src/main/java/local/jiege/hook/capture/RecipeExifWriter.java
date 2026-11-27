package local.jiege.hook.capture;

import android.hardware.HardwareBuffer;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ModeName;
import local.jiege.hook.common.PhotoRecipe;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterLabels;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.mastercolor.MasterColor;
import local.jiege.settings.LaunchModes;
import local.jiege.settings.MasterStyle;
import local.jiege.settings.MasterStyles;
import local.jiege.settings.ModuleSettings;

/**
 * 相机侧拍摄配方采集与写入：
 * 1. Java EXIF 路径：在 OplusExifInterface 写入 EXIF 时注入 TAG_USER_COMMENT；
 * 2. Native 直出 JPEG 路径（含 MPF/UltraHDR）：在 FormatConverter.writeHardwareBufferToFile 完成后
 *    于文件末尾无损追加配方标记，不改变头部偏移。
 */
public final class RecipeExifWriter {
    private static final String TAG = "RecipeExifWriter";
    private static final String EXIF = "com.oplus.exif.OplusExifInterface";

    private static volatile String lastMode;
    private static volatile String lastFilter;

    private RecipeExifWriter() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "capture state trackers", () -> hookStateTrackers());
        Log.guard(TAG, "EXIF user comment", () -> hookExifWrite(classLoader));
        Log.guard(TAG, "hardware buffer trailer", () -> hookHardwareBufferWrite(classLoader));
    }

    private static void hookStateTrackers() {
        final Symbols symbols = Symbols.get();
        // 跟踪模式切换（确保进入普通、理光 GR、POP 等任意模式都能准确记录）
        Log.guard(TAG, "track mode switch", () -> XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object to = param.args.length > 1 ? param.args[1] : null;
                if (to instanceof String && !((String) to).isEmpty()) {
                    if (!to.equals(lastMode)) {
                        lastMode = (String) to;
                        lastFilter = null;
                    }
                }
            }
        }));
        // 跟踪当前拍摄模式（与 SoftLight 复用同一组生命周期钩子）
        for (String stage : new String[] {"FilterPresenter.apply", "FilterPresenter.captureRequest"}) {
            Log.guard(TAG, "track mode at " + stage, () -> XposedBridge.hookMethod(symbols.method(stage), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object mode = symbols.get("FeaturePresenter.modeName", param.thisObject);
                    if (mode instanceof String && !((String) mode).isEmpty()) {
                        lastMode = (String) mode;
                    }
                }
            }));
        }
        // 跟踪当前滤镜/预设名称
        Log.guard(TAG, "track filter name", () -> XposedBridge.hookMethod(symbols.method("Card.layout"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object model = symbols.get("Card.mode", param.thisObject);
                    if (model == null) return;
                    String mode = ModeName.of(model);
                    if (mode != null && !mode.isEmpty()) lastMode = mode;
                    String type = (String) symbols.call("ModeUi.filterType", model);
                    if (type == null || type.isEmpty() || "default".equalsIgnoreCase(type) || "none".equalsIgnoreCase(type)) {
                        lastFilter = null;
                    } else {
                        FilterScope scope = FilterScope.ofMode(mode);
                        String title = FilterLabels.titleFor(scope, type);
                        lastFilter = (title != null && !title.isEmpty()) ? title : type;
                    }
                } catch (Throwable ignored) {
                }
            }
        }));
    }

    private static void hookExifWrite(ClassLoader classLoader) {
        final Class<?> exif = XposedHelpers.findClass(EXIF, classLoader);
        final int tagUserComment = XposedHelpers.getStaticIntField(exif, "TAG_USER_COMMENT");
        XC_MethodHook beforeWrite = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!Settings.get(ModuleSettings.PHOTO_RECIPE_EXIF)) return;
                try {
                    PhotoRecipe recipe = buildCurrentRecipe();
                    if (recipe.isEmpty()) return;
                    String encoded = recipe.encode();
                    if (encoded.isEmpty()) return;
                    Object tag = XposedHelpers.callMethod(param.thisObject, "buildTag", tagUserComment, (Object) encoded);
                    if (tag != null) {
                        XposedHelpers.callMethod(param.thisObject, "setTag", tag);
                        Log.i(TAG, "EXIF UserComment: " + encoded);
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "EXIF UserComment write failed", error);
                }
            }
        };
        XposedBridge.hookAllMethods(exif, "writeExif", beforeWrite);
        XposedBridge.hookAllMethods(exif, "rewriteExif", beforeWrite);
        XposedBridge.hookAllMethods(exif, "forceRewriteExif", beforeWrite);
        Log.i(TAG, "EXIF recipe hook installed");
    }

    private static void hookHardwareBufferWrite(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.oplus.camera.jni.FormatConverter", classLoader,
            "writeHardwareBufferToFile", HardwareBuffer.class, int.class, long.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Settings.get(ModuleSettings.PHOTO_RECIPE_EXIF)) return;
                    try {
                        param.setObjectExtra("recipe_fd", ParcelFileDescriptor.fromFd((Integer) param.args[1]));
                    } catch (Throwable error) {
                        Log.e(TAG, "dup fd for recipe trailer failed", error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ParcelFileDescriptor pfd = (ParcelFileDescriptor) param.getObjectExtra("recipe_fd");
                    if (pfd == null) return;
                    try (pfd) {
                        if (!Boolean.TRUE.equals(param.getResult())) return;
                        FileDescriptor fd = pfd.getFileDescriptor();
                        long size = Os.fstat(fd).st_size;
                        String grFilter = "gr".equals(lastMode) && lastFilter == null ? detectGrFilterFromTail(fd, size) : null;
                        PhotoRecipe recipe = buildCurrentRecipe(grFilter);
                        if (recipe.isEmpty()) return;
                        String encoded = recipe.encode();
                        if (encoded.isEmpty()) return;
                        byte[] trailer = ("\n" + encoded + "\n").getBytes(StandardCharsets.UTF_8);
                        int written = 0;
                        while (written < trailer.length) {
                            written += Os.pwrite(fd, trailer, written, trailer.length - written, size + written);
                        }
                        Log.i(TAG, "appended recipe trailer: " + encoded);
                    } catch (Throwable error) {
                        Log.e(TAG, "append recipe trailer failed", error);
                    }
                }
            });
        Log.i(TAG, "hardware buffer recipe hook installed");
    }

    private static PhotoRecipe buildCurrentRecipe() {
        return buildCurrentRecipe(null);
    }

    private static PhotoRecipe buildCurrentRecipe(String fallbackFilter) {
        String modeKey = lastMode;
        if (modeKey == null) {
            modeKey = MasterColor.inMaster() ? FilterScope.MASTER_MODE : "common";
        }
        String modeLabel = LaunchModes.label(modeKey);
        if ("common".equals(modeKey) && Settings.get(ModuleSettings.NATURAL_MODE)) {
            modeLabel = "照片 (自然)";
        }

        String filterLabel = lastFilter != null ? lastFilter : fallbackFilter;
        String engineLabel = resolveEngineLabel(modeKey);
        String softLightLabel = resolveSoftLightLabel(modeKey);
        return new PhotoRecipe(modeLabel, engineLabel, filterLabel, softLightLabel);
    }

    /** 从原厂照片末尾的 filter.info 块中识别理光 GR 的 5 种原生影调。 */
    private static String detectGrFilterFromTail(FileDescriptor fd, long size) {
        try {
            int readLen = (int) Math.min(4096L, size);
            if (readLen <= 0) return "标准";
            byte[] buf = new byte[readLen];
            int n = Os.pread(fd, buf, 0, readLen, size - readLen);
            if (n <= 0) return "标准";
            String tail = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            if (tail.contains("gr.posi.rgba.bin")) return "正片";
            if (tail.contains("gr.nega.rgba.bin")) return "负片";
            if (tail.contains("gr.hi.bw.rgba.bin")) return "高对比黑白";
            if (tail.contains("gr.bw.rgba.bin")) return "单色";
        } catch (Throwable ignored) {
        }
        return "标准";
    }

    /** 解析当前生效的色彩引擎名称（大师模式下读取 MasterStyles 配置）。 */
    private static String resolveEngineLabel(String modeKey) {
        boolean isMaster = FilterScope.MASTER_MODE.equals(modeKey) || (modeKey == null && MasterColor.inMaster());
        if (!isMaster) return null;
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND;
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (blend && !style.blendable()) style = MasterStyles.DEFAULT;
        if (blend) {
            int tone = Settings.getInt(ModuleSettings.MASTER_BLEND_TONE);
            int colour = Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR);
            if (tone == 0 && colour == 0) return "原厂";
        }
        MasterStyle.IntLookup lookup = Settings::getInt;
        StringBuilder sb = new StringBuilder(style.label);
        for (MasterStyle.Choice choice : style.choices()) {
            if (!choice.visible.test(lookup)) continue;
            int idx = lookup.get(choice.key);
            if (idx < 0 || idx >= choice.ids.length) idx = 0;
            String label = choice.label(idx, lookup);
            if (label != null && !label.isEmpty() && !label.equals(style.label)) {
                sb.append(' ').append(label);
            }
        }
        if (blend) {
            sb.append(" (混色)");
        }
        return sb.toString();
    }

    /** 解析大师/理光模式下启用的柔光替换档位。 */
    private static String resolveSoftLightLabel(String modeKey) {
        boolean applies = FilterScope.MASTER_MODE.equals(modeKey) || "gr".equals(modeKey)
            || (modeKey == null && MasterColor.inMaster());
        if (!applies) return null;
        List<String> enabled = new ArrayList<>(3);
        if (Settings.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF)) enabled.add("HDF");
        if (Settings.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST)) enabled.add("1/4黑柔");
        if (Settings.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST)) enabled.add("白柔");
        if (enabled.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < enabled.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(enabled.get(i));
        }
        return sb.toString();
    }
}
