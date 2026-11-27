package local.jiege.settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import local.jiege.hook.filters.FilterCore;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.MasterConfig;
import local.jiege.hook.filters.MasterLayout;
import local.jiege.lut.Rec709LutBaker;

/**
 * The settings app's master filter data (plain Java, no Android, so it can be tested offline).
 * All files lie flat in one directory, readable by everyone:
 *   <dir>/master_config.txt   MasterConfig (order, visibility, imported filters)
 *   <dir>/master_camera.txt   the camera's laid-out master list (MasterFilterContract stock text)
 *   <dir>/master_lut_<id>     imported LUTs, device format
 * In the app the directory is the module's LSPosed preferences directory (xposedsharedprefs),
 * which the camera reads directly (MasterFilterSync), without starting this app. Changes apply
 * when the camera starts next.
 */
public final class MasterFilterStore {
    public static final String CONFIG = "master_config.txt";
    private static final String CAMERA = "master_camera.txt";
    public static final String LUT_PREFIX = "master_lut_";
    /** Backup archive layout: config.txt and lut/<id>. */
    private static final String BACKUP_CONFIG = "config.txt";
    private static final String BACKUP_LUT = "lut/";
    /** Names of X10 filters, which keep the camera's own name resource. */
    private static final Map<String, String> CAMERA_NAMES = new LinkedHashMap<>();

    static {
        CAMERA_NAMES.put(MasterLayout.STANDARD, "标准");
        CAMERA_NAMES.put("qing_tou.bin", "清透");
        CAMERA_NAMES.put("hu_po.bin", "琥珀");
    }

    /** One line of the master filter list in the settings app. */
    public static final class Row {
        public final String id;
        public final String category;
        public final String title;
        public final String defaultTitle;
        public final boolean renamed;
        public final boolean visible;
        /** 标准 cannot be hidden. */
        public final boolean fixed;
        public final boolean imported;
        /** A custom preset (custom_N) saved in the camera; category names the filter it uses. */
        public final boolean preset;

        Row(String id, String category, String title, String defaultTitle, boolean renamed,
            boolean visible, boolean fixed, boolean imported, boolean preset) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.defaultTitle = defaultTitle;
            this.renamed = renamed;
            this.visible = visible;
            this.fixed = fixed;
            this.imported = imported;
            this.preset = preset;
        }

