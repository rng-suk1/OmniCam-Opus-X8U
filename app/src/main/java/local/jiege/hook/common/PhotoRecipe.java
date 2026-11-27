package local.jiege.hook.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 拍摄配方（模式、色彩引擎、滤镜、柔光）的 EXIF UserComment 编解码工具。
 * 格式：OmniCam|m=大师|e=Leica M9 · 标准|f=电影|s=HDF
 */
public final class PhotoRecipe {
    public static final String PREFIX = "OmniCam|";

    public final String mode;
    public final String engine;
    public final String filter;
    public final String softLight;

    public PhotoRecipe(String mode, String engine, String filter, String softLight) {
        this.mode = clean(mode);
        this.engine = clean(engine);
        this.filter = clean(filter);
        this.softLight = clean(softLight);
    }

    public boolean isEmpty() {
        return mode == null && engine == null && filter == null && softLight == null;
    }

    /** 编码为可写入 EXIF UserComment 的紧凑字符串。 */
    public String encode() {
        List<String> parts = new ArrayList<>(4);
        if (mode != null) parts.add("m=" + escape(mode));
        if (engine != null) parts.add("e=" + escape(engine));
        if (filter != null) parts.add("f=" + escape(filter));
        if (softLight != null) parts.add("s=" + escape(softLight));
        if (parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 从 EXIF UserComment 解析配方；非 OmniCam 数据返回 null。 */
    public static PhotoRecipe parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        int idx = raw.indexOf(PREFIX);
        if (idx < 0) return null;
        String body = raw.substring(idx + PREFIX.length()).trim();
        if (body.isEmpty()) return null;
        String mode = null;
        String engine = null;
        String filter = null;
        String softLight = null;
        for (String token : body.split("\\|")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq >= token.length() - 1) continue;
            String k = token.substring(0, eq);
            String v = unescape(token.substring(eq + 1));
            switch (k) {
                case "m": mode = v; break;
                case "e": engine = v; break;
                case "f": filter = v; break;
                case "s": softLight = v; break;
                default: break;
            }
        }
        PhotoRecipe recipe = new PhotoRecipe(mode, engine, filter, softLight);
        return recipe.isEmpty() ? null : recipe;
    }

    /** 格式化为相册详情页展示文本。 */
    public String formatDisplay() {
        List<String> parts = new ArrayList<>(4);
        if (mode != null) parts.add(mode);
        if (engine != null) parts.add("色彩 " + engine);
        if (filter != null) parts.add("滤镜 " + filter);
        if (softLight != null) parts.add("柔光 " + softLight);
        if (parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String clean(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escape(String s) {
        return s.replace("|", "/").replace("\n", " ").replace("\r", "");
    }

    private static String unescape(String s) {
        return s.trim();
    }
}
