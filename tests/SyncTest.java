package com.tapedecksampler.app;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class SyncTest {
    private static int checks;
    private static final long SECOND = 1_000_000_000L;
    private static final long DAY = 86_400 * SECOND;

    public static void main(String[] args) throws Exception {
        check(ClockSync.nextBoundaryNanos(8 * SECOND, 24) == 10 * SECOND, "Eight seconds waits for the ten-second loop boundary");
        check(ClockSync.framesUntilBoundary(8 * SECOND, 24, Tape.RATE) == Tape.RATE * 2, "Example waits exactly two seconds");
        check(ClockSync.nextBoundaryNanos(10 * SECOND, 24) == 10 * SECOND, "Exact boundaries can start immediately");
        check(ClockSync.nextBoundaryNanos(DAY - 1, 127) == DAY, "Midnight is a shared boundary");
        check(ClockSync.nextBoundaryNanos(-1, 24) == 0, "Clock arithmetic handles dates before the epoch");
        check(ClockSync.framesUntilBoundary(10 * SECOND - 1, 24, Tape.RATE) == 1, "A fractional frame is rounded forward rather than starting early");
        for (int bpm = 40; bpm <= 240; bpm++) {
            long now = 20_000 * DAY + 43_210 * SECOND + 123_456_789;
            long next = ClockSync.nextBoundaryNanos(now, bpm);
            check(next >= now && next - now <= (240 * SECOND + bpm - 1) / bpm, "Boundary is within one complete loop at " + bpm);
            check(ClockSync.nextBoundaryNanos(next, bpm) == next, "Rounded rational boundaries remain stable at " + bpm);
            check(ClockSync.nextBoundaryNanos(next - 1, bpm) == next, "Devices pressing Play at different times share the boundary at " + bpm);
            check(ClockSync.nextBoundaryNanos(now + DAY, bpm) == next + DAY, "The grid repeats at each midnight at " + bpm);
            for (int loop : new int[]{1, 17, 10000}) {
                long boundary = (loop * 240 * SECOND + bpm - 1) / bpm;
                check(ClockSync.nextBoundaryNanos(boundary, bpm) == boundary, "Non-integer loop lengths do not accumulate rounding drift at " + bpm);
            }
        }
        check(ClockSync.presentationNanos(10000, 5200, 2 * SECOND, 2 * SECOND, Tape.RATE) == 2 * SECOND + SECOND / 10, "Audio queue duration is included in the presentation estimate");
        check(ClockSync.presentationNanos(5200, 5200, SECOND, 2 * SECOND, Tape.RATE) == 2 * SECOND, "Presentation estimates cannot schedule samples in the past");
        check(ClockSync.unwrapFrame(32, (1L << 32) + 256) == (1L << 32) + 32, "Playback counters unwrap after a full 32-bit cycle");
        check(ClockSync.unwrapFrame(0xfffffff0L, (1L << 32) + 16) == (1L << 32) - 16, "A queued reference before counter rollover stays in the previous cycle");
        File path = Files.createTempFile("tape-sync", ".wav").toFile();
        try (Tape tape = new Tape(path)) {
            short[] signal = new short[Tape.RATE]; Arrays.fill(signal, (short) 12000); tape.write(0, signal, signal.length);
            Sequencer seq = new Sequencer(tape); seq.tracks[0].start = 0; seq.tracks[0].end = signal.length; seq.tracks[0].steps = 1;
            seq.playSynced();
            check(seq.isSyncWaiting() && !seq.isPlaying() && seq.step() == -1, "Arming sync leaves the transport stopped");
            short[] beforeClock = new short[256]; seq.render(beforeClock);
            check(allZero(beforeClock) && seq.isSyncWaiting(), "The audio engine must supply a presentation clock before starting");
            short[] silence = new short[Tape.RATE / 2]; seq.renderSynced(silence, SECOND * 3 / 2, SECOND / (double) Tape.RATE, true);
            check(allZero(silence) && seq.isSyncWaiting(), "Waiting emits silence through the frame before the boundary");
            seq.renderSynced(new short[1], 2 * SECOND, SECOND / (double) Tape.RATE, true);
            check(seq.isPlaying() && !seq.isSyncWaiting() && seq.step() == 0, "The boundary starts exactly at pad one");
            short[] sound = new short[256]; seq.renderSynced(sound, 2 * SECOND + SECOND / Tape.RATE, SECOND / (double) Tape.RATE, true); check(!allZero(sound), "Synced transport generates the sample audio");
            seq.playSynced(); seq.stop(); seq.render(silence);
            check(allZero(silence) && !seq.isSyncWaiting() && !seq.isPlaying(), "Stop cancels a pending start without a delayed trigger");
            seq.playSynced(); seq.renderSynced(new short[1], SECOND * 21 / 10, SECOND / (double) Tape.RATE, true); seq.tempo(240);
            check(seq.isSyncWaiting(), "Tempo edits re-arm pending sync");
            seq.renderSynced(new short[Tape.RATE * 9 / 10], SECOND * 21 / 10, SECOND / (double) Tape.RATE, true); seq.renderSynced(new short[1], 3 * SECOND, SECOND / (double) Tape.RATE, true);
            check(seq.isPlaying() && seq.step() == 0, "Re-armed sync follows the new tempo's grid");
            seq.renderSynced(silence, 3 * SECOND, SECOND / (double) Tape.RATE, false);
            check(seq.isSyncWaiting() && !seq.isPlaying() && allZero(silence), "An unavailable clock silences playback and re-arms the next shared loop");
            seq.preview(0);
            check(!seq.isSyncWaiting() && !seq.isPlaying(), "Sample preview cancels pending beat sync");
            seq.playSynced(); seq.toggleSelection(signal, 0, signal.length);
            check(seq.isPreviewing() && !seq.isSyncWaiting(), "Timeline audition stays immediate and independent of sync");
            seq.stop(); seq.play();
            check(seq.isPlaying() && !seq.isSyncWaiting(), "Ordinary playback remains immediate");
            for (int swing : new int[]{10, 25, 50, 60, 67, 75, 90}) {
                Sequencer groove = new Sequencer(tape);
                groove.swing(swing); groove.play();
                int longStep = Tape.RATE * 30 / 120 * swing / 100;
                groove.render(new short[longStep]);
                check(groove.step() == 0, "Swing lengthens the first sixteenth at " + swing + "%");
                groove.render(new short[1]);
                check(groove.step() == 1, "Swing delays the offbeat precisely");
                groove.render(new short[Tape.RATE / 4 - longStep]);
                check(groove.step() == 2, "Each swung pair retains its original duration");
                groove.stop(); groove.playSynced();
                groove.renderSynced(new short[1], 0, SECOND / (double) Tape.RATE, true);
                long offbeat = SECOND / 4 * swing / 100;
                groove.renderSynced(new short[1], offbeat - 1, SECOND / (double) Tape.RATE, true);
                check(groove.step() == 0, "Synced swing waits through the frame before its offbeat");
                groove.renderSynced(new short[1], offbeat, SECOND / (double) Tape.RATE, true);
                check(groove.step() == 1, "Synced swing uses the shared clap clock");
                groove.renderSynced(new short[1], 2 * SECOND, SECOND / (double) Tape.RATE, true);
                check(groove.step() == 0, "Swing keeps clap loop boundaries unchanged");
            }
            for (int bpm : new int[]{60, 90, 137, 240}) {
                Sequencer changed = new Sequencer(tape), restarted = new Sequencer(tape);
                for (Sequencer player : new Sequencer[]{changed, restarted}) {
                    player.tracks[0].steps = 0xffff;
                    player.tracks[0].end = signal.length;
                    player.playSynced();
                    player.renderSynced(new short[512], 2 * SECOND, SECOND / (double) Tape.RATE, true);
                }
                changed.tempo(120);
                check(changed.isPlaying() && !changed.isSyncWaiting(), "Unchanged BPM keeps playback running");
                changed.tempo(bpm);
                restarted.stop(); restarted.tempo(bpm); restarted.playSynced();
                check(!changed.isPlaying() && changed.isSyncWaiting() && changed.step() == -1, "Changing BPM pauses synced playback until the new loop");
                long now = SECOND * 17 / 8, boundary = ClockSync.nextLoopBoundaryNanos(now, bpm);
                int frames = (int) ((boundary - now) * Tape.RATE / SECOND);
                short[] automatic = new short[frames], manual = new short[frames];
                changed.renderSynced(automatic, now, SECOND / (double) Tape.RATE, true);
                restarted.renderSynced(manual, now, SECOND / (double) Tape.RATE, true);
                check(allZero(automatic) && Arrays.equals(automatic, manual) && changed.isSyncWaiting(), "Tempo changes wait silently like pause then play");
                automatic = new short[512]; manual = new short[512];
                changed.renderSynced(automatic, boundary, SECOND / (double) Tape.RATE, true);
                restarted.renderSynced(manual, boundary, SECOND / (double) Tape.RATE, true);
                check(changed.isPlaying() && changed.step() == 0 && !allZero(automatic) && Arrays.equals(automatic, manual), "New BPM resumes at pad one on the original clap grid");
                changed.stop(); changed.tempo(120);
                check(!changed.isPlaying() && !changed.isSyncWaiting(), "Changing BPM while stopped does not start playback");
            }
        } finally { Files.deleteIfExists(path.toPath()); }
        System.out.println(checks + " clock sync checks passed");
    }

    private static boolean allZero(short[] samples) { for (short sample : samples) if (sample != 0) return false; return true; }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
}
