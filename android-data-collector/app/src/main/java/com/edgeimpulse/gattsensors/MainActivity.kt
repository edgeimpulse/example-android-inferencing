package com.edgeimpulse.gattsensors

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Edge Impulse brand palette (matches docs.edgeimpulse.com)
//   primary   #a7f400  lime-green accent
//   secondary #3ec98a  teal
//   bg        #0e0e10  near-black
//   surface   #1a1a1f  panel
val EIDarkTheme = darkColorScheme(
    primary            = Color(0xFFA7F400),
    onPrimary          = Color(0xFF0E0E10),
    secondary          = Color(0xFF3EC98A),
    onSecondary        = Color(0xFF0E0E10),
    background         = Color(0xFF0E0E10),
    onBackground       = Color(0xFFF4F4F5),
    surface            = Color(0xFF1A1A1F),
    onSurface          = Color(0xFFF4F4F5),
    surfaceVariant     = Color(0xFF26262C),
    onSurfaceVariant   = Color(0xFFB4B4BA),
    outline            = Color(0xFF3A3A42),
    error              = Color(0xFFFF5252),
)

// =============================================================================
// Activity
// =============================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SensorViewModel
    private val cameraHelper = CameraHelper(this)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Result ignored — the app launches regardless. Each tab
            // re-requests its own permissions when the user touches a control
            // that needs them.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always render the app shell. Permissions are requested in the
        // background — denial does not block the UI.
        viewModel = ViewModelProvider(
            this, ViewModelFactory(application)
        )[SensorViewModel::class.java]
        cameraHelper.bindToLifecycle(this)
        setContent { AppRoot(viewModel, cameraHelper) }

        val missing = optionalPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** All permissions the app *can* use. None are required to launch. */
    private fun optionalPermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.BODY_SENSORS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }
}

