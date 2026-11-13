package com.tapedecksampler.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

class Sequencer(private val tape: Tape) {
    open class Effects {
        @JvmField var chain: IntArray = DEFAULT_CHAIN
        @JvmField var neutralGain = DEFAULT_GAIN
        @JvmField var gain = DEFAULT_GAIN
        @JvmField var mute = false
        @JvmField var speed = 100
        @JvmField var pitch = 0
        @JvmField var reverb = 0
        @JvmField var distortion = 0
        @JvmField var drive = DEFAULT_DRIVE
        @JvmField var distortionMix = 100
        @JvmField var crushBits = TrackEffects.DEFAULT_CRUSH_BITS
        @JvmField var crushRate = TrackEffects.DEFAULT_CRUSH_RATE
        @JvmField var highPass = 0
        @JvmField var lowPass = 0
        @JvmField var bandPass = 0
        @JvmField var highResonance = 0
        @JvmField var lowResonance = 0
        @JvmField var bandResonance = 0
        @JvmField var delayBeat = DEFAULT_DELAY_BEAT
        @JvmField var delayEchoes = DEFAULT_DELAY_ECHOES
        @JvmField var delayMix = 0
        @JvmField var attack = 0
        @JvmField var decay = 0
        @JvmField var sustain = 100
        @JvmField var release = 0
        @JvmField var swingOverride = 0
        @JvmField var nudge = 0
        @JvmField var autoKey = 0
        @JvmField var autoScale = 0
        @JvmField var autoStrength = 0
        @JvmField var autoRetune = DEFAULT_RETUNE_MS
        @JvmField var autoMix = 100
        @JvmField var robotProfile = RobotEffect.DEFAULT_PROFILE
        @JvmField var robotMorph = RobotEffect.DEFAULT_MORPH
        @JvmField var robotMix = 0
        @JvmField var chance = 100
        @JvmField var every = 1
        @JvmField var wobbleMode = 0
        @JvmField var wobbleRate = DEFAULT_WOBBLE_RATE
        @JvmField var wobbleDepth = 0
        @JvmField var trimStart = 0
        @JvmField var trimEnd = 0
        @JvmField var effectPower = 0
        @JvmField var effectPowerSet = 0
        @JvmField var custom = 0
        @JvmField internal var prepared: PreparedSample? = null

        fun sameGroup(group: Int, other: Effects): Boolean {
            val bit = 1 shl group
            if ((effectPower and bit) != (other.effectPower and bit) || (effectPowerSet and bit) != (other.effectPowerSet and bit)) return false
            return when (group) {
                SAMPLE -> speed == other.speed
                AUTOTUNE -> autoKey == other.autoKey && autoScale == other.autoScale && autoStrength == other.autoStrength && autoRetune == other.autoRetune && autoMix == other.autoMix
                ROBOT -> robotProfile == other.robotProfile && robotMorph == other.robotMorph && robotMix == other.robotMix
                HIGH_PASS -> highPass == other.highPass && highResonance == other.highResonance
                LOW_PASS -> lowPass == other.lowPass && lowResonance == other.lowResonance
                BAND_PASS -> bandPass == other.bandPass && bandResonance == other.bandResonance
                DELAY -> delayBeat == other.delayBeat && delayEchoes == other.delayEchoes && delayMix == other.delayMix
                DISTORTION -> distortion == other.distortion && drive == other.drive && distortionMix == other.distortionMix && crushBits == other.crushBits && crushRate == other.crushRate
                ENVELOPE -> attack == other.attack && decay == other.decay && sustain == other.sustain && release == other.release
                REVERB -> reverb == other.reverb
                SWING -> swingOverride == other.swingOverride
                NUDGE -> nudge == other.nudge
                CHANCE -> chance == other.chance
                EVERY -> every == other.every
                WOBBLE -> wobbleMode == other.wobbleMode && wobbleRate == other.wobbleRate && wobbleDepth == other.wobbleDepth
                SHIFT -> trimStart == other.trimStart && trimEnd == other.trimEnd
                VOLUME -> gain == other.gain
                PITCH -> pitch == other.pitch
                else -> true
            }
        }

        fun differences(track: Effects): Int {
            var mask = if (chain.contentEquals(track.chain)) 0 else CHAIN_BIT
            for (group in 0 until GROUPS) if (inherits(group) && !sameGroup(group, track)) mask = mask or (1 shl group)
            return mask
        }

        fun stack(): IntArray {
            val order = IntArray(GROUPS)
            var count = 0
            for (group in SOURCE_STAGE) if (powered(group)) order[count++] = group
            for (group in chain) if (powered(group)) order[count++] = group
            for (group in OUTPUT_STAGE) if (powered(group)) order[count++] = group
            return order.copyOf(count)
        }

        fun position(group: Int): Int {
            var position = 0
            for (other in chain) {
                if (other == group) return position
                if (powered(other)) position++
            }
            return position
        }

        fun place(group: Int, position: Int) {
            val next = IntArray(chain.size)
            var written = 0
            var passed = 0
            var placed = false
            for (other in chain) {
                if (other == group) continue
                if (!placed && passed == position) { next[written++] = group; placed = true }
                next[written++] = other
                if (powered(other)) passed++
            }
            if (!placed) next[written] = group
            chain = next
        }

        fun move(group: Int, delta: Int): Boolean {
            val count = chain.count { it != group && powered(it) }
            val from = position(group)
            val to = (from + delta).coerceIn(0, count)
            if (to == from) return false
            place(group, to)
            return true
        }

        fun restoreOrder(saved: IntArray?) {
            if (saved == null) return
            val expanded = saved.toMutableList()
            if (expanded.size == LEGACY_CHAIN_LENGTH) {
                val high = expanded.indexOf(HIGH_PASS)
                if (high < 0) return
                expanded.add(high + 1, LOW_PASS)
            }
            if (expanded.size == SPLIT_CHAIN_LENGTH) {
                val low = expanded.indexOf(LOW_PASS)
                if (low < 0) return
                expanded.add(low + 1, BAND_PASS)
            }
            val order = expanded.toIntArray()
            if (order.size != CHAIN_LENGTH) return
            var seen = 0
            for (group in order) {
                if (!movable(group) || (seen and (1 shl group)) != 0) return
                seen = seen or (1 shl group)
            }
            chain = order
        }

