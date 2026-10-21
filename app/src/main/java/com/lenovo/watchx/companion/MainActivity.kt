package com.lenovo.watchx.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

enum class AppNavTab(val label: String, val icon: ImageVector) {
    ACTIVITY("Activity", Icons.Filled.DirectionsWalk),
    HEART("Vitals", Icons.Filled.Favorite),
    CALIBRATE("Hands", Icons.Filled.Schedule),
    OLED("Alerts", Icons.Filled.Notifications),
    DEVICE("Watch", Icons.Filled.Tune)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        // Safely start BLE companion service without aggressive foreground requirements
        try {
            val serviceIntent = Intent(this, LenovoWatchBleService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start BLE service safely", e)
        }

        setContent {
            val service = LenovoWatchBleService.instance

            val connState by (service?.connectionState?.collectAsState() ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) })
            val battery by (service?.batteryLevel?.collectAsState() ?: remember { mutableStateOf(85) })
            val firmware by (service?.firmwareVersion?.collectAsState() ?: remember { mutableStateOf("1.2.8") })
            val steps by (service?.todaySteps?.collectAsState() ?: remember { mutableStateOf(6420) })
            val stepGoal by (service?.stepGoal?.collectAsState() ?: remember { mutableStateOf(10000) })
            val heartRate by (service?.heartRateBpm?.collectAsState() ?: remember { mutableStateOf(72) })
            val isMeasuringHr by (service?.isMeasuringHr?.collectAsState() ?: remember { mutableStateOf(false) })
            val sleepData by (service?.sleepSession?.collectAsState() ?: remember { mutableStateOf(SleepSession()) })
            val findPhoneActive by (service?.findPhoneTrigger?.collectAsState() ?: remember { mutableStateOf(false) })
            val discoveredDevices by (service?.discoveredDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

            var selectedTab by remember { mutableStateOf(AppNavTab.ACTIVITY) }
            var showScanDialog by remember { mutableStateOf(false) }

            WatchXTheme {
                Scaffold(
                    containerColor = Color(0xFF090D16),
                    bottomBar = {
                        WatchXBottomBar(
                            currentTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            // Top Header with Watch Card
                            WatchHeaderCard(
                                state = connState,
                                battery = battery,
                                firmware = firmware,
                                onScanClick = {
                                    showScanDialog = true
                                    service?.startScan()
                                },
                                onSyncClock = {
                                    service?.syncTime()
                                    Toast.makeText(this@MainActivity, "Atomic clock synchronized!", Toast.LENGTH_SHORT).show()
                                }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Main Tab Content
                            Box(modifier = Modifier.weight(1f)) {
                                when (selectedTab) {
                                    AppNavTab.ACTIVITY -> ActivityTab(
                                        steps = steps,
                                        stepGoal = stepGoal,
                                        sleep = sleepData,
                                        onRefresh = {
                                            service?.querySteps()
                                            service?.querySleep()
                                            Toast.makeText(this@MainActivity, "Syncing activity from watch NVRAM...", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    AppNavTab.HEART -> HeartRateTab(
                                        bpm = heartRate,
                                        isMeasuring = isMeasuringHr,
                                        onMeasure = {
                                            if (connState == BleConnectionState.CONNECTED) {
                                                service?.measureHeartRate()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connect watch first", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    AppNavTab.CALIBRATE -> CalibrateTab(
                                        onCalibrate = { hour, min ->
                                            if (connState == BleConnectionState.CONNECTED) {
                                                service?.calibrateHands(hour, min)
                                                Toast.makeText(this@MainActivity, "Calibrating mechanical steppers...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connect watch first", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                    AppNavTab.OLED -> OledNotificationsTab(
                                        onPush = { title, body, channel ->
                                            if (connState == BleConnectionState.CONNECTED) {
                                                service?.pushNotification(title, body, channel)
                                                Toast.makeText(this@MainActivity, "Sent to OLED Screen!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connect watch first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onOpenNotifSettings = {
                                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    )
                                    AppNavTab.DEVICE -> DeviceSettingsTab(
                                        stepGoal = stepGoal,
                                        onSetGoal = { service?.setStepGoal(it) },
                                        onToggleDnd = { service?.setDnd(it) },
                                        onToggleTilt = { service?.setLiftToWake(it) },
                                        onFindWatch = {
                                            service?.findMyWatch()
                                            Toast.makeText(this@MainActivity, "Signaling watch buzzer...", Toast.LENGTH_SHORT).show()
                                        },
                                        onSetAlarm = { slot, h, m, mask, en ->
                                            service?.setAlarm(slot, h, m, mask, en)
                                            Toast.makeText(this@MainActivity, "Alarm synced to watch", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }

                        // Scanning Dialog
                        if (showScanDialog) {
                            ScanDeviceDialog(
                                devices = discoveredDevices,
                                onSelect = { mac ->
                                    service?.connect(mac)
                                    showScanDialog = false
                                },
                                onDismiss = {
                                    service?.stopScan()
                                    showScanDialog = false
                                }
                            )
                        }

                        // "Find My Phone" Incoming Watch Signal Alert
                        if (findPhoneActive) {
                            FindPhoneModal(
                                onDismiss = { service?.dismissFindPhone() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}

/* ========================================================================= */
/* --- HEADER COMPONENT ---                                                  */
/* ========================================================================= */

@Composable
fun WatchHeaderCard(
    state: BleConnectionState,
    battery: Int,
    firmware: String,
    onScanClick: () -> Unit,
    onSyncClock: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LENOVO WATCH X",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Hybrid Smartwatch Companion",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                }

                // Connection status indicator pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (state) {
                                BleConnectionState.CONNECTED -> Color(0xFF064E3B)
                                BleConnectionState.CONNECTING -> Color(0xFF451A03)
                                BleConnectionState.SCANNING -> Color(0xFF1E1B4B)
                                BleConnectionState.DISCONNECTED -> Color(0xFF1E293B)
                            }
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (state) {
                                    BleConnectionState.CONNECTED -> Color(0xFF10B981)
                                    BleConnectionState.CONNECTING -> Color(0xFFF59E0B)
                                    BleConnectionState.SCANNING -> Color(0xFF818CF8)
                                    BleConnectionState.DISCONNECTED -> Color(0xFF94A3B8)
                                }
                            )
                    )
                    Text(
                        text = state.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = when (state) {
                            BleConnectionState.CONNECTED -> Color(0xFF6EE7B7)
                            BleConnectionState.CONNECTING -> Color(0xFFFCD34D)
                            BleConnectionState.SCANNING -> Color(0xFFA5B4FC)
                            BleConnectionState.DISCONNECTED -> Color(0xFF94A3B8)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Stats and action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BatteryFull,
                            contentDescription = "Battery",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "$battery%",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE2E8F0)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "FW:",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "v$firmware",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == BleConnectionState.CONNECTED) {
                        OutlinedButton(
                            onClick = onSyncClock,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Icon(Icons.Filled.Update, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync Clock", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                    }

                    Button(
                        onClick = onScanClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state == BleConnectionState.CONNECTED) "Change" else "Pair Watch",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 1: ACTIVITY (PEDOMETER & SLEEP) ---                               */
/* ========================================================================= */

@Composable
fun ActivityTab(
    steps: Int,
    stepGoal: Int,
    sleep: SleepSession,
    onRefresh: () -> Unit
) {
    val progress = (steps.toFloat() / stepGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val calories = (steps * 0.04f).toInt()
    val distanceKm = String.format("%.2f", steps * 0.00075f)

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Daily Steps Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.DirectionsWalk, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                            }
                            Text("Pedometer", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }

                        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = "%,d".format(steps),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                            val goalFormatted = String.format("%,d", stepGoal)
                            val progressPct = (progress * 100).toInt()
                            Text(
                                text = "Goal: $goalFormatted steps ($progressPct%)",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "$calories kcal", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFB923C))
                            Text(text = "$distanceKm km", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFFF59E0B),
                        trackColor = Color(0xFF1E293B)
                    )
                }
            }
        }

        // Sleep Tracking Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF818CF8).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(18.dp))
                            }
                            Text("Sleep Quality", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }

                        // Sleep score badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF4338CA).copy(alpha = 0.4f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Score: ${sleep.score}/100",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFA5B4FC)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val totalH = sleep.totalMinutes / 60
                    val totalM = sleep.totalMinutes % 60
                    Text(
                        text = "${totalH}h ${totalM}m",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text("Total Rest Duration", fontSize = 12.sp, color = Color(0xFF94A3B8))

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sleep phases visual breakdown bar
                    val deepRatio = sleep.deepMinutes.toFloat() / sleep.totalMinutes.coerceAtLeast(1)
                    val lightRatio = sleep.lightMinutes.toFloat() / sleep.totalMinutes.coerceAtLeast(1)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    ) {
                        Box(modifier = Modifier.weight(deepRatio.coerceAtLeast(0.05f)).fillMaxHeight().background(Color(0xFF4F46E5)))
                        Box(modifier = Modifier.weight(lightRatio.coerceAtLeast(0.05f)).fillMaxHeight().background(Color(0xFF818CF8)))
                        Box(modifier = Modifier.weight(0.1f).fillMaxHeight().background(Color(0xFF334155)))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SleepPhaseItem("Deep Sleep", "${sleep.deepMinutes / 60}h ${sleep.deepMinutes % 60}m", Color(0xFF4F46E5))
                        SleepPhaseItem("Light Sleep", "${sleep.lightMinutes / 60}h ${sleep.lightMinutes % 60}m", Color(0xFF818CF8))
                        SleepPhaseItem("Awake", "${sleep.awakeMinutes}m", Color(0xFF64748B))
                    }
                }
            }
        }
    }
}

@Composable
fun SleepPhaseItem(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Column {
            Text(label, fontSize = 10.sp, color = Color(0xFF94A3B8))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/* ========================================================================= */
/* --- TAB 2: VITALS (HEART RATE PPG) ---                                    */
/* ========================================================================= */

@Composable
fun HeartRateTab(
    bpm: Int,
    isMeasuring: Boolean,
    onMeasure: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Optical Heart Rate", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("PPG Sensor", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE11D48))
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Pulsing Heart Centerpiece
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            if (isMeasuring) Color(0xFFE11D48).copy(alpha = 0.2f)
                            else Color(0xFF1E293B)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Heart",
                        tint = if (isMeasuring) Color(0xFFF43F5E) else Color(0xFFE11D48),
                        modifier = Modifier.size(64.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isMeasuring) "--" else bpm.toString(),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = "BPM",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Text(
                    text = if (isMeasuring) "Measuring optical blood pulse..." else "Normal resting pulse",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onMeasure,
                    enabled = !isMeasuring,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isMeasuring) "Sampling Sensor..." else "Trigger Measurement", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Vitals summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                VitalsStatItem("Min BPM", "58", Color(0xFF38BDF8))
                VitalsStatItem("Avg BPM", "$bpm", Color(0xFF10B981))
                VitalsStatItem("Max BPM", "118", Color(0xFFF43F5E))
            }
        }
    }
}

@Composable
fun VitalsStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, color = color)
    }
}

/* ========================================================================= */
/* --- TAB 3: CALIBRATE (MECHANICAL HANDS) ---                               */
/* ========================================================================= */

@Composable
fun CalibrateTab(
    onCalibrate: (Int, Int) -> Unit
) {
    var hour by remember { mutableStateOf(12) }
    var minute by remember { mutableStateOf(0) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mechanical Hand Alignment", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        TextButton(onClick = { hour = 12; minute = 0 }) {
                            Text("Reset 12:00", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "1. Look at where your physical watch hands are pointing.\n2. Set the dials below to match that exact position.\n3. Tap 'Align Hands' and the watch steppers will rotate to current atomic time.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Big Current Set Time Display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF090D16))
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "%02d:%02d".format(hour, minute),
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF38BDF8)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Hour Stepper Buttons
                    Text("Hour Hand:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { hour = if (hour <= 1) 12 else hour - 1 },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text("-1h", color = Color.White)
                        }
                        Button(
                            onClick = { hour = if (hour >= 12) 1 else hour + 1 },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text("+1h", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Minute Stepper Buttons
                    Text("Minute Hand:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { minute = (minute - 5 + 60) % 60 },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("-5m", fontSize = 11.sp, color = Color.White) }
                        Button(
                            onClick = { minute = (minute - 1 + 60) % 60 },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("-1m", fontSize = 11.sp, color = Color.White) }
                        Button(
                            onClick = { minute = (minute + 1) % 60 },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("+1m", fontSize = 11.sp, color = Color.White) }
                        Button(
                            onClick = { minute = (minute + 5) % 60 },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("+5m", fontSize = 11.sp, color = Color.White) }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onCalibrate(if (hour == 12) 0 else hour, minute) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Align Hands to Atomic Time", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 4: OLED NOTIFICATIONS ---                                         */
/* ========================================================================= */

@Composable
fun OledNotificationsTab(
    onPush: (String, String, Int) -> Unit,
    onOpenNotifSettings: () -> Unit
) {
    var title by remember { mutableStateOf("WhatsApp") }
    var message by remember { mutableStateOf("See you at 6:30 PM!") }
    var channel by remember { mutableStateOf(7) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // OLED preview card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Watch OLED Screen Preview", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("96×32 Pixel Monochromatic Matrix", fontSize = 11.sp, color = Color(0xFF64748B))

                    Spacer(modifier = Modifier.height(14.dp))

                    // Pixel OLED Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .border(1.5.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = title.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF10B981)
                            )
                            Text(
                                text = message,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6EE7B7),
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(14) },
                        label = { Text("Sender Title (Max 14 chars)", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it.take(64) },
                        label = { Text("Message Body (Max 64 chars)", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { onPush(title, message, channel) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Push Alert to Watch OLED", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Notification listener access prompt
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Auto-Forward Phone Notifications", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Allow Chronos X to listen to incoming Android notifications (WhatsApp, Calls, SMS) and automatically transmit them to your watch vibration motor and OLED display.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenNotifSettings,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Grant Notification Access", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 5: DEVICE & ALARMS ---                                            */
/* ========================================================================= */

@Composable
fun DeviceSettingsTab(
    stepGoal: Int,
    onSetGoal: (Int) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onToggleTilt: (Boolean) -> Unit,
    onFindWatch: () -> Unit,
    onSetAlarm: (Int, Int, Int, Int, Boolean) -> Unit
) {
    var dndEnabled by remember { mutableStateOf(false) }
    var tiltEnabled by remember { mutableStateOf(true) }
    var goalSlider by remember { mutableStateOf(stepGoal.toFloat()) }

    var alarmHour by remember { mutableStateOf(7) }
    var alarmMin by remember { mutableStateOf(0) }
    var alarmEnabled by remember { mutableStateOf(true) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Find Watch
        item {
            Button(
                onClick = onFindWatch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
            ) {
                Icon(Icons.Filled.Vibration, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Find My Watch (Vibrate)", fontWeight = FontWeight.Bold)
            }
        }

        // Hardware Alarms
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Hardware Alarm #1", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("Stored in watch NVRAM (Vibrates without phone)", fontSize = 11.sp, color = Color(0xFF64748B))

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "%02d:%02d".format(alarmHour, alarmMin),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )

                        Switch(
                            checked = alarmEnabled,
                            onCheckedChange = { alarmEnabled = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { alarmHour = (alarmHour + 1) % 24 },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("+1h", fontSize = 11.sp, color = Color.White) }

                        Button(
                            onClick = { alarmMin = (alarmMin + 5) % 60 },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) { Text("+5m", fontSize = 11.sp, color = Color.White) }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = { onSetAlarm(1, alarmHour, alarmMin, 0xFE, alarmEnabled) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) { Text("Save Alarm", fontSize = 11.sp, color = Color.White) }
                    }
                }
            }
        }

        // Hardware Preferences
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Watch Hardware Controls", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

                    // Tilt to wake
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Tilt-to-Wake Display", fontSize = 13.sp, color = Color.White)
                            Text("Light up OLED on wrist raise", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = tiltEnabled,
                            onCheckedChange = {
                                tiltEnabled = it
                                onToggleTilt(it)
                            }
                        )
                    }

                    Divider(color = Color(0xFF1E293B))

                    // DND
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Do Not Disturb", fontSize = 13.sp, color = Color.White)
                            Text("Mute vibrations & OLED popups", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                        Switch(
                            checked = dndEnabled,
                            onCheckedChange = {
                                dndEnabled = it
                                onToggleDnd(it)
                            }
                        )
                    }

                    Divider(color = Color(0xFF1E293B))

                    // Step goal slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Target Step Goal", fontSize = 13.sp, color = Color.White)
                            Text("${goalSlider.toInt()} steps", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        }
                        Slider(
                            value = goalSlider,
                            onValueChange = { goalSlider = it },
                            onValueChangeFinished = { onSetGoal(goalSlider.toInt()) },
                            valueRange = 3000f..25000f,
                            steps = 21
                        )
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- BOTTOM NAVIGATION BAR ---                                             */
/* ========================================================================= */

@Composable
fun WatchXBottomBar(
    currentTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF0B101D),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        modifier = Modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        AppNavTab.values().forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp)) },
                label = { Text(tab.label, fontSize = 10.sp, fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF00F0FF),
                    selectedTextColor = Color(0xFF00F0FF),
                    unselectedIconColor = Color(0xFF64748B),
                    unselectedTextColor = Color(0xFF64748B),
                    indicatorColor = Color(0xFF00F0FF).copy(alpha = 0.15f)
                )
            )
        }
    }
}

/* ========================================================================= */
/* --- SCAN DEVICES DIALOG ---                                               */
/* ========================================================================= */

@Composable
fun ScanDeviceDialog(
    devices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B2E),
        title = { Text("Discovered BLE Devices", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = Color(0xFF00F0FF), modifier = Modifier.size(28.dp))
                        Text("Scanning for nearby Lenovo Watch X...", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices) { (name, mac) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF090D16))
                                .clickable { onSelect(mac) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text(mac, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                            }
                            Text("Connect", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00F0FF))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

/* ========================================================================= */
/* --- FIND PHONE INCOMING MODAL ---                                         */
/* ========================================================================= */

@Composable
fun FindPhoneModal(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF7F1D1D),
        title = { Text("FIND PHONE TRIGGERED!", color = Color.White, fontWeight = FontWeight.ExtraBold) },
        text = {
            Text(
                "Your Lenovo Watch X button was long-pressed. Alarm is ringing.",
                color = Color(0xFFFECDD3),
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("Dismiss Alarm", color = Color(0xFF7F1D1D), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun WatchXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF090D16),
            surface = Color(0xFF131B2E),
            primary = Color(0xFF00F0FF),
            secondary = Color(0xFF10B981)
        ),
        content = content
    )
}
