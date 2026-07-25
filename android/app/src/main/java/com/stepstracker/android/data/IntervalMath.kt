package com.stepstracker.android.data

import java.time.Instant
import java.util.UUID

object IntervalMath {
    fun alignedEpochSeconds(instant:Instant):Long=instant.epochSecond/900*900
    fun stableId(deviceId:String,source:String,startEpochSeconds:Long):String=UUID.nameUUIDFromBytes("$deviceId:$source:$startEpochSeconds".toByteArray()).toString()
    fun sensorDelta(previous:Long,current:Long,bootReset:Boolean):Int=if(bootReset||current<previous)0 else (current-previous).coerceIn(0,10000).toInt()

    // TYPE_STEP_COUNTER reports the cumulative step count since boot. On the first reading (previous==null) or
    // after a reboot the counter has no usable baseline; if the device also booted today, the whole count is
    // today's steps, so we seed with it instead of discarding it (which previously made the app show only the
    // steps taken while it was in the foreground). Otherwise we emit the delta between consecutive readings.
    fun stepCounterDelta(previous:Long?,current:Long,reset:Boolean,bootedToday:Boolean):Int=when {
        current<0 -> 0
        previous==null||reset -> if(bootedToday) current.coerceIn(0,200000).toInt() else 0
        else -> (current-previous).coerceIn(0,10000).toInt()
    }
}

