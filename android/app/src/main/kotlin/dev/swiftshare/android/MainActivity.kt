package dev.swiftshare.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.swiftshare.domain.PeerApprovalPolicy
import dev.swiftshare.domain.TransferActivityPhase

class MainActivity : ComponentActivity() {
    private var receiveState by mutableStateOf<ReceiveServerState>(ReceiveServerState.Stopped)
    private var availabilityState by mutableStateOf<AvailabilityRuntimeState>(AvailabilityRuntimeState.Inactive)
    private var availabilityPolicy by mutableStateOf(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
    private var localPin by mutableStateOf("")
    private var pairingState by mutableStateOf<AndroidPairingState>(AndroidPairingState.Idle)
    private var endpointQr by mutableStateOf<String?>(null)
    private var peerRevision by mutableIntStateOf(0)
    private lateinit var trustRepository: AndroidTrustRepository
    private lateinit var runtime: ReceiveRuntime
    private lateinit var outboundRuntime: AndroidOutboundRuntime
    private var outboundState by mutableStateOf(AndroidOutboundSnapshot())
    private var pairingClient: AndroidPairingClient? = null
    private lateinit var notificationPermission: ActivityResultLauncher<String>
    private lateinit var documentPicker: ActivityResultLauncher<Array<String>>
    private val runtimeObserver: (ReceiveRuntimeSnapshot) -> Unit = { value ->
        receiveState = value.transfer
        availabilityState = value.availability
        availabilityPolicy = value.policy
        endpointQr = value.endpointQr
    }
    private val outboundObserver: (AndroidOutboundSnapshot) -> Unit = { outboundState = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SwiftShareApplication
        trustRepository = app.trustRepository
        runtime = app.receiveRuntime
        outboundRuntime = app.outboundRuntime
        runtime.observe(runtimeObserver)
        outboundRuntime.observe(outboundObserver)
        val startupIdentityStore = AndroidDevelopmentIdentityStore()
        if (!startupIdentityStore.exists() && trustRepository.peers().isNotEmpty()) runCatching { trustRepository.clear() }
        pairingClient = AndroidPairingClient(
            startupIdentityStore, trustRepository,
            AndroidPairingListener { state -> runOnUiThread {
                pairingState = state
                if (state is AndroidPairingState.Paired) {
                    peerRevision++
                    runtime.accept(ReceiveAvailabilityInput.PairedPeersChanged)
                }
            } },
        )
        localPin = runCatching {
            AndroidDevelopmentIdentityStore().loadOrCreate().spkiSha256.toHex()
        }.getOrElse {
            runtime.accept(ReceiveAvailabilityInput.Blocked("identity_unavailable"))
            "unavailable"
        }
        notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableReceiveMode() else {
                runtime.accept(ReceiveAvailabilityInput.Blocked("notification_permission_denied"))
            }
        }
        documentPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) outboundRuntime.prepareDocuments(uris)
        }
        setContent {
            SwiftShareTheme {
                ReceiveScreen(
                    localPin = localPin,
                    state = receiveState,
                    availability = availabilityState,
                    policy = availabilityPolicy,
                    endpointQr = endpointQr,
                    onAvailableWhileOpen = ::useAvailableWhileOpen,
                    onReceiveMode = ::requestReceiveMode,
                    onStop = ::stopReceiving,
                    onApprove = runtime::approve,
                    onReject = runtime::reject,
                    onCancel = runtime::cancelActive,
                    outbound = outboundState,
                    onChooseDocument = { documentPicker.launch(arrayOf("*/*")) },
                    onClearOutbound = outboundRuntime::clearSelection,
                    onSelectOutboundPeer = outboundRuntime::selectPeer,
                    onSendOutbound = outboundRuntime::send,
                    onCancelOutbound = outboundRuntime::cancel,
                    pairingState = pairingState,
                    pairedPeers = trustRepository.peers().also { peerRevision },
                    onPair = { pairingClient?.start(it) },
                    onPairApprove = { pairingClient?.approve() },
                    onPairReject = { pairingClient?.reject() },
                    onUnpair = { peerId ->
                        if (runCatching { trustRepository.remove(peerId) }.getOrDefault(false)) {
                            runtime.unpairActive(peerId)
                            outboundRuntime.unpair(peerId)
                            peerRevision++
                            runtime.accept(ReceiveAvailabilityInput.PairedPeersChanged)
                        }
                    },
                    onApprovalPolicy = { peerId, policy ->
                        runCatching { trustRepository.setApprovalPolicy(peerId, policy) }
                            .onSuccess { peerRevision++ }
                            .onFailure { runtime.accept(ReceiveAvailabilityInput.Blocked("peer_storage_error")) }
                    },
                )
            }
        }
        // Only consume the launch intent on first creation. On a configuration change or
        // process-death recreation the system re-delivers the same SEND intent; re-running
        // handleSendIntent there would re-prepare (and re-hash) the batch and clobber edits.
        if (savedInstanceState == null) {
            handleSendIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.accept(ReceiveAvailabilityInput.AppVisibility(foreground = true))
        outboundRuntime.startDiscovery()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    /**
     * Routes Sharesheet ACTION_SEND / ACTION_SEND_MULTIPLE inputs into the outbound batch as
     * process-lifetime sources (US19/US20). SwiftShare's own picker remains durable (US21).
     */
    private fun handleSendIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                streamExtra(intent)?.let { outboundRuntime.prepareShared(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                streamListExtra(intent).takeIf { it.isNotEmpty() }?.let(outboundRuntime::prepareShared)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }

    override fun onStop() {
        if (!isChangingConfigurations) {
            runtime.accept(ReceiveAvailabilityInput.AppVisibility(foreground = false))
            outboundRuntime.cancel()
            outboundRuntime.stopDiscovery()
        }
        super.onStop()
    }

    override fun onDestroy() {
        runtime.removeObserver(runtimeObserver)
        outboundRuntime.removeObserver(outboundObserver)
        pairingClient?.close()
        super.onDestroy()
    }

    private fun useAvailableWhileOpen() {
        runtime.accept(
            ReceiveAvailabilityInput.SelectPolicy(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN),
        )
        stopService(Intent(this, ReceiveModeService::class.java))
    }

    private fun requestReceiveMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            runtime.accept(ReceiveAvailabilityInput.Blocked("notifications_disabled"))
            return
        }
        enableReceiveMode()
    }

    private fun enableReceiveMode() {
        runtime.accept(ReceiveAvailabilityInput.SelectPolicy(AndroidAvailabilityPolicy.RECEIVE_MODE))
        ReceiveModeService.start(this)
    }

    private fun stopReceiving() {
        runtime.accept(ReceiveAvailabilityInput.StopRequested)
        stopService(Intent(this, ReceiveModeService::class.java))
    }
}

