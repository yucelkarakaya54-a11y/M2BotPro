package com.m2bot.pro;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Random;

/**
 * Central decision engine that drives the bot's behaviour loop.
 * <p>
 * Runs on the main-thread {@link Handler} via {@code postDelayed} so each
 * tick is non-blocking and can be cancelled at any time.
 * <p>
 * <b>State machine:</b>
 * <pre>
 *   IDLE -> LEARNING -> SEARCHING -> MOVING_TO_TARGET -> ATTACKING
 *                                          |                  |
 *                                          v                  v
 *                                     COLLECTING         USING_SKILL
 *                                          |                  |
 *                                          +------<-----------+
 *                                          v
 *                                      SEARCHING
 *                                          |
 *                                     BREAK / DEAD
 * </pre>
 */
public final class BotBrain {

    private static final String TAG = Constants.APP_TAG + ".Brain";

    /* ================================================================== */
    /*  State enum                                                        */
    /* ================================================================== */

    public enum State {
        IDLE, SEARCHING, ATTACKING, COLLECTING, USING_POT, DEAD,
        USING_SKILL, MOVING_TO_TARGET, BREAK, LEARNING
    }

    /* ================================================================== */
    /*  User-facing settings                                              */
    /* ================================================================== */

    public static final class Settings {
        public boolean autoAttack  = true;
        public boolean autoPot     = true;
        public boolean autoCollect = true;
        public boolean farmBot     = true;
        public boolean useSkills   = true;
        public boolean searchMetin = false;
        public boolean mountAttack = false;
        public boolean gmAlert     = false;

        public int hpPotThreshold = 60;
        public int mpPotThreshold = 40;
        public int attackSpeed    = 25;   // 1..49
        public int moveSpeed      = 25;   // 1..49
        public int skillCooldown  = 3000; // ms
        public int collectCount   = 5;

        public boolean sk1 = true, sk2 = true, sk3 = false, sk4 = false, sk86 = false;

        /** Interval between individual attack taps. */
        public int getAttackIntervalMs() {
            return Math.max(30, 1050 - attackSpeed * 20);
        }

        /** Interval between movement commands while searching. */
        public int getMoveIntervalMs() {
            return Math.max(200, 3200 - moveSpeed * 60);
        }

        /** Number of rapid taps per attack burst. */
        public int getBurstCount() {
            if (attackSpeed > 40) return 8;
            if (attackSpeed > 30) return 5;
            if (attackSpeed > 20) return 3;
            return 1;
        }

        /** Main-loop period (ms). */
        public int getLoopMs() {
            return Math.max(100, 500 - attackSpeed * 6);
        }
    }

    /* ================================================================== */
    /*  Listener                                                          */
    /* ================================================================== */

    public interface Listener {
        void onStateChanged(State state);
        void onLog(String message);
        void onStats(int kills, int loots, int hp, int mp);
    }

    /* ================================================================== */
    /*  Fields                                                            */
    /* ================================================================== */

    private final CoordManager         coords;
    private final AdaptiveColorLearner colorLearner;
    private final Handler              handler;
    private final Settings             settings;
    private final AntiDetection        antiDetect;
    private final Random               rng;

    private Listener listener;
    private State    state = State.IDLE;
    private boolean  running;
    private Runnable loopRunnable;

    /* ---- Timing ---- */
    private long lastAttack, lastSkill, lastMove, lastPot;
    private long lastTargetCheck, lastGmCheck;
    private long noTargetSince, lastSkillTime;

    /* ---- Counters ---- */
    private int  kills, loots, skillPhase, tickCount;
    private int  stuckCounter, deathCounter, consecutiveMisses;
    private int  learnPhase;

    /* ---- Movement ---- */
    private double lastMoveAngle;

    /* ---- Target tracking ---- */
    private GameVision.Entity currentTarget;

    /* ---- Break flag ---- */
    private boolean breakActive;

    /* ================================================================== */
    /*  Construction                                                      */
    /* ================================================================== */

    public BotBrain(CoordManager coords, AdaptiveColorLearner learner) {
        this.coords       = coords;
        this.colorLearner = learner;
        this.handler      = new Handler(Looper.getMainLooper());
        this.settings     = new Settings();
        this.antiDetect   = new AntiDetection();
        this.rng          = new Random();
    }

