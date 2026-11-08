package com.tapedecksampler.app;

public final class TimelineWindowTest {
    private static int checks;

    public static void main(String[] args) {
        long full = Tape.LENGTH, start = Tape.RATE * 12, end = Tape.RATE * 16;
        long span = TimelineWindow.fit(full, 48, start, end);
        check(span == Tape.RATE * 5, "A close zoom expands to include the clip with 25 percent context");
        long from = TimelineWindow.start(full, span, start, start, end);
        check(from < start && from + span > end, "Both clip boundaries and surrounding audio are visible");
        check(TimelineWindow.start(full, span, end, start, end) == from, "Switching boundaries keeps the full clip in the same window");
        check(TimelineWindow.fit(full, Tape.RATE * 10, start, end) == Tape.RATE * 10, "Fitting preserves an existing wider zoom");
        long narrow = Tape.RATE / 10;
        check(TimelineWindow.start(full, narrow, end, start, end) == end - narrow / 2, "Manual close zoom follows the edited boundary");
        check(TimelineWindow.fit(full, 48, 0, full) == full && TimelineWindow.start(full, full, full, 0, full) == 0, "Full-length clips stay within the tape");
        check(TimelineWindow.start(full, span, 0, 0, end - start) == 0, "Clips at the beginning cannot pan before the tape");
        check(TimelineWindow.start(full, span, full, full - (end - start), full) == full - span, "Clips at the end cannot pan beyond the tape");
        check(TimelineWindow.fit(full, 48, start, start + 48) == 4848, "Tiny clips retain useful context");
        long source = Tape.RATE * 3600L, offset = Tape.RATE * 3000L;
        long sourceSpan = TimelineWindow.fit(source, 48, offset, offset + end - start);
        check(TimelineWindow.start(source, sourceSpan, offset, offset, offset + end - start) == offset - Tape.RATE / 2, "Long imports use the same padded framing");
        SampleRegions samples = new SampleRegions();
        TimelineWindow.Overview overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.start == 0 && overview.span == Tape.RATE * 10, "Empty projects keep a useful default overview");
        samples.place(0, Tape.RATE, "a", "First"); samples.place(Tape.RATE, Tape.RATE * 2, "b", "Second");
        overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.start == 0 && overview.span == Tape.RATE * 4, "Overview fits all samples plus two average sample lengths");
        samples.place(Tape.RATE * 3, Tape.RATE * 5, "c", "Longer");
        overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.span == Tape.RATE * 5 + (Tape.RATE * 8L + 2) / 3, "Overview includes gaps and uses the average sample duration");
        samples = new SampleRegions(); samples.place(Tape.RATE * 12, Tape.RATE * 13, "offset", "Offset");
        overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.start == Tape.RATE * 12 && overview.span == Tape.RATE * 3, "Samples far into the tape remain visible with room after them");
        samples = new SampleRegions(); samples.place(Tape.LENGTH - Tape.RATE, Tape.LENGTH, "last", "Last");
        overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.start == full - Tape.RATE * 3 && overview.start + overview.span == full, "Overview stays within the tape at its end");
        samples.place(0, Tape.LENGTH, "whole", "Whole");
        overview = TimelineWindow.overview(full, samples.snapshot(), Tape.RATE * 10);
        check(overview.start == 0 && overview.span == full, "Large samples clamp the overview to the full tape");
        System.out.println(checks + " timeline framing checks passed");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
