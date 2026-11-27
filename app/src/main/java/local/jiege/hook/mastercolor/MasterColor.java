package local.jiege.hook.mastercolor;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.content.Context;
import android.app.AndroidAppHelper;
import java.util.HashMap;
import android.hardware.camera2.params.ColorSpaceTransform;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Lut3dInvalidate;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.softlight.SoftLight;
import local.jiege.settings.MasterAlign;
import local.jiege.settings.MasterStyle;
import local.jiege.settings.MasterStyles;
import local.jiege.settings.ModuleSettings;

/**
 * Master colour (大师色彩), Java half: live look tables (HNCS, Lumix, film, ...) in place of the master LMT.
 * The capture half and the baker are native (master_color.cpp, master_core.cpp, gr_basictone.cpp).
 *
 * Selection: the module setting (MasterStyles, 大师色彩 page) is mirrored to files/jiege/mastercolor/look,
 * which the native capture hooks read per frame; "oppo" restores the stock master LMT. In blend mode
 * the look file also carries the tone and colour weights (percent); blend mode has no "oppo" family,
 * and 0 % / 0 % keeps nothing of the look, so it is written as "oppo".
 *
 * Preview: master preview runs the stock LMTLut (the same table as capture) through CombineLut, so
 * its codes carry the capture encoding; the physical sensor calibration, CCT and CCM define each table. The latest
 * master preview TotalCaptureResult gives both; they are
 * written to files/jiege/mastercolor/live/p.../key.bin and CombineLutHelper.init is pointed there. The
 * native path hooks bake the table on the first LMTLut read. A key is exact (CCT and matrix as the frame reports
 * them); the table in use is replaced when the exact tables of the two keys differ by more than
 * PREVIEW_DISTANCE_CODES (native previewDistance), so the preview stays within that of the capture.
 */
public final class MasterColor {
    private static final String TAG = "MasterColor";
    private static final String MASTER_MODE = "professional";
    private static final String ROOT = "/data/user/0/com.oplus.camera/files/jiege/mastercolor";
    private static final String LIVE = ROOT + "/live/";
    private static final int PREVIEW_INTERVAL_MS = 200;
    // Some streams (dual-camera portrait) alternate two physical cameras frame by frame; the preview follows a
    // new camera only after it has been the only one reporting for this long.
    private static final int CAMERA_SWITCH_MS = 300;
    // A new preview table is baked when the one in use is off by more than this (10-bit codes; 99th percentile over
    // the mid-tone and highlight nodes of the table), measured on the exact tables of the two keys.
    private static final double PREVIEW_DISTANCE_CODES = 16;
    private static final int KEY_SETTINGS_OFFSET = 52;   // MCOLKEY9: magic, path, cct, 9 matrix floats, then the look settings
    // The settings are re-read at most this often; the page applies a change without restarting the camera, so this is the wait.
    private static final long SETTING_INTERVAL_MS = 100;

    private static final CaptureResult.Key<Integer> AWB_CCT = key("org.quic.camera2.statsconfigs.AWBFrameControlCCT", Integer.class);

    private static volatile boolean enabled = true;
    private static volatile String look;
    private static volatile int tonePercent = 100;
    private static volatile int colourPercent = 100;
    /** The look style's own settings by name, in the style's units (mastercolor::Key::params); sorted for stable keys. */
    private static volatile Map<String, Float> styleParams = Collections.emptyMap();
    private static volatile long settingCheckedAt;
    private static volatile boolean inMaster;

    public static boolean inMaster() {
        return inMaster;
    }
    private static volatile Object manager;
    private static volatile Object[] lastLoadArgs;
    private static long previewResultAt;
    private static String candidateCameraId;
    private static long candidateSince;

