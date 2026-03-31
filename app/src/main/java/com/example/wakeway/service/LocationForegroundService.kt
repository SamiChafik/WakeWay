package com.example.wakeway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wakeway.MainActivity
import com.example.wakeway.WakeUpActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationForegroundService : Service() {

    companion object {
        const val TAG = "WakeWayService"
        const val CHANNEL_ID = "wakeway_trip_channel"
        const val ALARM_CHANNEL_ID = "wakeway_alarm_channel_v3"
        const val NOTIFICATION_ID = 1001
        const val ALARM_NOTIFICATION_ID = 1002
        const val ACTION_TRIGGER_ALARM = "com.example.wakeway.ACTION_TRIGGER_ALARM"
        const val ACTION_DISMISS_ALARM = "com.example.wakeway.ACTION_DISMISS_ALARM"
        const val ACTION_TRIP_ENDED = "com.example.wakeway.ACTION_TRIP_ENDED"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isAlarmPlaying = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_ALARM -> {
                Log.i(TAG, "ACTION_TRIGGER_ALARM received — starting alarm!")
                triggerAlarm()
            }
            ACTION_DISMISS_ALARM -> {
                Log.i(TAG, "ACTION_DISMISS_ALARM received — stopping alarm.")
                dismissAlarm()
            }
            else -> {
                val lat = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
                val lon = intent?.getDoubleExtra("lon", 0.0) ?: 0.0
                val radius = intent?.getFloatExtra("radius", 5000f) ?: 5000f

                val notification = buildMonitoringNotification(lat, lon, radius)
                startForeground(NOTIFICATION_ID, notification)
                
                // Keep the GPS awake so geofences trigger reliably in the background
                requestContinuousLocation()
            }
        }

        return START_STICKY
    }

    private fun buildMonitoringNotification(lat: Double, lon: Double, radius: Float): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val radiusKm = "%.1f".format(radius / 1000f)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌙 WakeWay is monitoring your trip")
            .setContentText("Alert radius: ${radiusKm}km • Destination: %.4f, %.4f".format(lat, lon))
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun requestContinuousLocation() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    // Do nothing - just keeping the GPS hardware active
                    // guarantees the GeofencingClient works instantly in the background.
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permissions to request continuous updates: ${e.message}")
        }
    }

    // ─── ALARM LOGIC (runs IN the service — guaranteed to work from background) ──

    /**
     * The alarm is managed entirely inside this foreground service.
     * This bypasses Android 12+ restrictions on starting activities from the background,
     * because foreground services ARE allowed to play audio and vibrate.
     *
     * The full-screen notification will launch WakeUpActivity when the user interacts.
     */
    private fun triggerAlarm() {
        if (isAlarmPlaying) return
        isAlarmPlaying = true

        // Notify MainActivity to change the button back to "Start Trip"
        sendBroadcast(Intent(ACTION_TRIP_ENDED))

        val prefs = getSharedPreferences("wakeway_prefs", Context.MODE_PRIVATE)
        val isSoundEnabled = prefs.getBoolean("enable_sound", true)
        val isVibrationEnabled = prefs.getBoolean("enable_vibration", true)

        // 1. Play alarm sound IN the service (if enabled)
        if (isSoundEnabled) {
            setUserAlarmVolume()
            startAlarmSound()
        }

        // 2. Start vibration IN the service (if enabled)
        if (isVibrationEnabled) {
            startVibration()
        }

        // 4. Show full-screen notification — this will launch WakeUpActivity
        //    when the screen is OFF (fullScreenIntent) or let the user tap it when ON.
        showAlarmNotification()

        // 5. Also try to launch the activity directly (works on some devices)
        try {
            val activityIntent = Intent(this, WakeUpActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start WakeUpActivity directly: ${e.message}")
            // That's okay — the full-screen notification handles it
        }
    }

    private fun setUserAlarmVolume() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Force ringer mode to normal so alarm stream plays even on mute
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        // Read the user's chosen volume from SharedPreferences
        val prefs = getSharedPreferences("wakeway_prefs", Context.MODE_PRIVATE)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val userVolume = prefs.getInt("alarm_volume", maxVolume)

        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            userVolume.coerceIn(1, maxVolume), // at least 1 so it's audible
            0
        )
    }

    private fun startAlarmSound() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@LocationForegroundService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound: ${e.message}")
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val pattern = longArrayOf(0, 800, 200, 800, 200, 400, 200, 400)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun showAlarmNotification() {
        val fullScreenIntent = Intent(this, WakeUpActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 1, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action on the notification itself
        val dismissIntent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("⏰ WAKE UP!")
            .setContentText("You've arrived at your destination!")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_delete, "Dismiss", dismissPendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    // ─── DISMISS ──────────────────────────────────────────────────────────────────

    private fun dismissAlarm() {
        // Stop sound
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Stop vibration
        vibrator?.cancel()
        vibrator = null

        isAlarmPlaying = false

        // Remove geofence
        val geofencingClient = LocationServices.getGeofencingClient(this)
        geofencingClient.removeGeofences(listOf("wakeway_destination"))

        // Clear alarm notification
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(ALARM_NOTIFICATION_ID)

        // Stop the entire service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── CHANNELS ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_ID,
                "WakeWay Trip Monitor",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while WakeWay monitors your trip"
                setShowBadge(false)
            }

            // High-priority channel (must allow default alert behaviors to prevent OS from downgrading it)
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "WakeWay Alarm",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Full-screen alarm when you reach your destination"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(monitorChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(ALARM_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
