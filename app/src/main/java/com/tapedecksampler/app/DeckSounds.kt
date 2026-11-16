package com.tapedecksampler.app

import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

class DeckSounds(assets: AssetManager) {
	enum class Cue(val file: String, val takes: Int) {
		HATCH_OPEN("hatch_open", 2),
		HATCH_CLOSE("hatch_close", 3),
		CASSETTE_EJECT("cassette_eject", 5),
		CASSETTE_INSERT("cassette_insert", 2)
	}

	private val pool = SoundPool.Builder()
		.setMaxStreams(MAX_STREAMS)
		.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
		.build()
	private val sounds = Cue.values().map { cue -> IntArray(cue.takes) { take -> assets.openFd("sounds/${cue.file}_$take.wav").use { pool.load(it, 1) } } }
	private val lastTake = IntArray(Cue.values().size) { -1 }
	var volumePercent = DEFAULT_VOLUME

	fun play(cue: Cue) {
		if (volumePercent <= 0) return
		val takes = sounds[cue.ordinal]
		var take = Random.nextInt(takes.size)
		if (takes.size > 1 && take == lastTake[cue.ordinal]) take = (take + 1) % takes.size
		lastTake[cue.ordinal] = take
		val volume = volumePercent / 100f
		pool.play(takes[take], volume, volume, 1, 0, 1f)
	}

	fun release() = pool.release()

	companion object {
		const val DEFAULT_VOLUME = 100
		private const val MAX_STREAMS = 4
	}
}
