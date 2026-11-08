package com.tapedecksampler.app

class ClapClock(private val origin: Long) {
    fun at(time: Long): Long = time - origin
}
