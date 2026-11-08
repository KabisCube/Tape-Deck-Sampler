package com.tapedecksampler.app

import android.content.SharedPreferences
import java.util.Random

class BatteryBay(private val settings: SharedPreferences) {
    private val random = Random()
    @JvmField val roll = FloatArray(SLOTS) { randomRoll() }
    @JvmField val installed = BooleanArray(SLOTS)
    @JvmField val brand = IntArray(SLOTS)
    @JvmField val charge = FloatArray(SLOTS)
    @JvmField val changedAt = LongArray(SLOTS) { Long.MIN_VALUE / 2 }
    @JvmField val leavingBrand = IntArray(SLOTS) { -1 }
    @JvmField val fresh = BooleanArray(SLOTS)
    @JvmField var lidOpen = false
    @JvmField var lidChangedAt = Long.MIN_VALUE / 2
    @JvmField var found = false
    @JvmField var useBatteries = true
    @JvmField var switches = ALL_SWITCHES
    private var replaceBrand = -1

    init {
        val mask = settings.getInt("batteryInstalled", (1 shl SLOTS) - 1)
        val brands = settings.getString("batteryBrands", "")!!.split(",")
        for (i in 0 until SLOTS) {
            installed[i] = mask and (1 shl i) != 0
            brand[i] = brands.getOrNull(i)?.toIntOrNull()?.coerceIn(0, BRAND_NAMES.size - 1) ?: 0
            charge[i] = settings.getFloat("batteryCharge$i", 1f).coerceIn(0f, 1f)
        }
        lidOpen = settings.getBoolean("batteryLidOpen", false)
        found = settings.getBoolean("batteryFound", false)
        useBatteries = !settings.getBoolean("noBatteries", false)
        switches = settings.getInt("caseSwitches", ALL_SWITCHES) and ALL_SWITCHES
        replaceBrand = settings.getInt("batteryReplaceBrand", -1)
    }

    fun full() = installed.all { it }
    fun powered() = full() && (!useBatteries || charge.all { it > 0f })
    fun level() = charge.minOrNull() ?: 0f
    fun remainingMillis() = (level() * LIFE_MILLIS).toLong()
    fun low() = remainingMillis() <= LOW_MILLIS

    fun openLid(now: Long) { lidOpen = true; lidChangedAt = now; found = true; save() }

    fun closeLid(now: Long): Boolean {
        if (!full()) return false
        lidOpen = false; lidChangedAt = now; save()
        return true
    }

    private fun randomRoll() = random.nextFloat() * 360 - 180

    fun kick(slot: Int, now: Long) {
        installed[slot] = false; changedAt[slot] = now; roll[slot] = randomRoll()
        if (useBatteries && charge[slot] <= SWAP_LEVEL) {
            leavingBrand[slot] = brand[slot]
            if (replaceBrand < 0 || brand.all { it == replaceBrand }) replaceBrand = freshBrand()
            brand[slot] = replaceBrand; charge[slot] = 1f; fresh[slot] = true
        } else { leavingBrand[slot] = -1; fresh[slot] = false }
        save()
    }

    fun insert(slot: Int, now: Long) { installed[slot] = true; changedAt[slot] = now; leavingBrand[slot] = -1; roll[slot] = randomRoll(); save() }

    fun toggleSwitch(index: Int) { switches = switches xor (1 shl index); save() }

    fun drain(millis: Long): Boolean {
        if (!useBatteries || !powered() || millis <= 0) return false
        for (i in 0 until SLOTS) charge[i] = (charge[i] - millis.toFloat() / LIFE_MILLIS).coerceAtLeast(0f)
        return !powered()
    }

    private fun freshBrand(): Int {
        val unused = BRAND_NAMES.indices.filter { it !in brand && it != replaceBrand }
        val pool = unused.ifEmpty { BRAND_NAMES.indices.filter { it != replaceBrand } }
        return pool[random.nextInt(pool.size)]
    }

    fun save() {
        var mask = 0
        for (i in 0 until SLOTS) if (installed[i]) mask = mask or (1 shl i)
        val editor = settings.edit().putInt("batteryInstalled", mask).putString("batteryBrands", brand.joinToString(","))
            .putBoolean("batteryLidOpen", lidOpen).putBoolean("batteryFound", found).putBoolean("noBatteries", !useBatteries)
            .putInt("caseSwitches", switches).putInt("batteryReplaceBrand", replaceBrand)
        for (i in 0 until SLOTS) editor.putFloat("batteryCharge$i", charge[i])
        editor.apply()
    }

    companion object {
        const val SLOTS = 4
        const val SWITCHES = 3
        const val ALL_SWITCHES = 7
        const val LIFE_MILLIS = 5 * 3_600_000L
        const val LOW_MILLIS = 15 * 60_000L
        const val SWAP_LEVEL = 0.5f
        @JvmField val BRAND_NAMES = arrayOf("TAPECELL", "CLAPTRON", "LOOPJUICE", "HISS+", "REWINDER", "WOW&FLUTTER", "B-SIDE", "SPOOLBOY", "PADPOWER", "KABISCELL", "DOUBLETIME", "VOLTOWL")
    }
}
