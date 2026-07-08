package com.stepstracker.android.data

import java.time.Instant
import java.util.UUID

object IntervalMath {
    fun alignedEpochSeconds(instant:Instant):Long=instant.epochSecond/900*900
    fun stableId(deviceId:String,source:String,startEpochSeconds:Long):String=UUID.nameUUIDFromBytes("$deviceId:$source:$startEpochSeconds".toByteArray()).toString()
    fun sensorDelta(previous:Long,current:Long,bootReset:Boolean):Int=if(bootReset||current<previous)0 else (current-previous).coerceIn(0,10000).toInt()
}

