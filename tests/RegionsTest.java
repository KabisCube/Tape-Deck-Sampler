package com.tapedecksampler.app;

import java.util.HashSet;

public final class RegionsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        SampleRegions regions = new SampleRegions();
        regions.place(0, 1000, "a", "Kick");
        int original = regions.snapshot().get(0).color;
        regions.checkpoint();
        regions.place(250, 750, "b", "Snare");
        check(regions.snapshot().size() == 3, "Overwrite splits existing region");
        check(regions.snapshot().get(0).end == 250 && regions.snapshot().get(2).start == 750, "Overwrite preserves exact boundaries");
        check(regions.snapshot().get(0).color == original && regions.snapshot().get(2).color == original, "Split fragments retain source color");
        check(regions.snapshot().get(1).color != original, "Different sources have different colors");
        check(regions.undo() && regions.snapshot().size() == 1 && regions.snapshot().get(0).end == 1000, "Undo restores region extent");
        check(regions.undo() && regions.snapshot().size() == 3, "Redo restores overwrite regions");
        regions.place(1200, 1500, "a", "Kick again");
        check(regions.snapshot().get(3).color == original, "Repeated source uses same color");
        regions.remove(100, 900);
        check(regions.snapshot().get(0).end == 100 && regions.snapshot().get(1).start == 900, "Silence removes only its selected region");
        SampleRegions restored = new SampleRegions();
        for (java.util.Map.Entry<String, Integer> color : regions.colors().entrySet()) restored.restoreColor(color.getKey(), color.getValue());
        for (SampleRegions.Region region : regions.snapshot()) restored.restore(region);
        check(restored.snapshot().size() == regions.snapshot().size() && restored.color("a") == original, "Colors and regions can be restored");
        HashSet<Integer> colors = new HashSet<>();
        for (int i = 0; i < 30; i++) { String id = "sample" + i; regions.place(2000 + i * 10, 2009 + i * 10, id, id); colors.add(regions.color(id)); }
        check(colors.size() == 30, "Unique source colors do not repeat");
        check(SampleRegions.identity(new short[]{1, 2, 3}).equals(SampleRegions.identity(new short[]{1, 2, 3})), "Identical PCM has stable identity");
        check(!SampleRegions.identity(new short[]{1, 2, 3}).equals(SampleRegions.identity(new short[]{1, 2, 4})), "Different PCM has different identity");
        WaveSummary wave = new WaveSummary();
        for (int i = 0; i < 2000; i++) wave.accept(i == 1000 ? (short) 32767 : 0);
        wave.finish();
        check(wave.length() == 2000, "Source waveform retains exact frame count");
        check(wave.waveform(0, 2000, 4)[2] > 0.99f, "Source waveform preserves transients");
        WaveSummary longWave = new WaveSummary();
        int length = 65536 * 480 + 2000;
        for (int i = 0; i < length; i++) longWave.accept(i >= length - 1000 ? (short) 16384 : 0);
        longWave.finish();
        check(longWave.length() == length, "Long waveform compaction retains length");
        check(longWave.waveform(length - 1000L, length, 1)[0] == 0.5f, "Long waveform compaction retains late peaks");
        waveformColors();
        System.out.println(checks + " timeline region checks passed");
    }

    private static void waveformColors() throws Exception {
        java.io.File file = java.nio.file.Files.createTempFile("region-waveform-", ".wav").toFile();
        try (Tape tape = new Tape(file)) {
            short[] samples = new short[256]; samples[3] = 32767; samples[4] = 1000; samples[7] = -32768;
            tape.write(0, samples, samples.length);
            SampleRegions regions = new SampleRegions();
            regions.place(2, 4, "a", "First"); regions.place(4, 5, "b", "Second"); regions.place(7, 8, "c", "Tiny");
            for (int[] window : new int[][]{{0, 256}, {0, 12}, {1, 9}, {2, 5}, {3, 8}, {4, 5}, {7, 8}}) {
                for (int bins : new int[]{1, 2, 3, 7, 160, 640}) {
                    java.util.List<ColoredWaveform.Segment> segments = ColoredWaveform.build(tape, window[0], window[1], bins, regions.snapshot());
                    int next = window[0]; boolean valid = true;
                    for (ColoredWaveform.Segment segment : segments) {
                        valid &= segment.start == next && segment.end > segment.start && segment.end <= window[1];
                        int peak = 0;
                        for (int frame = segment.start; frame < segment.end; frame++) {
                            int color = frame >= 2 && frame < 4 ? regions.color("a") : frame == 4 ? regions.color("b") : frame == 7 ? regions.color("c") : ColoredWaveform.EMPTY_COLOR;
                            valid &= segment.color == color;
                            peak = Math.max(peak, Math.abs((int) samples[frame]));
                        }
                        valid &= segment.peak == peak / 32768f;
                        next = segment.end;
                    }
                    check(valid && next == window[1], "Zoom window " + window[0] + "-" + window[1] + " with " + bins + " bins preserves exact colors and peaks");
                }
            }
            java.util.List<ColoredWaveform.Segment> shared = ColoredWaveform.build(tape, 0, 12, 1, regions.snapshot());
            check(shared.size() == 6 && shared.get(1).start == 2 && shared.get(1).end == 4 && shared.get(2).start == 4 && shared.get(2).end == 5, "Regions sharing one waveform bin retain their exact boundaries");
            check(shared.get(1).peak > .99f && shared.get(2).peak < .04f, "A loud ending cannot bleed into the next sample's waveform");
            long version = regions.revision(); regions.place(3, 6, "overwrite", "Overwrite");
            check(regions.revision() > version, "Overwrites invalidate cached waveform colors");
            regions.checkpoint(); version = regions.revision(); regions.remove(0, 8);
            check(regions.revision() > version, "Removing audio invalidates cached waveform colors");
            version = regions.revision(); regions.undo();
            check(regions.revision() > version, "Undo invalidates cached waveform colors");
        } finally { java.nio.file.Files.deleteIfExists(file.toPath()); }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