        fun splitGroups() {
            val high = 1 shl HIGH_PASS
            val low = 1 shl LOW_PASS
            val swing = 1 shl SWING
            effectPower = effectPower and swing.inv()
            effectPowerSet = effectPowerSet and swing.inv()
            val off = (effectPowerSet and high) != 0 && (effectPower and high) == 0
            effectPower = effectPower and (high or low).inv()
            effectPowerSet = effectPowerSet and (high or low).inv()
            if (off) effectPowerSet = effectPowerSet or high or low
        }

        fun normalize() {
            for (group in SAMPLE until GROUPS) {
                if (powered(group)) { effectPowerSet = effectPowerSet or (1 shl group); effectPower = effectPower or (1 shl group) }
            }
        }

        fun powered(group: Int): Boolean {
            val bit = 1 shl group
            if ((effectPowerSet and bit) != 0) return (effectPower and bit) != 0
            return when (group) {
                ROBOT -> robotMix > 0
                AUTOTUNE -> autoStrength > 0 && autoMix > 0
                HIGH_PASS -> highPass > 0
                LOW_PASS -> lowPass > 0
                BAND_PASS -> bandPass > 0
                DELAY -> delayMix > 0
                DISTORTION -> distortion != 0 && distortionMix > 0
                ENVELOPE -> attack > 0 || decay > 0 || sustain != 100 || release > 0
                REVERB -> reverb > 0
                SAMPLE -> speed != 100
                PITCH -> pitch != 0
                SWING -> swingOverride != 0
                NUDGE -> nudge != 0
                CHANCE -> chance != 100
                EVERY -> every != 1
                WOBBLE -> wobbleDepth > 0
                SHIFT -> trimStart != 0 || trimEnd != 0
                VOLUME -> gain != neutralGain
                else -> false
            }
        }

        fun power(group: Int, enabled: Boolean) {
            require(hasPowerSwitch(group)) { "Effect has no power switch" }
            val bit = 1 shl group
            if (enabled && (effectPowerSet and bit) == 0) {
                when (group) {
                    ROBOT -> if (robotMix == 0) robotMix = 100
                    AUTOTUNE -> if (autoStrength == 0) autoStrength = ON_AUTOTUNE_STRENGTH
                    HIGH_PASS -> if (highPass == 0) highPass = ON_HIGH_PASS_HZ
                    LOW_PASS -> if (lowPass == 0) lowPass = ON_LOW_PASS_HZ
                    BAND_PASS -> if (bandPass == 0) bandPass = ON_BAND_PASS_HZ
                    DISTORTION -> if (distortion == 0) distortion = ON_DISTORTION_TYPE
                    DELAY -> { if (delayMix == 0) delayMix = ON_DELAY_MIX; delayBeat = DEFAULT_DELAY_BEAT }
                    ENVELOPE -> if (attack == 0 && decay == 0 && sustain == 100 && release == 0) release = ON_RELEASE_MS
                    REVERB -> if (reverb == 0) reverb = ON_REVERB
                    CHANCE -> if (chance == 100) chance = ON_CHANCE
                    EVERY -> if (every == 1) every = ON_EVERY
                    WOBBLE -> if (wobbleDepth == 0) wobbleDepth = ON_WOBBLE_DEPTH
                }
            }
            effectPowerSet = effectPowerSet or bit
            effectPower = if (enabled) effectPower or bit else effectPower and bit.inv()
        }

        fun wobbles(): Boolean = powered(WOBBLE) && wobbleDepth > 0

        fun hasRobot(): Boolean = powered(ROBOT) && robotMix > 0

        fun hasAutotune(): Boolean = powered(AUTOTUNE) && autoStrength > 0 && autoMix > 0

        fun resetGroup(group: Int) {
            when (group) {
                SAMPLE -> { speed = 100; prepared = null }
                AUTOTUNE -> { autoKey = 0; autoScale = 0; autoStrength = 0; autoRetune = DEFAULT_RETUNE_MS; autoMix = 100; prepared = null }
                ROBOT -> { robotProfile = RobotEffect.DEFAULT_PROFILE; robotMorph = RobotEffect.DEFAULT_MORPH; robotMix = 0; prepared = null }
                HIGH_PASS -> { highPass = 0; highResonance = 0 }
                LOW_PASS -> { lowPass = 0; lowResonance = 0 }
                BAND_PASS -> { bandPass = 0; bandResonance = 0 }
                DELAY -> { delayBeat = DEFAULT_DELAY_BEAT; delayEchoes = DEFAULT_DELAY_ECHOES; delayMix = 0 }
                DISTORTION -> { distortion = 0; drive = DEFAULT_DRIVE; distortionMix = 100; crushBits = TrackEffects.DEFAULT_CRUSH_BITS; crushRate = TrackEffects.DEFAULT_CRUSH_RATE }
                ENVELOPE -> { attack = 0; decay = 0; release = 0; sustain = 100 }
                REVERB -> reverb = 0
                SWING -> swingOverride = 0
                NUDGE -> nudge = 0
                CHANCE -> chance = 100
                EVERY -> every = 1
                WOBBLE -> { wobbleMode = 0; wobbleRate = DEFAULT_WOBBLE_RATE; wobbleDepth = 0 }
                SHIFT -> { trimStart = 0; trimEnd = 0; prepared = null }
                VOLUME -> gain = neutralGain
                PITCH -> { pitch = 0; prepared = null }
                else -> throw IllegalArgumentException("Unknown effect group")
            }
            if (hasPowerSwitch(group)) {
                effectPower = effectPower and (1 shl group).inv()
                effectPowerSet = effectPowerSet and (1 shl group).inv()
            }
        }

        fun restoreGroup(group: Int, source: Effects) {
            when (group) {
                SAMPLE -> { speed = source.speed; prepared = null }
                AUTOTUNE -> {
                    autoKey = source.autoKey; autoScale = source.autoScale; autoStrength = source.autoStrength
                    autoRetune = source.autoRetune; autoMix = source.autoMix; prepared = null
                }
                ROBOT -> {
                    robotProfile = source.robotProfile; robotMorph = source.robotMorph; robotMix = source.robotMix; prepared = null
                }
                HIGH_PASS -> { highPass = source.highPass; highResonance = source.highResonance }
                LOW_PASS -> { lowPass = source.lowPass; lowResonance = source.lowResonance }
                BAND_PASS -> { bandPass = source.bandPass; bandResonance = source.bandResonance }
                DELAY -> { delayBeat = source.delayBeat; delayEchoes = source.delayEchoes; delayMix = source.delayMix }
                DISTORTION -> { distortion = source.distortion; drive = source.drive; distortionMix = source.distortionMix; crushBits = source.crushBits; crushRate = source.crushRate }
                ENVELOPE -> { attack = source.attack; decay = source.decay; sustain = source.sustain; release = source.release }
                REVERB -> reverb = source.reverb
                SWING -> swingOverride = source.swingOverride
                NUDGE -> nudge = source.nudge
                CHANCE -> chance = source.chance
                EVERY -> every = source.every
                WOBBLE -> { wobbleMode = source.wobbleMode; wobbleRate = source.wobbleRate; wobbleDepth = source.wobbleDepth }
                SHIFT -> { trimStart = source.trimStart; trimEnd = source.trimEnd; prepared = null }
                VOLUME -> gain = source.gain
                PITCH -> { pitch = source.pitch; prepared = null }
                else -> throw IllegalArgumentException("Unknown effect group")
            }
            val bit = 1 shl group
            effectPower = (effectPower and bit.inv()) or (source.effectPower and bit)
            effectPowerSet = (effectPowerSet and bit.inv()) or (source.effectPowerSet and bit)
        }

