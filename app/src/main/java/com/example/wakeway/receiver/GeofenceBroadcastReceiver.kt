package com.example.wakeway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.wakeway.service.LocationForegroundService
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error code: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.i(TAG, "GEOFENCE_TRANSITION_ENTER detected — triggering alarm via service!")

            // Instead of launching an activity directly (blocked on Android 10+),
            // tell the foreground service to fire the alarm notification.
            val serviceIntent = Intent(context, LocationForegroundService::class.java).apply {
                action = LocationForegroundService.ACTION_TRIGGER_ALARM
            }
            context.startService(serviceIntent)
        }
    }
}
