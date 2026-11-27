package local.jiege.settings;

/**
 * Settings shared by the settings UI (writer) and the hooks (reader, via LSPosed's
 * xposedsharedprefs). Keys and defaults live only here.
 */
public final class ModuleSettings {
    /** SharedPreferences file name in the module app. */
    public static final String FILE = "module_settings";

    /** POP: let POP use photo mode's 25M output size decision in good light. */
    public static final String POP_HIGH_PIXEL = "pop_high_pixel";
    /** POP captures as SDR (8-bit JPEG) instead of Ultra HDR. */
    public static final String POP_SDR_STYLE = "pop_sdr_style";

    /** Shutter sound: master switch for the two cases below. */
    public static final String SHUTTER_ALWAYS = "shutter_always";
    public static final String SHUTTER_IN_SILENT = "shutter_in_silent";
    public static final String SHUTTER_IN_VIBRATE = "shutter_in_vibrate";
    /** Shutter sound with headphones connected: only on the headphones, without interrupting their music. */
    public static final String SHUTTER_KEEP_MEDIA = "shutter_keep_media";
    /** Shutter sound: own volume (SHUTTER_VOLUME percent, on the media stream) in every ringer mode. */
    public static final String SHUTTER_VOLUME_CUSTOM = "shutter_volume_custom";
    /** Integer setting, 0-100. */
    public static final String SHUTTER_VOLUME = "shutter_volume";
    /** Shutter sound style of the modes other than GR and XPAN: SHUTTER_SOUND_* (integer, applies after the camera restarts). */
    public static final String SHUTTER_SOUND = "shutter_sound";
    public static final int SHUTTER_SOUND_MODERN = 0;
    public static final int SHUTTER_SOUND_RETRO = 1;
    public static final int SHUTTER_SOUND_XPAN = 2;
    public static final int SHUTTER_SOUND_GR = 3;

    /** Flash and live photo state kept per mode instead of shared by all photo modes. */
    public static final String FLASH_PER_MODE = "flash_per_mode";
    public static final String LIVE_PHOTO_PER_MODE = "live_photo_per_mode";

    /** Master mode: restore effects that the normal-mode version of a filter has. */
    public static final String MASTER_EFFECT_NEON = "master_effect_neon";
    public static final String MASTER_EFFECT_PORTRA = "master_effect_portra";
    public static final String MASTER_PRO_EXPAND = "master_pro_expand";
    public static final String MASTER_PRO_LABEL = "master_pro_label";

    /**
     * Master colour: the style plugin (MasterStyles; the persisted MasterStyle.index). A style's own choices and
     * parameters are integer settings it declares itself (MasterStyle), with their defaults.
     */
    public static final String MASTER_LOOK = "master_look";
    /** Master colour mode (MasterStyles.MODE_*): 0 replace the stock LMT, 1 blend with it. */
    public static final String MASTER_LOOK_MODE = "master_look_mode";
    /** Blend mode: how much of the chosen look replaces the stock tone / colour, percent (100 = all of it). */
    public static final String MASTER_BLEND_TONE = "master_blend_tone";
    public static final String MASTER_BLEND_COLOUR = "master_blend_colour";
    /** Master colour exposure compensation in 1/100 EV on top of the engine default (no UI yet). */
    public static final String MASTER_LOOK_EXPOSURE = "master_look_exposure";
    /** Master colour exposure alignment to the stock look (MasterAlign.*): added to the exposure compensation. */
    public static final String MASTER_ALIGN = "master_align";
    /** Master colour exposure compensation mode (MasterAlign.MODE_*): an EV offset, or an alignment to the stock look. */
    public static final String MASTER_EXPOSURE_MODE = "master_exposure_mode";
    /** Master colour per-engine white point offset: temperature (-800..800 K) and cyan-magenta tint (-50..50). */
    public static final String MASTER_WB_TEMP = "master_wb_temp";
    public static final String MASTER_WB_TINT = "master_wb_tint";
    /** Optical cos^4(theta) vignette mode in Master mode: 0 off, 1 on, 2 follow per-engine preference. */
    public static final String MASTER_FILM_VIGNETTE = "master_look_vignette";
    public static final int VIGNETTE_OFF = 0;
    public static final int VIGNETTE_ON = 1;
    public static final int VIGNETTE_FOLLOW_ENGINE = 2;
    /** Global luminance compensation for optical vignette in non-stock master colour engines (0..100 -> 0.0..1.0). */
    public static final String MASTER_VIGNETTE_LUMA_COMP = "master_vignette_luma_comp";