        fun copy(): Effects = Effects().also { it.copyFrom(this) }

        fun copyFrom(source: Effects) {
            gain = source.gain; mute = source.mute; speed = source.speed; pitch = source.pitch
            reverb = source.reverb; distortion = source.distortion; drive = source.drive; distortionMix = source.distortionMix; prepared = source.prepared
            highPass = source.highPass; lowPass = source.lowPass; bandPass = source.bandPass
            highResonance = source.highResonance; lowResonance = source.lowResonance; bandResonance = source.bandResonance
            crushBits = source.crushBits; crushRate = source.crushRate; delayBeat = source.delayBeat
            delayEchoes = source.delayEchoes; delayMix = source.delayMix
            attack = source.attack; decay = source.decay; sustain = source.sustain; release = source.release
            swingOverride = source.swingOverride; nudge = source.nudge
            autoKey = source.autoKey; autoScale = source.autoScale; autoStrength = source.autoStrength
            autoRetune = source.autoRetune; autoMix = source.autoMix
            robotProfile = source.robotProfile; robotMorph = source.robotMorph; robotMix = source.robotMix
            effectPower = source.effectPower; effectPowerSet = source.effectPowerSet
            chain = source.chain; neutralGain = source.neutralGain
            chance = source.chance; every = source.every
            wobbleMode = source.wobbleMode; wobbleRate = source.wobbleRate; wobbleDepth = source.wobbleDepth
            trimStart = source.trimStart; trimEnd = source.trimEnd
            custom = source.custom
        }

        companion object {
            const val SAMPLE = 0
            const val AUTOTUNE = 1
            const val HIGH_PASS = 2
            const val DELAY = 3
            const val DISTORTION = 4
            const val ENVELOPE = 5
            const val REVERB = 6
            const val SWING = 7
            const val VOLUME = 8
            const val SHIFT = 9
            const val PITCH = 10
            const val LOW_PASS = 11
            const val NUDGE = 12
            const val CHANCE = 13
            const val EVERY = 14
            const val WOBBLE = 15
            const val WOBBLE_VOLUME = 0
            const val WOBBLE_PITCH = 1
            const val WOBBLE_SPEED = 2
            const val ROBOT = 16
            const val BAND_PASS = 17
            const val GROUPS = 18
            private const val LEGACY_CHAIN_LENGTH = 4
            const val SPLIT_CHAIN_LENGTH = 5
            const val CHAIN_LENGTH = 6
            const val CHAIN_BIT = 1 shl 30
            const val DEFAULT_GAIN = 0.8f
            const val DEFAULT_DRIVE = 40
            const val DEFAULT_DELAY_BEAT = 8
            const val DEFAULT_DELAY_ECHOES = 3
            const val DEFAULT_RETUNE_MS = 0
            const val DEFAULT_WOBBLE_RATE = 50
            private const val ON_AUTOTUNE_STRENGTH = 100
            private const val ON_HIGH_PASS_HZ = 200
            private const val ON_LOW_PASS_HZ = 12000
            private const val ON_BAND_PASS_HZ = 1000
            private const val ON_DELAY_MIX = 25
            private const val ON_DISTORTION_TYPE = 1
            private const val ON_RELEASE_MS = 50
            private const val ON_REVERB = 25
            private const val ON_CHANCE = 50
            private const val ON_EVERY = 2
            private const val ON_WOBBLE_DEPTH = 50
            private val DEFAULT_CHAIN = intArrayOf(HIGH_PASS, LOW_PASS, BAND_PASS, DISTORTION, REVERB, DELAY)
            private val SOURCE_STAGE = intArrayOf(CHANCE, EVERY, SHIFT, SAMPLE, PITCH, ROBOT, AUTOTUNE, SWING, NUDGE, WOBBLE, ENVELOPE)
            private val OUTPUT_STAGE = intArrayOf(VOLUME)

            @JvmStatic fun hasPowerSwitch(group: Int): Boolean = group in SAMPLE until GROUPS

            @JvmStatic fun inherits(group: Int): Boolean = group != VOLUME && group != SWING && group != NUDGE

            @JvmStatic fun movable(group: Int): Boolean = group == HIGH_PASS || group == LOW_PASS || group == BAND_PASS || group == DELAY || group == DISTORTION || group == REVERB
        }
    }

    class Track : Effects() {
        @JvmField var steps = 0
        @JvmField var start = 0
        @JvmField var end = DEFAULT_SLICE_FRAMES
        @JvmField var padEffects = arrayOfNulls<Effects>(STEPS)
    }

    class Page internal constructor(@JvmField var color: Int) {
        @JvmField var enabled = false
        @JvmField val steps = IntArray(TRACKS)
        @JvmField val padEffects: Array<Array<Effects?>> = Array(TRACKS) { arrayOfNulls(STEPS) }
        @JvmField val trackEffects: Array<Effects> = Array(TRACKS) { Effects() }
        internal val buses: Array<Array<StepBus?>> = Array(TRACKS) { arrayOfNulls(STEPS) }
        internal val trackBuses = arrayOfNulls<StepBus>(TRACKS)
        internal val passes = Array(TRACKS) { IntArray(STEPS) }

        fun copy(): Page {
            val result = Page(color)
            result.enabled = enabled
            steps.copyInto(result.steps)
            for (t in 0 until TRACKS) {
                result.trackEffects[t].copyFrom(trackEffects[t])
                for (p in 0 until STEPS) result.padEffects[t][p] = padEffects[t][p]?.copy()
            }
            return result
        }
    }

