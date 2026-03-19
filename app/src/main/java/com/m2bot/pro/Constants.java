package com.m2bot.pro;

/**
 * Centralised application constants.
 * <p>
 * Every magic number that was previously scattered across the codebase is
 * collected here so that tuning and auditing become straightforward.
 */
public final class Constants {

    private Constants() { /* utility class */ }

    // ---------------------------------------------------------------
    //  App metadata
    // ---------------------------------------------------------------
    public static final String APP_TAG           = "M2Bot";
    public static final String VERSION_LABEL     = "v5.0";

    // ---------------------------------------------------------------
    //  SharedPreferences file names
    // ---------------------------------------------------------------
    public static final String PREFS_COLORS      = "m2bot_colors";
    public static final String PREFS_COORDS      = "m2bot_koord";
    public static final String PREFS_SETTINGS    = "m2bot_ayar";

    // ---------------------------------------------------------------
    //  Notification channels
    // ---------------------------------------------------------------
    public static final String CHANNEL_CAPTURE   = "m2bot_capture";
    public static final String CHANNEL_OVERLAY   = "m2bot_overlay";
    public static final int    NOTIF_CAPTURE_ID  = 1003;
    public static final int    NOTIF_OVERLAY_ID  = 1002;

    // ---------------------------------------------------------------
    //  Reference resolution (design coordinates are authored at 1920x1080)
    // ---------------------------------------------------------------
    public static final int REF_WIDTH  = 1920;
    public static final int REF_HEIGHT = 1080;

    // ---------------------------------------------------------------
    //  Screen-capture tuning
    // ---------------------------------------------------------------
    /** Minimum milliseconds between captured frames. */
    public static final long   CAPTURE_MIN_INTERVAL_MS = 80;
    /** Scale divisor applied when the screen exceeds 1920 px width. */
    public static final int    CAPTURE_DOWNSCALE_THRESHOLD = 1920;
    /** Max ImageReader buffer size. */
    public static final int    CAPTURE_BUFFER_SIZE = 3;

    // ---------------------------------------------------------------
    //  Touch-engine tuning
    // ---------------------------------------------------------------
    public static final int TOUCH_MIN_INTERVAL_MS   = 35;
    public static final int TOUCH_JITTER_PX         = 6;
    public static final int TOUCH_DURATION_BASE_MS  = 35;
    public static final int TOUCH_DURATION_RANGE_MS = 55;

    // ---------------------------------------------------------------
    //  Color-analysis defaults (RGB)
    // ---------------------------------------------------------------
    public static final int DEF_HP_BAR_R = 200, DEF_HP_BAR_G = 40,  DEF_HP_BAR_B = 40;
    public static final int DEF_HP_EMP_R = 40,  DEF_HP_EMP_G = 40,  DEF_HP_EMP_B = 40;
    public static final int DEF_MP_BAR_R = 40,  DEF_MP_BAR_G = 80,  DEF_MP_BAR_B = 220;
    public static final int DEF_MP_EMP_R = 30,  DEF_MP_EMP_G = 30,  DEF_MP_EMP_B = 50;
    public static final int DEF_MOB_R    = 220, DEF_MOB_G    = 30,  DEF_MOB_B    = 30;
    public static final int DEF_METIN_R  = 50,  DEF_METIN_G  = 230, DEF_METIN_B  = 50;
    public static final int DEF_ITEM_R   = 240, DEF_ITEM_G   = 240, DEF_ITEM_B   = 240;

    public static final double DEF_HP_TOLERANCE    = 55.0;
    public static final double DEF_MP_TOLERANCE    = 55.0;
    public static final double DEF_MOB_TOLERANCE   = 50.0;
    public static final double DEF_METIN_TOLERANCE = 50.0;
    public static final double DEF_ITEM_TOLERANCE  = 45.0;

    /** Minimum learned samples before the learner is considered ready. */
    public static final int MIN_LEARN_SAMPLES = 5;

    // ---------------------------------------------------------------
    //  Vision / blob-detection thresholds
    // ---------------------------------------------------------------
    public static final int   BLOB_MIN_MOB_PX      = 5;
    public static final int   BLOB_MAX_MOB_PX      = 500;
    public static final int   BLOB_MIN_METIN_PX    = 6;
    public static final int   BLOB_MAX_METIN_PX    = 600;
    public static final int   BLOB_MIN_ITEM_PX     = 4;
    public static final int   BLOB_MAX_ITEM_PX     = 200;
    public static final float MOB_MIN_ASPECT        = 1.5f;
    public static final float MOB_MAX_ASPECT        = 25.0f;
    public static final float MOB_MIN_DENSITY       = 0.1f;
    public static final float METIN_MIN_ASPECT      = 1.2f;
    public static final float METIN_MAX_ASPECT      = 30.0f;

    /** Pixel offset below a name-tag centre to derive the click-target Y. */
    public static final int MOB_CLICK_OFFSET_Y   = 45;
    public static final int METIN_CLICK_OFFSET_Y = 70;

    /** Duplicate-entity merge radius (in screen pixels). */
    public static final int ENTITY_MERGE_RADIUS_MOB   = 60;
    public static final int ENTITY_MERGE_RADIUS_METIN = 80;
    public static final int ENTITY_MERGE_RADIUS_ITEM  = 40;

    /** Scene-change sampling grid (columns x rows). */
    public static final int SCENE_SAMPLE_COLS = 30;
    public static final int SCENE_SAMPLE_ROWS = 20;
    /** Per-channel threshold to consider a pixel "changed". */
    public static final int SCENE_CHANNEL_THRESHOLD = 20;
    /** Percentage of changed pixels to declare a scene change. */
    public static final int SCENE_CHANGE_PERCENT = 5;

    /** Percentage of name-pixels remaining below which target is "dead". */
    public static final int TARGET_DEAD_THRESHOLD_PERCENT = 3;

    // ---------------------------------------------------------------
    //  Anti-detection timing
    // ---------------------------------------------------------------
    public static final long   BREAK_SOFT_MS      = 180_000L; // 3 min
    public static final long   BREAK_HARD_MS      = 300_000L; // 5 min
    public static final int    BREAK_ACTION_LIMIT  = 500;
    public static final int    BREAK_SHORT_MIN_MS  = 1000;
    public static final int    BREAK_SHORT_RANGE   = 3000;
    public static final int    BREAK_LONG_MIN_MS   = 5000;
    public static final int    BREAK_LONG_RANGE    = 10000;
    public static final int    BREAK_EVERY_N       = 5;

    // ---------------------------------------------------------------
    //  BotBrain loop timing
    // ---------------------------------------------------------------
    public static final int BRAIN_TARGET_CHECK_MS  = 2000;
    public static final int BRAIN_GM_CHECK_MS      = 10000;
    public static final int BRAIN_POT_COOLDOWN_MS  = 1500;
    public static final int BRAIN_DEATH_CONFIRM    = 3;
    public static final int BRAIN_DEATH_RESPAWN_MS = 5000;
    public static final int BRAIN_LEARN_TICKS      = 10;
    public static final int BRAIN_NO_TARGET_ITEM_MS = 2500;
}
