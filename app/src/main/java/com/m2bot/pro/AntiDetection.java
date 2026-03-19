package com.m2bot.pro;

import java.util.Random;

/**
 * Introduces randomised timing variations, periodic breaks and pattern
 * shuffling to make the bot's behaviour less predictable and harder to
 * detect through server-side heuristic analysis.
 */
public final class AntiDetection {

    private final Random rng = new Random();
    private long sessionStart;
    private int  actionCount;
    private long lastBreakTime;
    private int  breaksTaken;

    /* ================================================================== */
    /*  Construction / reset                                              */
    /* ================================================================== */

    public AntiDetection() {
        resetSession();
    }

    public void resetSession() {
        sessionStart  = System.currentTimeMillis();
        lastBreakTime = sessionStart;
        actionCount   = 0;
        breaksTaken   = 0;
    }

    /* ================================================================== */
    /*  Timing                                                            */
    /* ================================================================== */

    /**
     * Returns a randomised delay based on {@code baseMs}.
     * Occasionally (5 %) adds a medium spike; rarely (2 %) adds a large spike.
     */
    public int getRandomDelay(int baseMs) {
        double factor = 0.7 + rng.nextDouble() * 0.6;   // 0.7 .. 1.3
        int delay = (int) (baseMs * factor);
        if (rng.nextInt(100) < 5)  delay += 200  + rng.nextInt(800);
        if (rng.nextInt(100) < 2)  delay += 1000 + rng.nextInt(2000);
        return Math.max(30, delay);
    }

    /** Returns a tap interval with +/- 25 % variance. */
    public int getTapInterval(int baseInterval) {
        int variance = baseInterval / 4;
        return baseInterval + rng.nextInt(Math.max(1, variance * 2)) - variance;
    }

    /* ================================================================== */
    /*  Break logic                                                       */
    /* ================================================================== */

    /** Returns {@code true} when the bot should pause for a short break. */
    public boolean shouldTakeBreak() {
        actionCount++;
        long sinceBreak = System.currentTimeMillis() - lastBreakTime;

        if (sinceBreak > Constants.BREAK_SOFT_MS && rng.nextInt(100) < 15) return true;
        if (sinceBreak > Constants.BREAK_HARD_MS && rng.nextInt(100) < 40) return true;
        if (actionCount > Constants.BREAK_ACTION_LIMIT && rng.nextInt(100) < 10) {
            actionCount = 0;
            return true;
        }
        return false;
    }

    /** Returns the duration (ms) the bot should rest for. */
    public int getBreakDuration() {
        breaksTaken++;
        lastBreakTime = System.currentTimeMillis();
        if (breaksTaken % Constants.BREAK_EVERY_N == 0) {
            return Constants.BREAK_LONG_MIN_MS + rng.nextInt(Constants.BREAK_LONG_RANGE);
        }
        return Constants.BREAK_SHORT_MIN_MS + rng.nextInt(Constants.BREAK_SHORT_RANGE);
    }

    /* ================================================================== */
    /*  Pattern variation                                                 */
    /* ================================================================== */

    /** 12 % chance to vary the current action pattern. */
    public boolean shouldVaryPattern() {
        return rng.nextInt(100) < 12;
    }

    /** Occasionally returns a random skill order instead of the normal one. */
    public int getSkillOrderVariation(int normalOrder) {
        if (rng.nextInt(100) < 20) return rng.nextInt(5);
        return normalOrder;
    }

    /** Adds +/- 20 deg jitter to a preferred movement angle (30 % chance of random). */
    public double getMovementAngle(double preferredAngle) {
        if (rng.nextInt(100) < 30) return rng.nextDouble() * 360.0;
        return preferredAngle + (rng.nextDouble() * 40 - 20);
    }

    /** 3 % chance to simply skip the current action entirely. */
    public boolean shouldSkipAction() {
        return rng.nextInt(100) < 3;
    }

    /* ================================================================== */
    /*  Session info                                                      */
    /* ================================================================== */

    public long getSessionDurationMs() {
        return System.currentTimeMillis() - sessionStart;
    }
}