    class PreparedSample internal constructor(
        @JvmField internal val samples: ShortArray,
        @JvmField internal val start: Int,
        @JvmField internal val end: Int,
        @JvmField internal val speed: Int,
        @JvmField internal val pitch: Int,
        @JvmField internal val revision: Long,
        controls: Effects,
    ) {
        private val autoKey = controls.autoKey
        private val autoScale = controls.autoScale
        private val autoStrength = controls.autoStrength
        private val autoRetune = controls.autoRetune
        private val autoMix = controls.autoMix
        private val robotProfile = controls.robotProfile
        private val robotMorph = controls.robotMorph
        private val robotMix = controls.robotMix
        private val robotPowered = controls.powered(Effects.ROBOT)
        private val autoPowered = controls.powered(Effects.AUTOTUNE)

        internal fun matches(track: Track, controls: Effects, version: Long): Boolean =
            start == sliceStart(track, controls) && end == sliceEnd(track, controls) && speed == controls.speed && pitch == controls.pitch && revision == version
                && tuningMatches(controls)

        internal fun tuningMatches(controls: Effects): Boolean =
            autoKey == controls.autoKey && autoScale == controls.autoScale && autoStrength == controls.autoStrength && autoRetune == controls.autoRetune && autoMix == controls.autoMix && autoPowered == controls.powered(Effects.AUTOTUNE)
                && robotProfile == controls.robotProfile && robotMorph == controls.robotMorph && robotMix == controls.robotMix && robotPowered == controls.powered(Effects.ROBOT)
    }

    private class Voice(@JvmField val track: Int, @JvmField val step: Int, @JvmField var samples: ShortArray, @JvmField var start: Int, @JvmField var end: Int) {
        @JvmField var position = start
        @JvmField var age = 0
        @JvmField var fraction = 0.0
        @JvmField var bus: StepBus? = null

        constructor(track: Int, samples: ShortArray, start: Int, end: Int) : this(track, -1, samples, start, end)
    }

    internal class StepBus(@JvmField val track: Int, @JvmField val step: Int, @JvmField val controls: Effects, @JvmField val owner: Page) {
        @JvmField val effects = TrackEffects()
        @JvmField var mix = 0f
        @JvmField var silentFrames = 0
        @JvmField var active = false
        @JvmField var processing = false
    }

    @JvmField val tracks = Array(TRACKS) { Track() }
    private val effects = Array(TRACKS) { TrackEffects() }
    private val mix = FloatArray(TRACKS)
    private val processing = BooleanArray(TRACKS)
    private val voices = ArrayList<Voice>()
    private val pages = Array(PAGE_COUNT) { Page(PAGE_COLORS[it]) }
    private var selectedPage = 0
    private var playingPage = 0
    private var previousPlayingPage = -1
    private var transportTick = -1L
    private var firstTrackTick = 0L
    private var sequenceTickLimit = Long.MAX_VALUE
    private val pageTicks = Array(TRACKS) { LongArray(PAGE_COUNT) }
    private val activeBuses = ArrayList<StepBus>()
    private var untilStep = 0.0
    private var running = false
    private var syncWaiting = false
    private var clockDriven = false
    private var syncStart = -1L
    private var syncTick = Long.MIN_VALUE
    private var step = -1
    private var bpm = DEFAULT_BPM
    private var swing = DEFAULT_SWING
    private var selectionVoice: Voice? = null
    private var selectionStart = 0
    private var limiter = 1f
    private var selectionPaused = false

    init {
        pages[0].enabled = true
        for (i in tracks.indices) tracks[i].padEffects = pages[0].padEffects[i]
    }

    @Synchronized fun swedenPageOrder(): Boolean = pages[0].color == PAGE_COLORS[8] && pages[1].color == PAGE_COLORS[2]
    @Synchronized fun transPageOrder(): Boolean = pages[0].color == PAGE_COLORS[7] && pages[1].color == PAGE_COLORS[13]
    @Synchronized fun allPagesMoved(): Boolean = pages.indices.none { pages[it].color == PAGE_COLORS[it] }

    @Synchronized fun selectedPage(): Int = selectedPage
    @Synchronized fun playingPage(): Int = playingPage
    @Synchronized fun page(index: Int): Page { capturePage(); return pages[index] }

    private fun capturePage() {
        val page = pages[selectedPage]
        for (t in 0 until TRACKS) {
            page.steps[t] = tracks[t].steps
            page.trackEffects[t].copyFrom(tracks[t])
        }
    }

    private fun bindPage() {
        val page = pages[selectedPage]
        for (t in 0 until TRACKS) {
            tracks[t].steps = page.steps[t]
            tracks[t].padEffects = page.padEffects[t]
            val mute = tracks[t].mute
            tracks[t].copyFrom(page.trackEffects[t])
            tracks[t].mute = mute
            refreshVoices(t)
        }
    }

    private fun trackControls(pageIndex: Int, track: Int): Effects =
        if (pageIndex == selectedPage || pageIndex < 0) tracks[track] else pages[pageIndex].trackEffects[track]

    private fun trackControls(page: Page, track: Int): Effects =
        if (page === pages[selectedPage]) tracks[track] else page.trackEffects[track]

    @Synchronized fun shareTrackEffects() {
        capturePage()
        for (page in pages) for (t in 0 until TRACKS) page.trackEffects[t].copyFrom(tracks[t])
    }

    @Synchronized fun selectPage(index: Int) {
        capturePage()
        selectedPage = index
        pages[index].enabled = true
        bindPage()
    }

    @Synchronized fun restoreSelectedPage(index: Int) {
        capturePage(); selectedPage = index; bindPage()
    }

    @Synchronized fun replacePage(index: Int, source: Page) {
        capturePage()
        discardPage(pages[index])
        val replacement = source.copy()
        replacement.color = pages[index].color; replacement.enabled = pages[index].enabled
        pages[index] = replacement
        bindPage()
    }

    @Synchronized fun clearPage(index: Int) {
        capturePage()
        val blank = Page(pages[index].color)
        for (t in 0 until TRACKS) blank.trackEffects[t].gain = pages[index].trackEffects[t].gain
        replacePage(index, blank)
    }

    @Synchronized fun swapPages(first: Int, second: Int) {
        capturePage()
        val page = pages[first]; pages[first] = pages[second]; pages[second] = page
        for (row in pageTicks) { val tick = row[first]; row[first] = row[second]; row[second] = tick }
        fun swapped(value: Int) = if (value == first) second else if (value == second) first else value
        selectedPage = swapped(selectedPage)
        playingPage = swapped(playingPage)
        previousPlayingPage = swapped(previousPlayingPage)
        bindPage()
    }

