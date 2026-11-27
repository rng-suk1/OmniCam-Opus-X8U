package local.jiege.hook.palette;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.filters.FilterCore;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.MasterConfig;
import local.jiege.hook.filters.MasterLayout;
import local.jiege.hook.filters.NormalMasterMirror;
import local.jiege.settings.MasterFilterContract;
import local.jiege.settings.MasterFilterStore;
import local.jiege.settings.ModuleSettings;

/**
 * Gallery process: the module's filters in the photo editor's filter / colour style lists.
 *
 * The gallery lists the camera unit SDK's filter groups (FilterGroupManager, from the jar
 * GalleryColorPalette loads). After the SDK fills them, the default catalog entries are appended:
 * normal entries to the common group, master entries to the master group. The name is the entry
 * title (the gallery shows a plain name as it is; "R.string.x" names are its own resources).
 * Imported LUTs live in the camera's data and are left out, as is any entry whose LUT is not in
 * the shared LUT folders. Appending keeps the stock indices, which watermarks refer to.
 */
final class GalleryFilters {
    private static final String TAG = "GalleryFilters";
    private static final String MANAGER = "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroupManager";
    private static final String[] LUT_DIRS = {"/odm/etc/camera/meishe_lut/", "/odm/etc/camera/filters_lut/"};

    private GalleryFilters() {}

    static void install(ClassLoader loader) {
        hookPaletteTables(loader);
        try {
            final Class<?> manager = XposedHelpers.findClass(MANAGER, loader);
            XposedHelpers.findAndHookMethod(manager, "init", android.content.Context.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MasterConfig config = loadMasterConfig();
                    int normal = append(XposedHelpers.getStaticObjectField(manager, "sFilterGroup"), FilterScope.NORMAL, config);
                    int master = append(XposedHelpers.getStaticObjectField(manager, "sProFilterGroup"), FilterScope.MASTER, config);
                    Log.i(TAG, "gallery filters synced: normal " + normal + ", master " + master + ", visibleOverrides=" + config.visible.size());
                    masterTypes = typesOf(XposedHelpers.getStaticObjectField(manager, "sProFilterGroup"));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gallery filter hook failed", error);
        }
    }

    private static volatile Set<String> masterTypes = Collections.emptySet();

    /** The type ids of the master group as the editor lists them; empty before the SDK filled it. */
    static Set<String> masterTypes() {
        return masterTypes;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> typesOf(Object group) {
        return new HashSet<>((List<String>) XposedHelpers.getObjectField(group, "mBackTypeList"));
    }

    private static MasterConfig loadMasterConfig() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MasterFilterContract.MODULE_PACKAGE, ModuleSettings.FILE);
            File dir = prefs.getFile().getParentFile();
            File file = dir == null ? null : new File(dir, MasterFilterStore.CONFIG);
            if (file != null && file.isFile()) {
                return MasterConfig.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.e(TAG, "load master_config failed", error);
        }
        return MasterConfig.empty();
    }

    @SuppressWarnings("unchecked")
    private static int append(Object group, FilterScope scope, MasterConfig config) {
        int added = 0;
        try {
            List<String> types = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
            java.lang.reflect.Field nameField = findNameListField(group.getClass(), false);
            List<?> names = nameField != null ? (List<?>) nameField.get(group) : null;
            // Hide stock filters explicitly turned off in MUSE master filter settings.
            if (types != null && names != null && types.size() == names.size()) {
                for (int i = types.size() - 1; i >= 0; i--) {
                    String id = types.get(i);
                    if ("default".equals(id)) continue;
                    if (Boolean.FALSE.equals(config.visible.get(id))) {
                        types.remove(i);
                        names.remove(i);
                    }
                }
            }
            for (FilterEntry entry : FilterCore.get(scope).entries()) {
                if (entry.masterOnly || entry.source == FilterEntry.Source.IMPORTED) continue;
                Boolean override = config.visible.get(entry.id);
                boolean visible = override != null ? override : entry.enabled;
                if (!visible) continue;
                String galleryId = galleryLutId(entry.id);
                if (!lutExists(galleryId)) continue;
                registerPalette(galleryId);
                if (types.contains(galleryId)) continue;
                String name = renamedTitle(scope, galleryId, config);
                if (name == null) name = entry.title != null ? entry.title : "R.string." + entry.nameResource;
                XposedHelpers.callMethod(group, "addBack", galleryId, name);
                added++;
            }
            if (types != null && names != null && types.size() == names.size()) {
                List<String> order = orderForScope(scope, config);
                if (!order.isEmpty()) {
                    MasterLayout.Saved saved = new MasterLayout.Saved(order, config.visible);
                    MasterLayout.Result layout = MasterLayout.compute(types, FilterCore.get(scope), saved, true);
                    MasterLayout.reorder(types, (List<Object>) names, layout.order);
                    reorderFrontIfPresent(group, layout.order);
                }
                applyRenames(types, (List<Object>) names, scope, config);
                applyFrontRenamesIfPresent(group, scope, config);
            }
        } catch (Throwable error) {
            Log.e(TAG, "gallery " + scope + " filters failed", error);
        }
        return added;
    }