    // Current source metadata and its preview directory.
    private static String keyIdentity;
    private static byte[] previewKey;
    private static final Map<String, String> lenses = new HashMap<>();
    private static volatile String previewDir;
    private static volatile String loadedDir;
    // When the preview last got a new table (the render thread uploaded it), and when the setting it answers changed.
    private static volatile long changeNanos, reloadedNanos, reportedChange;
    private static volatile String appliedDir;
    // The camera's own EV (exposure compensation) in stops, from the preview results.
    private static volatile float cameraEv;
    private static volatile float lastLoggedEv = Float.NaN;
    private static final Map<String, Float> evSteps = new HashMap<>();
    private static boolean evMissingReported;
    // A film's exact lattice takes a moment to build; until it is ready the preview asks for a coarse stand-in (setting "exact" = 0).
    private static volatile boolean exactReady = true;
    private static java.util.concurrent.ExecutorService appliedWriter;
    private static volatile int generation;   // bumped by every look change; a request of an older one is not published

    // Preview table worker: one thread. Requests queued while a table is baking are reduced to the latest; a table
    // that is baking always finishes and is published (the preview then follows the newest within one bake), and it is
    // baked before it is published, so the camera never waits for a bake.
    private static final class Request {
        final int generation, cct;
        final float[] ccm;
        final String look, lens;
        final int tone, colour;
        final Map<String, Float> params;
        Request(int generation, int cct, float[] ccm, String look, int tone, int colour, Map<String, Float> params, String lens) {
            this.generation = generation; this.cct = cct; this.ccm = ccm; this.look = look;
            this.tone = tone; this.colour = colour; this.params = params; this.lens = lens;
        }
    }
    private static final Request CLEAR = new Request(0, 0, null, null, 0, 0, null, null);
    private static final Object QUEUE = new Object();
    private static Request pending;
    private static Thread worker;
    private static final long LENS_RETRY_MS = 2000;
    private static final Map<String, Long> lensFailedAt = new HashMap<>();

    private MasterColor() {}

    @SuppressWarnings("unchecked")
    private static <T> CaptureResult.Key<T> key(String name, Class<T> type) {
        return (CaptureResult.Key<T>) XposedHelpers.newInstance(CaptureResult.Key.class,
            new Class<?>[] {String.class, Class.class}, name, type);
    }

    public static void installCamera(ClassLoader classLoader) {
        new File(LIVE).mkdirs();
        syncFilmData();
        warmCurrentLook();
        Log.guard(TAG, "master colour preview LUT", () -> hookPreviewLut(classLoader));
        Log.guard(TAG, "master colour preview results", MasterColor::hookResults);
        Log.guard(TAG, "master colour mode", () -> hookModeSwitch());
        Log.guard(TAG, "master colour applied", MasterColor::hookApplied);
        MasterPhysicalVignette.installCamera(classLoader);
        Log.i(TAG, "installed");
    }

    private static byte[] readStream(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = src.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] readBundledFilmData() throws Exception {
        InputStream in = MasterColor.class.getClassLoader().getResourceAsStream("assets/fotufilm.bin");
        if (in != null) return readStream(in);
        String path = Symbols.modulePath();
        if (path == null) return null;
        try (ZipFile apk = new ZipFile(path)) {
            ZipEntry entry = apk.getEntry("assets/fotufilm.bin");
            return entry == null ? null : readStream(apk.getInputStream(entry));
        }
    }