@Composable
private fun SwiftShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun ReceiveScreen(
    localPin: String,
    state: ReceiveServerState,
    availability: AvailabilityRuntimeState,
    policy: AndroidAvailabilityPolicy,
    endpointQr: String?,
    onAvailableWhileOpen: () -> Unit,
    onReceiveMode: () -> Unit,
    onStop: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    outbound: AndroidOutboundSnapshot,
    onChooseDocument: () -> Unit,
    onClearOutbound: () -> Unit,
    onSelectOutboundPeer: (String) -> Unit,
    onSendOutbound: () -> Unit,
    onCancelOutbound: () -> Unit,
    pairingState: AndroidPairingState,
    pairedPeers: List<StoredPeer>,
    onPair: (String) -> Unit,
    onPairApprove: () -> Unit,
    onPairReject: () -> Unit,
    onUnpair: (String) -> Unit,
    onApprovalPolicy: (String, PeerApprovalPolicy) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("LAN receive mode", style = MaterialTheme.typography.titleMedium)
            Text(if (policy == AndroidAvailabilityPolicy.RECEIVE_MODE) {
                "Receive Mode stays available in the background with a visible notification."
            } else {
                "Available only while SwiftShare is open. Incoming files require approval by default."
            })
            AvailabilityContent(availability)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onAvailableWhileOpen,
                    enabled = policy != AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN,
                ) { Text("While open") }
                Button(
                    onClick = onReceiveMode,
                    enabled = policy != AndroidAvailabilityPolicy.RECEIVE_MODE,
                ) { Text("Receive Mode") }
            }
            PairingContent(
                pairingState, pairedPeers, onPair, onPairApprove, onPairReject, onUnpair, onApprovalPolicy,
            )
            OutboundContent(
                outbound,
                pairedPeers,
                onChooseDocument,
                onClearOutbound,
                onSelectOutboundPeer,
                onSendOutbound,
                onCancelOutbound,
            )
            Text("Android SPKI pin", fontWeight = FontWeight.SemiBold)
            Text(localPin, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            ReceiveStateContent(state, onApprove, onReject, onCancel)
            if (endpointQr != null && state is ReceiveServerState.Listening) {
                Text("Discovery unavailable? Scan this endpoint QR on your Mac.", fontWeight = FontWeight.SemiBold)
                Image(endpointQrBitmap(endpointQr, 480).asImageBitmap(), "SwiftShare endpoint QR", Modifier.height(240.dp))
                Text(endpointQr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (availability is AvailabilityRuntimeState.Available ||
                availability is AvailabilityRuntimeState.Starting ||
                availability is AvailabilityRuntimeState.Restarting
            ) {
                OutlinedButton(onClick = onStop) { Text("Stop receiving") }
            }
        }
    }
}

