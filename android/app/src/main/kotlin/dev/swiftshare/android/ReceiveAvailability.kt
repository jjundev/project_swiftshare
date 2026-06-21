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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

enum class AndroidAvailabilityPolicy { AVAILABLE_WHILE_OPEN, RECEIVE_MODE }

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

    override fun onCreate() {
        super.onCreate()
        trustRepository = AndroidTrustRepository(this)
        availabilityPolicies = AvailabilityPolicyRepository(this)
        receiveRuntime = ReceiveRuntime(this, trustRepository, availabilityPolicies)
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
    private var appForeground = false
    private var serviceActive = false
    private var suppressedUntilNextForeground = false
    private var restartScheduled = false
    private var lastNetworkSignature = networkSignature()
    @Volatile private var snapshot = initialSnapshot()

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

    fun observe(observer: (ReceiveRuntimeSnapshot) -> Unit) {
        observers += observer
        main.post { observer(snapshot) }
    }

    fun removeObserver(observer: (ReceiveRuntimeSnapshot) -> Unit) { observers -= observer }

    fun enterForeground() {
        if (!appForeground) suppressedUntilNextForeground = false
        appForeground = true
        reconcile()
    }

    fun leaveForeground() {
        appForeground = false
        suppressedUntilNextForeground = false
        if (policyOrDefault() == AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN) stop()
    }

    fun setServiceActive(value: Boolean) {
        serviceActive = value
        reconcile()
    }

    fun setPolicy(policy: AndroidAvailabilityPolicy, startImmediately: Boolean = true) {
        try {
            policies.write(policy)
            if (startImmediately) suppressedUntilNextForeground = false
            publish(snapshot.copy(policy = policy))
            if (startImmediately) reconcile() else stop()
        } catch (error: Exception) {
            publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "policy_storage_error"))
            stop()
        }
    }

    fun reconcile() {
        val policy = policyOrDefault()
        publish(snapshot.copy(policy = policy))
        val shouldRun = when (policy) {
            AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN -> appForeground && !suppressedUntilNextForeground
            AndroidAvailabilityPolicy.RECEIVE_MODE -> serviceActive
        }
        if (shouldRun) start() else stop()
    }

    @Synchronized fun start() {
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
            val localIdentity = identityStore.loadOrCreate()
            val tls = DevelopmentTlsContextFactory(identityStore).create(trustRepository)
            val value = FixedEndpointReceiveServer(
                tlsContext = tls,
                destination = MediaStoreDestination(context.contentResolver),
                authenticatedSenderName = "Paired Mac",
                localDeviceId = localIdentity.spkiSha256,
                trustRepository = trustRepository,
                endpointTickets = endpointTickets,
                listener = ReceiveServerListener(::onServerState),
            )
            server = value
            value.start(0)
        } catch (error: Exception) {
            server = null
            publish(snapshot.copy(
                availability = AvailabilityRuntimeState.Blocked(error.message ?: "receive_start_failed"),
                transfer = ReceiveServerState.Failed(TransferErrorCategory.AUTHENTICATION, error.message ?: "receive_start_failed"),
            ))
        }
    }

    @Synchronized fun stop() {
        val value = server
        if (value == null) {
            if (snapshot.availability !is AvailabilityRuntimeState.Blocked) {
                publishAvailability(AvailabilityRuntimeState.Inactive)
            }
            return
        }
        server = null
        publishAvailability(AvailabilityRuntimeState.Stopping)
        advertiser.stop()
        endpointTickets.invalidate()
        value.setDiscoveryEpoch(null)
        value.close()
        publish(ReceiveRuntimeSnapshot(policyOrDefault(), AvailabilityRuntimeState.Inactive, ReceiveServerState.Stopped))
    }

    fun approve() = server?.approve()
    fun reject() = server?.reject()
    fun cancelActive() = server?.cancelActive()
    fun unpairActive(peerIdHex: String) = server?.unpairActive(peerIdHex)

    fun stopFromNotification() {
        suppressedUntilNextForeground = true
        serviceActive = false
        try {
            policies.write(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
            publish(snapshot.copy(policy = AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN))
        } catch (error: Exception) {
            publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "policy_storage_error"))
        }
        stop()
    }

    fun block(reason: String) {
        stop()
        publishAvailability(AvailabilityRuntimeState.Blocked(reason))
    }

    private fun onServerState(state: ReceiveServerState) {
        main.post {
            if (state is ReceiveServerState.Listening && server != null) {
                try {
                    val epoch = advertiser.start(state.port)
                    server?.setDiscoveryEpoch(epoch.rotatingId)
                    val qr = runCatching { endpointQrIssuer.issue(state.port) }.getOrNull()
                    publish(ReceiveRuntimeSnapshot(policyOrDefault(), AvailabilityRuntimeState.Available(state.port), state, qr))
                } catch (error: Exception) {
                    publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "discovery_start_failed"))
                }
            } else if (server != null || state is ReceiveServerState.Completed || state is ReceiveServerState.Failed) {
                publish(snapshot.copy(transfer = state))
            }
        }
    }

    private fun policyOrDefault(): AndroidAvailabilityPolicy = try {
        policies.read()
    } catch (error: Exception) {
        publishAvailability(AvailabilityRuntimeState.Blocked(error.message ?: "policy_storage_error"))
        AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN
    }

    private fun initialSnapshot(): ReceiveRuntimeSnapshot {
        val policy = runCatching { policies.read() }.getOrDefault(AndroidAvailabilityPolicy.AVAILABLE_WHILE_OPEN)
        val availability = if (runCatching { policies.read() }.isFailure) {
            AvailabilityRuntimeState.Blocked("policy_storage_error")
        } else AvailabilityRuntimeState.Inactive
        return ReceiveRuntimeSnapshot(policy, availability, ReceiveServerState.Stopped)
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
            if (next == lastNetworkSignature) return@postDelayed
            lastNetworkSignature = next
            if (server != null) {
                publishAvailability(AvailabilityRuntimeState.Restarting("network_changed"))
                stop()
            }
            reconcile()
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
            app.receiveRuntime.stopFromNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val policy = runCatching { app.availabilityPolicies.read() }.getOrNull()
        if (policy != AndroidAvailabilityPolicy.RECEIVE_MODE || !notificationsVisible()) {
            app.receiveRuntime.setServiceActive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        app.receiveRuntime.setServiceActive(true)
        return START_STICKY
    }

    override fun onDestroy() {
        app.receiveRuntime.removeObserver(observer)
        app.receiveRuntime.setServiceActive(false)
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