// =============================================================================
// Root — NavigationBar drives primary vs secondary screens
// =============================================================================

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: SensorViewModel, cameraHelper: CameraHelper) {
    MaterialTheme(colorScheme = EIDarkTheme) {
        var selectedTab    by remember { mutableIntStateOf(0) }
        var showSettings   by remember { mutableStateOf(false) }

        // ── Voice control (Hey Android wake word + on-device STT) ──────────
        val context = LocalContext.current
        val voiceScope = rememberCoroutineScope()
        var voiceStatus     by remember { mutableStateOf("") }
        var voiceTranscript by remember { mutableStateOf("") }
        val voiceManager = remember {
            com.edgeimpulse.gattsensors.voice.VoiceCommandManager(
                context     = context.applicationContext,
                scope       = voiceScope,
                viewModel   = viewModel,
                onStatus    = { voiceStatus = it },
                onTranscript = { voiceTranscript = it },
                defaultAction = {
                    val s = viewModel.voiceSettingsStore.get()
                    if (s.enabled && s.label.isNotBlank()) {
                        com.edgeimpulse.gattsensors.voice.VoiceCommand(
                            durationSeconds = s.durationSec,
                            label = s.label,
                            raw = "<default-action>",
                        )
                    } else null
                },
            )
        }
        val voiceEnabled by voiceManager.enabled.collectAsState()
        val micPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) voiceManager.enable()
            else voiceStatus = "RECORD_AUDIO permission denied"
        }

        // Always-on by default: kick off wake-word listening as soon as the UI
        // mounts. Request RECORD_AUDIO if we don't already have it; otherwise
        // enable straight away. Continuous loop — KwsEngine re-arms itself
        // after every wake / capture cycle.
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) voiceManager.enable()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        DisposableEffect(Unit) {
            // Let audio capture pause the wake-word listener for the duration
            // of a manual mic recording, then re-arm KWS automatically.
            viewModel.onMicAcquire = { voiceManager.disable() }
            viewModel.onMicRelease = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) voiceManager.enable()
            }
            onDispose {
                viewModel.onMicAcquire = null
                viewModel.onMicRelease = null
                voiceManager.disable()
            }
        }

        // ── Settings dialog (API key + voice defaults) ─────────────────────
        if (showSettings) {
            SettingsDialog(
                apiKeyStore        = viewModel.apiKeyStore,
                voiceSettingsStore = viewModel.voiceSettingsStore,
                onDismiss          = { showSettings = false }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Edge Impulse Data Collector",
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (voiceEnabled) {
                                    voiceManager.disable()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) voiceManager.enable()
                                    else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ) {
                            Icon(
                                if (voiceEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Voice control",
                                tint = if (voiceEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "API key settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = EIDarkTheme.surface,
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = EIDarkTheme.surface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick  = { selectedTab = 0 },
                        icon     = { Icon(Icons.Default.Sensors, contentDescription = null) },
                        label    = { Text("Collect") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick  = { selectedTab = 1 },
                        icon     = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                        label    = { Text("Zephyr BLE") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick  = { selectedTab = 2 },
                        icon     = { Icon(Icons.Default.Watch, contentDescription = null) },
                        label    = { Text("WearOS") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick  = { selectedTab = 3 },
                        icon     = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label    = { Text("Datasets") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    if (voiceEnabled || voiceStatus.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(
                                    text = voiceStatus.ifBlank { "Voice ready" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (voiceTranscript.isNotBlank()) {
                                    Text(
                                        text = "> $voiceTranscript",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> CollectScreen(viewModel, cameraHelper)
                            1 -> ZephyrBLEScreen(viewModel)
                            2 -> WearOSScreen(viewModel, cameraHelper)
                            3 -> DatasetsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// PRIMARY: Collect screen
// Unified multi-stream recorder. Pick label + duration, toggle the streams
// you want (phone SensorManager, Wear OS watch, Nesso N1 BLE, camera
// snapshot) and hit Start. A single-source mode is also available for
// quick one-sensor captures and for sources that aren't part of the
// unified flow (GPS, Microphone).
// =============================================================================

private const val MODE_MULTI  = 0
private const val MODE_SINGLE = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(viewModel: SensorViewModel, cameraHelper: CameraHelper) {
    val context = LocalContext.current
    val sensorData       by viewModel.sensorData.collectAsState()
    val isRunning        by viewModel.isCollecting.collectAsState()
    val multiRecording   by viewModel.multiRecording.collectAsState()
    val eiConnected      by viewModel.eiConnected.collectAsState()
    val eiError          by viewModel.eiConnectionError.collectAsState()
    val wearNode         by viewModel.wearNode.collectAsState()
    val wearCount        by viewModel.wearSampleCount.collectAsState()
    val zephyrConnected  by viewModel.zephyrConnected.collectAsState()

    var label      by remember { mutableStateOf("") }
    var offlineOn  by remember { mutableStateOf(false) }
    var statusMsg  by remember { mutableStateOf("") }
    var mode       by remember { mutableIntStateOf(MODE_MULTI) }

    // Multi-stream toggles
    var usePhone  by remember { mutableStateOf(true) }
    var useWear   by remember { mutableStateOf(true) }
    var useZephyr by remember { mutableStateOf(true) }
    var useCamera by remember { mutableStateOf(false) }

    // Auto-enable offline CSV logging whenever uploads can't reach Edge
    // Impulse — either because no API key is configured, or the device is
    // offline. The switch is locked on while that's true so the user can't
    // accidentally drop samples on the floor.
    val apiKey by viewModel.apiKeyStore.apiKey.collectAsState()
    val online by rememberOnline()
    val forceOffline = apiKey.isBlank() || !online
    val forceReason = when {
        apiKey.isBlank() && !online -> "No API key + no network"
        apiKey.isBlank()            -> "No Edge Impulse API key"
        !online                     -> "No network"
        else                        -> null
    }
    LaunchedEffect(forceOffline) {
        if (forceOffline && !offlineOn) offlineOn = true
    }

    val sensorOptions = viewModel.collectSourceOptions
    var dropdownOpen   by remember { mutableStateOf(false) }
    var selectedSensor by remember { mutableStateOf(sensorOptions.firstOrNull() ?: "Accelerometer") }

    // Location permission — prompted lazily the first time GPS is selected.
    val locationPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* state read directly via ContextCompat at click time */ }

    // Camera permission state — re-checked on every resume so changes made in
    // system Settings flow back into the UI.
    val activity = context as android.app.Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    fun hasCamPerm() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var hasCameraPermission by remember { mutableStateOf(hasCamPerm()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasCameraPermission = hasCamPerm()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            statusMsg = if (!activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                "Permission permanently denied — tap \"Open Settings\" below"
            else
                "Camera permission denied"
        }
    }
    val cameraPermanentlyDenied = !hasCameraPermission &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    // Duration state
    val durationPresets = listOf(1, 2, 10, 20)
    var sliderValue      by remember { mutableFloatStateOf(2f) }
    var customDuration   by remember { mutableStateOf("") }  // for > 100 s
    val effectiveDurationSec: Int = customDuration.toIntOrNull()?.takeIf { it > 0 }
        ?: sliderValue.toInt().coerceAtLeast(1)

    val anyRunning = isRunning || multiRecording

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── EI remote-management connection banner ──────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (eiConnected) Color(0xFF1B5E20) else Color(0xFF37474F),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (eiConnected) "Connected to Edge Impulse Studio"
                        else "Not connected to Edge Impulse",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!eiConnected && eiError.isNotBlank()) {
                        Text(eiError, color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!eiConnected) {
                    TextButton(onClick = { viewModel.reconnectEI() }) {
                        Text("Retry", color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color.White)
                }
            }
        }

        // ── External-device status (only when something is paired) ──────────
        if (wearNode != null || zephyrConnected) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Paired devices", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        if (wearNode != null) {
                            SourceRow(
                                icon = Icons.Default.Watch,
                                title = "Wear OS: $wearNode",
                                subtitle = "Samples received: $wearCount",
                                ok = true,
                            )
                        }
                        if (zephyrConnected) {
                            SourceRow(
                                icon = Icons.Default.Bluetooth,
                                title = "Nesso N1: connected",
                                subtitle = "Streaming IMU over BLE",
                                ok = true,
                            )
                        }
                    }
                }
            }
        }

        // ── Mode toggle ─────────────────────────────────────────────────────
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == MODE_MULTI,
                    onClick  = { mode = MODE_MULTI },
                    shape    = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Multi-stream") }
                SegmentedButton(
                    selected = mode == MODE_SINGLE,
                    onClick  = { mode = MODE_SINGLE },
                    shape    = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Single source") }
            }
        }

        // ── Label + duration (shared by both modes) ─────────────────────────
        item {
            OutlinedTextField(
                value         = label,
                onValueChange = { label = it },
                label         = { Text("Edge Impulse label") },
                placeholder   = { Text("e.g. normal, anomaly, idle") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )
        }
        item {
            HorizontalDivider()
            Text("Sample duration", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                durationPresets.forEach { sec ->
                    FilterChip(
                        selected = customDuration.isEmpty() && sliderValue.toInt() == sec,
                        onClick  = { sliderValue = sec.toFloat(); customDuration = "" },
                        label    = { Text("${sec}s") }
                    )
                }
            }
        }
        item {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (customDuration.isNotBlank()) "Custom: ${customDuration}s"
                        else "${sliderValue.toInt()} s",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("1 – 100 s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value         = sliderValue,
                    onValueChange = { sliderValue = it; customDuration = "" },
                    valueRange    = 1f..100f,
                    steps         = 98,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            OutlinedTextField(
                value         = customDuration,
                onValueChange = { customDuration = it },
                label         = { Text("Custom duration (s)") },
                placeholder   = { Text("Enter any value > 100") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier      = Modifier.fillMaxWidth(),
                trailingIcon  = { Text("s", modifier = Modifier.padding(end = 12.dp)) }
            )
        }

        // ── Mode-specific content ───────────────────────────────────────────
        if (mode == MODE_MULTI) {
            item {
                HorizontalDivider()
                Text("Streams", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            item {
                ToggleRow(
                    "Phone SensorManager (accel, gyro, mag, light, pressure, GPS, …)",
                    usePhone,
                ) { usePhone = it }
            }
            item {
                ToggleRow(
                    if (wearNode != null) "Wear OS watch (accel, gyro, heart rate)"
                    else "Wear OS watch — open the WearOS tab to pair",
                    useWear, enabled = wearNode != null,
                ) { useWear = it }
            }
            item {
                ToggleRow(
                    if (zephyrConnected) "Nesso N1 IMU (BLE)"
                    else "Nesso N1 IMU — open the Zephyr BLE tab to connect",
                    useZephyr, enabled = zephyrConnected,
                ) { useZephyr = it }
            }
            item {
                ToggleRow("Camera snapshot at session start",
                    useCamera, enabled = true) { useCamera = it }
            }

            // Camera viewfinder + permission flow (only when Camera toggle on)
            if (useCamera) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasCameraPermission) {
                            // `key(hasCameraPermission)` forces AndroidView's
                            // factory to re-run if the user goes denied →
                            // granted while the toggle stays on, so the
                            // PreviewView (and the underlying CameraX bind)
                            // are re-created with permission in hand.
                            androidx.compose.runtime.key(hasCameraPermission) {
                                AndroidView(
                                    factory = { ctx ->
                                        PreviewView(ctx).also { pv ->
                                            pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                            cameraHelper.bindToLifecycle(lifecycleOwner, pv.surfaceProvider)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                )
                            }
                            // Standalone single-image capture so the user can
                            // grab a labelled photo without running a full
                            // multi-stream session.
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled  = label.isNotBlank() && !anyRunning,
                                onClick  = {
                                    statusMsg = "Capturing…"
                                    viewModel.captureAndUploadImage(cameraHelper, label) { _, msg ->
                                        statusMsg = msg
                                    }
                                },
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Capture & upload single image")
                            }
                            if (label.isBlank()) {
                                Text(
                                    "Enter a label above to enable single-image capture.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (cameraPermanentlyDenied) {
                                        context.startActivity(
                                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            ) {
                                Icon(Icons.Default.NoPhotography, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (cameraPermanentlyDenied) "Open Settings to grant camera"
                                     else "Grant camera permission")
                            }
                        }
                    }
                }
            }
        } else {
            // Single-source mode — sensor dropdown
            item {
                HorizontalDivider()
                Text("Data source", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            item {
                ExposedDropdownMenuBox(expanded = dropdownOpen, onExpandedChange = { dropdownOpen = it }) {
                    OutlinedTextField(
                        readOnly      = true,
                        value         = selectedSensor,
                        onValueChange = {},
                        label         = { Text("Sensor") },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(dropdownOpen) },
                        modifier      = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded        = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false }
                    ) {
                        sensorOptions.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = {
                                selectedSensor = s; dropdownOpen = false
                            })
                        }
                    }
                }
            }
        }

        // ── Offline CSV switch (shared) ─────────────────────────────────────
        item {
            HorizontalDivider()
            Text("Offline logging", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = offlineOn,
                        onCheckedChange = { offlineOn = it },
                        enabled = !forceOffline,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Log samples to CSV on device")
                }
                if (forceOffline && forceReason != null) {
                    Text(
                        "Auto-enabled — $forceReason. Samples are stored " +
                            "locally and can be uploaded once uploads work again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 52.dp, top = 2.dp),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled  = label.isNotBlank(),
                onClick  = {
                    viewModel.uploadStoredCsvFiles(label)
                    statusMsg = "Uploading CSV files with label ‘$label’…"
                }
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Upload stored CSV to Edge Impulse")
            }
        }

        // ── Start / Stop ────────────────────────────────────────────────────
        item {
            HorizontalDivider()
            Text("Capture", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            if (mode == MODE_MULTI) {
                val canStart = !anyRunning && label.isNotBlank() &&
                    (usePhone || useWear || useZephyr || useCamera) &&
                    (!useCamera || hasCameraPermission)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled  = canStart,
                    onClick  = {
                        viewModel.lastLabel = label
                        if (offlineOn) viewModel.startOfflineLogging()
                        viewModel.startUnifiedRecording(
                            durationMs          = effectiveDurationSec * 1000L,
                            label               = label.trim(),
                            includePhoneSensors = usePhone,
                            includeWear         = useWear,
                            includeZephyr       = useZephyr,
                            cameraHelper        = cameraHelper.takeIf { useCamera },
                        )
                        statusMsg = "Recording '$label' for ${effectiveDurationSec}s…"
                    }
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (multiRecording) "Recording '$label'…"
                         else "Record '${label.ifBlank { "label" }}' for ${effectiveDurationSec}s")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled  = !isRunning && label.isNotBlank(),
                        onClick  = {
                            viewModel.lastLabel = label
                            if (selectedSensor == "GPS (Location)") {
                                val ok = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!ok) {
                                    locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                    statusMsg = "Grant location permission and tap Start again."
                                    return@Button
                                }
                            }
                            if (offlineOn) viewModel.startOfflineLogging()
                            viewModel.startSensorForDuration(
                                selectedSensor,
                                effectiveDurationSec * 1000L
                            )
                            statusMsg = "Collecting $selectedSensor for ${effectiveDurationSec}s…"
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Start (${effectiveDurationSec}s)")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        enabled  = isRunning,
                        onClick  = {
                            viewModel.stopSensor()
                            if (offlineOn) viewModel.stopOfflineLogging()
                            statusMsg = "Stopped."
                        }
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }

        // ── Live readout ────────────────────────────────────────────────────
        if (sensorData != null) {
            item {
                HorizontalDivider()
                Text("Live sensor data", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(sensorData!!.values.entries.toList()) { (k, v) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(EIDarkTheme.surface, MaterialTheme.shapes.small)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(k, style = MaterialTheme.typography.bodyMedium)
                    Text("${"%.4f".format(v)}", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Status message ──────────────────────────────────────────────────
        if (statusMsg.isNotBlank()) {
            item {
                Text(statusMsg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// =============================================================================
// SECONDARY: Zephyr BLE screen
// =============================================================================

@Composable
fun ZephyrBLEScreen(viewModel: SensorViewModel) {
    val isConnected   by viewModel.zephyrConnected.collectAsState()
    val inference     by viewModel.zephyrInference.collectAsState()
    val devices       by viewModel.zephyrDevices.collectAsState()
    val currentLabel  by viewModel.zephyrLabel.collectAsState()
    val sampleCount   by viewModel.zephyrSampleCount.collectAsState()
    val isRecording   by viewModel.zephyrRecording.collectAsState()

    val labels = listOf("idle", "circle", "updown")
    val recordingDurationMs = 5000L

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("Zephyr BLE (EI-Monitor)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "Connect to a Zephyr Thingy:53 running the EI-Monitor firmware to relay " +
                "live inference results and raw sensor data to Edge Impulse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        // Connection banner
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isConnected) Color(0xFF1B5E20) else Color(0xFF37474F),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (isConnected) "Connected to EI-Monitor" else "Not connected",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                    contentDescription = null, tint = Color.White
                )
            }
        }

        // Scan / Disconnect
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled  = !isConnected,
                    onClick  = { viewModel.zephyrBLEClient.startScan() }
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Scan for EI-Monitor")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled  = isConnected,
                    onClick  = { viewModel.zephyrBLEClient.disconnect() }
                ) { Text("Disconnect") }
            }
        }

        // ---- Capture label picker + status ----
        item {
            Text("Capture label", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { label ->
                    FilterChip(
                        selected = currentLabel == label,
                        onClick  = { viewModel.setZephyrLabel(label) },
                        enabled  = isConnected && !isRecording,
                        label    = { Text(label) }
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Status", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Active label: $currentLabel")
                    Text("Samples received this window: $sampleCount")
                    Text(
                        if (isRecording) "Recording \u2014 hold still / perform gesture\u2026"
                        else "Idle",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRecording) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled  = isConnected && !isRecording,
                onClick  = { viewModel.startZephyrRecording(recordingDurationMs) }
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isRecording) "Recording\u2026"
                    else "Record \"$currentLabel\" from Nesso N1 (${recordingDurationMs / 1000}s)"
                )
            }
        }

        // Device list
        if (devices.isNotEmpty()) {
            item {
                Text("Nearby devices", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(devices) { dev ->
                ListItem(
                    headlineContent   = { Text(dev.name.ifBlank { "Unknown" }) },
                    supportingContent = { Text("${dev.address}  RSSI: ${dev.rssi} dBm") },
                    leadingContent    = { Icon(Icons.Default.Sensors, contentDescription = null) }
                )
                HorizontalDivider()
            }
        }

        // Inference result
        item {
            Text("Latest inference", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        if (inference != null) {
            item {
                val r = inference!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(r.label, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Confidence: ${"%.1f".format(r.confidence * 100)}%")
                        Text("DSP: ${r.dspTimeMs} ms  |  Classify: ${r.classificationTimeMs} ms")
                        Text(
                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(r.receivedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    if (isConnected) "Waiting for inference notifications…"
                    else "Connect to EI-Monitor to start receiving data",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// =============================================================================
// API-key settings dialog
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    apiKeyStore: ApiKeyStore,
    voiceSettingsStore: VoiceSettingsStore,
    onDismiss: () -> Unit,
) {
    val currentKey   by apiKeyStore.apiKey.collectAsState()
    var draft        by remember(currentKey) { mutableStateOf(currentKey) }
    var showKey      by remember { mutableStateOf(false) }
    val hasOverride       = apiKeyStore.hasOverride()
    val buildTimeKey = runCatching { BuildConfig.EI_API_KEY }.getOrElse { "" }
    val isUsingBuild = !hasOverride && buildTimeKey.isNotBlank()

    val voiceState by voiceSettingsStore.state.collectAsState()
    var voiceEnabled    by remember(voiceState) { mutableStateOf(voiceState.enabled) }
    var voiceLabel      by remember(voiceState) { mutableStateOf(voiceState.label) }
    var voiceDurationS  by remember(voiceState) { mutableStateOf(voiceState.durationSec.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("Edge Impulse API key",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)

                // Status chip
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val (chipColor, chipText) = when {
                        hasOverride    -> Pair(Color(0xFF1B5E20), "Runtime override active")
                        isUsingBuild   -> Pair(Color(0xFF1565C0), "Using build-time key")
                        else           -> Pair(MaterialTheme.colorScheme.error, "No key set — uploads will fail")
                    }
                    Surface(
                        color  = chipColor,
                        shape  = MaterialTheme.shapes.small
                    ) {
                        Text(
                            chipText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = Color.White
                        )
                    }
                }

                Text(
                    "Enter your project API key from Edge Impulse Studio → Dashboard → Keys. " +
                    "Leave blank to use the build-time key from gradle.properties.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value         = draft,
                    onValueChange = { draft = it },
                    label         = { Text("API key (ei_…)") },
                    placeholder   = { Text("ei_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon  = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key"
                            )
                        }
                    }
                )

                if (hasOverride) {
                    TextButton(
                        onClick = { apiKeyStore.set(""); onDismiss() },
                        colors  = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear override — revert to build-time key")
                    }
                }

                HorizontalDivider()

                Text("Voice control — default action after wake word",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "When enabled, saying \"hey android\" skips the spoken " +
                    "command and immediately records a labelled sample for " +
                    "the configured duration. Leave disabled to use the " +
                    "default \"record N seconds as <label>\" voice commands.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Default action enabled")
                }
                OutlinedTextField(
                    value         = voiceLabel,
                    onValueChange = { voiceLabel = it },
                    label         = { Text("Default label") },
                    placeholder   = { Text("e.g. quick-capture") },
                    singleLine    = true,
                    enabled       = voiceEnabled,
                    modifier      = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value         = voiceDurationS,
                    onValueChange = { voiceDurationS = it.filter(Char::isDigit) },
                    label         = { Text("Duration (seconds)") },
                    placeholder   = { Text("5") },
                    singleLine    = true,
                    enabled       = voiceEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier      = Modifier.fillMaxWidth(),
                )
                if (voiceEnabled && voiceLabel.isBlank()) {
                    Text(
                        "Set a label so the recording can be uploaded with a class name.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                apiKeyStore.set(draft)
                voiceSettingsStore.update(
                    enabled = voiceEnabled,
                    label = voiceLabel,
                    durationSec = voiceDurationS.toIntOrNull() ?: 5,
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// =============================================================================
// SECONDARY: WearOS / multi-modal capture screen
// =============================================================================

@Composable
fun WearOSScreen(viewModel: SensorViewModel, cameraHelper: CameraHelper) {
    // Recording lives in the Collect tab — this screen is a small status
    // panel that surfaces watch pairing state and a nudge towards where
    // the actual workflow lives.
    @Suppress("UNUSED_PARAMETER") val _unused = cameraHelper
    val wearNode  by viewModel.wearNode.collectAsState()
    val wearCount by viewModel.wearSampleCount.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Wear OS companion",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Install the companion app from the wearosdatalogger module on " +
            "your watch, then pair it from the Wear OS app on the phone. " +
            "Once a watch is connected its sensor stream becomes available " +
            "as a toggle in the Collect tab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Connection status",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                SourceRow(
                    icon = Icons.Default.Watch,
                    title = if (wearNode != null) "Connected to: $wearNode"
                            else "No paired watch detected",
                    subtitle = "Samples received this session: $wearCount",
                    ok = wearNode != null,
                )
                TextButton(onClick = { viewModel.refreshWearNode() }) {
                    Text("Refresh Wear OS connection")
                }
            }
        }
        Text(
            "Tip: head to the Collect tab and enable the Wear OS stream toggle " +
            "to record watch sensors as part of a multi-modal capture.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, ok: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String, checked: Boolean, enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}
