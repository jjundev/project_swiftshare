package dev.swiftshare.android

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.swiftshare.domain.TransferErrorCategory
import dev.swiftshare.domain.TransferSessionScheduler
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

enum class AndroidAvailabilityPolicy { AVAILABLE_WHILE_OPEN, RECEIVE_MODE }

sealed interface ReceiveAvailabilityInput {
    data class AppVisibility(val foreground: Boolean) : ReceiveAvailabilityInput
    data class ReceiveModeLifecycle(val active: Boolean) : ReceiveAvailabilityInput
    data class SelectPolicy(val policy: AndroidAvailabilityPolicy) : ReceiveAvailabilityInput
    data object PairedPeersChanged : ReceiveAvailabilityInput
    data object StopRequested : ReceiveAvailabilityInput
    data class Blocked(val reason: String) : ReceiveAvailabilityInput
}

internal data class ReceiveAvailabilityDecision(
    val shouldListen: Boolean,
    val restartAdvertisingEpoch: Boolean = false,
)

internal class ReceiveAvailabilityModel(
    initialPolicy: AndroidAvailabilityPolicy,
    initialNetworkSignature: String,
) {
    var policy: AndroidAvailabilityPolicy = initialPolicy
        private set
    private var appForeground = false
    private var receiveModeActive = false
    private var suppressedUntilNextForeground = false
    private var networkSignature = initialNetworkSignature

    fun accept(input: ReceiveAvailabilityInput): ReceiveAvailabilityDecision {
        when (input) {
            is ReceiveAvailabilityInput.AppVisibility -> {
                if (input.foreground && !appForeground) suppressedUntilNextForeground = false
                appForeground = input.foreground
            }
            is ReceiveAvailabilityInput.ReceiveModeLifecycle -> receiveModeActive = input.active
            is ReceiveAvailabilityInput.SelectPolicy -> {
                policy = input.policy
                suppressedUntilNextForeground = false
            }
            ReceiveAvailabilityInput.PairedPeersChanged -> Unit
            ReceiveAvailabilityInput.StopRequested -> {
                policy = AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN
                receiveModeActive = false
                suppressedUntilNextForeground = true
            }
            is ReceiveAvailabilityInput.Blocked -> Unit
        }
        return decision()
    }

    fun networkChanged(signature: String): ReceiveAvailabilityDecision {
        val changed = signature != networkSignature
        networkSignature = signature
        return decision(restartAdvertisingEpoch = changed && shouldListen())
    }

    private fun decision(restartAdvertisingEpoch: Boolean = false) = ReceiveAvailabilityDecision(
        shouldListen = shouldListen(),
        restartAdvertisingEpoch = restartAdvertisingEpoch,
    )

    private fun shouldListen(): Boolean = when (policy) {
        AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN -> appForeground && !suppressedUntilNextForeground
        AndroidAvailabilityPolicy.RECEIVE_MODE -> receiveModeActive
    }
}

sealed interface AvailabilityRuntimeState {
    data object Inactive : AvailabilityRuntimeState
    data object Starting : AvailabilityRuntimeState
    data class Available(val port: Int) : AvailabilityRuntimeState
    data class Restarting(val reason: String) : AvailabilityRuntimeState
    data class Blocked(val reason: String) : AvailabilityRuntimeState
    data object Stopping : AvailabilityRuntimeState
}

data class ReceiveRuntimeSnapshot(
    val policy: AndroidAvailabilityPolicy,
    val availability: AvailabilityRuntimeState,
    val transfer: ReceiveServerState,
    val endpointQr: String? = null,
)

class AvailabilityPolicyRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "receive-availability-v1.json"))

    @Synchronized fun read(): AndroidAvailabilityPolicy {
        if (!file.baseFile.exists()) return AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN
        val root = try {
            file.openRead().use { JSONObject(it.readBytes().decodeToString()) }
        } catch (error: Exception) {
            throw PeerStorageException("availability_policy_storage_error", error)
        }
        if (root.optInt("schema", -1) != 1) throw PeerStorageException("availability_policy_schema_error")
        return when (root.optString("policy")) {
            "available_while_open" -> AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN
            "receive_mode" -> AndroidAvailabilityPolicy.RECEIVE_MODE
            else -> throw PeerStorageException("availability_policy_value_error")
        }
    }

    @Synchronized fun write(policy: AndroidAvailabilityPolicy) {
        val bytes = JSONObject()
            .put("schema", 1)
            .put("policy", when (policy) {
                AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN -> "available_while_open"
                AndroidAvailabilityPolicy.RECEIVE_MODE -> "receive_mode"
            })
            .toString().toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw PeerStorageException("availability_policy_write_failed", error)
        }
    }
}

class SwiftShareApplication : Application() {
    lateinit var trustRepository: AndroidTrustRepository
        private set
    lateinit var availabilityPolicies: AvailabilityPolicyRepository
        private set
    lateinit var receiveRuntime: ReceiveRuntime
        private set
    lateinit var outboundRuntime: AndroidOutboundRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        trustRepository = AndroidTrustRepository(this)
        availabilityPolicies = AvailabilityPolicyRepository(this)
        receiveRuntime = ReceiveRuntime(this, trustRepository, availabilityPolicies)
        outboundRuntime = AndroidOutboundRuntime(this, trustRepository, receiveRuntime.scheduler())
    }
}

