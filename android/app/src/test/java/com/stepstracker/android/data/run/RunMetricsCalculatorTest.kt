package com.stepstracker.android.data.run

import org.junit.Assert.*
import org.junit.Test

class RunMetricsCalculatorTest {
    private fun point(at:Long,lat:Double=46.0,lon:Double=8.0,accuracy:Float=5f)=RunPointEntity("run",0,at,lat,lon,null,accuracy)
    @Test fun knownDistance(){assertEquals(111.2,RunMetricsCalculator.distanceMeters(0.0,0.0,0.001,0.0),0.5)}
    @Test fun rejectsInaccurateAndImpossibleSamples(){assertFalse(RunMetricsCalculator.accept(null,1,0.0,0.0,51f).first);assertFalse(RunMetricsCalculator.accept(point(1000),2000,1.0,1.0,5f).first)}
    @Test fun rejectsNoise(){assertFalse(RunMetricsCalculator.accept(point(1000),3000,46.000001,8.0,10f).first)}
    @Test fun derivesSpeedAndPace(){assertEquals(2.0,RunMetricsCalculator.averageSpeed(1000.0,500000),0.001);assertEquals(500.0,RunMetricsCalculator.pace(1000.0,500000)!!,0.001);assertNull(RunMetricsCalculator.pace(49.0,10000))}
}
