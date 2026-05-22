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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
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

        // ── API-key settings dialog ─────────────────────────────────────────
        if (showSettings) {
            ApiKeyDialog(
                apiKeyStore = viewModel.apiKeyStore,
                onDismiss   = { showSettings = false }
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
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> CollectScreen(viewModel, cameraHelper)
                    1 -> ZephyrBLEScreen(viewModel)
                    2 -> WearOSScreen()
                }
            }
        }
    }
}

// =============================================================================
// PRIMARY: Collect screen  (phone sensors + camera + EI upload)
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectScreen(viewModel: SensorViewModel, cameraHelper: CameraHelper) {
    val sensorData by viewModel.sensorData.collectAsState()
    var isRunning  by remember { mutableStateOf(false) }
    var label      by remember { mutableStateOf("") }
    var offlineOn  by remember { mutableStateOf(false) }
    var statusMsg  by remember { mutableStateOf("") }

    val sensorOptions = listOf("Accelerometer", "PPG (Heart Rate)")
    var dropdownOpen   by remember { mutableStateOf(false) }
    var selectedSensor by remember { mutableStateOf(sensorOptions[0]) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── Section: Sensor picker ──────────────────────────────────────────
        item {
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

        // ── Section: Label ─────────────────────────────────────────────────
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

        // ── Section: Collection controls ───────────────────────────────────
        item {
            Text("Collection", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled  = !isRunning,
                    onClick  = {
                        viewModel.startSensor(selectedSensor)
                        if (offlineOn) viewModel.startOfflineLogging()
                        isRunning = true
                        statusMsg = "Collecting $selectedSensor…"
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
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
                        isRunning = false
                        statusMsg = "Stopped."
                    }
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }

        // ── Section: Offline CSV toggle + upload ───────────────────────────
        item {
            HorizontalDivider()
            Text("Offline logging", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = offlineOn, onCheckedChange = { offlineOn = it })
                Spacer(Modifier.width(10.dp))
                Text("Log samples to CSV on device")
            }
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled  = label.isNotBlank(),
                onClick  = {
                    viewModel.uploadStoredCsvFiles(label)
                    statusMsg = "Uploading CSV files with label '$label'…"
                }
            ) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Upload stored CSV to Edge Impulse")
            }
        }

        // ── Section: Camera ─────────────────────────────────────────────────
        item {
            HorizontalDivider()
            Text("Camera", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            val context = LocalContext.current
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                )
            }
            val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasCameraPermission = granted
                if (granted) {
                    viewModel.captureAndUploadImage(cameraHelper, label)
                    statusMsg = "Capturing image…"
                } else {
                    statusMsg = "Camera permission denied — grant it in Settings"
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled  = label.isNotBlank(),
                onClick  = {
                    if (hasCameraPermission) {
                        viewModel.captureAndUploadImage(cameraHelper, label)
                        statusMsg = "Capturing image…"
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            ) {
                Icon(
                    if (hasCameraPermission) Icons.Default.CameraAlt else Icons.Default.NoPhotography,
                    contentDescription = null
                )
                Spacer(Modifier.width(6.dp))
                Text(if (hasCameraPermission) "Capture & upload image" else "Grant camera & capture")
            }
        }

        // ── Section: Live readout ───────────────────────────────────────────
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

        // ── Status message ─────────────────────────────────────────────────
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
    val isConnected by viewModel.zephyrConnected.collectAsState()
    val inference   by viewModel.zephyrInference.collectAsState()
    val devices     by viewModel.zephyrDevices.collectAsState()

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
fun ApiKeyDialog(apiKeyStore: ApiKeyStore, onDismiss: () -> Unit) {
    val currentKey   by apiKeyStore.apiKey.collectAsState()
    var draft        by remember(currentKey) { mutableStateOf(currentKey) }
    var showKey      by remember { mutableStateOf(false) }
    val hasOverride       = apiKeyStore.hasOverride()
    val buildTimeKey = runCatching { BuildConfig.EI_API_KEY }.getOrElse { "" }
    val isUsingBuild = !hasOverride && buildTimeKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edge Impulse API key", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            }
        },
        confirmButton = {
            Button(onClick = {
                apiKeyStore.set(draft)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// =============================================================================
// SECONDARY: WearOS screen
// =============================================================================

@Composable
fun WearOSScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Watch, contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("WearOS sensor relay", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(
                "Pair a Wear OS watch to relay heart rate,\n" +
                "accelerometer, and GPS to Edge Impulse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