    private static String galleryLutId(String id) {
        if ("JG_RICOH_POSITIVE.cube.rgb.bin".equals(id) && lutExists("gr.posi.rgba.bin")) return "gr.posi.rgba.bin";
        if ("JG_RICOH_NEGATIVE.cube.rgb.bin".equals(id) && lutExists("gr.nega.rgba.bin")) return "gr.nega.rgba.bin";
        return id;
    }

    private static String configIdForGallery(String galleryId) {
        if ("gr.posi.rgba.bin".equals(galleryId)) return "JG_RICOH_POSITIVE.cube.rgb.bin";
        if ("gr.nega.rgba.bin".equals(galleryId)) return "JG_RICOH_NEGATIVE.cube.rgb.bin";
        return galleryId;
    }

    private static List<String> orderForScope(FilterScope scope, MasterConfig config) {
        if (config.order.isEmpty()) return config.order;
        List<String> mapped = new ArrayList<>(config.order.size());
        for (String id : config.order) {
            String target = scope == FilterScope.MASTER ? id : NormalMasterMirror.toNormal(id);
            mapped.add(galleryLutId(target));
        }
        return mapped;
    }

    private static String renamedTitle(FilterScope scope, String id, MasterConfig config) {
        String renamed = config.renameOf(id);
        if (renamed != null) return renamed;
        String configId = configIdForGallery(id);
        if (!configId.equals(id)) {
            renamed = config.renameOf(configId);
            if (renamed != null) return renamed;
        }
        if (scope == FilterScope.NORMAL) {
            String master = NormalMasterMirror.toMaster(id);
            if (master != null) return config.renameOf(master);
        }
        return null;
    }

    private static void applyRenames(List<String> types, List<Object> names, FilterScope scope, MasterConfig config) {
        if (config.renames.isEmpty() || types == null || names == null || types.size() != names.size()) return;
        for (int i = 0; i < types.size(); i++) {
            String renamed = renamedTitle(scope, types.get(i), config);
            if (renamed != null) names.set(i, renamed);
        }
    }

    private static java.lang.reflect.Field findNameListField(Class<?> cls, boolean front) {
        String[] candidates = front
            ? new String[] {"mFrontNameIdList", "mFrontNameList", "mFrontNameResList"}
            : new String[] {"mBackNameIdList", "mBackNameList", "mBackNameResList"};
        for (String name : candidates) {
            java.lang.reflect.Field field = XposedHelpers.findFieldIfExists(cls, name);
            if (field != null) return field;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void reorderFrontIfPresent(Object group, List<String> order) {
        try {
            java.lang.reflect.Field typeField = XposedHelpers.findFieldIfExists(group.getClass(), "mFrontTypeList");
            java.lang.reflect.Field nameField = findNameListField(group.getClass(), true);
            if (typeField == null || nameField == null) return;
            List<String> frontTypes = (List<String>) typeField.get(group);
            List<Object> frontNames = (List<Object>) nameField.get(group);
            if (frontTypes != null && frontNames != null && frontTypes.size() == frontNames.size() && !frontTypes.isEmpty()) {
                MasterLayout.reorder(frontTypes, frontNames, order);
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void applyFrontRenamesIfPresent(Object group, FilterScope scope, MasterConfig config) {
        if (config.renames.isEmpty()) return;
        try {
            java.lang.reflect.Field typeField = XposedHelpers.findFieldIfExists(group.getClass(), "mFrontTypeList");
            java.lang.reflect.Field nameField = findNameListField(group.getClass(), true);
            if (typeField == null || nameField == null) return;
            List<String> frontTypes = (List<String>) typeField.get(group);
            List<Object> frontNames = (List<Object>) nameField.get(group);
            applyRenames(frontTypes, frontNames, scope, config);
        } catch (Throwable ignored) {}
    }

    private static final Set<String> moduleTypes = Collections.synchronizedSet(new HashSet<>());

    private static void registerPalette(String type) {
        moduleTypes.add(type);
    }

    /**
     * The editor opens the palette panel only for types in the SDK's colour tables (hasPalette, get); the
     * tables are fixed maps without the module's filters. A module filter is answered with the default row
     * of the table, which keeps the panel and its seek bar drawable.
     */
    private static void hookPaletteTables(ClassLoader loader) {
        String base = "com.oplus.ocs.camera.ipusdk.processunit.filter.palette.";
        for (String table : new String[] {"PaletteColorTable", "PaletteSeekbarColorTable"}) {
            try {
                Class<?> cls = XposedHelpers.findClass(base + table, loader);
                XposedHelpers.findAndHookMethod(cls, "get", String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null || !moduleTypes.contains(param.args[0])) return;
                        param.setResult(XposedHelpers.callStaticMethod(cls, "get", "default"));
                    }
                });
                if ("PaletteColorTable".equals(table)) {
                    XposedHelpers.findAndHookMethod(cls, "hasPalette", String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (moduleTypes.contains(param.args[0])) param.setResult(Boolean.TRUE);
                        }
                    });
                }
            } catch (Throwable error) {
                Log.e(TAG, "palette table hook " + table, error);
            }
        }
    }

    private static boolean lutExists(String type) {
        for (String dir : LUT_DIRS) {
            if (new File(dir + type).isFile()) return true;
        }
        return false;
    }
}