        /** "类别-名称". */
        public String displayName() {
            return category + "-" + title;
        }
    }

    private final File dir;

    public MasterFilterStore(File dir) {
        this.dir = dir;
    }

    // ---- configuration --------------------------------------------------------------------------

    public synchronized MasterConfig config() {
        return MasterConfig.parse(read(new File(dir, CONFIG)));
    }

    public synchronized String configText() {
        return config().write();
    }

    private void save(MasterConfig config) throws IOException {
        write(new File(dir, CONFIG), config.write().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stores the camera's list. Order and visibility records of custom presets the camera no longer
     * has are dropped, so a later preset reusing that custom_N starts with defaults.
     */
    public synchronized void saveCameraList(String text) throws IOException {
        String list = text == null ? "" : text;
        write(new File(dir, CAMERA), list.getBytes(StandardCharsets.UTF_8));
        List<String> presets = new ArrayList<>();
        for (String line : list.split("\n")) {
            String id = line.split("\t", -1)[0];
            if (isPreset(id)) presets.add(id);
        }
        MasterConfig config = config();
        List<String> order = new ArrayList<>();
        for (String id : config.order) if (!isPreset(id) || presets.contains(id)) order.add(id);
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> e : config.visible.entrySet()) if (!isPreset(e.getKey()) || presets.contains(e.getKey())) visible.put(e.getKey(), e.getValue());
        if (order.size() != config.order.size() || visible.size() != config.visible.size()) {
            save(new MasterConfig(order, visible, config.imported, config.renames));
        }
    }

    public static boolean isPreset(String id) {
        return id != null && id.startsWith("custom_");
    }

    /** True once the camera has reported its master list (camera started with the module). */
    public synchronized boolean hasCameraList() {
        return new File(dir, CAMERA).isFile();
    }

    public File lutFile(String id) {
        return lutFile(dir, id);
    }

    public static File lutFile(File dir, String id) {
        if (!MasterConfig.isImportId(id)) throw new IllegalArgumentException("Not an imported filter: " + id);
        return new File(dir, LUT_PREFIX + id);
    }

    // ---- list -------------------------------------------------------------------------------------

    /** The master list as the camera will show it next start: visible first, then hidden. */
    public synchronized List<Row> rows() {
        MasterConfig config = config();
        FilterCore.configure(config);
        FilterCore catalog = FilterCore.get(FilterScope.MASTER);
        Map<String, String> cameraNames = new LinkedHashMap<>();
        Map<String, String> presetTypes = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        for (String line : read(new File(dir, CAMERA)).split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            String id = fields[0];
            // Imported filters deleted since the camera's report are gone.
            if (MasterConfig.isImportId(id) && catalog.find(id) == null) continue;
            if (!current.contains(id)) current.add(id);
            if (fields.length > 1 && !fields[1].isEmpty()) cameraNames.put(id, fields[1]);
            if (isPreset(id) && fields.length > 2) presetTypes.put(id, fields[2]);
        }
        if (!current.contains(MasterLayout.STANDARD)) current.add(0, MasterLayout.STANDARD);
        for (FilterEntry entry : catalog.entries()) if (!current.contains(entry.id)) current.add(entry.id);

        MasterLayout.Result layout = MasterLayout.compute(current, catalog, config.layout(), false);
        List<Row> rows = new ArrayList<>();
        for (String id : layout.order) {
            if (isPreset(id)) {
                String type = presetTypes.get(id);
                FilterEntry used = catalog.find(type);
                String lut = config.renameOf(type) != null ? config.renameOf(type)
                    : used != null && used.title != null ? used.title
                    : cameraNames.containsKey(type) ? cameraNames.get(type)
                    : CAMERA_NAMES.containsKey(type) ? CAMERA_NAMES.get(type) : "标准";
                String presetTitle = cameraNames.containsKey(id) ? cameraNames.get(id) : id;
                rows.add(new Row(id, "我的预设 · " + lut, presetTitle, presetTitle, false,
                    MasterLayout.isVisible(id, (FilterEntry) null, config.layout()), false, false, true));
                continue;
            }
            FilterEntry entry = catalog.find(id);
            String category = entry == null ? FilterEntry.Category.PRESET.displayName : entry.category.displayName;
            String defaultTitle = entry != null && entry.title != null ? entry.title
                : cameraNames.containsKey(id) ? cameraNames.get(id)
                : CAMERA_NAMES.containsKey(id) ? CAMERA_NAMES.get(id) : id;
            boolean imported = entry != null && entry.source == FilterEntry.Source.IMPORTED;
            String customName = imported ? null : config.renameOf(id);
            boolean renamed = customName != null && !customName.isEmpty() && !customName.equals(defaultTitle);
            String title = renamed ? customName : defaultTitle;
            rows.add(new Row(id, category, title, defaultTitle, renamed, MasterLayout.isVisible(id, catalog, config.layout()),
                MasterLayout.STANDARD.equals(id), imported, false));
        }
        return rows;
    }

    /** Moves the row at from to position to; 标准 moves like any other entity. */
    public synchronized void move(int from, int to) throws IOException {
        List<Row> rows = rows();
        if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size() || from == to) return;
        List<String> order = ids(rows);
        order.add(to, order.remove(from));
        MasterConfig config = config();
        save(new MasterConfig(order, config.visible, config.imported, config.renames));
    }

    /** Drops the saved order: filters return to the camera's order, custom presets to its head. */
    public synchronized void resetOrder() throws IOException {
        MasterConfig config = config();
        save(new MasterConfig(new ArrayList<>(), config.visible, config.imported, config.renames));
    }

    public synchronized void setVisible(String id, boolean visible) throws IOException {
        if (MasterLayout.STANDARD.equals(id)) return;
        MasterConfig config = config();
        Map<String, Boolean> map = new LinkedHashMap<>(config.visible);
        map.put(id, visible);
        save(new MasterConfig(config.order, map, config.imported, config.renames));
    }

    // ---- import / rename / delete ---------------------------------------------------------------

    /** Converts a Rec.709 .cube and adds it as an imported filter; returns its ID. */
    public synchronized String importCube(String fileName, InputStream cube, byte[] header) throws IOException {
        Rec709LutBaker.Cube parsed = Rec709LutBaker.parse(new InputStreamReader(cube, StandardCharsets.UTF_8));
        byte[] lut = Rec709LutBaker.bake(parsed, header);
        String id = MasterConfig.IMPORT_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT)
            + MasterConfig.LUT_SUFFIX;
        File file = lutFile(id);
        write(file, lut);
        MasterConfig config = config();
        List<MasterConfig.Imported> imported = new ArrayList<>(config.imported);
        imported.add(new MasterConfig.Imported(id, titleOf(fileName), lut.length));
        save(new MasterConfig(config.order, config.visible, imported, config.renames));
        return id;
    }

    /**
     * Renames any filter (imported or built-in). For built-in filters, an empty newTitle removes
     * the override and restores the default name.
     */
    public synchronized void rename(String id, String newTitle) throws IOException {
        if (id == null || id.isEmpty() || isPreset(id)) throw new IllegalArgumentException("不支持重命名该项: " + id);
        if (MasterConfig.isImportId(id)) {
            renameImported(id, newTitle);
            return;
        }
        String cleaned = MasterConfig.cleanTitle(newTitle);
        MasterConfig config = config();
        Map<String, String> renames = new LinkedHashMap<>(config.renames);
        if (cleaned.isEmpty()) {
            renames.remove(id);
        } else {
            renames.put(id, cleaned);
        }
        save(new MasterConfig(config.order, config.visible, config.imported, renames));
    }

    /** Renames an imported filter in place, keeping its ID, LUT file, order and visibility. */
    public synchronized void renameImported(String id, String newTitle) throws IOException {
        if (!MasterConfig.isImportId(id)) throw new IllegalArgumentException("Not an imported filter: " + id);
        String cleaned = new MasterConfig.Imported(id, newTitle, 1).title;
        if (cleaned.isEmpty()) throw new IllegalArgumentException("滤镜名称不能为空");
        MasterConfig config = config();
        List<MasterConfig.Imported> imported = new ArrayList<>(config.imported.size());
        boolean found = false;
        for (MasterConfig.Imported item : config.imported) {
            if (item.id.equals(id)) {
                imported.add(new MasterConfig.Imported(item.id, cleaned, item.size));
                found = true;
            } else {
                imported.add(item);
            }
        }
        if (!found) throw new IllegalArgumentException("未找到该导入滤镜");
        save(new MasterConfig(config.order, config.visible, imported, config.renames));
    }

    public synchronized void deleteImported(String id) throws IOException {
        MasterConfig config = config();
        List<MasterConfig.Imported> imported = new ArrayList<>();
        for (MasterConfig.Imported item : config.imported) if (!item.id.equals(id)) imported.add(item);
        List<String> order = new ArrayList<>(config.order);
        order.remove(id);
        Map<String, Boolean> visible = new LinkedHashMap<>(config.visible);
        visible.remove(id);
        Map<String, String> renames = new LinkedHashMap<>(config.renames);
        renames.remove(id);
        save(new MasterConfig(order, visible, imported, renames));
        lutFile(id).delete();
    }

    static String titleOf(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".cube")) name = name.substring(0, name.length() - 5);
        name = name.trim();
        return name.isEmpty() ? "LUT" : name;
    }

    // ---- backup ---------------------------------------------------------------------------------

    /** Writes config.txt and every imported LUT into a zip archive. */
    public synchronized void backup(OutputStream out) throws IOException {
        MasterConfig config = config();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(BACKUP_CONFIG));
            zip.write(config.write().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (MasterConfig.Imported item : config.imported) {
                File file = lutFile(item.id);
                if (!file.isFile()) continue;
                zip.putNextEntry(new ZipEntry(BACKUP_LUT + item.id));
                zip.write(Files.readAllBytes(file.toPath()));
                zip.closeEntry();
            }
        }
    }

    /**
     * Replaces the configuration and the imported LUTs with a backup. The archive is checked
     * first (config present, every imported LUT present and of the recorded size); nothing is
     * changed if it is not valid.
     */
    public synchronized void restore(InputStream in) throws IOException {
        String configText = null;
        Map<String, byte[]> luts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                byte[] data = readAll(zip, 64L << 20);
                if (BACKUP_CONFIG.equals(entry.getName())) configText = new String(data, StandardCharsets.UTF_8);
                else if (entry.getName().startsWith(BACKUP_LUT)) luts.put(entry.getName().substring(BACKUP_LUT.length()), data);
            }
        }
        if (configText == null) throw new IOException("Not a backup: config.txt missing");
        MasterConfig config = MasterConfig.parse(configText);
        for (MasterConfig.Imported item : config.imported) {
            byte[] data = luts.get(item.id);
            if (data == null || data.length != item.size) throw new IOException("Backup LUT missing or damaged: " + item.title);
        }
        Set<String> keep = new HashSet<>();
        for (MasterConfig.Imported item : config.imported) {
            write(lutFile(item.id), luts.get(item.id));
            keep.add(item.id);
        }
        File[] existing = dir.listFiles();
        for (File file : existing == null ? new File[0] : existing) {
            String name = file.getName();
            if (name.startsWith(LUT_PREFIX) && !keep.contains(name.substring(LUT_PREFIX.length()))) file.delete();
        }
        save(config);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<String> ids(List<Row> rows) {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) ids.add(row.id);
        return ids;
    }

    private static String read(File file) {
        try {
            return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException error) {
            return "";
        }
    }

    /** Writes through a temporary file, so readers never see a partial file; readable by all. */
    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File temp = new File(parent, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        }
        temp.setReadable(true, false);
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) throw new IOException("Cannot write " + file);
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > limit) throw new IOException("Backup entry too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
