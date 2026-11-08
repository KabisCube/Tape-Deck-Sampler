package com.tapedecksampler.app;

import java.util.Arrays;
import java.util.Random;

public final class ClapTest {
    private static int checks;

    public static void main(String[] args) {
        int rate = Tape.RATE;
        short[] silence = new short[rate];
        ClapDetector detector = new ClapDetector(rate);
        short[] startup = new short[rate]; Arrays.fill(startup, (short) 12000);
        check(detector.accept(startup, startup.length, false) == -1, "Warm-up cannot trigger sync");
        detector = new ClapDetector(rate);
        detector.accept(silence, silence.length, false);
        check(detector.accept(silence, silence.length, true) == -1, "Silence cannot trigger sync");
        short[] onset = {2000, 2500};
        check(detector.accept(onset, onset.length, true) == -1, "A weak sound waits for clap confirmation");
        check(detector.accept(new short[]{-12000}, 1, true) == 2L * rate, "Clap onset survives a buffer boundary and negative peaks");
        detector = new ClapDetector(rate);
        Random random = new Random(17);
        short[] noise = new short[rate];
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (random.nextInt(801) - 400);
        detector.accept(noise, noise.length, false);
        check(detector.accept(noise, noise.length, true) == -1, "Background noise does not trigger sync");
        short[] clap = new short[1024]; clap[57] = Short.MIN_VALUE;
        check(detector.accept(clap, clap.length, true) == 2L * rate + 57, "Clipped impulses retain their sample position");
        detector = new ClapDetector(rate);
        detector.accept(silence, silence.length, false);
        detector.accept(silence, silence.length, true);
        short[] weak = new short[rate / 10]; weak[0] = 2000;
        check(detector.accept(weak, weak.length, true) == -1, "An unconfirmed onset expires");
        check(detector.accept(new short[]{15000}, 1, true) == 2L * rate + weak.length, "A later clap establishes a fresh origin");
        long originA = 123_000_000_000L, originB = 987_654_321_000L;
        ClapClock a = new ClapClock(originA), b = new ClapClock(originB);
        long elapsed = 23_000_000_000L;
        check(a.at(originA + elapsed) == b.at(originB + elapsed), "Independent device clocks share elapsed clap time");
        check(ClockSync.nextLoopBoundaryNanos(elapsed, 24) == 30_000_000_000L, "Play at clap plus 23 seconds joins at plus 30 seconds");
        for (int bpm = 40; bpm <= 240; bpm++) {
            long now = 86_400_000_000_000L + 123_456_789L;
            long next = ClockSync.nextLoopBoundaryNanos(now, bpm);
            check(next >= now && next - now <= 240_000_000_000L / bpm + 1, "Clap grid continues beyond one day");
            check(ClockSync.nextLoopBoundaryNanos(next, bpm) == next, "Fractional loop boundaries remain stable");
            check(ClockSync.nextLoopBoundaryNanos(next - 1, bpm) == next, "Boundary never starts early");
        }
        System.out.println(checks + " clap sync checks passed");
    }

    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
}
