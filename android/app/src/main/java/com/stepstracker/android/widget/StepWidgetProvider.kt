package com.stepstracker.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.stepstracker.android.MainActivity
import com.stepstracker.android.R
import com.stepstracker.android.StepsTrackerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class StepWidgetProvider:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone=ZoneId.systemDefault();val day=LocalDate.now(zone)
                val from=day.atStartOfDay(zone).toInstant().toEpochMilli();val to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val steps=(context.applicationContext as StepsTrackerApp).database.intervals().total(from,to)
                ids.forEach { id->
                    val views=RemoteViews(context.packageName,R.layout.step_widget).apply {
                        setTextViewText(R.id.widget_steps,steps.toString())
                        setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                    manager.updateAppWidget(id,views)
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        fun requestUpdate(context:Context) {
            val manager=AppWidgetManager.getInstance(context)
            val component=ComponentName(context,StepWidgetProvider::class.java)
            val ids=manager.getAppWidgetIds(component)
            if(ids.isNotEmpty())context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS,ids))
        }
    }
}

