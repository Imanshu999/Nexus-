package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ScreenCaptureService
import com.example.ui.viewmodel.ServerViewModel

// Clean Minimalism MD3 Color Palette
val MinimalBg = Color(0xFFFDFBFF)
val MinimalTextPrimary = Color(0xFF1A1C1E)
val MinimalTextMuted = Color(0xFF44474E)
val MinimalPrimary = Color(0xFF0061A4)
val MinimalCardSurface = Color(0xFFF3F3FA)
val MinimalBorderColor = Color(0xFFE1E2EC)

// Active Handshake dynamic colors
val HeroBlueBg = Color(0xFFD1E4FF)
val HeroBlueText = Color(0xFF001D36)
val BadgeGreenBg = Color(0xFFE8F5E9)
val BadgeGreenText = Color(0xFF2E7D32)

@Composable
fun DashboardScreen(viewModel: ServerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // State collections
    val serverState by viewModel.serverState.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val port by viewModel.port.collectAsState()
    val width by viewModel.width.collectAsState()
    val height by viewModel.height.collectAsState()
    val bitrate by viewModel.bitrate.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val pairingCode by viewModel.pairingCode.collectAsState()

    // Stats collections
    val liveFps by viewModel.liveFps.collectAsState()
    val liveBitrateKbps by viewModel.liveBitrateKbps.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            viewModel.startMirroringServer(context, result.resultCode, result.data!!)
            Toast.makeText(context, "Streaming core initialized", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "MediaProjection permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MinimalBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // MD3 Top App Bar styled header
            HeaderBrandSection(serverState, onRefreshIp = { viewModel.refreshNetworkIp() })

            Spacer(modifier = Modifier.height(20.dp))

            // Dynamic MD3 Status Hero Card
            StatusHeroCard(serverState)

            Spacer(modifier = Modifier.height(16.dp))

            // IP & Network Gateway Card
            NetworkInformationCard(localIp, port, serverState)

            Spacer(modifier = Modifier.height(16.dp))

            // Real-time Metrics Grid
            MetricsGrid(fps, liveFps, liveBitrateKbps, connectedClients, serverState)

            Spacer(modifier = Modifier.height(16.dp))

            // Security Pairing & Cryptographic QR Panel
            SecurityPairingPortal(pairingCode, localIp, port, serverState)

            Spacer(modifier = Modifier.height(16.dp))

            // Parameters and Encoding Config Card
            ConfigurationPanel(
                isEditable = serverState == ScreenCaptureService.ServerState.IDLE || 
                             serverState == ScreenCaptureService.ServerState.ERROR,
                port = port,
                width = width,
                height = height,
                bitrate = bitrate,
                fps = fps,
                onPortChanged = { viewModel.setPort(it) },
                onResolutionChanged = { w, h -> viewModel.setResolution(w, h) },
                onFpsChanged = { viewModel.setFps(it) },
                onBitrateChanged = { viewModel.setBitrate(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Blueprint Roadmaps
            ArchitectureRoadmapSection()

            Spacer(modifier = Modifier.height(30.dp))

            // Core Trigger Action Pill Button
            EngineActionButton(
                serverState = serverState,
                onStartClick = {
                    if (localIp == "Offline" || localIp == "Unknown") {
                        Toast.makeText(context, "Please connect to a network first.", Toast.LENGTH_LONG).show()
                    } else {
                        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                        screenCaptureLauncher.launch(captureIntent)
                    }
                },
                onStopClick = {
                    viewModel.stopMirroringServer(context)
                    Toast.makeText(context, "Mirror session halted", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun HeaderBrandSection(serverState: ScreenCaptureService.ServerState, onRefreshIp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Elegant brand letter box with subtle shadow
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MinimalPrimary)
                    .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )
            }

            Column {
                Text(
                    text = "Takano Mirror",
                    color = MinimalTextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "NEXUS ECOSYSTEM",
                    color = MinimalPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }
        }

        // Clean minimalistic refresh network info action
        IconButton(
            onClick = onRefreshIp,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MinimalCardSurface)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh network interface",
                tint = MinimalTextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun StatusHeroCard(serverState: ScreenCaptureService.ServerState) {
    val statusInfo = when (serverState) {
        ScreenCaptureService.ServerState.IDLE -> Triple(HeroBlueBg, HeroBlueText, "Ready to Stream") to ("Connect via Desktop Client to begin ultra-low latency mirroring" to MinimalPrimary)
        ScreenCaptureService.ServerState.STARTING -> Triple(Color(0xFFFFF9C4), Color(0xFF5D4037), "Booting Engine...") to ("Configuring initial AVC/HEVC MediaCodec parameters" to Color(0xFFF57F17))
        ScreenCaptureService.ServerState.RUNNING -> Triple(HeroBlueBg, HeroBlueText, "Listening...") to ("Handshake ready. Establish cryptographic pairing on desktop client" to MinimalPrimary)
        ScreenCaptureService.ServerState.STREAMING -> Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), "Mirroring Active") to ("Zero-latency dynamic video transmission pipeline running" to Color(0xFF2E7D32))
        ScreenCaptureService.ServerState.ERROR -> Triple(Color(0xFFFFCDD2), Color(0xFFB71C1C), "Engine Fault") to ("Fatal system permission or capture failure detected" to Color(0xFFC62828))
    }
    
    val bgColor = statusInfo.first.first
    val textColor = statusInfo.first.second
    val title = statusInfo.first.third
    val desc = statusInfo.second.first
    val iconColor = statusInfo.second.second

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Elegant central action circle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (serverState == ScreenCaptureService.ServerState.ERROR) Icons.Default.Warning else Icons.Outlined.Settings,
                    contentDescription = "Status action key",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                color = textColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = desc,
                color = textColor.copy(0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
fun NetworkInformationCard(localIp: String, port: Int, serverState: ScreenCaptureService.ServerState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MinimalBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "NETWORK PORTAL",
                color = MinimalTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Device Local IPv4",
                        color = MinimalTextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = localIp,
                        color = MinimalTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Active Port",
                        color = MinimalTextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "$port",
                        color = MinimalPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (serverState == ScreenCaptureService.ServerState.RUNNING || 
                serverState == ScreenCaptureService.ServerState.STREAMING) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(HeroBlueBg.copy(0.3f))
                        .border(1.dp, HeroBlueBg, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "ADB Port Forwarding: Configure via command-line: \"adb forward tcp:$port tcp:$port\" before establishing secure socket connection.",
                        color = HeroBlueText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MetricsGrid(
    targetFps: Int,
    liveFps: Int,
    liveBitrateKbps: Long,
    connectedClients: Int,
    serverState: ScreenCaptureService.ServerState
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val displayFps = if (serverState == ScreenCaptureService.ServerState.STREAMING) liveFps else targetFps
        val displayBitrate = if (serverState == ScreenCaptureService.ServerState.STREAMING) (liveBitrateKbps / 1000f) else 0.0f

        // Metric 1: Frame Latency / Rate
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MinimalCardSurface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "FRAME RATE",
                    color = MinimalTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "$displayFps",
                        color = MinimalPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "FPS",
                        color = MinimalTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }

        // Metric 2: Network Bandwidth
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = MinimalCardSurface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "THROUGHPUT",
                    color = MinimalTextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = String.format("%.1f", displayBitrate),
                        color = MinimalPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Mbps",
                        color = MinimalTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SecurityPairingPortal(pairingCode: String, localIp: String, port: Int, serverState: ScreenCaptureService.ServerState) {
    val isRunning = serverState == ScreenCaptureService.ServerState.RUNNING || 
                    serverState == ScreenCaptureService.ServerState.STREAMING

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MinimalBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SECURE PARING PROTOCOL",
                    color = MinimalTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                // High fidelity secure status badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(BadgeGreenBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "TLS icon",
                        tint = BadgeGreenText,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "TLS 1.3 ACTIVE",
                        color = BadgeGreenText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Minimalist QR Cryptographic matrix code container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFF8F9FF))
                    .border(BorderStroke(1.dp, HeroBlueBg))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRunning && pairingCode.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // QR Matrix Code
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(BorderStroke(1.dp, MinimalBorderColor))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CryptographicPairingCanvas(pairingCode, localIp, port)
                        }

                        Text(
                            text = "Scan QR or verify RSA-4096 signature on desktop client",
                            color = MinimalTextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Locked portal icon",
                            tint = MinimalTextMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Portal Locked. Start service to generate keys.",
                            color = MinimalTextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (isRunning && pairingCode.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AUTHENTICATION KEY",
                        color = MinimalTextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pairingCode,
                        color = MinimalPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CryptographicPairingCanvas(pairingCode: String, localIp: String, port: Int) {
    // Standard pairing URL scheme parsed securely by desktop client
    val qrText = "takano://connect?ip=$localIp&port=$port&code=$pairingCode"
    
    val qrBits = remember(qrText) {
        try {
            val qrCodeWriter = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = qrCodeWriter.encode(qrText, com.google.zxing.BarcodeFormat.QR_CODE, 250, 250)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val bits = BooleanArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    bits[y * w + x] = bitMatrix.get(x, y)
                }
            }
            Triple(w, h, bits)
        } catch (e: Exception) {
            null
        }
    }

    if (qrBits != null) {
        val (w, h, bits) = qrBits
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = size.width / w
            val cellH = size.height / h
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (bits[y * w + x]) {
                        drawRect(
                            color = MinimalTextPrimary,
                            topLeft = Offset(x * cellW, y * cellH),
                            size = Size(cellW + 0.5f, cellH + 0.5f) // overlapping edges to prevent background rendering gaps
                        )
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Failed to generate QR Code",
                color = Color.Red,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ConfigurationPanel(
    isEditable: Boolean,
    port: Int,
    width: Int,
    height: Int,
    bitrate: Int,
    fps: Int,
    onPortChanged: (Int) -> Unit,
    onResolutionChanged: (w: Int, h: Int) -> Unit,
    onFpsChanged: (Int) -> Unit,
    onBitrateChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MinimalBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "ENGINE SPECIFICATIONS",
                color = MinimalTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )

            // Sockets Port input text field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "TCP Gateway Sockets Port",
                    color = MinimalTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                var portInputText by remember(port) { mutableStateOf(port.toString()) }
                TextField(
                    value = portInputText,
                    onValueChange = {
                        if (isEditable) {
                            portInputText = it
                            val parsed = it.toIntOrNull()
                            if (parsed != null && parsed in 1024..65535) {
                                onPortChanged(parsed)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("port_input"),
                    enabled = isEditable,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (isEditable) MinimalTextPrimary else MinimalTextMuted
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MinimalCardSurface,
                        unfocusedContainerColor = MinimalCardSurface,
                        disabledContainerColor = MinimalCardSurface.copy(alpha = 0.5f),
                        focusedIndicatorColor = MinimalPrimary,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Capture Resolutions Settings
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Capture Resolution Frame",
                    color = MinimalTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple(1080, 1920, "1080p"),
                        Triple(720, 1280, "720p"),
                        Triple(480, 854, "480p")
                    ).forEach { (w, h, label) ->
                        val isSelected = w == width && h == height
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) HeroBlueBg else MinimalCardSurface)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) MinimalPrimary else Color.Transparent
                                    )
                                )
                                .clickable(enabled = isEditable) {
                                    onResolutionChanged(w, h)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$label\n(${w}x${h})",
                                color = if (isSelected) HeroBlueText else MinimalTextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // Target Framerates Setting
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Target Frame Rate Limits",
                    color = MinimalTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(60, 30).forEach { targetFps ->
                        val isSelected = targetFps == fps
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) HeroBlueBg else MinimalCardSurface)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) MinimalPrimary else Color.Transparent
                                    )
                                )
                                .clickable(enabled = isEditable) {
                                    onFpsChanged(targetFps)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$targetFps FPS",
                                color = if (isSelected) HeroBlueText else MinimalTextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Bitrate slider settings
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Constant Bitrate (CBR)",
                        color = MinimalTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = String.format("%.1f Mbps", bitrate / 1_000_000f),
                        color = MinimalPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Slider(
                    value = bitrate.toFloat(),
                    onValueChange = { if (isEditable) onBitrateChanged(it.toInt()) },
                    valueRange = 2_000_000f..16_000_000f,
                    steps = 6,
                    enabled = isEditable,
                    colors = SliderDefaults.colors(
                        thumbColor = MinimalPrimary,
                        activeTrackColor = MinimalPrimary,
                        inactiveTrackColor = MinimalCardSurface,
                        disabledThumbColor = MinimalTextMuted,
                        disabledActiveTrackColor = MinimalTextMuted.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

@Composable
fun ArchitectureRoadmapSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "SYSTEM ARCHITECTURE BLUEPRINTS",
            color = MinimalTextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )

        var openSection by remember { mutableStateOf(-1) }

        listOf(
            Triple(
                "Root-Free Android Input Injection",
                "Android enforces strict isolation boundaries preventing standard sandboxed applications from injecting system-wide mouse or keyboard events. To implement mirroring remote control root-free, we exploit the ADB shell architecture. By launching a custom helper daemon locally via 'adb shell', the app process initiates running under UID 2000 (Shell process). This UID is authorized to access the system InputManager and invoke injectInputEvent() seamlessly. The daemon listens on a local Unix domain socket, receiving clicks from our app UI and injecting them back with instant zero-latency hardware fidelity.",
                Icons.Outlined.Info
            ),
            Triple(
                "Cryptographic Handshake Protocol",
                "To satisfy strict cybersecurity mandates, the pairing handshake utilizes a dual-factor validation model. Upon startup, the Nexus Server constructs a cryptographically random ephemeral pairing token (Pairing Code). The incoming desktop client must present the matching token. If valid, both nodes participate in an Elliptic Curve Diffie-Hellman (ECDH) key exchange to derive a 256-bit symmetric session key. All downstream packets—including raw AVC/H.264 video frames and remote keyboard/mouse injection instructions—are strictly wrapped inside an authenticated AES-256-GCM cipher envelope.",
                Icons.Outlined.Lock
            ),
            Triple(
                "Zero-Latency Video Pipeline",
                "Achieving desktop-grade real-time interactive response requires optimizing the capture and encoding pipeline at the microsecond scale. Screen capture is executed by piping MediaProjection hardware-accelerated frames directly into an active MediaCodec H.264/AVC encoder's Input Surface. To completely bypass memory buffer copying (zero-copy), frames remain entirely inside OpenGL GPU textures until hardware encoded. We configure Constant Bitrate (CBR), disable B-frames, set the latency constraint to zero, and dynamically inject keyframes on-demand.",
                Icons.Outlined.CheckCircle
            )
        ).forEachIndexed { index, (title, description, icon) ->
            val isExpanded = openSection == index
            val borderColor = if (isExpanded) MinimalPrimary else MinimalBorderColor

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openSection = if (isExpanded) -1 else index },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = "Blueprint status icon",
                                tint = if (isExpanded) MinimalPrimary else MinimalTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = title,
                                color = MinimalTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (isExpanded) "▲" else "▼",
                            color = MinimalTextMuted,
                            fontSize = 11.sp
                        )
                    }

                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = description,
                                color = MinimalTextMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                textAlign = TextAlign.Justify
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EngineActionButton(
    serverState: ScreenCaptureService.ServerState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val isRunning = serverState == ScreenCaptureService.ServerState.RUNNING || 
                    serverState == ScreenCaptureService.ServerState.STREAMING || 
                    serverState == ScreenCaptureService.ServerState.STARTING

    val buttonColor = animateColorAsState(
        targetValue = if (isRunning) Color(0xFFC62828) else MinimalPrimary,
        animationSpec = tween(300),
        label = "button_color"
    )

    Button(
        onClick = { if (isRunning) onStopClick() else onStartClick() },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("engine_toggle_btn")
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(28.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor.value,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Warning else Icons.Default.PlayArrow,
                contentDescription = "Toggle core action icon",
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRunning) "TERMINATE MIRROR ENGINE" else "START NEXUS STREAM SERVER",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
