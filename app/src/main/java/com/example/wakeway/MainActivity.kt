package com.example.wakeway

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wakeway.receiver.GeofenceBroadcastReceiver
import com.example.wakeway.service.LocationForegroundService
import com.example.wakeway.ui.theme.*
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var geofencingClient: GeofencingClient

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                Toast.makeText(this, "Location permission is required for WakeWay", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid configuration
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        geofencingClient = LocationServices.getGeofencingClient(this)

        requestPermissions()

        setContent {
            WakeWayTheme {
                WakeWayScreen(
                    onStartTrip = { geoPoint, radiusMeters ->
                        registerGeofence(geoPoint, radiusMeters)
                        startForegroundService(geoPoint, radiusMeters)
                    },
                    onStopTrip = {
                        stopTrip()
                    }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }
    }

    private fun registerGeofence(geoPoint: GeoPoint, radiusMeters: Float) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId("wakeway_destination")
            .setCircularRegion(geoPoint.latitude, geoPoint.longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Toast.makeText(this, "Geofence set! Safe travels 🌙", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to set geofence: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun startForegroundService(geoPoint: GeoPoint, radiusMeters: Float) {
        // Request background location before starting service
        requestBackgroundLocation()

        val intent = Intent(this, LocationForegroundService::class.java).apply {
            putExtra("lat", geoPoint.latitude)
            putExtra("lon", geoPoint.longitude)
            putExtra("radius", radiusMeters)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTrip() {
        // Remove geofence
        geofencingClient.removeGeofences(listOf("wakeway_destination"))
        // Stop foreground service
        val intent = Intent(this, LocationForegroundService::class.java)
        stopService(intent)
        Toast.makeText(this, "Trip cancelled", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
    }
}

// ─── Compose UI ───────────────────────────────────────────────────────────────

@Composable
fun WakeWayScreen(
    onStartTrip: (GeoPoint, Float) -> Unit,
    onStopTrip: () -> Unit,
) {
    val context = LocalContext.current

    var destinationPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var radiusKm by remember { mutableFloatStateOf(5f) }
    var isTripActive by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    var radiusCircle by remember { mutableStateOf<Polygon?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Full-screen osmdroid Map ──
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(6.0)
                    controller.setCenter(GeoPoint(36.75, 3.06)) // Default to Algiers

                    // Long-press to drop pin
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null && !isTripActive) {
                                destinationPoint = p

                                // Remove old marker and circle
                                destinationMarker?.let { overlays.remove(it) }
                                radiusCircle?.let { overlays.remove(it) }

                                // New marker
                                val marker = Marker(this@apply).apply {
                                    position = p
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title = "Destination"
                                }
                                overlays.add(marker)
                                destinationMarker = marker

                                // Radius circle
                                val circle = Polygon(this@apply).apply {
                                    points = Polygon.pointsAsCircle(p, radiusKm.toDouble() * 1000)
                                    fillPaint.color = 0x264E6AFF // semi-transparent accent
                                    outlinePaint.color = 0xFF4E6AFF.toInt()
                                    outlinePaint.strokeWidth = 3f
                                }
                                overlays.add(circle)
                                radiusCircle = circle

                                invalidate()
                            }
                            return true
                        }
                    })
                    overlays.add(eventsOverlay)
                    mapView = this
                }
            },
            update = { view ->
                // Update radius circle when slider changes
                destinationPoint?.let { point ->
                    radiusCircle?.let { circle ->
                        view.overlays.remove(circle)
                    }
                    val newCircle = Polygon(view).apply {
                        points = Polygon.pointsAsCircle(point, radiusKm.toDouble() * 1000)
                        fillPaint.color = 0x264E6AFF
                        outlinePaint.color = 0xFF4E6AFF.toInt()
                        outlinePaint.strokeWidth = 3f
                    }
                    view.overlays.add(newCircle)
                    radiusCircle = newCircle
                    view.invalidate()
                }
            }
        )

        // ── Top Bar with App Name ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NightBlack.copy(alpha = 0.9f), Color.Transparent)
                    )
                )
                .padding(top = 48.dp, bottom = 24.dp)
                .align(Alignment.TopCenter),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.NightsStay,
                    contentDescription = null,
                    tint = NightAccentBright,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "WakeWay",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightTextPrimary,
                )
            }
        }

        // ── Bottom Control Panel ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, NightBlack.copy(alpha = 0.95f), NightBlack)
                    )
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Destination info
            if (destinationPoint != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NightCard),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = NightAccentBright,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Destination Set",
                                style = MaterialTheme.typography.labelLarge,
                                color = NightTextPrimary,
                            )
                            Text(
                                text = "%.4f, %.4f".format(
                                    destinationPoint!!.latitude,
                                    destinationPoint!!.longitude
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NightTextSecondary,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Text(
                    text = "Long-press on the map to set your destination",
                    color = NightTextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Radius slider
            Text(
                text = "Alert Radius: ${radiusKm.roundToInt()} km",
                color = NightTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = radiusKm,
                onValueChange = { radiusKm = it },
                valueRange = 1f..10f,
                steps = 8,
                enabled = !isTripActive,
                colors = SliderDefaults.colors(
                    thumbColor = NightAccentBright,
                    activeTrackColor = NightAccent,
                    inactiveTrackColor = NightSliderTrack,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Start / Stop Trip button
            val buttonColor by animateColorAsState(
                targetValue = if (isTripActive) NightRed else NightGreen,
                animationSpec = tween(300),
                label = "btnColor",
            )

            Button(
                onClick = {
                    if (!isTripActive) {
                        val dest = destinationPoint
                        if (dest == null) {
                            Toast.makeText(context, "Drop a pin first!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isTripActive = true
                        onStartTrip(dest, radiusKm * 1000f)
                    } else {
                        isTripActive = false
                        onStopTrip()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            ) {
                Text(
                    text = if (isTripActive) "⏹  Stop Trip" else "▶  Start Trip",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = NightBlack,
                )
            }

            // Pulsing indicator when trip is active
            if (isTripActive) {
                Spacer(modifier = Modifier.height(8.dp))
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pulseAlpha",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(NightGreen.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Monitoring your trip…",
                        color = NightTextSecondary.copy(alpha = alpha),
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