    private fun discardPage(page: Page) {
        for (bus in page.buses.flatten() + page.trackBuses.toList()) {
            if (bus != null) { voices.removeAll { it.bus === bus }; activeBuses.remove(bus) }
        }
    }

    private fun nextEnabledPage(current: Int): Int {
        for (i in 1..PAGE_COUNT) {
            val next = (current + i) % PAGE_COUNT
            if (pages[next].enabled) return next
        }
        return -1
    }

    private fun startPage() {
        capturePage()
        playingPage = if (pages[selectedPage].enabled) selectedPage else nextEnabledPage(selectedPage)
    }

    @Synchronized fun play() {
        stop()
        startPage()
        untilStep = 0.0
        running = true
    }

    @Synchronized fun playSynced() {
        stop()
        startPage()
        syncWaiting = true
    }

    @Synchronized fun isSyncWaiting(): Boolean = syncWaiting

    @Synchronized fun exportCopy(): Sequencer {
        capturePage()
        val result = Sequencer(tape)
        for (i in tracks.indices) {
            result.tracks[i].copyFrom(tracks[i])
            result.tracks[i].start = tracks[i].start; result.tracks[i].end = tracks[i].end
        }
        result.selectedPage = -1
        for (i in pages.indices) {
            result.pages[i] = pages[i].copy()
            if (result.selectedPage < 0 && pages[i].enabled) result.selectedPage = i
        }
        if (result.selectedPage < 0) result.selectedPage = 0
        result.bpm = bpm; result.swing = swing; result.bindPage()
        return result
    }

    @Synchronized fun playPasses(passes: Int): Long {
        play()
        val count = pages.count { it.enabled }
        sequenceTickLimit = count * 16L * passes
        return ceil(count * passes * Tape.RATE * LOOP_SECONDS_AT_1_BPM / bpm).toLong()
    }

    @Synchronized fun passesForAllPads(): Int {
        capturePage()
        var passes = 1
        for (track in tracks.indices) {
            if (tracks[track].mute) continue
            for (page in pages) {
                val base = trackControls(page, track)
                if (!page.enabled || base.gain == 0f) continue
                for (pad in 0 until STEPS) {
                    if ((page.steps[track] and (1 shl pad)) == 0) continue
                    val controls = page.padEffects[track][pad] ?: base
                    if (controls.mute || controls.gain == 0f || controls.chance == 0) continue
                    passes = maxOf(passes, controls.every)
                }
            }
        }
        return passes
    }

    @Synchronized fun hasActiveVoices(): Boolean = voices.isNotEmpty()

    @Synchronized fun stop() {
        stopSelectionPreview()
        running = false
        syncWaiting = false
        clockDriven = false
        syncStart = -1; syncTick = Long.MIN_VALUE
        transportTick = -1; firstTrackTick = 0; previousPlayingPage = -1
        sequenceTickLimit = Long.MAX_VALUE
        for (row in pageTicks) row.fill(Long.MIN_VALUE)
        for (page in pages) for (row in page.passes) row.fill(0)
        limiter = 1f
        step = -1
        voices.clear()
        for (effect in effects) effect.reset()
        for (bus in activeBuses) { bus.effects.reset(); bus.active = false }
        activeBuses.clear()
    }

    @Synchronized fun isPlaying(): Boolean = running
    @Synchronized fun step(): Int = step
    @Synchronized fun bpm(): Int = bpm
    @Synchronized fun swing(): Int = swing
    @Synchronized fun swing(value: Int) { swing = value.coerceIn(MIN_SWING, MAX_SWING) }

    @Synchronized fun tempo(value: Int) {
        val next = value.coerceIn(MIN_BPM, MAX_BPM)
        if (next != bpm) {
            bpm = next
            if (syncWaiting || clockDriven && running) playSynced()
            syncStart = -1
        }
    }

    @Synchronized fun preview(track: Int) = previewStep(track, -1)

    @Synchronized fun previewStep(track: Int, step: Int) {
        if (syncWaiting) stop()
        stopSelectionPreview(); trigger(track, step)
    }

    @Synchronized fun controls(track: Int, step: Int): Effects? = if (step < 0) tracks[track] else tracks[track].padEffects[step]

    @Synchronized fun editStep(track: Int, step: Int): Effects {
        tracks[track].padEffects[step]?.let { return it }
        val result = tracks[track].copy()
        result.neutralGain = 1f
        result.gain = 1f
        result.mute = false
        result.effectPower = result.effectPower and (1 shl Effects.VOLUME).inv()
        result.effectPowerSet = result.effectPowerSet and (1 shl Effects.VOLUME).inv()
        result.custom = 0
        tracks[track].padEffects[step] = result
        return result
    }

    @Synchronized fun inheritTrack(index: Int) {
        val track = tracks[index]
        for (pad in track.padEffects) {
            if (pad == null) continue
            for (group in 0 until Effects.GROUPS) {
                if (!Effects.inherits(group) || pad.custom and (1 shl group) != 0 || pad.sameGroup(group, track)) continue
                pad.restoreGroup(group, track)
            }
            if (pad.custom and Effects.CHAIN_BIT == 0) pad.chain = track.chain
        }
        refreshVoices(index)
    }

    @Synchronized fun resetStep(track: Int, step: Int) {
        tracks[track].padEffects[step] = null
        val bus = pages[selectedPage].buses[track][step]
        voices.removeAll { it.bus != null && it.bus === bus }
        activeBuses.remove(bus)
        pages[selectedPage].buses[track][step] = null
        refreshVoices(track)
    }

    @Synchronized fun setSlice(index: Int, start: Int, end: Int) {
        require(start >= 0 && end <= Tape.LENGTH && end > start) { "Invalid sample slice" }
        tracks[index].start = start; tracks[index].end = end
        refreshVoices(index)
    }

    @Synchronized fun refresh(index: Int) = refreshVoices(index)

    @Synchronized fun refresh(index: Int, step: Int) = refreshVoices(index, controls(index, step))

    @Synchronized fun needsPreparation(index: Int, step: Int): Boolean {
        val controls = controls(index, step) ?: return false
        return needsPreparation(tracks[index], controls)
    }

    @Synchronized fun isPreviewing(): Boolean = selectionVoice != null && !selectionPaused
    @Synchronized fun hasSelectionPreview(): Boolean = selectionVoice != null

    @Synchronized fun stopSelectionPreview() {
        selectionVoice = null
        selectionPaused = false
    }

