package com.lenovo.watchx.companion

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

/* ========================================================================= */
/* --- APPLE DESIGN SYSTEM PALETTE ---                                       */
/* ========================================================================= */

object AppleTheme {
    val Black = Color(0xFF000000)
    val CardDark = Color(0xFF121214)
    val InsetDark = Color(0xFF1C1C1E)
    val Border = Color(0xFF2C2C2E)
    val Divider = Color(0xFF2C2C2E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E93)
    val TextTertiary = Color(0xFF636366)

    val CoralRed = Color(0xFFFF453A)      // Apple Activity Ring Red
    val NeonGreen = Color(0xFF30D158)     // Apple Success / Battery / Activity Green
    val ElectricBlue = Color(0xFF0A84FF)  // Apple System Blue / Activity Blue
    val CardioPink = Color(0xFFFF2D55)    // Apple Health Heart Rate Pink
    val SleepIndigo = Color(0xFF5E5CE6)   // Apple Sleep Purple/Indigo
    val AmberOrange = Color(0xFFFF9F0A)   // Apple Stand / Goal Orange
}

enum class AppNavTab(val label: String, val icon: ImageVector) {
    SUMMARY("Summary", Icons.Filled.Dashboard),
    FITNESS("Fitness", Icons.Filled.DirectionsRun),
    HANDS("Hands", Icons.Filled.Schedule),
    SETTINGS("Watch", Icons.Filled.Watch)
}

class MainActivity : ComponentActivity() {