    public static String vignetteKey(int styleIndex) {
        return MASTER_FILM_VIGNETTE + "_" + styleIndex;
    }

    public static String vignetteCompKey(int styleIndex) {
        return MASTER_VIGNETTE_LUMA_COMP + "_" + styleIndex;
    }

    public static boolean isVignetteKey(String key) {
        return hasNumericSuffix(key, MASTER_FILM_VIGNETTE) || hasNumericSuffix(key, MASTER_VIGNETTE_LUMA_COMP);
    }

    private static boolean hasNumericSuffix(String key, String base) {
        if (base.equals(key)) return true;
        if (!key.startsWith(base + "_")) return false;
        String suffix = key.substring(base.length() + 1);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    /** Normal video: keep normal filters at 4K30 / 4K60 / 1080p60 (stock falls back to 1080p30). */
    public static final String VIDEO_FILTER_HIGH_SPEC = "video_filter_high_spec";
    /** Normal video: keep face beauty at 4K / 60fps (stock turns them off or falls back to 1080p30). */
    public static final String VIDEO_BEAUTY_HIGH_SPEC = "video_beauty_high_spec";
    public static final String VIEWFINDER_HDR = "viewfinder_hdr";
    /** Master Pro gear: focus peaking is drawn with autofocus too (stock: manual focus only). */
    public static final String PRO_PEAKING_AF = "pro_peaking_af";

    /** Natural photo mode: master switch, the two features it turns off, and the extra softening (0-100 %). */
    public static final String NATURAL_MODE = "natural_mode";
    public static final String NATURAL_NO_AI_DETAIL = "natural_no_ai_detail";
    public static final String NATURAL_NO_LCE = "natural_no_lce";
    public static final String NATURAL_SOFTEN = "natural_soften";
    /** Natural photo mode: no sunset tuning (classifier never reports sunset, SunsetEnhance off). */
    public static final String NATURAL_NO_SUNSET = "natural_no_sunset";
    /** Natural photo mode: no high-zoom LDR contrast (highZoomLceContrastParam / hightZoom200MLceParm = 1). */
    public static final String NATURAL_NO_TELE_CONTRAST = "natural_no_tele_contrast";
    /** Natural photo mode: no stage-2 AI super-resolution (the GAN route, and the diffusion model above 60x). */
    public static final String NATURAL_NO_TELE_SR = "natural_no_tele_sr";

    /** Independent capture dumps, read when the camera process starts. */
    public static final String DUMP_RAW = "dump_raw";
    public static final String DUMP_CCM = "dump_ccm_vec";
    public static final String DUMP_HDRT = "dump_hdrtransform";

    /** Normal-mode 25MP: relaxed lux / ISO / thermal gates and decision tree (applies after the camera restarts). */
    public static final String UNLOCK_25MP = "unlock_25mp";

    /** 哈苏超清: filters and palette (menu entries and the palette capture / preview path). */
    public static final String HIGH_PIXEL_EFFECTS = "high_pixel_effects";

    /** 哈苏超清: real 200MP readout without the big-face condition (applies after the camera restarts). */
    public static final String HIGH_PIXEL_200M_NO_FACE = "high_pixel_200m_no_face";

    /** Photo, portrait and 哈苏超清 (with its palette) list the master filters in the master order (applies after the camera restarts). */
    public static final String NORMAL_FOLLOWS_MASTER = "normal_follows_master";

    /** Experimental: replace a stock soft light level with a physically simulated filter (applies when the camera opens). */
    public static final String SOFT_LIGHT_WEAK_HDF = "soft_light_weak_hdf";
    public static final String SOFT_LIGHT_MEDIUM_BLACK_MIST = "soft_light_medium_black_mist";
    public static final String SOFT_LIGHT_STRONG_WHITE_MIST = "soft_light_strong_white_mist";

    /** The photo editor renders a master capture's soft light with the replaced levels, at the photo's focal length. */
    public static final String SOFT_LIGHT_GALLERY = "soft_light_gallery";

    /** Master captures with a replaced soft light skip HDRTransform's local contrast fusion. */
    public static final String SOFT_LIGHT_NO_LOCAL_CONTRAST = "soft_light_no_local_contrast";

    /** Experimental: 和光 capture renders Portra 400's emulsion scatter, halation and DIR edges (实验/Portra光学) instead of the stock highlight blend. */
    public static final String MASTER_PORTRA_PHYSICAL = "master_portra_physical";

    /** Experimental: XPAN photos become live photos playing the developing animation (applies after the camera restarts). */
    public static final String XPAN_DEVELOPING_LIVE = "xpan_developing_live";

    /** Experimental: XPan 取景净化, each part on its own (applies when the camera opens). */
    public static final String XPAN_CLEAN_SMALL_MASK = "xpan_clean_small_mask";
    public static final String XPAN_CLEAN_LARGE_MASK = "xpan_clean_large_mask";
    public static final String XPAN_CLEAN_ANIMATION = "xpan_clean_animation";

    /** Experimental: AI 灵感构图 suggestions from the system AI unit's cloud ability, gyroscope tracking (applies when the camera opens). */
    public static final String AI_COMPOSITION_CLOUD = "ai_composition_cloud";

    /** Watermark: the XPAN watermark applies only in XPAN mode (saved aside and cleared in other modes). */
    public static final String XPAN_WATERMARK_ISOLATION = "xpan_watermark_isolation";
    /** Leica watermark: replace Leica circle/wordmark badges with Hasselblad H / HASSELBLAD vectors. */
    public static final String LEICA_WATERMARK_HASSELBLAD_BADGE = "leica_watermark_hasselblad_badge";
    /** String setting: photo modes (LaunchModes names, comma separated) that keep their own watermark settings. */
    public static final String WATERMARK_MODES = "watermark_modes";

    /** Model disguise: watermark text, and the EXIF Model (only while the watermark disguise is on). */
    public static final String MODEL_SPOOF_WATERMARK = "model_spoof_watermark";
    public static final String MODEL_SPOOF_EXIF = "model_spoof_exif";
    /** String settings: the selected model preset id, and the user's presets (ModelPresets JSON). */
    public static final String MODEL_PRESET_ID = "model_preset_id";
    public static final String MODEL_CUSTOM_PRESETS = "model_custom_presets";

    /** Master and Ricoh full-screen viewfinder: the quick EV ruler above the filter entry button (applies when the camera opens). */
    public static final String QUICK_EV_RULER = "quick_ev_ruler";

    /** Quick launch key: open the camera in KEY_LAUNCH_MODE (a LaunchModes name) instead of the remembered mode. */
    public static final String KEY_LAUNCH_MODE_ON = "key_launch_mode_on";
    public static final String KEY_LAUNCH_MODE = "key_launch_mode";

    /** How the quick filter strip opens from the master card / palette entry: FILTER_PANEL_* (integer). */
    public static final String FILTER_PANEL_TRIGGER = "filter_panel_trigger";
    public static final int FILTER_PANEL_NONE = 0;
    public static final int FILTER_PANEL_SWIPE_UP = 1;
    public static final int FILTER_PANEL_LONG_PRESS = 2;

    /** Record capture recipe (mode, colour engine, filter, soft light) in EXIF and display in gallery details. */
    public static final String PHOTO_RECIPE_EXIF = "photo_recipe_exif";

    private ModuleSettings() {}

    public static String defaultString(String key) {
        if (MODEL_PRESET_ID.equals(key)) return ModelPresets.DEFAULT_ID;
        if (MODEL_CUSTOM_PRESETS.equals(key)) return "[]";
        if (KEY_LAUNCH_MODE.equals(key)) return LaunchModes.DEFAULT;
        if (WATERMARK_MODES.equals(key)) return "";
        throw new IllegalArgumentException("Unknown string setting " + key);
    }

    /**
     * Whether the camera applies a change of this setting while it runs, without a restart. The master colour settings are re-read
     * on every preview result in master mode (hook/mastercolor/MasterColor.syncSetting), and the capture path reads the look
     * file per frame.
     */
    public static boolean appliesLive(String key) {
        return MASTER_LOOK.equals(key) || MASTER_LOOK_MODE.equals(key) || MASTER_BLEND_TONE.equals(key) || MASTER_BLEND_COLOUR.equals(key)
            || isVignetteKey(key)
            || MasterAlign.isExposureKey(key)
            || MasterStyles.defaultOf(key) != null;
    }

    public static int defaultInt(String key) {
        if (SHUTTER_VOLUME.equals(key)) return 60;
        if (SHUTTER_SOUND.equals(key)) return SHUTTER_SOUND_MODERN;
        if (FILTER_PANEL_TRIGGER.equals(key)) return FILTER_PANEL_SWIPE_UP;
        if (NATURAL_SOFTEN.equals(key)) return 0;
        if (MASTER_LOOK.equals(key)) return MasterStyles.DEFAULT.index;
        if (MASTER_LOOK_MODE.equals(key)) return MasterStyles.MODE_REPLACE;
        if (MASTER_BLEND_TONE.equals(key) || MASTER_BLEND_COLOUR.equals(key)) return 100;
        if (isVignetteKey(key)) return 0;
        if (MasterAlign.isExposureKey(key)) return 0;
        Integer style = MasterStyles.defaultOf(key);
        if (style != null) return style;
        throw new IllegalArgumentException("Unknown integer setting " + key);
    }

    public static boolean defaultValue(String key) {
        switch (key) {
            case SHUTTER_ALWAYS:
            case SHUTTER_VOLUME_CUSTOM:
            case VIDEO_FILTER_HIGH_SPEC:
            case VIDEO_BEAUTY_HIGH_SPEC:
            case VIEWFINDER_HDR:
            case POP_SDR_STYLE:
            case NATURAL_MODE:
            case DUMP_RAW:
            case DUMP_CCM:
            case DUMP_HDRT:
            case NORMAL_FOLLOWS_MASTER:
            case MASTER_PRO_EXPAND:
            case XPAN_WATERMARK_ISOLATION:
            case MODEL_SPOOF_WATERMARK:
            case MODEL_SPOOF_EXIF:
            case KEY_LAUNCH_MODE_ON:
            case SOFT_LIGHT_WEAK_HDF:
            case SOFT_LIGHT_MEDIUM_BLACK_MIST:
            case SOFT_LIGHT_STRONG_WHITE_MIST:
            case SOFT_LIGHT_NO_LOCAL_CONTRAST:
            case SOFT_LIGHT_GALLERY:
            case XPAN_DEVELOPING_LIVE:
            case MASTER_PORTRA_PHYSICAL:
            case XPAN_CLEAN_SMALL_MASK:
            case XPAN_CLEAN_LARGE_MASK:
            case XPAN_CLEAN_ANIMATION:
            case AI_COMPOSITION_CLOUD:
            case HIGH_PIXEL_200M_NO_FACE:
            case LEICA_WATERMARK_HASSELBLAD_BADGE:
                return false;
            case POP_HIGH_PIXEL:
            case UNLOCK_25MP:
            case HIGH_PIXEL_EFFECTS:
            case SHUTTER_IN_SILENT:
            case SHUTTER_IN_VIBRATE:
            case SHUTTER_KEEP_MEDIA:
            case MASTER_EFFECT_NEON:
            case MASTER_EFFECT_PORTRA:
            case MASTER_PRO_LABEL:
            case FLASH_PER_MODE:
            case LIVE_PHOTO_PER_MODE:
            case NATURAL_NO_AI_DETAIL:
            case NATURAL_NO_LCE:
            case NATURAL_NO_SUNSET:
            case NATURAL_NO_TELE_CONTRAST:
            case NATURAL_NO_TELE_SR:
            case PRO_PEAKING_AF:
            case QUICK_EV_RULER:
            case PHOTO_RECIPE_EXIF:
                return true;
            default:
                throw new IllegalArgumentException("Unknown setting " + key);
        }
    }
}
