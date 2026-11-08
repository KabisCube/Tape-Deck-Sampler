package com.tapedecksampler.app;

public final class SampleProcessorStressTest {
    public static void main(String[] args) {
        short[] source = new short[Tape.LENGTH];
        for (int i = 0; i < source.length; i++) source[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * 430 * i / Tape.RATE));
        for (int[] setting : new int[][]{{25, -24}, {25, 24}, {400, -24}, {400, 24}}) {
            long started = System.nanoTime();
            short[] result = SampleProcessor.process(source, setting[0], setting[1]);
            if (result.length != Math.round(source.length * 100f / setting[0])) throw new AssertionError("Incorrect duration");
            int crossings = 0, from = result.length / 4, to = result.length * 3 / 4;
            for (int i = from + 1; i < to; i++) if (result[i - 1] <= 0 && result[i] > 0) crossings++;
            double frequency = crossings * (double) Tape.RATE / (to - from);
            if (Math.abs(frequency - 430 * Math.pow(2, setting[1] / 12.0)) > 6) throw new AssertionError("Incorrect pitch: " + frequency);
            System.out.printf("60s sample, speed %d%%, pitch %+d: %.2fs processing%n", setting[0], setting[1], (System.nanoTime() - started) / 1e9);
        }
    }
}