    private val bleServiceState = mutableStateOf<LenovoWatchBleService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder is LenovoWatchBleService.LocalBinder) {
                bleServiceState.value = binder.getService()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleServiceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()

        try {
            val serviceIntent = Intent(this, LenovoWatchBleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start/bind BLE service safely", e)
        }

        setContent {
            val service by bleServiceState

            // Automatically start BLE scanning if watch is not connected
            LaunchedEffect(service) {
                val s = service ?: return@LaunchedEffect
                if (s.connectionState.value == BleConnectionState.DISCONNECTED) {
                    s.startScan()
                }
            }

            val connState by (service?.connectionState?.collectAsState() ?: remember { mutableStateOf(BleConnectionState.DISCONNECTED) })
            val battery by (service?.batteryLevel?.collectAsState() ?: remember { mutableStateOf(0) })
            val firmware by (service?.firmwareVersion?.collectAsState() ?: remember { mutableStateOf("") })
            val steps by (service?.todaySteps?.collectAsState() ?: remember { mutableStateOf(0) })
            val stepGoal by (service?.stepGoal?.collectAsState() ?: remember { mutableStateOf(10000) })
            val heartRate by (service?.heartRateBpm?.collectAsState() ?: remember { mutableStateOf(0) })
            val isMeasuringHr by (service?.isMeasuringHr?.collectAsState() ?: remember { mutableStateOf(false) })
            val sleepData by (service?.sleepSession?.collectAsState() ?: remember { mutableStateOf(SleepSession()) })
            val findPhoneActive by (service?.findPhoneTrigger?.collectAsState() ?: remember { mutableStateOf(false) })
            val discoveredDevices by (service?.discoveredDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

            val is24Hour by (service?.is24Hour?.collectAsState() ?: remember { mutableStateOf(true) })
            val isMetric by (service?.isMetric?.collectAsState() ?: remember { mutableStateOf(true) })
            val isEnglish by (service?.isEnglish?.collectAsState() ?: remember { mutableStateOf(true) })
            val sedentaryEnabled by (service?.sedentaryEnabled?.collectAsState() ?: remember { mutableStateOf(false) })
            val drinkWaterEnabled by (service?.drinkWaterEnabled?.collectAsState() ?: remember { mutableStateOf(false) })
            val dndEnabled by (service?.dndEnabled?.collectAsState() ?: remember { mutableStateOf(false) })
            val tiltEnabled by (service?.tiltEnabled?.collectAsState() ?: remember { mutableStateOf(true) })
            val cameraActive by (service?.cameraModeActive?.collectAsState() ?: remember { mutableStateOf(false) })

            var selectedTab by remember { mutableStateOf(AppNavTab.SUMMARY) }
            var showScanDialog by remember { mutableStateOf(false) }

            AppleWatchTheme {
                Scaffold(
                    containerColor = AppleTheme.Black,
                    bottomBar = {
                        AppleBottomNavBar(
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
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Apple Watch Hero Header Card
                            AppleHeroWatchHeader(
                                state = connState,
                                battery = battery,
                                firmware = firmware,
                                onPairClick = {
                                    showScanDialog = true
                                    service?.startScan()
                                },
                                onSyncTime = {
                                    service?.syncTime()
                                    Toast.makeText(this@MainActivity, "Time synchronized", Toast.LENGTH_SHORT).show()
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Main Tab Container
                            Box(modifier = Modifier.weight(1f)) {
                                when (selectedTab) {
                                    AppNavTab.SUMMARY -> AppleSummaryTab(
                                        steps = steps,
                                        stepGoal = stepGoal,
                                        heartRate = heartRate,
                                        sleep = sleepData,
                                        battery = battery,
                                        isMetric = isMetric,
                                        onMeasureHr = {
                                            if (connState == BleConnectionState.CONNECTED) {
                                                service?.measureHeartRate()
                                                Toast.makeText(this@MainActivity, "Sampling optical sensor...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connect watch first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onRefreshTelemetry = {
                                            service?.querySteps()
                                            service?.querySleep()
                                            Toast.makeText(this@MainActivity, "Refreshing telemetry...", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    AppNavTab.FITNESS -> AppleFitnessDetailTab(
                                        steps = steps,
                                        stepGoal = stepGoal,
                                        heartRate = heartRate,
                                        isMeasuringHr = isMeasuringHr,
                                        sleep = sleepData,
                                        isMetric = isMetric,
                                        onSetGoal = { service?.setStepGoal(it) },
                                        onMeasureHr = { service?.measureHeartRate() }
                                    )
                                    AppNavTab.HANDS -> AppleCalibrationTab(
                                        onCalibrate = { hour, min ->
                                            if (connState == BleConnectionState.CONNECTED) {
                                                service?.calibrateHands(hour, min)
                                                Toast.makeText(this@MainActivity, "Motors moving hands into place...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connect watch first", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onSyncClock = {
                                            service?.syncTime()
                                            Toast.makeText(this@MainActivity, "Synchronized to atomic time", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    AppNavTab.SETTINGS -> AppleWatchSettingsTab(
                                        is24Hour = is24Hour,
                                        isMetric = isMetric,
                                        isEnglish = isEnglish,
                                        sedentaryEnabled = sedentaryEnabled,
                                        drinkWaterEnabled = drinkWaterEnabled,
                                        dndEnabled = dndEnabled,
                                        tiltEnabled = tiltEnabled,
                                        cameraActive = cameraActive,
                                        onToggle24h = { service?.setTimeFormat(it) },
                                        onToggleMetric = { service?.setUnit(it) },
                                        onToggleEnglish = { service?.setLanguage(it) },
                                        onToggleSedentary = { service?.setSedentary(it) },
                                        onToggleDrinkWater = { service?.setDrinkWater(it) },
                                        onToggleDnd = { service?.setDnd(it) },
                                        onToggleTilt = { service?.setLiftToWake(it) },
                                        onToggleCamera = { service?.setCameraMode(it) },
                                        onFindWatch = {
                                            service?.findMyWatch()
                                            Toast.makeText(this@MainActivity, "Vibrating watch motor...", Toast.LENGTH_SHORT).show()
                                        },
                                        onSetAlarm = { slot, h, m, mask, en ->
                                            service?.setAlarm(slot, h, m, mask, en)
                                            Toast.makeText(this@MainActivity, "Alarm written to NVRAM", Toast.LENGTH_SHORT).show()
                                        },
                                        onSendTestNotification = { title, body ->
                                            service?.pushNotification(title, body)
                                            Toast.makeText(this@MainActivity, "Sent to watch OLED", Toast.LENGTH_SHORT).show()
                                        },
                                        onOpenSystemNotifSettings = {
                                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    )
                                }
                            }
                        }

                        // Scanning Modal
                        if (showScanDialog) {
                            AppleScanDialog(
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

                        // Find My Phone Alert Sheet
                        if (findPhoneActive) {
                            AppleFindPhoneSheet(onDismiss = { service?.dismissFindPhone() })
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            // Ignore unbind if not registered
        }
    }
}

/* ========================================================================= */
/* --- APPLE-GRADE HERO WATCH HEADER ---                                     */
/* ========================================================================= */

@Composable
fun AppleHeroWatchHeader(
    state: BleConnectionState,
    battery: Int,
    firmware: String,
    onPairClick: () -> Unit,
    onSyncTime: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppleTheme.CardDark),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, AppleTheme.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Minimal Watch Dial Glyph
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(AppleTheme.InsetDark)
                        .border(1.dp, AppleTheme.Border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Watch,
                        contentDescription = "Watch",
                        tint = if (state == BleConnectionState.CONNECTED) AppleTheme.TextPrimary else AppleTheme.TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Lenovo Watch X",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppleTheme.TextPrimary,
                            letterSpacing = (-0.3).sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        // Connection Pill Indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (state) {
                                            BleConnectionState.CONNECTED -> AppleTheme.NeonGreen
                                            BleConnectionState.CONNECTING -> AppleTheme.AmberOrange
                                            BleConnectionState.SCANNING -> AppleTheme.ElectricBlue
                                            BleConnectionState.DISCONNECTED -> AppleTheme.TextSecondary
                                        }
                                    )
                            )
                            Text(
                                text = when (state) {
                                    BleConnectionState.CONNECTED -> "Connected"
                                    BleConnectionState.CONNECTING -> "Connecting..."
                                    BleConnectionState.SCANNING -> "Scanning..."
                                    BleConnectionState.DISCONNECTED -> "Offline"
                                },
                                fontSize = 12.sp,
                                color = AppleTheme.TextSecondary
                            )
                        }

                        Text("•", fontSize = 10.sp, color = AppleTheme.Border)

                        // Battery Pill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Filled.BatteryFull,
                                contentDescription = null,
                                tint = AppleTheme.NeonGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (battery > 0) "$battery%" else "--%",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppleTheme.TextSecondary
                            )
                        }
                    }
                }
            }

            // Action Pill Button
            if (state == BleConnectionState.CONNECTED) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppleTheme.InsetDark)
                        .clickable { onSyncTime() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Sync Time",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppleTheme.ElectricBlue
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppleTheme.ElectricBlue)
                        .clickable { onPairClick() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Pair",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 1: APPLE HEALTH SUMMARY ---                                       */
/* ========================================================================= */

@Composable
fun AppleSummaryTab(
    steps: Int,
    stepGoal: Int,
    heartRate: Int,
    sleep: SleepSession,
    battery: Int,
    isMetric: Boolean,
    onMeasureHr: () -> Unit,
    onRefreshTelemetry: () -> Unit
) {
    val distance = if (isMetric) {
        "%.2f km".format(steps * 0.00078)
    } else {
        "%.2f mi".format(steps * 0.00078 * 0.621371)
    }
    val calories = (steps * 0.042).toInt()
    val progressPercent = ((steps.toFloat() / stepGoal.coerceAtLeast(1)) * 100).toInt()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Apple Fitness Activity Ring Hero Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = AppleTheme.CardDark),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, AppleTheme.Border)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ACTIVITY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = AppleTheme.CoralRed
                        )
                        Text(
                            text = "$progressPercent% OF GOAL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppleTheme.TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Numeric Stats
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column {
                                Text(
                                    text = "%,d".format(steps),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-1).sp,
                                    color = AppleTheme.TextPrimary
                                )
                                Text(
                                    text = "STEPS TODAY",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.8.sp,
                                    color = AppleTheme.TextSecondary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column {
                                    Text(
                                        text = "$calories",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleTheme.AmberOrange
                                    )
                                    Text("KCAL", fontSize = 10.sp, color = AppleTheme.TextSecondary)
                                }
                                Column {
                                    Text(
                                        text = distance,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppleTheme.ElectricBlue
                                    )
                                    Text("DISTANCE", fontSize = 10.sp, color = AppleTheme.TextSecondary)
                                }
                            }
                        }

                        // Concentric Activity Ring Canvas
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                            AppleActivityRingCanvas(
                                progress = (steps.toFloat() / stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
                            )
                            Icon(
                                Icons.Filled.DirectionsWalk,
                                contentDescription = null,
                                tint = AppleTheme.CoralRed,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Apple Health 2x2 Metric Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Heart Rate Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.CardDark),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AppleTheme.Border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = null,
                                tint = AppleTheme.CardioPink,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "HEART RATE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTheme.CardioPink,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (heartRate > 0) "$heartRate" else "--",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTheme.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "BPM",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppleTheme.TextSecondary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppleTheme.InsetDark)
                                .clickable { onMeasureHr() }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Measure", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.CardioPink)
                        }
                    }
                }

                // Sleep Metric Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = AppleTheme.CardDark),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AppleTheme.Border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Bedtime,
                                contentDescription = null,
                                tint = AppleTheme.SleepIndigo,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "SLEEP",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTheme.SleepIndigo,
                                letterSpacing = 0.5.sp
                            )
                        }

                        val hours = sleep.totalMinutes / 60
                        val mins = sleep.totalMinutes % 60
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${hours}h ${mins}m",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppleTheme.TextPrimary
                            )
                        }

                        Text(
                            text = "Score: ${sleep.score}/100",
                            fontSize = 11.sp,
                            color = AppleTheme.TextSecondary
                        )
                    }
                }
            }
        }

        // Quick Sync Strip
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppleTheme.InsetDark)
                    .clickable { onRefreshTelemetry() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, tint = AppleTheme.ElectricBlue, modifier = Modifier.size(15.dp))
                    Text("Refresh Sensor Data", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppleTheme.ElectricBlue)
                }
            }
        }
    }
}

