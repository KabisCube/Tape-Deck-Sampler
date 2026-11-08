package com.tapedecksampler.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class CoreTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File path = Files.createTempFile("tape-test", ".wav").toFile();
        try {
            try (Tape tape = new Tape(path)) {
                check(path.length() == 1_920_044, "Fixed 20 second file size");
                ByteBuffer header = ByteBuffer.wrap(Tape.header()).order(ByteOrder.LITTLE_ENDIAN);
                check(header.getInt(24) == 48000 && header.getShort(22) == 1 && header.getShort(34) == 16, "48k mono 16 bit WAV");
                short[] signal = new short[4800];
                Arrays.fill(signal, (short) 12000);
                tape.write(1000, signal, signal.length);
                check(tape.samples[999] == 0 && tape.samples[1000] == 12000 && tape.samples[5800] == 0, "Positioned overwrite preserves neighbors");
                tape.checkpoint();
                check(tape.write(Tape.LENGTH - 2, signal, signal.length) == 2, "Writes clip at 20 seconds");
                check(tape.undo() && tape.samples[Tape.LENGTH - 1] == 0, "Undo write");
                check(tape.undo() && tape.samples[Tape.LENGTH - 1] == 12000, "Redo write");
                tape.silence(2000, 3000);
                check(tape.samples[1999] == 12000 && tape.samples[2000] == 0 && tape.samples[3000] == 12000, "Silence only selected region");
                tape.undo();
                tape.normalize(1000, 5800);
                check(tape.samples[2000] == 31128, "Normalize headroom");
                tape.undo();
                tape.fade(1000, 5800);
                check(tape.samples[1000] == 0 && tape.samples[5799] == 0 && tape.samples[3000] == 12000, "Edge fades retain center");
                check(tape.waveform(0, Tape.LENGTH, 160)[0] > 0, "Waveform sees sample");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                tape.export(out);
                check(out.size() == 1_920_044 && out.toByteArray()[0] == 'R', "Export is full WAV");
                Sequencer sequencer = new Sequencer(tape);
                Sequencer.Track t = sequencer.tracks[0];
                t.start = 1000; t.end = 5800; t.steps = 0xffff;
                sequencer.tempo(120);
                sequencer.play();
                short[] one = new short[1];
                sequencer.render(one);
                check(sequencer.step() == 0, "Playback begins at pad 1");
                sequencer.render(new short[5999]);
                check(sequencer.step() == 0, "Step 1 holds for exactly 6000 frames at 120 BPM");
                sequencer.render(one);
                check(sequencer.step() == 1, "Pad 2 follows pad 1");
                sequencer.render(new short[14 * 6000]);
                check(sequencer.step() == 15, "Pad 16 follows pad 15");
                sequencer.render(new short[6000]);
                check(sequencer.step() == 0, "Pad 16 loops to pad 1");
                sequencer.stop();
                short[] quiet = new short[256];
                sequencer.render(quiet);
                check(sequencer.step() == -1 && allZero(quiet), "Stop clears transport and voices");
                sequencer.play();
                short[] sound = new short[256];
                sequencer.render(sound);
                check(!allZero(sound), "Enabled step produces audio");
                sequencer.stop();
                t.mute = true;
                sequencer.play();
                sequencer.render(sound);
                check(allZero(sound), "Muted track is silent");
                sequencer.stop();
                t.mute = false;
                for (Sequencer.Track track : sequencer.tracks) {
                    track.start = 1000; track.end = 5800; track.steps = 1; track.gain = 1;
                }
                sequencer.play();
                sequencer.render(sound);
                check(sound[255] > 30000 && sound[255] <= 31131, "Eight track mix limits safely without integer wrap");
                sequencer.stop();
                sequencer.play();
                sequencer.render(one);
                check(sequencer.step() == 0, "Restart resets to pad 1");
                for (Sequencer.Track track : sequencer.tracks) track.steps = 0;
                t.steps = 1;
                for (int bpm : new int[]{40, 127, 240}) {
                    sequencer.tempo(bpm);
                    sequencer.play();
                    int frames = (int) Math.ceil(16 * Tape.RATE * 15.0 / bpm);
                    sequencer.render(new short[frames]);
                    check(sequencer.step() == 15, "No drift before bar end at " + bpm);
                    sequencer.render(one);
                    check(sequencer.step() == 0, "Accurate bar wrap at " + bpm);
                }
                selectionPlayback(tape);
                tape.checkpoint();
                tape.attenuate(1000, 5800, 50);
                check(tape.samples[3000] == 6000, "Adjustable silence amount attenuates without erasing the selection");
                tape.undo();
                tape.fade(1000, 5800, 960, 240);
                check(tape.samples[1000] == 0 && tape.samples[1480] == 6000 && tape.samples[3000] == 12000 && tape.samples[5799] == 0, "Independent fade lengths affect only their own edges");
                tape.undo();
                tape.normalize(1000, 5800, 50);
                check(tape.samples[3000] == 16383, "Normalization accepts a selected peak level");
                tape.undo();
            }
            try (Tape reopened = new Tape(path)) {
                check(reopened.samples[3000] == 12000, "Tape survives reopening");
            }
            sessions(path);
            System.out.println(checks + " core checks passed");
        } finally {
            Files.deleteIfExists(path.toPath());
        }
    }

    private static void selectionPlayback(Tape tape) {
        Sequencer sequencer = new Sequencer(tape);
        for (Sequencer.Track track : sequencer.tracks) {
            track.steps = 0xffff; track.mute = true; track.gain = 0;
            track.reverb = 100; track.distortion = 3; track.speed = 50; track.pitch = 12;
        }
        short[] source = new short[1500];
        for (int i = 0; i < source.length; i++) source[i] = (short) (3000 + i);
        long revision = tape.revision();
        sequencer.play();
        sequencer.toggleSelection(source, 100, 1100);
        check(sequencer.isPreviewing() && !sequencer.isPlaying() && sequencer.step() == -1, "Selection playback replaces beat playback");
        short[] block = new short[128];
        sequencer.render(block);
        check(block[100] == source[200], "Selection plays raw audio from its start regardless of track controls");
        sequencer.toggleSelection(source, 100, 1100);
        sequencer.render(block);
        check(sequencer.hasSelectionPreview() && !sequencer.isPreviewing() && allZero(block), "Pause keeps the selection and renders silence");
        sequencer.toggleSelection(source, 100, 1100);
        sequencer.render(block);
        check(block[0] == source[228], "Resume continues from the paused position");
        sequencer.toggleSelection(source, 300, 900);
        sequencer.render(block);
        check(block[100] == source[400], "A different selection starts from its own boundary");
        short[] tail = new short[1000];
        sequencer.render(tail);
        check(!sequencer.hasSelectionPreview() && tail[471] > 0 && tail[472] == 0 && tail[999] == 0, "Selection stops exactly at its end without playing later samples");
        sequencer.toggleSelection(source, 100, 1100);
        sequencer.stop(); sequencer.render(block);
        check(!sequencer.hasSelectionPreview() && allZero(block), "Stop clears selection playback");
        sequencer.toggleSelection(source, 100, 1100);
        sequencer.play();
        check(sequencer.isPlaying() && !sequencer.hasSelectionPreview(), "Normal beat playback clears selection playback");
        sequencer.toggleSelection(source, 5, 5);
        check(sequencer.isPlaying() && !sequencer.hasSelectionPreview(), "Empty selections do not disturb playback");
        check(tape.revision() == revision, "Selection playback leaves the session tape untouched");
    }

    private static void sessions(File original) throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("tape-sessions");
        try {
            File legacy = root.resolve("tape.wav").toFile();
            Files.copy(original.toPath(), legacy.toPath());
            SessionStore store = new SessionStore(root.toFile());
            check(SessionStore.nextDefaultName(store.list()).equals("Project 01"), "First default project name starts at 01");
            SessionStore.Entry first = store.migrate(legacy);
            check(first.name().equals("Project 01") && store.list().size() == 1, "Legacy tape becomes first session");
            check(Arrays.equals(Files.readAllBytes(legacy.toPath()), Files.readAllBytes(first.tape.toPath())), "Migration preserves every audio byte");
            SessionStore.Entry second = store.create();
            check(second.name().equals("Project 02") && !first.tape.equals(second.tape), "Sessions have separate audio paths");
            try (Tape a = new Tape(first.tape); Tape b = new Tape(second.tape)) {
                a.write(12, new short[]{1111}, 1);
                b.write(12, new short[]{2222}, 1);
                check(a.samples[12] == 1111 && b.samples[12] == 2222, "Session audio edits remain isolated");
            }
            check(store.list().get(1).id.equals(second.id), "Session list is ordered");
            check(store.migrate(legacy).id.equals(first.id), "Migration is repeatable");
            try (Tape a = new Tape(first.tape); Tape b = new Tape(second.tape)) {
                check(a.samples[12] == 1111 && b.samples[12] == 2222, "Independent tapes persist without migration overwrites");
            }
            check(Arrays.equals(Files.readAllBytes(legacy.toPath()), Files.readAllBytes(original.toPath())), "Legacy source remains untouched");
            SessionStore.Entry named = store.create("  Night / groove ☾  ");
            try (Tape ignored = new Tape(named.tape)) {}
            check(named.name().equals("Night / groove ☾") && named.tape.getParentFile().getName().equals(named.id), "Session names are separate from safe stable storage paths");
            check(store.list().get(2).name().equals("Night / groove ☾"), "Custom session names persist without changing the WAV path");
            check(SessionStore.nextDefaultName(Arrays.asList(first, named, second)).equals("Project 03"), "Custom names do not advance default project numbering");
            SessionStore.Entry ninth = store.create("Project 09");
            check(SessionStore.nextDefaultName(Arrays.asList(first, ninth, second)).equals("Project 10"), "Default names advance past the highest existing number");
        } finally {
            java.nio.file.Path temp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if (!root.toRealPath().startsWith(temp) || !root.getFileName().toString().startsWith("tape-sessions")) throw new IllegalStateException("Invalid test cleanup path");
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root)) {
                for (java.nio.file.Path item : paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(item);
            }
        }
    }

    private static boolean allZero(short[] data) {
        for (short sample : data) if (sample != 0) return false;
        return true;
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
}