@Composable
private fun OutboundContent(
    state: AndroidOutboundSnapshot,
    peers: List<StoredPeer>,
    onChooseDocument: () -> Unit,
    onClear: () -> Unit,
    onSelectPeer: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send Android → Mac", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.preparing) {
            Text("Preparing files…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (state.drafts.isEmpty()) {
            Text("Choose files or share them to SwiftShare. The picker keeps durable read access; Sharesheet items are process-lifetime.")
        } else {
            Text("${state.itemCount} item(s) · ${state.batchByteCount} bytes", fontWeight = FontWeight.SemiBold)
            state.drafts.forEach { draft ->
                Text("• ${draft.displayName} (${draft.byteCount} bytes)", style = MaterialTheme.typography.bodySmall)
            }
            if (state.hasProcessLifetime) {
                Text(
                    "Shared items are process-lifetime: if SwiftShare restarts mid-transfer it cannot resume and you must re-share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (state.rejected.isNotEmpty()) {
            Text(
                "Skipped (unsupported or unreadable): ${state.rejected.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onChooseDocument, enabled = !state.active) { Text("Choose files") }
            if (state.drafts.isNotEmpty() && !state.active) {
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
        if (peers.isEmpty()) {
            Text("Pair a Mac before sending.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Destination Mac", fontWeight = FontWeight.SemiBold)
            peers.forEach { peer ->
                val online = peer.keyIdHex in state.onlinePeerIds
                OutlinedButton(
                    onClick = { onSelectPeer(peer.keyIdHex) },
                    enabled = online && !state.active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${if (state.selectedPeerId == peer.keyIdHex) "✓ " else ""}${peer.displayName} · ${if (online) "Online" else "Offline"}")
                }
            }
        }
        state.phase?.let { phase ->
            Text(phase.outboundLabel(), fontWeight = FontWeight.SemiBold)
            if (state.totalItems > 1) {
                val current = (state.completedItems + 1).coerceAtMost(state.totalItems)
                val name = if (state.currentItemName.isEmpty()) "" else " · ${state.currentItemName}"
                Text("Item $current of ${state.totalItems}$name", style = MaterialTheme.typography.bodySmall)
            }
            if (state.totalBytes > 0 && phase in setOf(
                    TransferActivityPhase.TRANSFERRING,
                    TransferActivityPhase.VERIFYING,
                    TransferActivityPhase.COMMITTING,
                )
            ) {
                LinearProgressIndicator(
                    progress = { (state.transferredBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.transferredBytes} / ${state.totalBytes} bytes", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSend,
                enabled = state.drafts.isNotEmpty() && state.selectedPeerId in state.onlinePeerIds && !state.active,
            ) { Text("Send to Mac") }
            if (state.active && !state.preparing) OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private fun TransferActivityPhase.outboundLabel(): String = when (this) {
    TransferActivityPhase.PREPARING -> "Preparing"
    TransferActivityPhase.CONNECTING -> "Connecting to Mac"
    TransferActivityPhase.AWAITING_APPROVAL -> "Waiting for approval on Mac"
    TransferActivityPhase.TRANSFERRING -> "Sending"
    TransferActivityPhase.VERIFYING -> "Mac is verifying"
    TransferActivityPhase.COMMITTING -> "Mac is committing"
    TransferActivityPhase.COMPLETED -> "Sent successfully"
    TransferActivityPhase.REJECTED -> "Declined on Mac"
    TransferActivityPhase.CANCELLED -> "Cancelled"
    TransferActivityPhase.FAILED -> "Transfer failed"
}

@Composable
private fun AvailabilityContent(state: AvailabilityRuntimeState) {
    when (state) {
        AvailabilityRuntimeState.Inactive -> Text("Unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AvailabilityRuntimeState.Starting -> Text("Starting availability…")
        is AvailabilityRuntimeState.Available -> Text("Available on LAN · port ${state.port}", color = MaterialTheme.colorScheme.primary)
        is AvailabilityRuntimeState.Restarting -> Text("Refreshing availability: ${state.reason}")
        is AvailabilityRuntimeState.Blocked -> Text("Unavailable: ${state.reason}", color = MaterialTheme.colorScheme.error)
        AvailabilityRuntimeState.Stopping -> Text("Stopping availability…")
    }
}

@Composable
private fun ReceiveStateContent(
    state: ReceiveServerState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        ReceiveServerState.Stopped -> Text("Not listening", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is ReceiveServerState.Listening -> Text("Listening on port ${state.port}")
        is ReceiveServerState.AwaitingApproval -> {
            Text("Incoming from ${state.summary.authenticatedSender}", fontWeight = FontWeight.SemiBold)
            Text("${state.summary.displayName} · ${state.summary.byteCount} bytes")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onApprove) { Text("Accept") }
                OutlinedButton(onClick = onReject) { Text("Decline") }
            }
        }
        is ReceiveServerState.Receiving -> {
            val total = state.summary.byteCount.coerceAtLeast(1)
            Text("Receiving ${state.summary.displayName}")
            LinearProgressIndicator(
                progress = { (state.verifiedBytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.verifiedBytes} / ${state.summary.byteCount} bytes verified")
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        is ReceiveServerState.Verifying -> {
            Text("Verifying ${state.summary.displayName}")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ReceiveServerState.Completed -> Text("Saved ${state.displayName} to Downloads/SwiftShare")
        is ReceiveServerState.Failed -> Text(
            "${state.category.name.lowercase()}: ${state.code}",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun PairingContent(
    state: AndroidPairingState,
    peers: List<StoredPeer>,
    onPair: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onUnpair: (String) -> Unit,
    onApprovalPolicy: (String, PeerApprovalPolicy) -> Unit,
) {
    var scanning by remember { mutableStateOf(false) }
    var manualQr by remember { mutableStateOf("") }
    var cameraDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted; cameraDenied = !granted
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pairing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when (state) {
            AndroidPairingState.Idle -> {
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else permission.launch(Manifest.permission.CAMERA)
                }) { Text("Scan Mac pairing QR") }
                if (cameraDenied) Text("Camera permission is required to scan. You can paste the QR payload below.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(manualQr, { manualQr = it }, label = { Text("Pairing QR payload") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onPair(manualQr) }, enabled = manualQr.startsWith("swiftshare://pair/v1/")) { Text("Pair from pasted code") }
            }
            AndroidPairingState.Connecting -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is AndroidPairingState.AwaitingConfirmation -> {
                Text("Confirm ${state.peerName}", fontWeight = FontWeight.SemiBold)
                Text("${state.platform} · key ${state.keyId.take(12)}", fontFamily = FontFamily.Monospace)
                Text(state.comparisonCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onApprove) { Text("Pair") }; OutlinedButton(onClick = onReject) { Text("Reject") } }
            }
            AndroidPairingState.WaitingForMac -> Text("Waiting for Mac confirmation…")
            is AndroidPairingState.Paired -> Text("Paired with ${state.peer.displayName}", color = MaterialTheme.colorScheme.primary)
            is AndroidPairingState.Failed -> {
                Text("Pairing failed: ${state.code}", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(manualQr, { manualQr = it }, label = { Text("Pairing QR payload") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onPair(manualQr) }, enabled = manualQr.startsWith("swiftshare://pair/v1/")) { Text("Try again") }
            }
        }
        if (scanning) QrScanner { value -> scanning = false; onPair(value) }
        peers.forEach { peer ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) { Text(peer.displayName); Text("${peer.platform} · ${peer.keyIdHex.take(12)}", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { onUnpair(peer.keyIdHex) }) { Text("Unpair") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-accept incoming transfers")
                        Text("Applies only to this authenticated Peer", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = peer.approvalPolicy == PeerApprovalPolicy.AUTO_ACCEPT,
                        onCheckedChange = { enabled ->
                            onApprovalPolicy(
                                peer.keyIdHex,
                                if (enabled) PeerApprovalPolicy.AUTO_ACCEPT else PeerApprovalPolicy.REQUIRE_APPROVAL,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QrScanner(onQr: (String) -> Unit) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        factory = { PreviewView(it).apply {
            val delivered = AtomicBoolean(false)
            scaleType = PreviewView.ScaleType.FILL_CENTER
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = CameraPreview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                val scanner = BarcodeScanning.getClient(options)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                    val image = imageProxy.image
                    if (image == null) imageProxy.close()
                    else scanner.process(InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { values ->
                            values.firstOrNull { it.rawValue?.startsWith("swiftshare://pair/v1/") == true }
                                ?.rawValue?.takeIf { delivered.compareAndSet(false, true) }?.let(onQr)
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(context as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
        } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ReceiveScreenPreview() {
    SwiftShareTheme {
        ReceiveScreen(
            localPin = "0123456789abcdef".repeat(4),
            state = ReceiveServerState.Listening(8443),
            availability = AvailabilityRuntimeState.Available(8443),
            policy = AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN,
            endpointQr = null,
            onAvailableWhileOpen = {},
            onReceiveMode = {},
            onStop = {},
            onApprove = {},
            onReject = {},
            onCancel = {},
            outbound = AndroidOutboundSnapshot(),
            onChooseDocument = {},
            onClearOutbound = {},
            onSelectOutboundPeer = {},
            onSendOutbound = {},
            onCancelOutbound = {},
            pairingState = AndroidPairingState.Idle,
            pairedPeers = emptyList(),
            onPair = {},
            onPairApprove = {},
            onPairReject = {},
            onUnpair = {},
            onApprovalPolicy = { _, _ -> },
        )
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
