package com.tapedecksampler.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class SampleDecoder {
    public static final class Source {
        public final Uri uri;
        public final WaveSummary wave;
        public final long frames;

        Source(Uri uri, WaveSummary wave) {
            this.uri = uri; this.wave = wave; this.frames = wave.length();
        }
    }

    private static final long MICROS_PER_SECOND = 1_000_000L, DECODE_TIMEOUT_NANOS = 120_000_000_000L;
    private static final int DEQUEUE_TIMEOUT_US = 10000, FLOAT_BYTES = 4, SHORT_BYTES = 2;
    private static final double TIME_EPSILON = 0.00001;

    private interface Sink { void accept(short sample); }

    public static Source inspect(Context context, Uri uri) throws IOException {
        WaveSummary wave = new WaveSummary();
        stream(context, uri, 0, Long.MAX_VALUE, wave::accept);
        wave.finish();
        return new Source(uri, wave);
    }

    public static short[] decode(Context context, Uri uri) throws IOException {
        return range(context, uri, 0, Tape.LENGTH);
    }

    public static short[] decode(Context context, Source source, long start, long end) throws IOException {
        if (start < 0 || end > source.frames || end <= start || end - start > Tape.LENGTH) throw new IOException("Select between 1 ms and " + Tape.SECONDS + " seconds");
        return range(context, source.uri, start, end);
    }

    private static short[] range(Context context, Uri uri, long start, long end) throws IOException {
        short[] result = new short[(int) (end - start)];
        int[] written = {0};
        stream(context, uri, start, end, sample -> result[written[0]++] = sample);
        return Arrays.copyOf(result, written[0]);
    }

    private static void stream(Context context, Uri uri, long start, long end, Sink sink) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i); format = candidate; break;
                }
            }
            if (format == null) throw new IOException("No supported audio track");
            if (start > 0) extractor.seekTo(start * MICROS_PER_SECOND / Tape.RATE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            long next = start;
            double previousTime = Double.NaN;
            short previous = 0;
            boolean inputDone = false, outputDone = false;
            long deadline = System.nanoTime() + DECODE_TIMEOUT_NANOS;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone && next < end) {
                if (System.nanoTime() > deadline) throw new IOException("Audio decoding timed out");
                if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                if (!inputDone) {
                    int input = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        if (buffer == null) throw new IOException("Decoder input unavailable");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int output = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = codec.getOutputFormat();
                    rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ? actual.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (channels < 1 || rate < 1 || (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)) throw new IOException("Unsupported decoded audio format");
                } else if (output >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(output);
                    if (buffer != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? FLOAT_BYTES : SHORT_BYTES;
                        int frames = buffer.remaining() / (channels * bytes);
                        double origin = info.presentationTimeUs * (Tape.RATE / (double) MICROS_PER_SECOND);
                        if (rate == Tape.RATE) origin = Math.rint(origin);
                        double interval = Tape.RATE / (double) rate;
                        for (int f = 0; f < frames && next < end; f++) {
                            float value = 0;
                            for (int c = 0; c < channels; c++) value += encoding == AudioFormat.ENCODING_PCM_FLOAT ? buffer.getFloat() * Short.MAX_VALUE : buffer.getShort();
                            short mono = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value / channels)));
                            double time = origin + f * interval;
                            if (Double.isNaN(previousTime)) { previousTime = time - interval; previous = mono; }
                            while (next <= time + TIME_EPSILON && next < end) {
                                double blend = time > previousTime ? Math.max(0, Math.min(1, (next - previousTime) / (time - previousTime))) : 1;
                                sink.accept((short) Math.round(previous + (mono - previous) * blend));
                                next++;
                            }
                            previousTime = time; previous = mono;
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(output, false);
                }
            }
            if (outputDone && !Double.isNaN(previousTime)) {
                long tail = (long) Math.ceil(previousTime + Tape.RATE / (double) rate - TIME_EPSILON);
                while (next < end && next < tail) { sink.accept(previous); next++; }
            }
            if (next == start) throw new IOException("This selection contains no audio");
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode this audio file", e);
        } finally {
            extractor.release();
            if (codec != null) {
                try { codec.stop(); } catch (IllegalStateException ignored) {}
                codec.release();
            }
        }
    }
}
