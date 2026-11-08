package com.tapedecksampler.app;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PadPitchTest {
    public static void main(String[] args) throws Exception {
        File file = Files.createTempFile("pad-pitch-", ".wav").toFile();
        try (Tape tape = new Tape(file)) {
            short[] source = new short[Tape.RATE / 5];
            for (int i = 0; i < source.length; i++) {
                double phase = 2 * Math.PI * 430 * i / Tape.RATE;
                source[i] = (short) Math.round(5000 * (Math.sin(phase) + .5 * Math.sin(phase * 2)));
            }
            tape.write(0, source, source.length);
            Sequencer seq = new Sequencer(tape);
            Sequencer.Track track = seq.tracks[0];
            track.end = source.length; track.steps = 0xffff; track.pitch = -1;
            track.power(Sequencer.Effects.AUTOTUNE, true);
            seq.prepareAll(); seq.play();
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread audio = new Thread(() -> {
                short[] block = new short[256];
                try {
                    while (running.get()) {
                        seq.render(block);
                        Thread.sleep(5);
                    }
                } catch (Throwable e) { failure.set(e); }
            });
            audio.start();
            try {
                Sequencer.Effects pad = seq.editStep(0, 4);
                for (int pitch : new int[]{1, -24, -7, 0, 7, 12, 24}) {
                    synchronized (seq) { pad.pitch = pitch; seq.refresh(0, 4); }
                    long started = System.nanoTime();
                    seq.apply(0, 4, seq.prepare(0, 4, pad.speed, pitch));
                    if (seq.needsPreparation() || track.pitch != -1 || !pad.hasAutotune()) throw new AssertionError("Pad override changed track effects");
                    System.out.printf("Playing track Pitch -1 + Auto Tune, pad Pitch %+d: %.2fs%n", pitch, (System.nanoTime() - started) / 1e9);
                }
            } finally { running.set(false); audio.join(5000); }
            if (audio.isAlive()) throw new AssertionError("Audio render did not finish");
            if (failure.get() != null) throw new AssertionError("Audio render failed", failure.get());
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