    @Synchronized fun toggleSelection(samples: ShortArray?, start: Int, end: Int) {
        if (samples == null || start < 0 || end > samples.size || end <= start) return
        val current = selectionVoice
        if (current != null && current.samples === samples && selectionStart == start && current.end == end) {
            selectionPaused = !selectionPaused
            return
        }
        stop()
        selectionStart = start
        selectionVoice = Voice(-1, samples, start, end)
    }

    fun prepare(index: Int, speed: Int, pitch: Int): PreparedSample = prepare(index, -1, speed, pitch)

    fun prepare(index: Int, step: Int, speed: Int, pitch: Int): PreparedSample {
        val start: Int
        val end: Int
        val snapshot: Effects
        synchronized(this) {
            val source = controls(index, step) ?: tracks[index]
            start = sliceStart(tracks[index], source); end = sliceEnd(tracks[index], source)
            snapshot = source.copy()
        }
        return prepare(start, end, speed, pitch, snapshot)
    }

    private fun prepare(start: Int, end: Int, speed: Int, pitch: Int, snapshot: Effects): PreparedSample {
        val source: ShortArray
        val revision: Long
        synchronized(tape) { source = tape.slice(start, end); revision = tape.revision() }
        val transformed = SampleProcessor.process(source, speed, pitch)
        return PreparedSample(PitchCorrector.process(RobotEffect.process(transformed, snapshot), snapshot), start, end, speed, pitch, revision, snapshot)
    }

    @Synchronized fun apply(index: Int, prepared: PreparedSample) = apply(index, -1, prepared)

    @Synchronized fun apply(index: Int, step: Int, prepared: PreparedSample) {
        val track = tracks[index]
        val controls = controls(index, step) ?: return
        check(sliceStart(track, controls) == prepared.start && sliceEnd(track, controls) == prepared.end && tape.revision() == prepared.revision) { "Sample changed while processing" }
        check(prepared.tuningMatches(controls)) { "Effects changed while processing" }
        controls.speed = prepared.speed; controls.pitch = prepared.pitch
        controls.prepared = if (hasTransform(controls)) prepared else null
        refreshVoices(index, controls)
    }

    @Synchronized fun needsPreparation(): Boolean {
        for (t in 0 until TRACKS) {
            if (needsPreparation(tracks[t], tracks[t])) return true
            for (page in pages) {
                if (page !== pages[selectedPage] && needsPreparation(tracks[t], page.trackEffects[t])) return true
            }
            for (page in pages) for (controls in page.padEffects[t]) {
                if (controls != null && needsPreparation(tracks[t], controls)) return true
            }
        }
        return false
    }

    fun prepareAll() {
        for (t in 0 until TRACKS) {
            prepareControls(t, tracks[t])
            for (page in synchronized(this) { pages.filter { it !== pages[selectedPage] } }) prepareControls(t, page.trackEffects[t])
            for (page in pages) for (controls in page.padEffects[t]) if (controls != null) prepareControls(t, controls)
        }
    }

    private fun prepareControls(index: Int, controls: Effects) {
        val start: Int
        val end: Int
        val speed: Int
        val pitch: Int
        val snapshot: Effects
        synchronized(this) {
            if (!needsPreparation(tracks[index], controls)) return
            speed = controls.speed; pitch = controls.pitch
            start = sliceStart(tracks[index], controls); end = sliceEnd(tracks[index], controls); snapshot = controls.copy()
        }
        val prepared = prepare(start, end, speed, pitch, snapshot)
        synchronized(this) {
            if (prepared.matches(tracks[index], controls, tape.revision())) {
                controls.prepared = prepared
                refreshVoices(index, controls)
            }
        }
    }

    private fun needsPreparation(track: Track, controls: Effects): Boolean =
        hasTransform(controls) && controls.prepared.let { it == null || !it.matches(track, controls, tape.revision()) }

    @Synchronized fun resetEffects(index: Int) {
        val track = tracks[index]
        voices.removeAll { it.track == index }
        val defaults = Effects()
        defaults.gain = track.gain; defaults.mute = track.mute
        track.copyFrom(defaults)
        for (page in pages) {
            page.padEffects[index].fill(null); page.buses[index].fill(null); page.trackBuses[index] = null
            page.trackEffects[index].copyFrom(track)
        }
        activeBuses.removeAll { it.track == index }
        effects[index].reset()
    }

    private fun trigger(index: Int, step: Int, pageIndex: Int = selectedPage) {
        val track = tracks[index]
        if (track.end <= track.start || track.start < 0 || track.end > Tape.LENGTH) return
        val page = pages[pageIndex]
        val base = trackControls(pageIndex, index)
        val controls: Effects = (if (step >= 0) page.padEffects[index][step] else null) ?: base
        var bus: StepBus? = null
        if (controls !== track) {
            bus = if (controls === base) page.trackBuses[index] ?: StepBus(index, -1, controls, page).also { page.trackBuses[index] = it }
            else page.buses[index][step] ?: StepBus(index, step, controls, page).also { page.buses[index][step] = it }
            bus.silentFrames = 0
            if (!bus.active) {
                bus.processing = bus.effects.prepareBlock(controls)
                bus.active = true; activeBuses.add(bus)
            }
        }
        val voice = Voice(index, step, tape.samples, track.start, track.end)
        voice.bus = bus
        assign(voice, controls, 0.0)
        voices.removeAll { it.track == index }
        voices.add(voice)
    }

    private fun assign(voice: Voice, controls: Effects, progress: Double) {
        val track = tracks[voice.track]
        val previousSamples = voice.samples
        val previousStart = voice.start
        val previousEnd = voice.end
        val prepared = controls.prepared
        if (hasTransform(controls) && prepared != null && prepared.revision == tape.revision() && prepared.start == sliceStart(track, controls) && prepared.end == sliceEnd(track, controls)) {
            voice.samples = prepared.samples; voice.start = 0; voice.end = prepared.samples.size
        } else {
            voice.samples = tape.samples; voice.start = sliceStart(track, controls); voice.end = sliceEnd(track, controls)
        }
        voice.position = minOf(voice.end, voice.start + Math.round(progress * (voice.end - voice.start)).toInt())
        if (voice.samples !== previousSamples || voice.start != previousStart || voice.end != previousEnd) voice.age = 0
    }

    private fun refreshVoices(index: Int, target: Effects? = null) {
        for (voice in voices) {
            if (voice.track != index) continue
            val controls = voice.bus?.controls ?: tracks[index]
            if (target != null && controls !== target) continue
            assign(voice, controls, (voice.position - voice.start) / maxOf(1, voice.end - voice.start).toDouble())
        }
    }

