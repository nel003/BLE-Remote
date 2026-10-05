package com.example.bleremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bleremote.ui.theme.*
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BLERemoteTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CloudWhite
                ) {
                    BleRemoteApp()
                }
            }
        }
    }
}

@Composable
fun BleRemoteApp() {
    val viewModel: BleViewModel = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current

    val connectionState by viewModel.connectionState.collectAsState()
    val lastMessage by viewModel.lastMessage.collectAsState()
    val isOn by viewModel.isOn.collectAsState()

    var permissionsGranted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            startAndBindService(context, viewModel)
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            permissionsGranted = true
            startAndBindService(context, viewModel)
        } else {
            launcher.launch(permissionsToRequest.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.unbindService(context)
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val isDrawerClosed = drawerState.isClosed

    AppDrawer(lastMessage, drawerState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Squishy Pill Status
            CloudStatusPill(connectionState)

            // Main Control: Claymorphic Power Button
            SquishyPowerButton(
                isConnected = connectionState.equals("Connected", ignoreCase = true),
                isDrawerClosed = isDrawerClosed,
                viewModel = viewModel
            )

            // Secondary Controls: Claymorphic Grid
            val isConnected = connectionState.equals("Connected", ignoreCase = true)
            val isActionsEnabled = isConnected && isDrawerClosed && isOn
            CloudActionGrid(viewModel, isActionsEnabled)

            // Footer Detail
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(6.dp)
                    .graphicsLayer { alpha = if (isConnected) 0.6f else 0.3f }
                    .background(CloudShadow, CircleShape)
            )
        }
    }
}

@Composable
fun CloudStatusPill(state: String) {
    val isConnected = state.equals("Connected", ignoreCase = true)
    
    // Claymorphic Pill (Outer Shadow + Inner Highlight)
    Box(
        modifier = Modifier
            .padding(top = 24.dp)
            .graphicsLayer {
                shadowElevation = 8.dp.toPx()
                shape = CircleShape
                clip = true
            }
            .background(CloudWhite, CircleShape)
            .border(
                width = 2.dp,
                color = CloudGlow.copy(alpha = 0.8f),
                shape = CircleShape
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .shadow(4.dp, CircleShape, ambientColor = CloudShadow, spotColor = CloudShadow)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isConnected) SoftGrass else Color(0xFFFF8A80),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = state.uppercase(),
                color = SoftCharcoal.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun SquishyPowerButton(isConnected: Boolean, isDrawerClosed: Boolean, viewModel: BleViewModel) {
    val isOn by viewModel.isOn.collectAsState()
    var isPressed by remember { mutableStateOf(false) }
    
    // Bouncy Spring Animation
    val scale by animateFloatAsState(
        targetValue = if (isPressed && isConnected && isDrawerClosed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "squishScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (isConnected) 1f else 0.5f
            }
            .size(300.dp)
            .pointerInput(isConnected, isDrawerClosed) {
                if (!isConnected || !isDrawerClosed) return@pointerInput
                detectTapGestures(
                    onTap = {
                        println("Normal Click!")
                        viewModel.mainToggle()
                    },
                    onLongPress = {
                        println("Long Press Detected!")
                        if (isOn) viewModel.start()
                    },
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()

                        isPressed = false
                        println("Long Press release!")
                        viewModel.stop()
                    }
                )
            }
    ) {
        // Claymorphic Layers
        // 1. Outer Deep Shadow
        Box(
            modifier = Modifier
                .size(220.dp)
                .shadow(
                    elevation = if (isPressed) 4.dp else 24.dp,
                    shape = CircleShape,
                    ambientColor = CloudShadow,
                    spotColor = CloudShadow
                )
                .background(CloudWhite, CircleShape)
        )
        
        // 2. Inner Light Highlight (Simulated with border and radial gradient)
        Box(
            modifier = Modifier
                .size(220.dp)
                .border(4.dp, CloudGlow.copy(alpha = 0.9f), CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CloudGlow.copy(alpha = 0.5f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(x = 150f, y = 150f)
                    ),
                    CircleShape
                )
        )

        // Icon
        Icon(
            imageVector = Icons.Outlined.PowerSettingsNew,
            contentDescription = "Power",
            tint = if (isOn) SoftGrass else InactiveGrey,
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer {
                    // Small "bounce" for the icon too
                    translationY = if (isPressed) 4f else 0f
                }
        )
    }
}

@Composable
fun CloudActionGrid(viewModel: BleViewModel, isEnabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquishyButton(Icons.Outlined.WarningAmber, "HAZARD", viewModel, isEnabled) { viewModel.hazardToggle() }
        SquishyButton(Icons.Outlined.SpeakerPhone, "HORN", viewModel, isEnabled) { /* HORN ACTION */ }
        SquishyButton(Icons.Outlined.Gesture, "U-BOX", viewModel, isEnabled) { /* U-BOX ACTION */ }
    }
}

@Composable
fun SquishyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    viewModel: BleViewModel,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val isHazard by viewModel.isHazard.collectAsState()
    
    // Determine if this specific button should show active state
    val isActive = if (label == "HAZARD") isHazard else false
    val activeColor = if (label == "HAZARD") WarningOrange else SoftGrass

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && isEnabled) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "buttonSquish"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.graphicsLayer { alpha = if (isEnabled) 1f else 0.3f }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .size(72.dp)
                .pointerInput(isEnabled) {
                    if (!isEnabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            if(label == "HORN") {
                                viewModel.hornOn()
                            }

                            if (label == "U-BOX") {
                                viewModel.openSeat()
                            }

                            tryAwaitRelease()

                            if(label == "HORN") {
                                viewModel.hornOff()
                            }

                            isPressed = false
                            onClick()
                        }
                    )
                }
                .shadow(
                    elevation = if (isPressed) 2.dp else 12.dp,
                    shape = CircleShape,
                    ambientColor = CloudShadow,
                    spotColor = CloudShadow
                )
                .background(
                    if (isActive) activeColor.copy(alpha = 0.2f) else CloudWhite,
                    CircleShape
                )
                .border(2.dp, if (isActive) activeColor.copy(alpha = 0.5f) else CloudGlow.copy(alpha = 0.7f), CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else SoftCharcoal.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else SoftCharcoal.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
        )
    }
}

private fun startAndBindService(context: android.content.Context, viewModel: BleViewModel) {
    val intent = Intent(context, BleService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    viewModel.bindService(context)
}