class ReceiveRuntime(
    private val context: Context,
    val trustRepository: AndroidTrustRepository,
    private val policies: AvailabilityPolicyRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(ReceiveRuntimeSnapshot) -> Unit>()
    private val identityStore = AndroidDevelopmentIdentityStore()
    private val endpointTickets = EndpointTicketRegistry()
    private val advertiser = AndroidLanAdvertiser(context) { code ->
        publishAvailability(AvailabilityRuntimeState.Blocked(code))
    }
    private val endpointQrIssuer = AndroidEndpointQrIssuer(context, identityStore, endpointTickets)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var server: FixedEndpointReceiveServer? = null
    private var activeServerGeneration: Long? = null
    private var nextServerGeneration = 0L
    private val receivers = mutableMapOf<Long, FixedEndpointReceiveServer>()
    private var transferScheduler: TransferSessionScheduler? = null
    private var restartScheduled = false
    private val initialPolicy = runCatching { policies.read() }
    private val availabilityModel = ReceiveAvailabilityModel(
        initialPolicy.getOrDefault(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN),
        networkSignature(),
    )
    @Volatile private var snapshot = ReceiveRuntimeSnapshot(
        availabilityModel.policy,
        if (initialPolicy.isFailure) {
            AvailabilityRuntimeState.Blocked("policy_storage_error")
        } else {
            AvailabilityRuntimeState.Inactive
        },
        ReceiveServerState.Stopped,
    )

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleNetworkReconcile()
                override fun onLost(network: Network) = scheduleNetworkReconcile()
                override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) =
                    scheduleNetworkReconcile()
            })
        }
    }

    fun snapshot(): ReceiveRuntimeSnapshot = snapshot

    @Synchronized fun scheduler(): TransferSessionScheduler {
        return transferScheduler ?: TransferSessionScheduler(identityStore.loadOrCreate().spkiSha256).also {
            transferScheduler = it
        }
    }

    fun observe(observer: (ReceiveRuntimeSnapshot) -> Unit) {
        observers += observer
        main.post { observer(snapshot) }
    }

    fun removeObserver(observer: (ReceiveRuntimeSnapshot) -> Unit) { observers -= observer }

    @Synchronized fun accept(input: ReceiveAvailabilityInput) {
        if (input is ReceiveAvailabilityInput.Blocked) {
            applyDecision(ReceiveAvailabilityDecision(shouldListen = false))
            publishAvailability(AvailabilityRuntimeState.Blocked(input.reason))
            return
        }
        try {
            when (input) {
                is ReceiveAvailabilityInput.SelectPolicy -> policies.write(input.policy)
                ReceiveAvailabilityInput.StopRequested -> policies.write(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
                else -> Unit
            }
            val decision = availabilityModel.accept(input)
            publish(snapshot.copy(policy = availabilityModel.policy))
            applyDecision(decision)
        } catch (error: Exception) {
            applyDecision(ReceiveAvailabilityDecision(shouldListen = false))
            publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "policy_storage_error"))
        }
    }

    @Synchronized private fun startListener() {
        if (server != null) return
        if (trustRepository.storageError() != null) {
            publishAvailability(AvailabilityRuntimeState.Blocked("peer_storage_error")); return
        }
        if (trustRepository.peers().isEmpty()) {
            publishAvailability(AvailabilityRuntimeState.Blocked("no_paired_peer")); return
        }
        if (!hasLanNetwork()) {
            publishAvailability(AvailabilityRuntimeState.Blocked("network_unavailable")); return
        }
        publishAvailability(AvailabilityRuntimeState.Starting)
        try {
            val scheduler = scheduler()
            val tls = DevelopmentTlsContextFactory(identityStore).create(trustRepository)
            val generation = ++nextServerGeneration
            val value = FixedEndpointReceiveServer(
                tlsContext = tls,
                destination = MediaStoreDestination(context.contentResolver),
                authenticatedSenderName = "Paired Mac",
                scheduler = scheduler,
                trustRepository = trustRepository,
                endpointTickets = endpointTickets,
                listener = ReceiveServerListener { state -> onServerState(generation, state) },
            )
            server = value
            activeServerGeneration = generation
            receivers[generation] = value
            value.start(0)
        } catch (error: Exception) {
            val failedServer = server
            val failedGeneration = activeServerGeneration
            server = null
            activeServerGeneration = null
            if (failedGeneration != null) receivers.remove(failedGeneration)
            runCatching { failedServer?.close() }
            publish(snapshot.copy(
                availability = AvailabilityRuntimeState.Blocked(error.message ?: "receive_start_failed"),
                transfer = ReceiveServerState.Failed(TransferErrorCategory.AUTHENTICATION, error.message ?: "receive_start_failed"),
            ))
        }
    }

    @Synchronized private fun stopListener() {
        val value = server
        if (value == null) {
            if (snapshot.availability !is AvailabilityRuntimeState.Blocked) {
                publishAvailability(AvailabilityRuntimeState.Inactive)
            }
            return
        }
        server = null
        activeServerGeneration = null
        publishAvailability(AvailabilityRuntimeState.Stopping)
        advertiser.stop()
        endpointTickets.invalidate()
        value.setDiscoveryEpoch(null)
        value.stopAccepting()
        val retainedTransfer = when (val transfer = snapshot.transfer) {
            is ReceiveServerState.AwaitingApproval,
            is ReceiveServerState.Receiving,
            is ReceiveServerState.Verifying,
            -> transfer
            else -> ReceiveServerState.Stopped
        }
        publish(
            ReceiveRuntimeSnapshot(
                availabilityModel.policy,
                AvailabilityRuntimeState.Inactive,
                retainedTransfer,
            ),
        )
    }

    fun approve() = receivers.values.forEach(FixedEndpointReceiveServer::approve)
    fun reject() = receivers.values.forEach(FixedEndpointReceiveServer::reject)
    fun cancelActive() = receivers.values.forEach(FixedEndpointReceiveServer::cancelActive)
    fun unpairActive(peerIdHex: String) = receivers.values.forEach { it.unpairActive(peerIdHex) }

    private fun applyDecision(decision: ReceiveAvailabilityDecision) {
        if (decision.restartAdvertisingEpoch && server != null) {
            publishAvailability(AvailabilityRuntimeState.Restarting("network_changed"))
            stopListener()
        }
        if (decision.shouldListen) startListener() else stopListener()
    }

    private fun onServerState(generation: Long, state: ReceiveServerState) {
        main.post {
            if (state is ReceiveServerState.Listening && generation == activeServerGeneration && server != null) {
                try {
                    val epoch = advertiser.start(state.port)
                    server?.setDiscoveryEpoch(epoch.rotatingId)
                    val qr = runCatching { endpointQrIssuer.issue(state.port) }.getOrNull()
                    val transfer = when (snapshot.transfer) {
                        is ReceiveServerState.AwaitingApproval,
                        is ReceiveServerState.Receiving,
                        is ReceiveServerState.Verifying,
                        -> snapshot.transfer
                        else -> state
                    }
                    publish(
                        ReceiveRuntimeSnapshot(
                            availabilityModel.policy,
                            AvailabilityRuntimeState.Available(state.port),
                            transfer,
                            qr,
                        ),
                    )
                } catch (error: Exception) {
                    publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "discovery_start_failed"))
                }
            } else if (state is ReceiveServerState.Completed || state is ReceiveServerState.Failed) {
                publish(snapshot.copy(transfer = state))
                if (generation != activeServerGeneration) receivers.remove(generation)
            } else if (state is ReceiveServerState.Stopped && generation != activeServerGeneration) {
                val receiver = receivers[generation]
                if (receiver?.hasActiveTransfer() != true) receivers.remove(generation)
            } else if (state !is ReceiveServerState.Listening && state !is ReceiveServerState.Stopped) {
                publish(snapshot.copy(transfer = state))
            }
        }
    }

    private fun publishAvailability(value: AvailabilityRuntimeState) = publish(snapshot.copy(availability = value))

    private fun publish(value: ReceiveRuntimeSnapshot) {
        snapshot = value
        main.post { observers.forEach { it(value) } }
    }

    private fun hasLanNetwork(): Boolean = connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.let { caps ->
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }

    private fun networkSignature(): String = connectivity.allNetworks.mapNotNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
        connectivity.getLinkProperties(network)?.linkAddresses?.joinToString(",") { it.address.hostAddress.orEmpty() }
    }.sorted().joinToString("|")

    private fun scheduleNetworkReconcile() {
        if (restartScheduled) return
        restartScheduled = true
        main.postDelayed({
            restartScheduled = false
            val next = networkSignature()
            applyDecision(availabilityModel.networkChanged(next))
        }, 500)
    }
}