    @Synchronized fun render(output: ShortArray) = render(output, false, 0, 0.0, false)

    @Synchronized fun renderSynced(output: ShortArray, firstSyncNanos: Long, nanosPerFrame: Double, ready: Boolean) =
        render(output, true, firstSyncNanos, nanosPerFrame, ready)

    private fun render(output: ShortArray, synced: Boolean, firstSyncNanos: Long, nanosPerFrame: Double, ready: Boolean) {
        synchronized(tape) {
            if ((syncWaiting || running) && synced) {
                if (!ready || !nanosPerFrame.isFinite() || nanosPerFrame <= 0) {
                    if (running) playSynced()
                    output.fill(0); return
                }
                clockDriven = true
                if (syncWaiting && syncStart < 0) syncStart = ClockSync.nextLoopBoundaryNanos(maxOf(0, firstSyncNanos), bpm)
            }
            if (syncWaiting && !synced) { output.fill(0); return }
            val selection = selectionVoice
            if (selection != null) {
                output.fill(0)
                if (selectionPaused) return
                val count = minOf(output.size, selection.end - selection.position)
                for (i in 0 until count) {
                    val envelope = minOf(1f, selection.age / DECLICK_FRAMES, (selection.end - selection.position) / DECLICK_FRAMES)
                    output[i] = Math.round(selection.samples[selection.position++] * envelope).toShort()
                    selection.age++
                }
                if (selection.position >= selection.end) stopSelectionPreview()
                return
            }
            for (track in tracks.indices) processing[track] = effects[track].prepareBlock(tracks[track])
            for (index in activeBuses.indices) {
                val bus = activeBuses[index]
                bus.processing = bus.effects.prepareBlock(bus.controls)
            }
            for (i in output.indices) {
                var tick = 0L
                if (clockDriven) {
                    val time = firstSyncNanos + Math.round(i * nanosPerFrame)
                    tick = ClockSync.stepAtNanos(time, bpm, swing)
                    if (syncWaiting && time >= syncStart) {
                        syncWaiting = false; running = true; syncTick = tick - 1
                        firstTrackTick = tick
                    }
                }
                if (running && (if (clockDriven) tick > syncTick else untilStep <= 0)) {
                    val previousStep = step
                    step = if (clockDriven) Math.floorMod(tick, STEPS.toLong()).toInt() else (step + 1) % STEPS
                    transportTick = if (clockDriven) tick else transportTick + 1
                    capturePage()
                    if (previousStep >= 0 && (if (clockDriven) Math.floorDiv(tick, STEPS.toLong()) > Math.floorDiv(syncTick, STEPS.toLong()) else step == 0)) {
                        previousPlayingPage = playingPage
                        playingPage = nextEnabledPage(playingPage)
                    }
                    if (playingPage >= 0 && !pages[playingPage].enabled) playingPage = nextEnabledPage(playingPage)
                    if (clockDriven) syncTick = tick
                    else untilStep += Tape.RATE * PAIR_SECONDS_AT_1_BPM / bpm * (if (step % 2 == 0) swing else 100 - swing) / 100
                }
                if (running) scheduleTracks(if (clockDriven) firstSyncNanos + Math.round(i * nanosPerFrame) else 0)
                mix.fill(0f)
                for (index in activeBuses.indices) activeBuses[index].mix = 0f
                for (index in voices.size - 1 downTo 0) {
                    val v = voices[index]
                    if (v.position >= v.end) {
                        voices.removeAt(index)
                        continue
                    }
                    val bus = v.bus
                    val controls = bus?.controls ?: tracks[v.track]
                    val envelope = TrackEffects.envelope(v.age, v.end - v.position, controls)
                    val sample = (if (controls.wobbles()) wobbled(v, controls) else v.samples[v.position++].toFloat()) * envelope / Tape.PCM_SCALE
                    if (bus == null) mix[v.track] += sample
                    else bus.mix += sample
                    v.age++
                }
                var sum = 0f
                for (track in tracks.indices) {
                    val controls = tracks[track]
                    val value = if (processing[track]) effects[track].process(mix[track], controls, bpm) else mix[track]
                    if (!controls.mute) sum += value * controls.gain
                }
                for (index in activeBuses.indices) {
                    val bus = activeBuses[index]
                    val track = tracks[bus.track]
                    val controls = bus.controls
                    val value = if (bus.processing) bus.effects.process(bus.mix, controls, bpm) else bus.mix
                    bus.silentFrames = if (bus.mix == 0f && abs(value) < SILENCE_LEVEL) bus.silentFrames + 1 else 0
                    if (track.mute) continue
                    if (bus.step < 0) sum += value * controls.gain
                    else if (!controls.mute) sum += value * controls.gain * trackControls(bus.owner, bus.track).gain
                }
                output[i] = Math.round(limited(sum) * Tape.PCM_SCALE).toShort()
                if (running && !clockDriven) untilStep--
            }
            activeBuses.removeAll { bus ->
                if (bus.silentFrames < Tape.RATE * BUS_IDLE_SECONDS) false
                else { bus.effects.reset(); bus.active = false; true }
            }
        }
    }

    private fun limited(sample: Float): Float {
        val level = abs(sample)
        if (level * limiter > LIMIT_CEILING) limiter = LIMIT_CEILING / level
        val result = sample * limiter
        limiter += (1 - limiter) * LIMIT_RELEASE
        return result
    }

    private fun conditionsPass(page: Page, index: Int, pad: Int, tick: Long): Boolean {
        val controls = page.padEffects[index][pad] ?: trackControls(page, index)
        val pass = ++page.passes[index][pad]
        return pass % maxOf(1, controls.every) == 0 && rolled(tick, index, pad, controls.chance)
    }

    private fun timingTick(timing: Effects, time: Long, elapsed: Double, pairLength: Double): Long {
        val groove = if (timing.swingOverride == 0) swing else timing.swingOverride
        return when {
            timing.nudge == 0 && groove == swing -> transportTick
            clockDriven -> ClockSync.stepAtNanos(time - timing.nudge * 1_000_000L, bpm, groove)
            else -> {
                val position = elapsed - timing.nudge * (Tape.RATE / MS_PER_SECOND)
                val pair = floor(position / pairLength).toLong()
                transportTick / 2 * 2 + pair * 2 + if (position - pair * pairLength >= pairLength * groove / 100) 1 else 0
            }
        }
    }