    /* ---- Accessors ---- */

    public void     setListener(Listener l)  { this.listener = l; }
    public Settings getSettings()            { return settings; }
    public State    getState()               { return state; }
    public boolean  isRunning()              { return running; }

    /* ================================================================== */
    /*  Start / Stop                                                      */
    /* ================================================================== */

    public void start() {
        if (running) return;

        BotAccessibilityService acc = BotAccessibilityService.get();
        ScreenCaptureService    cap = ScreenCaptureService.getInstance();
        if (acc == null) { log("ERROR: Accessibility service not connected!"); return; }
        if (cap == null) { log("ERROR: Screen capture not available!");       return; }

        // Quick smoke-test tap
        log("Sending test tap...");
        acc.tap(coords.atkX, coords.atkY);

        Bitmap test = cap.getLatestBitmap();
        if (test != null) {
            log("Screen OK: " + test.getWidth() + "x" + test.getHeight()
                    + "  scale=" + cap.getCaptureScale());
        } else {
            log("Screen not ready yet -- will retry in loop");
        }

        log("Attack=" + coords.atkX + "," + coords.atkY
                + "  Joy=" + coords.joyCX + "," + coords.joyCY
                + "  R=" + coords.joyR);

        running = true;
        resetTimers();
        antiDetect.resetSession();

        if (colorLearner != null && !colorLearner.isLearned()) {
            setState(State.LEARNING);
            log("Entering colour-learning mode...");
        } else {
            setState(State.SEARCHING);
        }
        log("Bot STARTED");

        loopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                try {
                    tick();
                } catch (Exception e) {
                    log("ERROR: " + e.getMessage());
                }
                int delay = antiDetect.getRandomDelay(settings.getLoopMs());
                handler.postDelayed(this, delay);
            }
        };
        handler.post(loopRunnable);
    }

    public void stop() {
        running = false;
        if (loopRunnable != null) {
            handler.removeCallbacks(loopRunnable);
            loopRunnable = null;
        }

        BotAccessibilityService acc = BotAccessibilityService.get();
        if (acc != null) log("Touch stats: " + acc.getStatusText());

        setState(State.IDLE);
        log("Bot STOPPED  K:" + kills + "  L:" + loots
                + "  Session:" + (antiDetect.getSessionDurationMs() / 1000) + "s");
    }

    /* ================================================================== */
    /*  Main tick                                                         */
    /* ================================================================== */

    private void tick() {
        BotAccessibilityService acc = BotAccessibilityService.get();
        ScreenCaptureService    cap = ScreenCaptureService.getInstance();
        if (acc == null) { log("Accessibility disconnected!"); stop(); return; }
        if (cap == null || !cap.isActive()) {
            if (tickCount % 20 == 0) log("Waiting for capture...");
            tickCount++;
            return;
        }
        tickCount++;

        Bitmap frame = cap.getLatestBitmap();
        if (frame == null || frame.isRecycled()) {
            if (tickCount % 20 == 0) log("Waiting for frame...");
            return;
        }

        long now = now();

        // ---- Anti-detection break ----
        if (antiDetect.shouldTakeBreak() && !breakActive) {
            breakActive = true;
            int breakDur = antiDetect.getBreakDuration();
            log("Break: " + breakDur + " ms");
            setState(State.BREAK);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    breakActive = false;
                    if (running) setState(State.SEARCHING);
                }
            }, breakDur);
            return;
        }
        if (breakActive) return;

        // ---- HP / MP analysis ----
        int hp = GameVision.analyzeHp(cap, coords, colorLearner);
        int mp = GameVision.analyzeMp(cap, coords, colorLearner);
        if (listener != null) listener.onStats(kills, loots, hp, mp);

        if (tickCount % 25 == 0) {
            log("T:" + tickCount + "  HP:" + hp + "  MP:" + mp
                    + "  " + acc.getStatusText() + "  F:" + cap.getFrameCount());
        }

        // ---- GM detection ----
        if (settings.gmAlert && now - lastGmCheck > Constants.BRAIN_GM_CHECK_MS) {
            lastGmCheck = now;
            if (GameVision.detectGM(cap, coords)) {
                log("*** GM DETECTED ***");
                stop();
                return;
            }
        }

        // ---- Death detection ----
        if (hp >= 0 && hp < 5) {
            deathCounter++;
            if (deathCounter >= Constants.BRAIN_DEATH_CONFIRM) {
                setState(State.DEAD);
                log("DEAD  HP:" + hp);
                deathCounter = 0;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (running) {
                            setState(State.SEARCHING);
                            noTargetSince = now();
                            stuckCounter = 0;
                        }
                    }
                }, Constants.BRAIN_DEATH_RESPAWN_MS + rng.nextInt(3000));
                return;
            }
        } else {
            deathCounter = 0;
        }

        // ---- Auto-potion ----
        if (settings.autoPot && hp > 0 && now - lastPot > Constants.BRAIN_POT_COOLDOWN_MS) {
            if (hp < settings.hpPotThreshold) {
                acc.tap(coords.potHpX, coords.potHpY);
                lastPot = now;
                log("HP POT " + hp + "%");
            }
            if (mp > 0 && mp < settings.mpPotThreshold) {
                acc.tap(coords.potMpX, coords.potMpY);
                lastPot = now;
                log("MP POT " + mp + "%");
            }
        }

        // ---- State dispatch ----
        switch (state) {
            case LEARNING:        doLearn(acc, cap);      break;
            case SEARCHING:       doSearch(acc, cap, now); break;
            case MOVING_TO_TARGET:doMoveToTarget(acc, cap); break;
            case ATTACKING:       doAttack(acc, cap, now); break;
            case USING_SKILL:     doSkill(acc, now);       break;
            case COLLECTING:      doCollect(acc, cap, now);break;
            default:              setState(State.SEARCHING);break;
        }
    }

    /* ================================================================== */
    /*  LEARNING state                                                    */
    /* ================================================================== */

    private void doLearn(BotAccessibilityService acc, ScreenCaptureService cap) {
        if (colorLearner == null) { setState(State.SEARCHING); return; }

        Bitmap bmp = cap.getLatestBitmap();
        if (bmp == null || bmp.isRecycled()) return;

        int scale = cap.getCaptureScale();
        int bw = bmp.getWidth(), bh = bmp.getHeight();

        int hpMidX = clamp((coords.hpBarX1 + coords.hpBarX2) / 2 / scale, 0, bw - 1);
        int hpMidY = clamp((coords.hpBarY1 + coords.hpBarY2) / 2 / scale, 0, bh - 1);
        int mpMidX = clamp((coords.mpBarX1 + coords.mpBarX2) / 2 / scale, 0, bw - 1);
        int mpMidY = clamp((coords.mpBarY1 + coords.mpBarY2) / 2 / scale, 0, bh - 1);

        // Learn HP bar (two samples + one empty sample)
        colorLearner.learnHpBarColor(bmp, hpMidX, hpMidY);
        colorLearner.learnHpBarColor(bmp,
                Math.min(hpMidX + 20, bw - 1), hpMidY);
        int emptyX = clamp(coords.hpBarX2 / scale - 5, 0, bw - 1);
        colorLearner.learnHpEmptyColor(bmp, emptyX, hpMidY);

        // Learn MP bar
        colorLearner.learnMpBarColor(bmp, mpMidX, mpMidY);

        learnPhase++;
        if (learnPhase >= Constants.BRAIN_LEARN_TICKS) {
            colorLearner.markLearned();
            log("Colour learning DONE (" + colorLearner.getLearnSamples() + " samples)");
            setState(State.SEARCHING);
        } else if (learnPhase % 3 == 0) {
            log("Learning: " + learnPhase + "/" + Constants.BRAIN_LEARN_TICKS);
        }
    }

    /* ================================================================== */
    /*  SEARCHING state                                                   */
    /* ================================================================== */

    private void doSearch(BotAccessibilityService acc, ScreenCaptureService cap, long now) {
        List<GameVision.Entity> targets = settings.searchMetin
                ? GameVision.findMetinStones(cap, coords, colorLearner)
                : GameVision.findMonsters(cap, coords, colorLearner);

        if (!targets.isEmpty()) {
            currentTarget = targets.get(0);
            stuckCounter = 0;
            noTargetSince = now;
            consecutiveMisses = 0;
            log("TARGET: " + currentTarget.type
                    + " (" + currentTarget.clickX + "," + currentTarget.clickY + ")"
                    + "  conf=" + currentTarget.confidence
                    + "  total=" + targets.size());

            int dist = Math.abs(currentTarget.clickX - coords.charCenterX)
                     + Math.abs(currentTarget.clickY - coords.charCenterY);

            acc.tap(currentTarget.clickX, currentTarget.clickY);

            if (dist > coords.screenW / 3) {
                setState(State.MOVING_TO_TARGET);
            } else {
                setState(State.ATTACKING);
            }
            return;
        }

        consecutiveMisses++;

        // Move around while looking
        if (settings.farmBot && now - lastMove > antiDetect.getRandomDelay(settings.getMoveIntervalMs())) {
            if (consecutiveMisses > 5) {
                lastMoveAngle = rng.nextDouble() * 360;
                consecutiveMisses = 0;
            } else {
                lastMoveAngle = antiDetect.getMovementAngle(lastMoveAngle);
            }
            acc.moveAngle(coords.joyCX, coords.joyCY, coords.joyR, lastMoveAngle);
            lastMove = now;
            if (tickCount % 8 == 0) log("Searching...  angle=" + (int) lastMoveAngle);
        }

        // Opportunistic item pickup
        if (settings.autoCollect && now - noTargetSince > Constants.BRAIN_NO_TARGET_ITEM_MS) {
            List<GameVision.Entity> items = GameVision.findItems(cap, coords, colorLearner);
            if (!items.isEmpty()) {
                acc.tap(items.get(0).clickX, items.get(0).clickY);
                loots++;
                log("Picked up item while searching");
            }
        }
    }

    /* ================================================================== */
    /*  MOVING_TO_TARGET state                                            */
    /* ================================================================== */

    private void doMoveToTarget(BotAccessibilityService acc, ScreenCaptureService cap) {
        if (currentTarget == null) { setState(State.SEARCHING); return; }

        stuckCounter++;
        if (stuckCounter > 8) {
            acc.tap(currentTarget.clickX, currentTarget.clickY);
            setState(State.ATTACKING);
            stuckCounter = 0;
            return;
        }

        if (stuckCounter % 3 == 0) {
            double angle = Math.toDegrees(Math.atan2(
                    currentTarget.clickY - coords.charCenterY,
                    currentTarget.clickX - coords.charCenterX));
            acc.moveAngle(coords.joyCX, coords.joyCY, coords.joyR, angle);
        }
    }

    /* ================================================================== */
    /*  ATTACKING state                                                   */
    /* ================================================================== */

    private void doAttack(BotAccessibilityService acc, ScreenCaptureService cap, long now) {
        if (currentTarget == null) { setState(State.SEARCHING); return; }
        if (antiDetect.shouldSkipAction()) return;

        // --- Attack tap ---
        int attackInterval = antiDetect.getTapInterval(settings.getAttackIntervalMs());
        if (now - lastAttack > attackInterval) {
            if (settings.mountAttack || settings.attackSpeed > 30) {
                int burst    = settings.getBurstCount();
                int interval = Math.max(25, attackInterval / burst);
                acc.rapidTap(coords.atkX, coords.atkY, burst, interval);
            } else if (settings.autoAttack) {
                acc.tap(coords.atkX, coords.atkY);
            }
            lastAttack = now;
        }

        // --- Skill rotation ---
        if (settings.useSkills
                && now - lastSkill > antiDetect.getRandomDelay(settings.skillCooldown)) {
            skillPhase = 0;
            lastSkillTime = 0;
            setState(State.USING_SKILL);
            return;
        }

        // --- Target health check ---
        if (now - lastTargetCheck > Constants.BRAIN_TARGET_CHECK_MS) {
            lastTargetCheck = now;

            if (GameVision.isTargetDead(cap, currentTarget, coords, colorLearner)) {
                onKill();
                return;
            }

            List<GameVision.Entity> targets = settings.searchMetin
                    ? GameVision.findMetinStones(cap, coords, colorLearner)
                    : GameVision.findMonsters(cap, coords, colorLearner);

            if (targets.isEmpty()) {
                onKill();
                return;
            }

            // Re-target if the nearest entity shifted significantly
            GameVision.Entity nearest = targets.get(0);
            if (currentTarget != null
                    && (Math.abs(nearest.clickX - currentTarget.clickX) > 100
                     || Math.abs(nearest.clickY - currentTarget.clickY) > 100)) {
                currentTarget = nearest;
                acc.tap(currentTarget.clickX, currentTarget.clickY);
                log("Target shifted");
            }
        }

        // --- Anti-stuck ---
        stuckCounter++;
        int maxStuck = Math.max(25, 5000 / Math.max(1, settings.getLoopMs()));
        if (stuckCounter > maxStuck) {
            log("STUCK -- changing direction");
            lastMoveAngle += 90 + rng.nextInt(180);
            acc.moveAngle(coords.joyCX, coords.joyCY, coords.joyR, lastMoveAngle);
            stuckCounter = 0;
            currentTarget = null;
            setState(State.SEARCHING);
        }
    }

    private void onKill() {
        kills++;
        log("KILLED  x" + kills);
        currentTarget = null;
        setState(settings.autoCollect ? State.COLLECTING : State.SEARCHING);
    }

    /* ================================================================== */
    /*  USING_SKILL state                                                 */
    /* ================================================================== */

    private void doSkill(BotAccessibilityService acc, long now) {
        if (now - lastSkillTime < antiDetect.getRandomDelay(220)) return;
        lastSkillTime = now;

        int order = antiDetect.shouldVaryPattern()
                ? antiDetect.getSkillOrderVariation(skillPhase)
                : skillPhase;

        switch (order) {
            case 0: if (settings.sk1)  acc.tap(coords.skill1X,  coords.skill1Y);  break;
            case 1: if (settings.sk2)  acc.tap(coords.skill2X,  coords.skill2Y);  break;
            case 2: if (settings.sk3)  acc.tap(coords.skill3X,  coords.skill3Y);  break;
            case 3: if (settings.sk4)  acc.tap(coords.skill4X,  coords.skill4Y);  break;
            case 4: if (settings.sk86) acc.tap(coords.skill86X, coords.skill86Y); break;
        }

        skillPhase++;
        if (skillPhase > 4) {
            skillPhase = 0;
            lastSkill = now;
            setState(State.ATTACKING);
        }
    }

    /* ================================================================== */
    /*  COLLECTING state                                                  */
    /* ================================================================== */

    private void doCollect(BotAccessibilityService acc, ScreenCaptureService cap, long now) {
        if (now - lastAttack < (long) settings.collectCount * 250L) {
            // Rapid-tap the pickup button
            HumanLikeTouchEngine engine = acc.getTouchEngine();
            if (engine != null) engine.tapWithVariance(coords.pickupX, coords.pickupY, 8);
            loots++;
        } else {
            // Also look for visible item drops
            List<GameVision.Entity> items = GameVision.findItems(cap, coords, colorLearner);
            if (!items.isEmpty()) {
                int maxPick = Math.min(3, items.size());
                for (int i = 0; i < maxPick; i++) {
                    acc.tap(items.get(i).clickX, items.get(i).clickY);
                    loots++;
                }
            }
            setState(State.SEARCHING);
        }
    }

    /* ================================================================== */
    /*  Helpers                                                           */
    /* ================================================================== */

    private void setState(State s) {
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (listener != null) listener.onLog(msg);
    }

    private static long now() { return System.currentTimeMillis(); }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private void resetTimers() {
        long now = now();
        lastAttack = lastSkill = lastMove = lastPot = 0;
        lastTargetCheck = lastGmCheck = now;
        noTargetSince   = now;
        lastSkillTime   = 0;
        stuckCounter = kills = loots = skillPhase = tickCount = 0;
        deathCounter = consecutiveMisses = learnPhase = 0;
        lastMoveAngle = rng.nextDouble() * 360;
        currentTarget = null;
        breakActive   = false;
    }
}
