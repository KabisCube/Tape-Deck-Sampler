package com.tapedecksampler.app;

import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.os.Process;

public final class ClapCapture {
    public interface Listener {
        void ready();
        void finished(long origin, String error);
    }

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final int MIN_BUFFER_BYTES = 4096, BLOCK_FRAMES = 1024;
    private static final long MAX_TIMESTAMP_AGE_NANOS = 2 * SECOND_NANOS;
    private static final long TIMING_TIMEOUT_NANOS = 4 * SECOND_NANOS, CLAP_TIMEOUT_NANOS = 30 * SECOND_NANOS;
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    public void start(int source, Listener listener) {
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioRecord input = null;
            long origin = 0;
            String error = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(Tape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                input = new AudioRecord(source, Tape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(MIN_BUFFER_BYTES, minimum));
                if (input.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone unavailable");
                input.startRecording();
                short[] block = new short[BLOCK_FRAMES];
                AudioTimestamp timestamp = new AudioTimestamp();
                ClapDetector detector = new ClapDetector(Tape.RATE);
                long frames = 0, started = System.nanoTime(), referenceFrame = 0, referenceTime = 0;
                boolean ready = false;
                while (!cancelled) {
                    int count = input.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IllegalStateException("Microphone disconnected");
                    long now = System.nanoTime();
                    if (input.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                        && timestamp.framePosition > 0 && Math.abs(timestamp.nanoTime - now) < MAX_TIMESTAMP_AGE_NANOS) {
                        referenceFrame = timestamp.framePosition;
                        referenceTime = timestamp.nanoTime;
                    }
                    boolean armed = ready;
                    long onset = detector.accept(block, count, armed);
                    frames += count;
                    if (!ready && frames >= Tape.RATE && referenceTime != 0) { ready = true; listener.ready(); }
                    if (onset >= 0) {
                        if (now - referenceTime > MAX_TIMESTAMP_AGE_NANOS) throw new IllegalStateException("Microphone timing lost. Try again.");
                        origin = referenceTime + Math.round((onset - referenceFrame) * (double) SECOND_NANOS / Tape.RATE);
                        break;
                    }
                    if (!ready && now - started > TIMING_TIMEOUT_NANOS) throw new IllegalStateException("Precise microphone timing unavailable on this device");
                    if (now - started > CLAP_TIMEOUT_NANOS) throw new IllegalStateException("No clap detected. Try again in a quiet room.");
                }
            } catch (SecurityException e) {
                error = "Microphone access is required";
            } catch (Exception e) {
                error = e.getMessage();
                if (error == null) error = "Clap sync failed. Try again.";
            } finally {
                if (input != null) {
                    try { input.stop(); } catch (IllegalStateException ignored) {}
                    input.release();
                }
            }
            if (!cancelled) listener.finished(origin, error);
        }, "tape-clap").start();
    }
}
