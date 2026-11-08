package com.tapedecksampler.app;

import com.sun.management.ThreadMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

public final class AudioRenderTest {
    public static void main(String[] args) throws Exception {
        File file = Files.createTempFile("audio-render-", ".wav").toFile();
        try (Tape tape = new Tape(file)) {
            short[] source = new short[Tape.RATE / 5];
            for (int i = 0; i < source.length; i++) source[i] = (short) Math.round(10000 * Math.sin(2 * Math.PI * 430 * i / Tape.RATE));
            tape.write(0, source, source.length);
            Sequencer seq = new Sequencer(tape);
            for (Sequencer.Track track : seq.tracks) { track.end = source.length; track.steps = 0xffff; }
            seq.tracks[0].pitch = -1;
            seq.tracks[0].power(Sequencer.Effects.AUTOTUNE, true);
            seq.editStep(0, 4).pitch = 1;
            seq.prepareAll(); seq.play();
            short[] block = new short[256];
            for (int i = 0; i < 400; i++) seq.render(block);
            ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            long allocated = bean.getThreadAllocatedBytes(thread), started = System.nanoTime();
            for (int i = 0; i < 600; i++) seq.render(block);
            long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
            System.out.printf("3.2s audio with pad Pitch override: %.3fs rendering, %d allocated bytes%n", (System.nanoTime() - started) / 1e9, bytes);
            if (bytes > 1024 * 1024) throw new AssertionError("Audio rendering allocates continuously");
        } finally { Files.deleteIfExists(file.toPath()); }
    }
}
