package com.tapedecksampler.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Process;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioEngine {
    public interface Listener {
        void recorded(int position);
        void recordingStarted();
        void finished(String error);
        void audioError(String error);
    }

    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final int MIN_OUTPUT_BUFFER_BYTES = 2048, MIN_INPUT_BUFFER_BYTES = 4096;
    private static final int OUTPUT_BLOCK_FRAMES = 256, INPUT_BLOCK_FRAMES = 1024;
    private static final long MAX_TIMESTAMP_SKEW_NANOS = 2 * SECOND_NANOS;
    private static final long MIN_TIMESTAMP_GAP_NANOS = 20_000_000L, MAX_TIMESTAMP_GAP_NANOS = 3 * SECOND_NANOS;
    private static final long TIMESTAMP_STALE_NANOS = 3 * SECOND_NANOS;
    private static final double MIN_CLOCK_SPEED = .9, MAX_CLOCK_SPEED = 1.1;
    private static final long CONFIRMED_POLL_NANOS = SECOND_NANOS, UNCONFIRMED_POLL_NANOS = 100_000_000L;
    private static final long JOIN_TIMEOUT_MS = 1500;

    private final Sequencer sequencer;
    private final Tape tape;
    private final Listener listener;
    private volatile boolean active;
    private volatile ClapClock clapClock;
    private final AtomicBoolean recording = new AtomicBoolean();
    private Thread outputThread;
    private Thread inputThread;

    public AudioEngine(Sequencer sequencer, Tape tape, Listener listener) {
        this.sequencer = sequencer;
        this.tape = tape;
        this.listener = listener;
    }

    public void setClapClock(ClapClock clock) { clapClock = clock; }

    public synchronized void start() {
        if (active) return;
        active = true;
        outputThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioTrack output = null;
            try {
                int minimum = AudioTrack.getMinBufferSize(Tape.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                output = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(Tape.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(MIN_OUTPUT_BUFFER_BYTES, minimum)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                if (output.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output unavailable");
                short[] block = new short[OUTPUT_BLOCK_FRAMES];
                AudioTimestamp timestamp = new AudioTimestamp();
                long writtenFrames = 0, timestampFrame = -1, timestampTime = 0, nextTimestamp = 0;
                long previousFrame = -1, previousTime = 0;
                boolean timestampConfirmed = false;
                output.play();
                while (active) {
                    ClapClock clap = clapClock;
                    if (clap != null && (sequencer.isSyncWaiting() || sequencer.isPlaying())) {
                        long now = System.nanoTime();
                        if (now >= nextTimestamp) {
                            if (output.getTimestamp(timestamp) && Math.abs(timestamp.nanoTime - now) < MAX_TIMESTAMP_SKEW_NANOS) {
                                long frame = ClockSync.unwrapFrame(timestamp.framePosition, writtenFrames), dt = timestamp.nanoTime - previousTime;
                                if (frame >= 0 && frame > previousFrame && dt > MIN_TIMESTAMP_GAP_NANOS && dt < MAX_TIMESTAMP_GAP_NANOS) {
                                    double speed = (frame - previousFrame) * (double) SECOND_NANOS / dt / Tape.RATE;
                                    if (previousFrame >= 0 && speed > MIN_CLOCK_SPEED && speed < MAX_CLOCK_SPEED) { timestampConfirmed = true; timestampFrame = frame; timestampTime = timestamp.nanoTime; }
                                }
                                if (frame >= 0) { previousFrame = frame; previousTime = timestamp.nanoTime; }
                            }
                            nextTimestamp = now + (timestampConfirmed ? CONFIRMED_POLL_NANOS : UNCONFIRMED_POLL_NANOS);
                        }
                        long head = Math.max(0, ClockSync.unwrapFrame(output.getPlaybackHeadPosition(), writtenFrames));
                        long presentation = timestampConfirmed && now - timestampTime < TIMESTAMP_STALE_NANOS
                            ? ClockSync.presentationNanos(writtenFrames, timestampFrame, timestampTime, now, Tape.RATE)
                            : ClockSync.presentationNanos(writtenFrames, head, now, now, Tape.RATE);
                        sequencer.renderSynced(block, clap.at(presentation), (double) SECOND_NANOS / Tape.RATE, true);
                    } else sequencer.render(block);
                    int offset = 0;
                    while (offset < block.length && active) {
                        int written = output.write(block, offset, block.length - offset, AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) throw new IOException("Audio output disconnected");
                        offset += written;
                        writtenFrames += written;
                    }
                }
            } catch (Exception e) {
                active = false;
                sequencer.stop();
                listener.audioError(e.getMessage());
            } finally {
                if (output != null) {
                    try { output.stop(); } catch (IllegalStateException ignored) {}
                    output.release();
                }
            }
        }, "tape-output");
        outputThread.start();
    }

    public boolean isRecording() { return recording.get(); }

    public void record(int start) {
        if (!recording.compareAndSet(false, true)) return;
        sequencer.stop();
        inputThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            AudioRecord input = null;
            String error = null;
            int position = start;
            try {
                int minimum = AudioRecord.getMinBufferSize(Tape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                input = new AudioRecord(MediaRecorder.AudioSource.MIC, Tape.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(MIN_INPUT_BUFFER_BYTES, minimum));
                if (input.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                input.startRecording();
                tape.checkpoint();
                listener.recordingStarted();
                short[] block = new short[INPUT_BLOCK_FRAMES];
                while (recording.get() && position < Tape.LENGTH) {
                    int count = input.read(block, 0, Math.min(block.length, Tape.LENGTH - position), AudioRecord.READ_BLOCKING);
                    if (count <= 0) throw new IOException("Microphone disconnected");
                    position += tape.write(position, block, count);
                    listener.recorded(position);
                }
                tape.flush();
            } catch (SecurityException e) {
                error = "Microphone permission was revoked";
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (input != null) {
                    try { input.stop(); } catch (IllegalStateException ignored) {}
                    input.release();
                }
                recording.set(false);
                listener.finished(error);
            }
        }, "tape-record");
        inputThread.start();
    }

    public void stopRecording() { recording.set(false); }

    public void stop() {
        active = false;
        recording.set(false);
        sequencer.stop();
        join(outputThread);
        join(inputThread);
    }

    private void join(Thread thread) {
        if (thread == null) return;
        try { thread.join(JOIN_TIMEOUT_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