class ReceiveModeService : Service() {
    private lateinit var app: SwiftShareApplication
    private val observer: (ReceiveRuntimeSnapshot) -> Unit = { updateNotification(it) }

    override fun onCreate() {
        super.onCreate()
        app = application as SwiftShareApplication
        createChannel()
        promote(app.receiveRuntime.snapshot())
        app.receiveRuntime.observe(observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.receiveRuntime.accept(ReceiveAvailabilityInput.StopRequested)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val policy = runCatching { app.availabilityPolicies.read() }.getOrNull()
        if (policy != AndroidAvailabilityPolicy.RECEIVE_MODE || !notificationsVisible()) {
            app.receiveRuntime.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        app.receiveRuntime.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = true))
        return START_STICKY
    }

    override fun onDestroy() {
        app.receiveRuntime.removeObserver(observer)
        app.receiveRuntime.accept(ReceiveAvailabilityInput.ReceiveModeLifecycle(active = false))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationsVisible(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun promote(snapshot: ReceiveRuntimeSnapshot) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(snapshot),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(snapshot: ReceiveRuntimeSnapshot) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(snapshot))
    }

    private fun notification(snapshot: ReceiveRuntimeSnapshot): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ReceiveModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (val transfer = snapshot.transfer) {
            is ReceiveServerState.AwaitingApproval -> "Incoming from ${transfer.summary.authenticatedSender} · open to review"
            is ReceiveServerState.Receiving -> "Receiving from ${transfer.summary.authenticatedSender} · ${transfer.verifiedBytes} of ${transfer.summary.byteCount} bytes"
            is ReceiveServerState.Verifying -> "Verifying an incoming transfer"
            else -> when (snapshot.availability) {
                is AvailabilityRuntimeState.Available -> "Available to paired devices"
                is AvailabilityRuntimeState.Restarting -> "Refreshing LAN availability"
                is AvailabilityRuntimeState.Blocked -> "Receive Mode needs attention"
                else -> "Starting Receive Mode"
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SwiftShare Receive Mode")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Receive Mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when SwiftShare is available to paired devices"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val ACTION_STOP = "dev.swiftshare.android.STOP_RECEIVE_MODE"
        private const val CHANNEL_ID = "receive_mode"
        private const val NOTIFICATION_ID = 6006

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiveModeService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReceiveModeService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