@Composable
fun AppleActivityRingCanvas(progress: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 10.dp.toPx()
        // Background track
        drawCircle(
            color = Color(0xFF2C2C2E),
            style = Stroke(width = strokeWidth)
        )
        // Active progress arc
        drawArc(
            color = AppleTheme.CoralRed,
            startAngle = -90f,
            sweepAngle = progress * 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}

/* ========================================================================= */
/* --- TAB 2: FITNESS & HEALTH DETAIL ---                                    */
/* ========================================================================= */

@Composable
fun AppleFitnessDetailTab(
    steps: Int,
    stepGoal: Int,
    heartRate: Int,
    isMeasuringHr: Boolean,
    sleep: SleepSession,
    isMetric: Boolean,
    onSetGoal: (Int) -> Unit,
    onMeasureHr: () -> Unit
) {
    var goalValue by remember { mutableStateOf(stepGoal.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step Goal Adjuster
        item {
            AppleGroupCard(title = "Daily Target") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Daily Step Goal", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.TextPrimary)
                        Text(
                            text = "%,d".format(goalValue.toInt()),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AppleTheme.CoralRed
                        )
                    }

                    Slider(
                        value = goalValue,
                        onValueChange = { goalValue = it },
                        onValueChangeFinished = { onSetGoal(goalValue.toInt()) },
                        valueRange = 3000f..25000f,
                        steps = 21,
                        colors = SliderDefaults.colors(
                            thumbColor = AppleTheme.CoralRed,
                            activeTrackColor = AppleTheme.CoralRed,
                            inactiveTrackColor = AppleTheme.InsetDark
                        )
                    )

                    Text(
                        text = "Synced directly to watch pedometer counter",
                        fontSize = 11.sp,
                        color = AppleTheme.TextSecondary
                    )
                }
            }
        }

        // Live Heart Rate Monitor
        item {
            AppleGroupCard(title = "Cardio Health") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Optical PPG Sensor", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.TextPrimary)
                            Text("Measures pulse via green photoplethysmography", fontSize = 11.sp, color = AppleTheme.TextSecondary)
                        }

                        Text(
                            text = if (heartRate > 0) "$heartRate BPM" else "-- BPM",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppleTheme.CardioPink
                        )
                    }

                    Button(
                        onClick = onMeasureHr,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.CardioPink)
                    ) {
                        Text(
                            text = if (isMeasuringHr) "Sampling..." else "Start Heart Rate Reading",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Sleep Breakdown Bars
        item {
            AppleGroupCard(title = "Sleep Architecture") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val total = sleep.totalMinutes.coerceAtLeast(1)
                    val deepPct = (sleep.deepMinutes * 100 / total)
                    val lightPct = (sleep.lightMinutes * 100 / total)
                    val awakePct = (sleep.awakeMinutes * 100 / total)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                    ) {
                        Box(modifier = Modifier.weight(deepPct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(AppleTheme.SleepIndigo))
                        Box(modifier = Modifier.weight(lightPct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(Color(0xFF818CF8)))
                        Box(modifier = Modifier.weight(awakePct.coerceAtLeast(1).toFloat()).fillMaxHeight().background(Color(0xFF334155)))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Deep Sleep", fontSize = 11.sp, color = AppleTheme.TextSecondary)
                            Text("${sleep.deepMinutes / 60}h ${sleep.deepMinutes % 60}m", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleTheme.SleepIndigo)
                        }
                        Column {
                            Text("Light Sleep", fontSize = 11.sp, color = AppleTheme.TextSecondary)
                            Text("${sleep.lightMinutes / 60}h ${sleep.lightMinutes % 60}m", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF818CF8))
                        }
                        Column {
                            Text("Awake", fontSize = 11.sp, color = AppleTheme.TextSecondary)
                            Text("${sleep.awakeMinutes}m", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppleTheme.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 3: PHYSICAL HANDS CALIBRATION ---                                 */
/* ========================================================================= */

@Composable
fun AppleCalibrationTab(
    onCalibrate: (Int, Int) -> Unit,
    onSyncClock: () -> Unit
) {
    var hour by remember { mutableStateOf(12) }
    var minute by remember { mutableStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppleGroupCard(title = "Mechanical Alignment") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Align Watch Needles",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleTheme.TextPrimary
                    )
                    Text(
                        text = "Look at where your physical mechanical hands are pointing on the watch face. Adjust below to match their current positions, then calibrate.",
                        fontSize = 12.sp,
                        color = AppleTheme.TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    // Dial Display
                    Text(
                        text = "%02d : %02d".format(hour, minute),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = AppleTheme.ElectricBlue
                    )

                    // Stepper Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { hour = if (hour <= 1) 12 else hour - 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("-1h", color = Color.White, fontSize = 12.sp) }

                        Button(
                            onClick = { hour = if (hour >= 12) 1 else hour + 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("+1h", color = Color.White, fontSize = 12.sp) }

                        Button(
                            onClick = { minute = (minute + 59) % 60 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("-1m", color = Color.White, fontSize = 12.sp) }

                        Button(
                            onClick = { minute = (minute + 1) % 60 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("+1m", color = Color.White, fontSize = 12.sp) }

                        Button(
                            onClick = { minute = (minute + 5) % 60 },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("+5m", color = Color.White, fontSize = 12.sp) }
                    }

                    Button(
                        onClick = { onCalibrate(hour, minute) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.ElectricBlue)
                    ) {
                        Text("Calibrate Mechanical Motors", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }

        item {
            AppleGroupCard(title = "Atomic Clock") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Once needles are aligned, synchronize with phone network time.",
                        fontSize = 12.sp,
                        color = AppleTheme.TextSecondary
                    )
                    Button(
                        onClick = onSyncClock,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark)
                    ) {
                        Text("Sync to Real Time", color = AppleTheme.TextPrimary)
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- TAB 4: APPLE-GRADE WATCH SETTINGS & CUSTOMIZATION ---                 */
/* ========================================================================= */

@Composable
fun AppleWatchSettingsTab(
    is24Hour: Boolean,
    isMetric: Boolean,
    isEnglish: Boolean,
    sedentaryEnabled: Boolean,
    drinkWaterEnabled: Boolean,
    dndEnabled: Boolean,
    tiltEnabled: Boolean,
    cameraActive: Boolean,
    onToggle24h: (Boolean) -> Unit,
    onToggleMetric: (Boolean) -> Unit,
    onToggleEnglish: (Boolean) -> Unit,
    onToggleSedentary: (Boolean) -> Unit,
    onToggleDrinkWater: (Boolean) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onToggleTilt: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    onFindWatch: () -> Unit,
    onSetAlarm: (slot: Int, hour: Int, min: Int, mask: Int, enabled: Boolean) -> Unit,
    onSendTestNotification: (title: String, body: String) -> Unit,
    onOpenSystemNotifSettings: () -> Unit
) {
    var alarmHour by remember { mutableStateOf(7) }
    var alarmMin by remember { mutableStateOf(30) }
    var alarmEnabled by remember { mutableStateOf(true) }

    var testTitle by remember { mutableStateOf("Test Alert") }
    var testBody by remember { mutableStateOf("Hello Watch X!") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // PREFERENCES & LOCALIZATION
        item {
            AppleGroupCard(title = "Display & Units") {
                // 12h / 24h Time Format
                AppleSettingRow(
                    icon = Icons.Filled.AccessTime,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.ElectricBlue,
                    title = "Time Format",
                    subtitle = if (is24Hour) "24-Hour (14:00)" else "12-Hour (2:00 PM)",
                    trailing = {
                        AppleSegmentedPicker(
                            options = listOf("12h", "24h"),
                            selectedIndex = if (is24Hour) 1 else 0,
                            onSelect = { onToggle24h(it == 1) }
                        )
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // Metric vs Imperial
                AppleSettingRow(
                    icon = Icons.Filled.Straighten,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.NeonGreen,
                    title = "Distance Units",
                    subtitle = if (isMetric) "Kilometers (km)" else "Miles (mi)",
                    trailing = {
                        AppleSegmentedPicker(
                            options = listOf("km", "mi"),
                            selectedIndex = if (isMetric) 0 else 1,
                            onSelect = { onToggleMetric(it == 0) }
                        )
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // Language
                AppleSettingRow(
                    icon = Icons.Filled.Language,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.SleepIndigo,
                    title = "Watch Language",
                    subtitle = if (isEnglish) "English" else "中文 (Chinese)",
                    trailing = {
                        AppleSegmentedPicker(
                            options = listOf("EN", "中文"),
                            selectedIndex = if (isEnglish) 0 else 1,
                            onSelect = { onToggleEnglish(it == 0) }
                        )
                    }
                )
            }
        }

        // HEALTH REMINDERS
        item {
            AppleGroupCard(title = "Health Reminders") {
                // Sedentary Alert
                AppleSettingRow(
                    icon = Icons.Filled.AccessibilityNew,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.AmberOrange,
                    title = "Sedentary Reminder",
                    subtitle = "Vibrates if inactive for over 60 mins",
                    trailing = {
                        Switch(
                            checked = sedentaryEnabled,
                            onCheckedChange = onToggleSedentary,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // Hydration Alert
                AppleSettingRow(
                    icon = Icons.Filled.LocalDrink,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.ElectricBlue,
                    title = "Drink Water Alert",
                    subtitle = "Reminds to hydrate every 2 hours",
                    trailing = {
                        Switch(
                            checked = drinkWaterEnabled,
                            onCheckedChange = onToggleDrinkWater,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }
                )
            }
        }

        // HARDWARE CONTROLS & GESTURES
        item {
            AppleGroupCard(title = "Hardware Gestures") {
                // Tilt to Wake
                AppleSettingRow(
                    icon = Icons.Filled.WbIncandescent,
                    iconTint = Color.White,
                    iconBackground = Color(0xFFFFD60A),
                    title = "Lift Wrist to Wake",
                    subtitle = "Lights up OLED display on wrist raise",
                    trailing = {
                        Switch(
                            checked = tiltEnabled,
                            onCheckedChange = onToggleTilt,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // Do Not Disturb
                AppleSettingRow(
                    icon = Icons.Filled.DoNotDisturbOn,
                    iconTint = Color.White,
                    iconBackground = Color(0xFF64748B),
                    title = "Do Not Disturb",
                    subtitle = "Silences all OLED vibration alerts",
                    trailing = {
                        Switch(
                            checked = dndEnabled,
                            onCheckedChange = onToggleDnd,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // Remote Camera Shutter
                AppleSettingRow(
                    icon = Icons.Filled.CameraAlt,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.CardioPink,
                    title = "Camera Shutter Mode",
                    subtitle = "Shake watch to trigger phone camera",
                    trailing = {
                        Switch(
                            checked = cameraActive,
                            onCheckedChange = onToggleCamera,
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }
                )
            }
        }

        // HARDWARE VIBRATION ALARM #1
        item {
            AppleGroupCard(title = "Hardware Alarm (NVRAM)") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            color = AppleTheme.TextPrimary
                        )
                        Switch(
                            checked = alarmEnabled,
                            onCheckedChange = { alarmEnabled = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = AppleTheme.NeonGreen)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { alarmHour = (alarmHour + 1) % 24 },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark)
                        ) { Text("+1h", fontSize = 11.sp, color = Color.White) }

                        Button(
                            onClick = { alarmMin = (alarmMin + 5) % 60 },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark)
                        ) { Text("+5m", fontSize = 11.sp, color = Color.White) }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = { onSetAlarm(1, alarmHour, alarmMin, 0xFE, alarmEnabled) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.ElectricBlue)
                        ) { Text("Save Alarm", fontSize = 11.sp, color = Color.White) }
                    }
                }
            }
        }

        // NOTIFICATIONS & FIND WATCH
        item {
            AppleGroupCard(title = "Alerts & Find Watch") {
                AppleSettingRow(
                    icon = Icons.Filled.NotificationsActive,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.ElectricBlue,
                    title = "System Notification Listener",
                    subtitle = "Forward WhatsApp, Calls, SMS to watch",
                    trailing = {
                        Button(
                            onClick = onOpenSystemNotifSettings,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.InsetDark)
                        ) { Text("Grant", fontSize = 11.sp, color = AppleTheme.ElectricBlue) }
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                AppleSettingRow(
                    icon = Icons.Filled.Vibration,
                    iconTint = Color.White,
                    iconBackground = AppleTheme.AmberOrange,
                    title = "Find My Watch",
                    subtitle = "Vibrates watch motor continuously",
                    trailing = {
                        Button(
                            onClick = onFindWatch,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.AmberOrange)
                        ) { Text("Buzzer", fontSize = 11.sp, color = Color.White) }
                    }
                )

                Divider(color = AppleTheme.Divider, thickness = 0.5.dp)

                // OLED Test Sender
                Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OLED Screen Previewer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppleTheme.TextPrimary)
                    OutlinedTextField(
                        value = testTitle,
                        onValueChange = { testTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppleTheme.ElectricBlue,
                            unfocusedBorderColor = AppleTheme.Border
                        )
                    )
                    OutlinedTextField(
                        value = testBody,
                        onValueChange = { testBody = it },
                        label = { Text("Message Body") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppleTheme.ElectricBlue,
                            unfocusedBorderColor = AppleTheme.Border
                        )
                    )
                    Button(
                        onClick = { onSendTestNotification(testTitle, testBody) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleTheme.ElectricBlue)
                    ) {
                        Text("Send to OLED Display", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/* ========================================================================= */
/* --- REUSABLE APPLE UI COMPONENTS ---                                      */
/* ========================================================================= */

@Composable
fun AppleGroupCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = AppleTheme.TextSecondary,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = AppleTheme.CardDark),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, AppleTheme.Border)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                content = content
            )
        }
    }
}

@Composable
fun AppleSettingRow(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppleTheme.TextPrimary)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp, color = AppleTheme.TextSecondary)
                }
            }
        }
        trailing()
    }
}

@Composable
fun AppleSegmentedPicker(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AppleTheme.InsetDark)
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (index == selectedIndex) Color(0xFF3A3A3C) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == selectedIndex) AppleTheme.TextPrimary else AppleTheme.TextSecondary
                )
            }
        }
    }
}

@Composable
fun AppleBottomNavBar(
    currentTab: AppNavTab,
    onTabSelected: (AppNavTab) -> Unit
) {
    NavigationBar(
        containerColor = AppleTheme.CardDark,
        tonalElevation = 0.dp,
        modifier = Modifier.border(
            width = 0.5.dp,
            color = AppleTheme.Border,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        )
    ) {
        AppNavTab.values().forEach { tab ->
            val selected = currentTab == tab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) AppleTheme.ElectricBlue else AppleTheme.TextSecondary
                    )
                },
                label = {
                    Text(
                        tab.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) AppleTheme.ElectricBlue else AppleTheme.TextSecondary
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = AppleTheme.ElectricBlue.copy(alpha = 0.15f)
                )
            )
        }
    }
}

@Composable
fun AppleScanDialog(
    devices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppleTheme.CardDark,
        title = { Text("Nearby Devices", color = AppleTheme.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = AppleTheme.ElectricBlue, modifier = Modifier.size(28.dp))
                        Text("Searching for Lenovo Watch...", fontSize = 12.sp, color = AppleTheme.TextSecondary)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices) { (name, mac) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppleTheme.InsetDark)
                                .clickable { onSelect(mac) }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(name, fontWeight = FontWeight.Bold, color = AppleTheme.TextPrimary, fontSize = 14.sp)
                                Text(mac, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppleTheme.TextSecondary)
                            }
                            Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppleTheme.ElectricBlue)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppleTheme.TextSecondary)
            }
        }
    )
}

@Composable
fun AppleFindPhoneSheet(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF381010),
        title = { Text("Watch Button Pressed", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Find Phone alert triggered from your Lenovo Watch physical crown button.",
                color = Color(0xFFFCA5A5),
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text("Dismiss", color = Color(0xFF991B1B), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun AppleWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AppleTheme.Black,
            surface = AppleTheme.CardDark,
            primary = AppleTheme.ElectricBlue,
            secondary = AppleTheme.NeonGreen
        ),
        content = content
    )
}
