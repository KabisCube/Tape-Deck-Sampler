package com.tapedecksampler.app;

import java.io.File;
import java.nio.file.Files;

public final class PagesTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File file = Files.createTempFile("tape-pages", ".wav").toFile();
        try (Tape tape = new Tape(file)) {
            Sequencer sequence = new Sequencer(tape);
            for (int p = 0; p < 16; p++) check(sequence.page(p).enabled == (p == 0), "Only first page enabled");
            sequence.tracks[0].steps = 0x8001;
            sequence.editStep(0, 0).pitch = 7;
            Sequencer.Page clipboard = sequence.page(0).copy();
            sequence.selectPage(2);
            check(sequence.tracks[0].steps == 0 && sequence.tracks[0].padEffects[0] == null, "New page is empty");
            check(Sequencer.updatedPageColor(0xffcc8850) == 0xff3cc4de && Sequencer.updatedPageColor(0xffeb5231) == 0xffeb5231 && Sequencer.updatedPageColor(0xff123456) == 0xff123456, "Saved palette colors update and others stay");
            Sequencer cleared = new Sequencer(tape);
            cleared.tracks[0].steps = 0x8001; cleared.editStep(0, 0).pitch = 7;
            cleared.selectPage(1); cleared.tracks[0].steps = 3; cleared.selectPage(0);
            int clearedColor = cleared.page(0).color;
            cleared.clearPage(0);
            check(cleared.tracks[0].steps == 0 && cleared.tracks[0].padEffects[0] == null, "Reset clears the page pattern and pad effects");
            check(cleared.page(0).enabled && cleared.page(0).color == clearedColor && cleared.page(1).steps[0] == 3, "Reset keeps color, enabled state and other pages");
            cleared.clearPage(1);
            check(cleared.page(1).steps[0] == 0 && cleared.selectedPage() == 0, "Reset works on a page that is not selected");
            Sequencer flags = new Sequencer(tape);
            check(!flags.transPageOrder() && !flags.allPagesMoved(), "Default order shows no secret settings");
            flags.swapPages(0, 7);
            check(!flags.transPageOrder(), "Blue alone does not count");
            flags.swapPages(1, 13);
            check(flags.transPageOrder() && !flags.allPagesMoved(), "Blue then pink is the exact trans order");
            flags.swapPages(0, 1);
            check(!flags.transPageOrder(), "Pink then blue is not the order");
            Sequencer moved = new Sequencer(tape);
            for (int p = 0; p < 7; p++) moved.swapPages(p, p + 8);
            check(!moved.allPagesMoved(), "One page in its default spot blocks the pride setting");
            moved.swapPages(7, 15);
            check(moved.allPagesMoved(), "Every page moved unlocks the pride setting");
            int color = sequence.page(2).color;
            sequence.replacePage(2, clipboard);
            check(sequence.page(2).color == color && sequence.page(2).enabled, "Paste retains destination identity");
            check(sequence.tracks[0].steps == 0x8001 && sequence.tracks[0].padEffects[0].pitch == 7, "Paste includes patterns and effects");
            sequence.editStep(0, 0).pitch = 12;
            check(clipboard.padEffects[0][0].pitch == 7 && sequence.page(0).padEffects[0][0].pitch == 7, "Clipboard and source are independent");
            sequence.selectPage(0);
            sequence.play();
            sequence.render(new short[96000]);
            check(sequence.playingPage() == 0 && sequence.step() == 15, "First page plays through pad sixteen");
            sequence.render(new short[1]);
            check(sequence.playingPage() == 2 && sequence.step() == 0, "Next enabled page begins at pad one");
            sequence.render(new short[96000]);
            check(sequence.playingPage() == 0 && sequence.step() == 0, "Last enabled page wraps");
            sequence.swapPages(0, 5);
            check(sequence.selectedPage() == 5 && sequence.playingPage() == 5, "Swap follows selected and playing content");
            check(sequence.page(5).color == clipboard.color && sequence.page(5).steps[0] == 0x8001, "Swap moves color and pattern");
            sequence.page(2).enabled = false;
            sequence.render(new short[96000]);
            check(sequence.playingPage() == 5, "Single enabled page loops");
            sequence.page(5).enabled = false;
            sequence.render(new short[96000]);
            check(sequence.playingPage() == -1, "No enabled pages plays silence");
            sequence.stop();
            sequence.selectPage(9);
            check(sequence.page(9).enabled, "Selecting a disabled page enables it");
            sequence.playSynced();
            sequence.renderSynced(new short[1], 0, 1_000_000_000.0 / Tape.RATE, true);
            check(sequence.step() == 0 && sequence.playingPage() == 9, "Synced playback starts selected page");
            sequence.page(12).enabled = true;
            sequence.renderSynced(new short[1], 2_000_000_000L, 1_000_000_000.0 / Tape.RATE, true);
            check(sequence.step() == 0 && sequence.playingPage() == 12, "Synced boundary advances pages");
            sequence.stop();
            sequence.selectPage(0);
            sequence.prepareAll();
            sequence.play();
            sequence.render(new short[96001]);
            sequence.resetEffects(0);
            sequence.render(new short[256]);
            for (int p = 0; p < 16; p++) check(sequence.page(p).padEffects[0][0] == null, "Track reset clears every page override");
            short[] sample = new short[1000];
            java.util.Arrays.fill(sample, (short) 12000); tape.write(0, sample, sample.length);
            Sequencer audio = new Sequencer(tape);
            audio.selectPage(2); audio.tracks[0].steps = 1;
            audio.selectPage(0); audio.editStep(0, 0).mute = true;
            audio.play(); short[] first = new short[96000]; audio.render(first);
            check(allZero(first), "Silent first page does not use another page pattern");
            short[] second = new short[256]; audio.render(second);
            check(!allZero(second), "Playing page uses its own pattern and effects while another page is edited");
            Sequencer perPage = new Sequencer(tape);
            perPage.tracks[0].reverb = 40; perPage.tracks[0].gain = 0.5f;
            perPage.selectPage(1);
            check(perPage.tracks[0].reverb == 0 && perPage.tracks[0].gain == Sequencer.Effects.DEFAULT_GAIN, "Each page has its own track effects");
            perPage.tracks[0].gain = 0f; perPage.tracks[0].mute = true;
            perPage.selectPage(0);
            check(perPage.tracks[0].reverb == 40 && perPage.tracks[0].gain == 0.5f && perPage.tracks[0].mute, "Switching pages restores that page's track effects and keeps mute global");
            check(perPage.page(1).trackEffects[0].gain == 0f, "Track effects of other pages are kept");
            Sequencer quiet = new Sequencer(tape);
            quiet.selectPage(2); quiet.tracks[0].steps = 1; quiet.tracks[0].gain = 0f;
            quiet.selectPage(0);
            quiet.play(); quiet.render(new short[96000]); short[] silent = new short[256]; quiet.render(silent);
            check(allZero(silent), "Playing page uses its own track volume while another page is selected");
            Sequencer shared = new Sequencer(tape);
            shared.tracks[0].reverb = 30; shared.shareTrackEffects(); shared.selectPage(3);
            check(shared.tracks[0].reverb == 30, "Older projects share their track effects with every page");
            Sequencer copied = new Sequencer(tape);
            copied.tracks[0].delayMix = 20; Sequencer.Page source = copied.page(0).copy();
            copied.replacePage(4, source); copied.selectPage(4);
            check(copied.tracks[0].delayMix == 20, "Copying a page includes its track effects");
            Sequencer inherit = new Sequencer(tape);
            inherit.tracks[0].reverb = 30;
            Sequencer.Effects pad = inherit.editStep(0, 2);
            pad.delayMix = 40; pad.custom |= 1 << Sequencer.Effects.DELAY;
            inherit.tracks[0].highPass = 300; inherit.tracks[0].delayMix = 10; inherit.tracks[0].reverb = 50;
            inherit.inheritTrack(0);
            check(pad.highPass == 300 && pad.reverb == 50 && pad.delayMix == 40, "Pads follow new track effects except the ones they changed");
            check(pad.differences(inherit.tracks[0]) == (1 << Sequencer.Effects.DELAY), "Pad changes can be inferred for older projects");
        } finally { Files.deleteIfExists(file.toPath()); }
        System.out.println(checks + " page checks passed");
    }

    private static boolean allZero(short[] samples) {
        for (short sample : samples) if (sample != 0) return false;
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