    private fun scheduleTracks(time: Long) {
        val pairLength = Tape.RATE * PAIR_SECONDS_AT_1_BPM / bpm
        val elapsed = pairLength * (if (step % 2 == 0) swing / 100.0 else 1.0) - untilStep
        val currentLoop = Math.floorDiv(transportTick, STEPS.toLong())
        val nextPage = nextEnabledPage(playingPage)
        for (index in tracks.indices) {
            val current = if (playingPage >= 0) trackControls(playingPage, index) else tracks[index]
            val currentTick = timingTick(current, time, elapsed, pairLength)
            for (role in 0 until 3) {
                val page = when (role) { 0 -> previousPlayingPage; 1 -> playingPage; else -> nextPage }
                if (page < 0) continue
                val timing = trackControls(page, index)
                val tick = if (timing.nudge == current.nudge && timing.swingOverride == current.swingOverride) currentTick else timingTick(timing, time, elapsed, pairLength)
                if (Math.floorDiv(tick, STEPS.toLong()) != currentLoop + role - 1) continue
                if (tick < firstTrackTick || tick >= sequenceTickLimit || tick <= pageTicks[index][page]) continue
                pageTicks[index][page] = tick
                val pad = Math.floorMod(tick, STEPS.toLong()).toInt()
                if (pages[page].enabled && (pages[page].steps[index] and (1 shl pad)) != 0 && conditionsPass(pages[page], index, pad, tick) && !tracks[index].mute) trigger(index, pad, page)
            }
        }
    }

    companion object {
        const val MIN_SWING = 10
        const val MAX_SWING = 90
        const val PAGE_COUNT = 16
        const val TRACKS = 8
        const val STEPS = 16
        const val MIN_BPM = 40
        const val MAX_BPM = 240
        const val DEFAULT_BPM = 120
        const val DEFAULT_SLICE_FRAMES = Tape.RATE / 4
        const val DEFAULT_SWING = 50
        private const val DECLICK_FRAMES = 48f
        private const val MIN_SLICE_FRAMES = 48
        private const val FRAMES_PER_MS = Tape.RATE / 1000
        private const val MS_PER_SECOND = 1000.0
        private const val PAIR_SECONDS_AT_1_BPM = 30.0
        private const val LOOP_SECONDS_AT_1_BPM = 240.0
        private const val BUS_IDLE_SECONDS = 5
        private const val SILENCE_LEVEL = 0.00001f
        private const val WOBBLE_RATE_STEPS_PER_HZ = 10.0
        private const val MAX_READ_OFFSET = 1.001
        private const val WOBBLE_PITCH_SWING = 0.1225
        private const val WOBBLE_SPEED_SWING = 0.5
        private const val LIMIT_CEILING = 0.95f
        private const val LIMIT_RELEASE = 1f / (Tape.RATE / 8)

        private fun colors(vararg values: Long) = IntArray(values.size) { values[it].toInt() }

        private val PAGE_COLORS = colors(0xffeb5231, 0xffff8a1f, 0xffffd93d, 0xffa8c644, 0xff48c95a, 0xff38c984, 0xff19c9a3, 0xff3cc4de, 0xff2fa8f0, 0xff4d7cf0, 0xff6462e8, 0xffab55ea, 0xffd77be0, 0xfff05fb5, 0xffff6b81, 0xffef3546)
        private val OLD_PAGE_COLORS = colors(0xffeb5231, 0xffe8b544, 0xff62b778, 0xff51b7c9, 0xff648bdb, 0xff9d7cda, 0xffd678b3, 0xffcc8850, 0xffa5bd50, 0xff4da98f, 0xff5799b9, 0xff8484ca, 0xffb7778a, 0xffd49a79, 0xff8cab85, 0xff9ca7bd)

        @JvmStatic
        fun updatedPageColor(saved: Int): Int {
            val index = OLD_PAGE_COLORS.indexOf(saved)
            return if (index >= 0) PAGE_COLORS[index] else saved
        }

        @JvmStatic
        internal fun sliceStart(track: Track, controls: Effects): Int {
            var start = track.start
            if (controls.powered(Effects.SHIFT)) start += controls.trimStart * FRAMES_PER_MS
            return start.coerceIn(0, Tape.LENGTH - MIN_SLICE_FRAMES)
        }

        @JvmStatic
        internal fun sliceEnd(track: Track, controls: Effects): Int {
            var end = track.end
            if (controls.powered(Effects.SHIFT)) end += controls.trimEnd * FRAMES_PER_MS
            return maxOf(sliceStart(track, controls) + MIN_SLICE_FRAMES, minOf(Tape.LENGTH, end))
        }

        private fun hasTransform(controls: Effects): Boolean = controls.speed != 100 || controls.pitch != 0 || controls.hasAutotune() || controls.hasRobot()

        private fun wobbled(v: Voice, controls: Effects): Float {
            val hertz = controls.wobbleRate / WOBBLE_RATE_STEPS_PER_HZ
            val depth = controls.wobbleDepth / 100.0
            val angle = 2 * PI * hertz * v.age / Tape.RATE
            if (controls.wobbleMode == Effects.WOBBLE_PITCH) {
                val swing = depth * WOBBLE_PITCH_SWING * Tape.RATE / (2 * PI * hertz)
                return sampleAt(v, v.position++ + swing * (1 - cos(angle)))
            }
            if (controls.wobbleMode == Effects.WOBBLE_SPEED) {
                val sample = sampleAt(v, v.position + v.fraction)
                v.fraction += 1 + depth * WOBBLE_SPEED_SWING * sin(angle)
                val whole = v.fraction.toInt()
                v.position += whole
                v.fraction -= whole
                return sample
            }
            return v.samples[v.position++] * (1 - depth * (0.5 - 0.5 * cos(angle))).toFloat()
        }

        private fun sampleAt(v: Voice, read: Double): Float {
            val clamped = maxOf(v.start.toDouble(), minOf(v.end - MAX_READ_OFFSET, read))
            val index = clamped.toInt()
            val blend = (clamped - index).toFloat()
            return v.samples[index] + (v.samples[minOf(index + 1, v.samples.size - 1)] - v.samples[index]) * blend
        }

        private fun rolled(tick: Long, index: Int, pad: Int, chance: Int): Boolean {
            if (chance >= 100) return true
            if (chance <= 0) return false
            var hash = (tick * 31 + index) * 31 + pad + 1
            hash = hash xor (hash ushr 33); hash *= 0xff51afd7ed558ccdUL.toLong()
            hash = hash xor (hash ushr 33); hash *= 0xc4ceb9fe1a85ec53UL.toLong()
            hash = hash xor (hash ushr 33)
            return java.lang.Long.remainderUnsigned(hash, 100L) < chance
        }
    }
}
