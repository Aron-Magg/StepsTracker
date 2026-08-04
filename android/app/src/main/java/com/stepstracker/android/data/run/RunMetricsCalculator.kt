package com.stepstracker.android.data.run

import kotlin.math.*

object RunMetricsCalculator {
    fun distanceMeters(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double { val earth=6371000.0;val p1=Math.toRadians(aLat);val p2=Math.toRadians(bLat);val dp=Math.toRadians(bLat-aLat);val dl=Math.toRadians(bLon-aLon);val x=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return earth*2*atan2(sqrt(x),sqrt(1-x)) }
    fun accept(previous:RunPointEntity?,recordedAt:Long,lat:Double,lon:Double,accuracy:Float):Pair<Boolean,Double> {
        if(accuracy !in 0f..50f || previous!=null&&recordedAt<=previous.recordedAt)return false to 0.0
        if(previous==null)return true to 0.0
        val distance=distanceMeters(previous.latitude,previous.longitude,lat,lon);val seconds=(recordedAt-previous.recordedAt)/1000.0
        if(seconds<=0||distance/seconds>12)return false to 0.0
        val noise=(previous.accuracyMeters+accuracy)*0.35
        return (distance>=noise) to if(distance>=noise)distance else 0.0
    }
    fun averageSpeed(distance:Double,activeMillis:Long)=if(activeMillis>0)distance/(activeMillis/1000.0) else 0.0
    fun pace(distance:Double,activeMillis:Long)=if(distance>=50)activeMillis/1000.0/(distance/1000.0) else null
}
