package com.stepstracker.android.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class IntervalMathTest {
    @Test fun alignsToUtcQuarterHour() = assertEquals(1_735_689_600L, IntervalMath.alignedEpochSeconds(Instant.parse("2025-01-01T00:07:59Z")))
    @Test fun resetDoesNotCreateFalseSteps() = assertEquals(0, IntervalMath.sensorDelta(45_000, 12, true))
    @Test fun regularSensorDeltaIsCounted() = assertEquals(125, IntervalMath.sensorDelta(1_000, 1_125, false))
    @Test fun intervalIdIsStable() = assertEquals(IntervalMath.stableId("device","STEP_COUNTER",900),IntervalMath.stableId("device","STEP_COUNTER",900))
    @Test fun firstReadingSeedsTodayWhenBootedToday() = assertEquals(1802, IntervalMath.stepCounterDelta(null, 1802, reset=false, bootedToday=true))
    @Test fun firstReadingIgnoresCumulativeWhenBootedEarlier() = assertEquals(0, IntervalMath.stepCounterDelta(null, 50_000, reset=false, bootedToday=false))
    @Test fun rebootTodayCountsStepsSinceBoot() = assertEquals(120, IntervalMath.stepCounterDelta(45_000, 120, reset=true, bootedToday=true))
    @Test fun consecutiveReadingsEmitDelta() = assertEquals(8, IntervalMath.stepCounterDelta(1_802, 1_810, reset=false, bootedToday=true))
}
