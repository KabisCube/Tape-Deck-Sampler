package com.tapedecksampler.app;

public final class ClockSync {
    private static final long SECOND = 1_000_000_000L;
    private static final long DAY = 86_400 * SECOND;
    private static final long LOOP = 240 * SECOND;
    private static final int MAX_BPM = 1000;
    private static final long FRAME_MASK = 0xffffffffL;
    private static final double STEP_PAIR_NANOS = 30.0 * SECOND;

    private ClockSync() {}

    public static long nextBoundaryNanos(long wallNanos, int bpm) {
        if (bpm < 1 || bpm > MAX_BPM) throw new IllegalArgumentException("Invalid tempo");
        long elapsed = Math.floorMod(wallNanos, DAY);
        long loop = elapsed * bpm / LOOP;
        long boundary = divideUp(loop * LOOP, bpm);
        if (boundary < elapsed) boundary = divideUp((loop + 1) * LOOP, bpm);
        return wallNanos - elapsed + boundary;
    }

    public static long framesUntilBoundary(long wallNanos, int bpm, int rate) {
        if (rate <= 0) throw new IllegalArgumentException("Invalid sample rate");
        return divideUp((nextBoundaryNanos(wallNanos, bpm) - wallNanos) * rate, SECOND);
    }

    public static long nextLoopBoundaryNanos(long elapsed, int bpm) {
        if (bpm < 1 || bpm > MAX_BPM || elapsed < 0) throw new IllegalArgumentException("Invalid loop clock");
        long loop = elapsed / LOOP * bpm + elapsed % LOOP * bpm / LOOP;
        long boundary = loop / bpm * LOOP + divideUp(loop % bpm * LOOP, bpm);
        if (boundary < elapsed) {
            loop++;
            boundary = loop / bpm * LOOP + divideUp(loop % bpm * LOOP, bpm);
        }
        return boundary;
    }

    public static long unwrapFrame(long position, long writtenFrames) {
        long frame = (writtenFrames & ~FRAME_MASK) | (position & FRAME_MASK);
        return frame > writtenFrames ? frame - (1L << 32) : frame;
    }

    public static long stepAtNanos(long time, int bpm, int swing) {
        double pairLength = STEP_PAIR_NANOS / bpm;
        long pair = (long) Math.floor(time / pairLength);
        return pair * 2 + (time - pair * pairLength >= pairLength * swing / 100 ? 1 : 0);
    }

    public static long presentationNanos(long writtenFrames, long referenceFrame, long referenceNanos, long nowNanos, int rate) {
        return Math.max(nowNanos, referenceNanos + Math.max(0, writtenFrames - referenceFrame) * SECOND / rate);
    }

    private static long divideUp(long value, long divisor) { return value / divisor + (value % divisor == 0 ? 0 : 1); }
}
