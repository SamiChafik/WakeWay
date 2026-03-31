package com.example.wakeway

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Geocoder
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var geofencingClient: GeofencingClient
    private var myLocationOverlay: MyLocationNewOverlay? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (!fineGranted) {
                Toast.makeText(this, "Location permission is required for WakeWay", Toast.LENGTH_LONG).show()
            } else {
                // Enable my-location overlay once permission is granted
                myLocationOverlay?.enableMyLocation()
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
                    activity = this,
                    onStartTrip = { geoPoint, radiusMeters ->
                        registerGeofence(geoPoint, radiusMeters)
                        startForegroundService(geoPoint, radiusMeters)
                    },
                    onStopTrip = {
                        stopTrip()
                    },
                    onMyLocationOverlayCreated = { overlay ->
                        myLocationOverlay = overlay
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

    fun checkOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please allow 'Display over other apps' to enable full-screen popup!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }
        return true
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
        myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        myLocationOverlay?.disableMyLocation()
    }
}

// ─── Compose UI ───────────────────────────────────────────────────────────────

@Composable
fun WakeWayScreen(
    activity: MainActivity,
    onStartTrip: (GeoPoint, Float) -> Unit,
    onStopTrip: () -> Unit,
    onMyLocationOverlayCreated: (MyLocationNewOverlay) -> Unit,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val prefs = remember { context.getSharedPreferences("wakeway_prefs", Context.MODE_PRIVATE) }

    var destinationPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var radiusKm by remember { mutableFloatStateOf(5f) }
    var isTripActive by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    var radiusCircle by remember { mutableStateOf<Polygon?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchSuggestions by remember { mutableStateOf<List<Pair<String, GeoPoint>>>(emptyList()) }
    var isSuggestionsExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Auto-complete Debounced Search
    LaunchedEffect(searchQuery) {
        if (searchQuery.length < 3) {
            searchSuggestions = emptyList()
            isSuggestionsExpanded = false
            return@LaunchedEffect
        }
        val coords = searchQuery.split(",").map { it.trim().toDoubleOrNull() }
        if (coords.size == 2 && coords[0] != null && coords[1] != null) return@LaunchedEffect
        
        kotlinx.coroutines.delay(600)
        try {
            withContext(Dispatchers.IO) {
                val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}&format=json&limit=4")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "WakeWay App")
                connection.connect()
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    val results = mutableListOf<Pair<String, GeoPoint>>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val lat = obj.getDouble("lat")
                        val lon = obj.getDouble("lon")
                        val name = obj.getString("display_name").split(",").take(2).joinToString(", ")
                        results.add(Pair(name, GeoPoint(lat, lon)))
                    }
                    withContext(Dispatchers.Main) {
                        searchSuggestions = results
                        isSuggestionsExpanded = results.isNotEmpty()
                    }
                }
            }
        } catch (e: Exception) {
            // Dry
        }
    }

    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    var alarmVolume by remember { mutableFloatStateOf(prefs.getInt("alarm_volume", maxVolume / 2).toFloat()) }
    var isSoundEnabled by remember { mutableStateOf(prefs.getBoolean("enable_sound", true)) }
    var isVibrationEnabled by remember { mutableStateOf(prefs.getBoolean("enable_vibration", true)) }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Map View ──
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(5.0)
                    controller.setCenter(GeoPoint(0.0, 0.0))

                    // Location Overlay
                    val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this)
                    locationOverlay.enableMyLocation()
                    locationOverlay.setPersonIcon(createPurplePersonBitmap())
                    locationOverlay.setDirectionArrow(createPurpleArrowBitmap(), createPurpleArrowBitmap())
                    overlays.add(locationOverlay)
                    onMyLocationOverlayCreated(locationOverlay)

                    // Long press listener to set destination
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            isSuggestionsExpanded = false
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (isTripActive) return false
                            p?.let {
                                destinationPoint = it
                                // Update Marker
                                if (destinationMarker == null) {
                                    destinationMarker = Marker(this@apply).apply {
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_destination_pin)
                                    }
                                    overlays.add(destinationMarker)
                                }
                                destinationMarker?.position = it
                                destinationMarker?.title = "Destination"
                                
                                // Update Radius Circle
                                if (radiusCircle == null) {
                                    radiusCircle = Polygon(this@apply)
                                    overlays.add(radiusCircle)
                                }
                                radiusCircle?.points = Polygon.pointsAsCircle(it, radiusKm.toDouble() * 1000)
                                radiusCircle?.fillColor = 0x227C4DFF // Translucent purple
                                radiusCircle?.strokeColor = 0xFF7C4DFF.toInt()
                                radiusCircle?.strokeWidth = 2f
                                
                                invalidate()
                            }
                            return true
                        }
                    })
                    overlays.add(eventsOverlay)
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                radiusCircle?.let {
                    destinationPoint?.let { pt ->
                        it.points = Polygon.pointsAsCircle(pt, radiusKm.toDouble() * 1000)
                        view.invalidate()
                    }
                }
            }
        )

        // ── Top Search Bar ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search city, station or lat,lon", color = NightTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NightAccentBright) },
                trailingIcon = {
                    if (isSearching) {
                        Text("...", color = NightAccentBright, modifier = Modifier.padding(end = 16.dp), fontWeight = FontWeight.Bold)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (searchQuery.isNotBlank()) {
                            isSearching = true
                            isSuggestionsExpanded = false
                            
                            // Try parsing coordinates
                            val coords = searchQuery.split(",").map { it.trim().toDoubleOrNull() }
                            if (coords.size == 2 && coords[0] != null && coords[1] != null) {
                                val lat = coords[0]!!
                                val lon = coords[1]!!
                                isSearching = false
                                val pt = GeoPoint(lat, lon)
                                mapView?.overlays?.filterIsInstance<MyLocationNewOverlay>()?.forEach { it.disableFollowLocation() }
                                mapView?.controller?.animateTo(pt)
                                mapView?.controller?.setZoom(15.0)
                                Toast.makeText(context, "Location set to Coordinates", Toast.LENGTH_SHORT).show()
                            } else {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val url = java.net.URL("https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}&format=json&limit=1")
                                        val connection = url.openConnection() as java.net.HttpURLConnection
                                        connection.setRequestProperty("User-Agent", "WakeWay App")
                                        connection.connect()
                                        
                                        if (connection.responseCode == 200) {
                                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                                            val jsonArray = org.json.JSONArray(response)
                                            if (jsonArray.length() > 0) {
                                                val jsonObject = jsonArray.getJSONObject(0)
                                                val lat = jsonObject.getDouble("lat")
                                                val lon = jsonObject.getDouble("lon")
                                                val displayName = jsonObject.getString("display_name").split(",").firstOrNull() ?: searchQuery
                                                
                                                withContext(Dispatchers.Main) {
                                                    isSearching = false
                                                    val pt = GeoPoint(lat, lon)
                                                    mapView?.overlays?.filterIsInstance<MyLocationNewOverlay>()?.forEach { it.disableFollowLocation() }
                                                    mapView?.controller?.animateTo(pt)
                                                    mapView?.controller?.setZoom(12.0)
                                                    Toast.makeText(context, "Found: $displayName", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    isSearching = false
                                                    Toast.makeText(context, "Location not found", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                isSearching = false
                                                Toast.makeText(context, "Search limit reached or failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        withContext(Dispatchers.Main) {
                                            isSearching = false
                                            Toast.makeText(context, "Search Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                ),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NightPurple,
                    unfocusedBorderColor = NightSliderTrack,
                    focusedContainerColor = NightCard,
                    unfocusedContainerColor = NightCard,
                    focusedTextColor = NightTextPrimary,
                    unfocusedTextColor = NightTextPrimary
                )
            )

            if (isSuggestionsExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = NightCard)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        searchSuggestions.forEach { suggestion ->
                            TextButton(
                                onClick = {
                                    searchQuery = suggestion.first
                                    isSuggestionsExpanded = false
                                    mapView?.overlays?.filterIsInstance<MyLocationNewOverlay>()?.forEach { it.disableFollowLocation() }
                                    mapView?.controller?.animateTo(suggestion.second)
                                    mapView?.controller?.setZoom(14.0)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = suggestion.first,
                                    color = NightTextPrimary,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start
                                )
                            }
                        }
                    }
                }
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

            Spacer(modifier = Modifier.height(8.dp))

            // ── Alarm Volume slider ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = NightAccentBright,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Alarm Volume",
                    color = NightTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            Slider(
                value = alarmVolume,
                onValueChange = { newVal ->
                    alarmVolume = newVal
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        newVal.roundToInt(),
                        0
                    )
                    // Persist for WakeUpActivity to read
                    context.getSharedPreferences("wakeway_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("alarm_volume", newVal.roundToInt())
                        .putInt("alarm_volume_max", maxVolume)
                        .apply()
                },
                valueRange = 0f..maxVolume.toFloat(),
                steps = maxVolume - 1,
                enabled = isSoundEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = NightPurple,
                    activeTrackColor = NightPurple.copy(alpha = 0.7f),
                    inactiveTrackColor = NightSliderTrack,
                    disabledThumbColor = NightSliderTrack,
                    disabledActiveTrackColor = NightSliderTrack.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Sound & Vibration Toggles ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // My Location Button
                IconButton(
                    onClick = {
                        mapView?.let { map ->
                            val loc = (map.overlays.firstOrNull { it is MyLocationNewOverlay } as? MyLocationNewOverlay)
                            loc?.enableFollowLocation()
                            loc?.myLocation?.let { pos ->
                                map.controller.animateTo(pos)
                                map.controller.setZoom(15.0)
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "My Location",
                        tint = NightAccentBright,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isSoundEnabled,
                        onCheckedChange = { 
                            isSoundEnabled = it
                            prefs.edit().putBoolean("enable_sound", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NightPurple,
                            checkedTrackColor = NightPurple.copy(alpha = 0.5f)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sound", color = NightTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isVibrationEnabled,
                        onCheckedChange = { 
                            isVibrationEnabled = it
                            prefs.edit().putBoolean("enable_vibration", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NightPurple,
                            checkedTrackColor = NightPurple.copy(alpha = 0.5f)
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vibration", color = NightTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                        // Force overlay permission for full-screen alarm behavior
                        if (!activity.checkOverlayPermission()) {
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

// ─── Purple Location Icons ────────────────────────────────────────────────────

/**
 * Creates a purple circle bitmap used as the "person" icon on the map.
 */
private fun createPurplePersonBitmap(): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Outer glow ring
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x407C4DFF // semi-transparent purple
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, glowPaint)

    // Inner solid circle
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C4DFF.toInt() // NightPurple
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 3f, fillPaint)

    // White center dot
    val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 8f, centerPaint)

    return bitmap
}

/**
 * Creates a purple directional arrow bitmap for when the user is moving.
 */
private fun createPurpleArrowBitmap(): Bitmap {
    val width = 48
    val height = 64
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val arrowPath = Path().apply {
        moveTo(width / 2f, 4f)           // Top point
        lineTo(width - 6f, height - 8f)  // Bottom right
        lineTo(width / 2f, height - 20f) // Inner notch
        lineTo(6f, height - 8f)          // Bottom left
        close()
    }

    // Arrow fill
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7C4DFF.toInt() // NightPurple
        style = Paint.Style.FILL
    }
    canvas.drawPath(arrowPath, fillPaint)

    // Arrow outline
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawPath(arrowPath, strokePaint)

    return bitmap
}