    /** Keeps files/jiege/mastercolor/fotufilm.bin aligned with the installed APK's bundled engine data. */
    private static void syncFilmData() {
        try {
            byte[] bundled = readBundledFilmData();
            if (bundled == null || bundled.length == 0) return;
            File target = new File(ROOT, "fotufilm.bin");
            if (target.isFile() && target.length() == bundled.length) {
                byte[] current = readStream(new FileInputStream(target));
                if (Arrays.equals(current, bundled)) return;
            }
            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(ROOT, "fotufilm.bin." + android.os.Process.myPid() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bundled);
            }
            if (tmp.renameTo(target)) Log.i(TAG, "fotufilm.bin synced (" + bundled.length + " bytes)");
            else tmp.delete();
        } catch (Throwable error) {
            Log.e(TAG, "sync fotufilm.bin", error);
        }
    }

    /**
     * The camera starts a moment before its first preview frame reaches the baker: a look that has to prepare something from its
     * settings alone (a film's lattice) does it now, in the background, instead of on the first frame.
     */
    private static void warmCurrentLook() {
        Thread thread = new Thread(() -> {
            try {
                syncSetting(); // reads the settings and warms the look they name
            } catch (Throwable error) {
                Log.e(TAG, "warm", error);
            }
        }, "MasterColorWarmStart");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Lets the native style prepare for `id` with these settings in the background (a new selection while the camera runs, too). */
    private static void warm(String id, Map<String, Float> params, boolean background) {
        if ("oppo".equals(id)) return;
        Thread thread = new Thread(() -> {
            try {
                float[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
                nativeWarm(keyBytes(5000, identity, id, 100, 100, params, "main23"), background);
                synchronized (MasterColor.class) {
                    // The exact lattice is ready: ask for the exact table (a different key) at the next preview result.
                    if (id.equals(look) && sameSelection(params, styleParams) && !exactReady) {
                        exactReady = true;
                        keyIdentity = null;
                        previewResultAt = 0;
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "warm", error);
            }
        }, "MasterColorWarm");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static native void nativeWarm(byte[] key, boolean background);

    // ---------------------------------------------------------------- switch
    /** Line 1 look, lines 2 and 3 the blend weights in percent, then one "name value" line per style setting (master_color.cpp masterColorLook). */
    private static void writeLookFile(String id, int tone, int colour, Map<String, Float> params) {
        try {
            File tmp = new File(ROOT, "look.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                StringBuilder text = new StringBuilder(id).append('\n').append(tone).append('\n').append(colour);
                for (Map.Entry<String, Float> entry : params.entrySet())
                    text.append('\n').append(entry.getKey()).append(' ').append(String.format(Locale.ROOT, "%.4f", entry.getValue()));
                out.write(text.toString().getBytes(StandardCharsets.US_ASCII));
            }
            if (!tmp.renameTo(new File(ROOT, "look"))) Log.i(TAG, "look file rename failed");
        } catch (Throwable error) {
            Log.e(TAG, "look file", error);
        }
    }

    /** Whether two settings maps name the same LUT table selection: equal in everything except exposure and physical vignette. */
    private static boolean sameSelection(Map<String, Float> a, Map<String, Float> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, Float> entry : a.entrySet()) {
            String key = entry.getKey();
            if ("exposure".equals(key) || "vignette".equals(key)) continue;
            if (!entry.getValue().equals(b.get(key))) return false;
        }
        return true;
    }

    private static boolean sameExposure(Map<String, Float> a, Map<String, Float> b) {
        Float ea = a.get("exposure");
        Float eb = b.get("exposure");
        return ea == null ? eb == null : ea.equals(eb);
    }

    private static void syncSetting() {
        long now = System.currentTimeMillis();
        if (now - settingCheckedAt < SETTING_INTERVAL_MS) return;
        settingCheckedAt = now;
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND;
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (blend && !style.blendable()) style = MasterStyles.DEFAULT;
        MasterStyle.IntLookup selections = Settings::getInt;
        String id = style.lookId(selections);
        int tone = blend ? clampPercent(Settings.getInt(ModuleSettings.MASTER_BLEND_TONE)) : 100;
        int colour = blend ? clampPercent(Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR)) : 100;
        if (tone == 0 && colour == 0) id = "oppo";
        int exposureMode = Settings.getInt(MasterAlign.exposureModeKey(style.index));
        int offsetCentiEv = Settings.getInt(MasterAlign.exposureKey(style.index));
        int alignMode = Settings.getInt(MasterAlign.alignKey(style.index));
        float ev = style.blendable() ? MasterAlign.evFor(id, exposureMode, offsetCentiEv, alignMode) : 0f;
        if (ev != lastLoggedEv) {
            lastLoggedEv = ev;
            Log.i(TAG, "exposure ev=" + ev + " (mode=" + exposureMode + " offset=" + (offsetCentiEv / 100f) + " align=" + alignMode + ")");
        }
        TreeMap<String, Float> params = new TreeMap<>();
        params.put("exposure", ev);
        if (style.blendable()) {
            int wbTemp = Math.max(-800, Math.min(800, Settings.getInt(MasterAlign.wbTempKey(style.index))));
            int wbTint = Math.max(-50, Math.min(50, Settings.getInt(MasterAlign.wbTintKey(style.index))));
            if (wbTemp != 0) params.put("wb_temp", (float) wbTemp);
            if (wbTint != 0) params.put("wb_tint", (float) wbTint);
        }
        addParams(params, style.params(selections));
        params.putAll(style.settings(selections));
        int vigMode = Settings.getInt(ModuleSettings.MASTER_FILM_VIGNETTE);
        boolean vigOn;
        int vigCompInt;
        if (vigMode == ModuleSettings.VIGNETTE_FOLLOW_ENGINE) {
            vigOn = Settings.getInt(ModuleSettings.vignetteKey(style.index)) > 0;
            vigCompInt = vigOn ? Settings.getInt(ModuleSettings.vignetteCompKey(style.index)) : 0;
        } else {
            vigOn = vigMode == ModuleSettings.VIGNETTE_ON;
            vigCompInt = vigOn ? Settings.getInt(ModuleSettings.MASTER_VIGNETTE_LUMA_COMP) : 0;
        }
        params.put("vignette", vigOn ? 1f : 0f);
        float vigComp = (vigOn && style.index != 0 && !"oppo".equals(id))
            ? Math.max(0f, Math.min(1f, vigCompInt / 100f))
            : 0f;
        params.put("vig_comp", vigComp);
        SoftLight.syncHncsGrain(inMaster);
        if (id.equals(look) && tone == tonePercent && colour == colourPercent && params.equals(styleParams)) return;
        writeLookFile(id, tone, colour, params);
        MasterPhysicalVignette.updateFilmSelection(id, params);
        if (id.equals(look) && tone == tonePercent && colour == colourPercent && sameSelection(params, styleParams)) {
            // Only exposure and/or physical vignette moved: keep the film lattice; only exposure requires rebaking the LUT.
            boolean exposureChanged = !sameExposure(params, styleParams);
            synchronized (MasterColor.class) {
                styleParams = params;
                if (exposureChanged) {
                    keyIdentity = null;
                    previewResultAt = 0;
                    ++generation;
                }
            }
            return;
        }
        final boolean first, keepPreview;
        synchronized (MasterColor.class) {
            first = look == null;
            // Between two looks the table on screen stays until the new one is ready; only the stock look clears the preview.
            keepPreview = !first && enabled && !"oppo".equals(id) && previewDir != null;
            look = id;
            tonePercent = tone;
            colourPercent = colour;
            styleParams = params;
            enabled = !"oppo".equals(id);
            exactReady = !id.startsWith("film_");
            keyIdentity = null; // the next result writes a key for the new look
            previewResultAt = 0;
            if (!keepPreview) previewDir = null;
            ++generation;
        }
        if (!first) changeNanos = System.nanoTime();
        Log.i(TAG, "master colour " + id + (blend ? " blend tone " + tone + "% colour " + colour + "%" : ""));
        if (!keepPreview) submit(CLEAR);
        warm(id, params, first);
    }

    // ---------------------------------------------------------------- preview LUT
    private static void hookPreviewLut(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final ThreadLocal<String> liveDir = new ThreadLocal<>();
        XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                syncSetting();
                manager = param.thisObject;
                boolean master = MASTER_MODE.equals(param.args[2]);
                boolean wasMaster = MASTER_MODE.equals(symbols.get("Lut3d.mode", param.thisObject));
                inMaster = master;
                MasterPhysicalVignette.setMasterMode(master);
                SoftLight.syncHncsGrain(master);
                if (master) lastLoadArgs = param.args.clone();
                String dir = master && enabled ? previewDir : null;
                // Entering / leaving master or a new look table invalidates the cached LUT (index -1).
                if (master != wasMaster || (master && !String.valueOf(dir).equals(String.valueOf(loadedDir)))) {
                    Lut3dInvalidate.now(param.thisObject, param.args);
                }
                if (master) loadedDir = dir;
                liveDir.set(dir);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                liveDir.remove();
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass("com.oplus.ocs.camera.CombineLutHelper", classLoader), "init",
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    String dir = liveDir.get();
                    if (dir != null) {
                        param.args[0] = dir;
                        Log.i(TAG, "preview LMT " + dir);
                    }
                }
            });
    }

    /** The settings of `parameters` in the style's units (setting / scale), clamped to the range the UI offers. */
    private static void addParams(Map<String, Float> params, MasterStyle.Param[] parameters) {
        for (MasterStyle.Param p : parameters)
            params.put(p.id, Math.max(p.min, Math.min(p.max, Settings.getInt(p.key))) / (float) p.scale);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static void submit(Request request) {
        synchronized (QUEUE) {
            pending = request;
            if (worker == null) {
                worker = new Thread(MasterColor::workerLoop, "MasterColorWorker");
                worker.setDaemon(true);
                worker.start();
            }
            QUEUE.notifyAll();
        }
    }

    private static boolean superseded() {
        synchronized (QUEUE) { return pending != null; }
    }

    private static void workerLoop() {
        for (;;) {
            Request request;
            synchronized (QUEUE) {
                while (pending == null) {
                    try { QUEUE.wait(); } catch (InterruptedException ignored) { return; }
                }
                request = pending;
                pending = null;
            }
            try {
                process(request);
            } catch (Throwable error) {
                Log.e(TAG, "preview table", error);
            }
        }
    }

    private static void process(Request request) throws Throwable {
        String dir = null;
        if (request != CLEAR) {
            dir = writeKey(request.cct, request.ccm, request.look, request.tone, request.colour, request.params, request.lens);
            if (dir == null) { clearPreview("preview: key write failed"); return; }
            // The native path hooks bake a table on the first read of its LMTLut0, here and not on a camera thread.
            if (superseded()) return;   // a newer table is already asked for: bake that one
            new File(dir, "LMTLut0").exists();
            synchronized (MasterColor.class) {
                if (request.generation != generation) return;
                previewDir = dir;
            }
            Log.i(TAG, "preview table " + dir);
        }
        long reloadStart = System.nanoTime();
        reload(dir);
        if (dir != null) Log.i(TAG, "preview reload " + (System.nanoTime() - reloadStart) / 1000000 + " ms");
    }

    /** Re-runs the manager's load for the master mode so a new table reaches the preview. */
    private static void reload(String dir) throws Throwable {
        final Object lut = manager;
        final Object[] args = lastLoadArgs;
        if (lut == null || args == null || !inMaster) return;
        String wanted = enabled ? dir : null;
        if (String.valueOf(wanted).equals(String.valueOf(loadedDir))) return;
        Symbols symbols = Symbols.get();
        Lut3dInvalidate.now(lut, args);
        symbols.call("Lut3d.load", lut, args);
        reloadedNanos = System.nanoTime();
        // The load builds the table but the camera only hands a table to the preview when it recombines (on a change of the scene's
        // exposure or colour temperature, or a slider): without this a still scene keeps the old look until the phone is moved.
        if (symbols.has("Lut3d.update"))
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (inMaster) symbols.call("Lut3d.update", lut);
                } catch (Throwable error) {
                    Log.e(TAG, "preview update", error);
                }
            });
    }

    // ---------------------------------------------------------------- applied
    /**
     * The preview draws on the camera's GL thread, which uploads a changed table as a texture (the symbol "Lut3d.upload") just before
     * it draws the frame; the first upload after our reload is the new look reaching the screen, a frame or two before it is shown.
     * It is logged ("preview applied") and written to files/jiege/mastercolor/applied for whatever reads it in the background.
     */
    private static void hookApplied() {
        final Symbols symbols = Symbols.get();
        if (!symbols.has("Lut3d.upload")) {
            Log.i(TAG, "preview upload not found: the applied state is unavailable");
            return;
        }
        XposedBridge.hookMethod(symbols.method("Lut3d.upload"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                noteApplied();
            }
        });
    }

    private static void noteApplied() {
        if (!inMaster) return;
        final long now = System.nanoTime(), changed = changeNanos, reloaded = reloadedNanos;
        final String dir = loadedDir;
        appliedDir = dir;
        if (changed == 0 || reloaded < changed || now < reloaded || changed == reportedChange) return; // not the first upload of the new look
        reportedChange = changed;
        final long afterChange = (now - changed) / 1000000, afterReload = (now - reloaded) / 1000000;
        Log.i(TAG, "preview applied " + afterChange + " ms after the setting changed (" + afterReload + " ms after the reload) "
            + (dir == null ? "stock" : new File(dir).getName()));
        synchronized (MasterColor.class) {
            if (appliedWriter == null) {
                appliedWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "MasterColorApplied");
                    thread.setDaemon(true);
                    return thread;
                });
            }
        }
        appliedWriter.execute(() -> {
            try {
                File tmp = new File(ROOT, "applied.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write((System.currentTimeMillis() + " " + afterChange + " " + (dir == null ? "stock" : new File(dir).getName()) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
                }
                tmp.renameTo(new File(ROOT, "applied"));
            } catch (Throwable error) {
                Log.e(TAG, "applied file", error);
            }
        });
    }

    // ---------------------------------------------------------------- mode
    /** Leaving master stops the preview work at once; Lut3d.load alone is not called by every mode. */
    private static void hookModeSwitch() {
        XposedBridge.hookMethod(Symbols.get().method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String to = (String) param.args[1];
                if (to == null || to.isEmpty()) return;
                boolean master = MASTER_MODE.equals(to);
                if (master == inMaster) return;
                inMaster = master;
                MasterPhysicalVignette.setMasterMode(master);
                SoftLight.syncHncsGrain(master);
                if (!master) leaveMaster();
            }
        });
    }

    private static void leaveMaster() {
        synchronized (QUEUE) { pending = null; }
        synchronized (MasterColor.class) {
            keyIdentity = null;
            candidateCameraId = null;
            previewResultAt = 0;
        }
    }

    // ---------------------------------------------------------------- preview results
    private static void hookResults() {
        // Android 17 hands CameraDeviceCallbacks.onResultReceived a CameraMetadataInfo (no get(Key));
        // every result still becomes a public TotalCaptureResult, read after construction.
        XposedBridge.hookAllConstructors(android.hardware.camera2.TotalCaptureResult.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!inMaster || param.hasThrowable()) return;
                Object result = param.thisObject;
                try {
                    syncSetting();
                    onPreviewResult(result);
                } catch (Throwable error) {
                    clearPreview("preview: source metadata read failed");
                    Log.e(TAG, "preview result", error);
                }
            }
        });
    }

    /**
     * Reads the EV of the physical camera that is driving the preview (`id`, the same one onPreviewResult just confirmed): every
     * physical camera streams its own TotalCaptureResult, and an inactive one's stale value would otherwise overwrite the real one.
     */
    private static void noteCameraEv(Object metadata, String id) {
        try {
            Integer steps = read(metadata, CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
            if (steps == null) {
                if (!evMissingReported) {
                    evMissingReported = true;
                    Log.i(TAG, "camera EV: the preview results carry no exposure compensation");
                }
                return;
            }
            float ev = steps * evStep(id);
            if (Math.abs(ev - cameraEv) < 0.005f) return;
            cameraEv = ev;
            Log.i(TAG, "camera EV " + ev);
        } catch (Throwable error) {
            Log.e(TAG, "camera EV", error);
        }
    }

    private static float evStep(String id) {
        if (id == null) return 1f / 6f;
        Float cached = evSteps.get(id);
        if (cached != null) return cached;
        float step = 1f / 6f;
        try {
            Context app = AndroidAppHelper.currentApplication();
            android.util.Rational rational = app == null ? null : ((CameraManager) app.getSystemService(Context.CAMERA_SERVICE))
                .getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (rational != null && rational.floatValue() > 0) step = rational.floatValue();
        } catch (Throwable error) {
            Log.e(TAG, "EV step " + id, error);
        }
        evSteps.put(id, step);
        return step;
    }

    private static <T> T read(Object metadata, CaptureResult.Key<T> key) {
        return ((CaptureResult) metadata).get(key);
    }

    // Scene front stage lenses (scene.bin, tools/mastercolor/export_scene.py): the 35 mm equivalent focal length
    // of a physical rear camera selects its colour calibration; within 25 % of a listed focal length.
    private static final String[] LENS_NAMES = {"uw14", "main23", "tele70", "tele230"};
    private static final double[] LENS_FOCALS = {14, 23, 70, 230};

    private static String lens(String id) {
        String cached = lenses.get(id);
        if (cached != null) return cached;
        Long failed = lensFailedAt.get(id);
        if (failed != null && System.currentTimeMillis() - failed < LENS_RETRY_MS) return null;
        String found = lookupLens(id);
        if (found == null) lensFailedAt.put(id, System.currentTimeMillis());
        return found;
    }

    private static String lookupLens(String id) {
        Context app = AndroidAppHelper.currentApplication();
        if (app == null) return null;
        try {
            CameraManager cameras = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics c = cameras.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (!c.getPhysicalCameraIds().isEmpty() || facing == null || facing != CameraCharacteristics.LENS_FACING_BACK
                || focals == null || focals.length == 0 || size == null || focals[0] <= 0) return null;
            double equivalent = focals[0] * 43.2666 / Math.hypot(size.getWidth(), size.getHeight());
            int best = -1;
            double bestError = Double.POSITIVE_INFINITY;
            for (int i = 0; i < LENS_FOCALS.length; ++i) {
                double error = Math.abs(Math.log(equivalent / LENS_FOCALS[i]));
                if (error < bestError) { bestError = error; best = i; }
            }
            if (best < 0) return null;
            lenses.put(id, LENS_NAMES[best]);
            return LENS_NAMES[best];
        } catch (Throwable error) {
            Log.e(TAG, "lens " + id, error);
            return null;
        }
    }

    private static float[] matrix(ColorSpaceTransform transform) {
        if (transform == null) return null;
        float[] values = new float[9];
        for (int i = 0; i < 9; ++i) values[i] = transform.getElement(i % 3, i / 3).floatValue();
        return values;
    }

    // A single bad preview result (AE/AF/3A lock around the shutter, a stream reconfiguration) must not flash the preview to
    // stock: only a failure that lasts keeps the table cleared.
    private static final long METADATA_FAIL_GRACE_MS = 400;
    private static long metadataFailSince;

    private static synchronized void clearPreview(String reason) {
        if (keyIdentity == null && previewDir == null) return;
        Log.i(TAG, reason);
        keyIdentity = null;
        if (previewDir != null) {
            previewDir = null;
            submit(CLEAR);
        }
    }

    /** A preview result this frame could not use: within the grace period, keeps showing the last good table instead of flashing;
     * past it, clears the preview as before. Either way the caller skips this frame. */
    private static void toleratedFailure(String reason) {
        long now = System.currentTimeMillis();
        if (metadataFailSince == 0) metadataFailSince = now;
        if (now - metadataFailSince >= METADATA_FAIL_GRACE_MS) clearPreview(reason);
    }

    private static synchronized void onPreviewResult(Object metadata) throws Exception {
        if (!enabled || look == null) return;
        String id = (String) read(metadata, CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        if (id == null) id = (String) XposedHelpers.callMethod(metadata, "getCameraId");
        if (id == null) { toleratedFailure("preview: physical camera identity unavailable"); return; }
        long now = System.currentTimeMillis();
        if (keyIdentity != null && !id.equals(keyIdentity)) {
            if (!id.equals(candidateCameraId)) { candidateCameraId = id; candidateSince = now; return; }
            if (now - candidateSince < CAMERA_SWITCH_MS) return;
        } else {
            candidateCameraId = null;
            if (now - previewResultAt < PREVIEW_INTERVAL_MS) return;
        }
        previewResultAt = now;
        noteCameraEv(metadata, id);
        String lens = lens(id);
        Integer cct = (Integer) read(metadata, AWB_CCT);
        float[] ccm = matrix((ColorSpaceTransform) read(metadata, CaptureResult.COLOR_CORRECTION_TRANSFORM));
        if (lens == null || cct == null || cct < 1500 || cct > 20000 || ccm == null) {
            toleratedFailure("preview: incomplete colour metadata for camera " + id); return;
        }
        metadataFailSince = 0;
        int keyCct = Math.max(1700, Math.min(15000, cct));
        Map<String, Float> keyParams = previewParams();
        byte[] key = keyBytes(keyCct, ccm, look, tonePercent, colourPercent, keyParams, lens);
        if (id.equals(keyIdentity) && previewKey != null && sameSettings(previewKey, key)
            && nativePreviewDistance(previewKey, key) <= PREVIEW_DISTANCE_CODES) return;
        keyIdentity = id;
        previewKey = key;
        Log.i(TAG, "preview key camera=" + id + " look=" + look + " cct=" + keyCct);
        submit(new Request(generation, keyCct, ccm, look, tonePercent, colourPercent, keyParams, lens));
    }

    /** The style settings of a preview key: the selection's, and "exact" = 0 while a film's exact lattice is still being built. */
    private static Map<String, Float> previewParams() {
        Map<String, Float> params = styleParams;
        String id = look;
        if (exactReady || id == null || !id.startsWith("film_")) return params;
        TreeMap<String, Float> out = new TreeMap<>(params);
        out.put("exact", 0f);
        return out;
    }

    private static boolean sameSettings(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = KEY_SETTINGS_OFFSET; i < a.length; ++i) if (a[i] != b[i]) return false;
        return true;
    }

    /** The distance between two keys' tables in 10-bit codes (see PREVIEW_DISTANCE_CODES); huge when a key is unreadable. */
    private static native double nativePreviewDistance(byte[] a, byte[] b);

    /** MCOLKEY9: the common key, the look, the blend weights, the scene front stage lens, then the style settings by name. */
    private static byte[] keyBytes(int cct, float[] ccm, String look, int tonePercent, int colourPercent, Map<String, Float> params, String lens) {
        float tone = tonePercent / 100f, colour = colourPercent / 100f;
        byte[] name = look.getBytes(StandardCharsets.US_ASCII);
        byte[] camera = lens.getBytes(StandardCharsets.US_ASCII);
        int size = 8 + 4 * 4 + 9 * 4 + 4 + name.length + 8 + 4 + camera.length + 4;
        for (String setting : params.keySet()) size += 4 + setting.length() + 4;
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("MCOLKEY9".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(2).putInt(cct);
        for (float v : ccm) buffer.putFloat(v);
        buffer.putInt(0).putInt(0);
        buffer.putInt(name.length).put(name);
        buffer.putFloat(tone).putFloat(colour);
        buffer.putInt(camera.length).put(camera);
        buffer.putInt(params.size());
        for (Map.Entry<String, Float> entry : params.entrySet()) {
            byte[] setting = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            buffer.putInt(setting.length).put(setting).putFloat(entry.getValue());
        }
        return buffer.array();
    }

    private static String writeKey(int cct, float[] ccm, String look, int tonePercent, int colourPercent, Map<String, Float> params, String lens) {
        byte[] bytes = keyBytes(cct, ccm, look, tonePercent, colourPercent, params, lens);
        long hash = 1469598103934665603L;
        for (byte b : bytes) hash = (hash ^ (b & 0xff)) * 1099511628211L;
        String dir = LIVE + String.format(Locale.ROOT, "p%05d_%016x", cct, hash);
        File folder = new File(dir);
        File key = new File(folder, "key.bin");
        if (key.exists()) {
            key.setLastModified(System.currentTimeMillis()); // recently used: native pruning keeps it
            return dir;
        }
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return null;
            File tmp = new File(folder, "key.bin.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(key)) return null;
            return dir;
        } catch (Throwable error) {
            Log.e(TAG, "preview key write", error);
            return null;
        }
    }
}
