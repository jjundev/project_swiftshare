package dev.swiftshare.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.AtomicFile
import dev.swiftshare.domain.DiscoveryProbe
import dev.swiftshare.domain.DiscoveryProbeCodec
import dev.swiftshare.domain.OutboundAuthenticatedPeerLocation
import dev.swiftshare.domain.OutboundAuthenticatedPeerLocating
import dev.swiftshare.domain.OutboundTransferIntent
import dev.swiftshare.domain.OutboundTransferPayload
import dev.swiftshare.domain.OutboundTransferPayloadStream
import dev.swiftshare.domain.OutboundTransferRecordChannel
import dev.swiftshare.domain.ProductionOutboundTransferSession
import dev.swiftshare.domain.RoleTransferOutcome
import dev.swiftshare.domain.TRANSFER_HEADER_SIZE
import dev.swiftshare.domain.TransferActivityPhase
import dev.swiftshare.domain.TransferDeadlineScheduler
import dev.swiftshare.domain.TransferEventSink
import dev.swiftshare.domain.TransferRecordHeader
import dev.swiftshare.domain.TransferRecordType
import dev.swiftshare.domain.TransferSessionEvent
import dev.swiftshare.domain.TransferSessionScheduler
import dev.swiftshare.domain.TransferTerminalResult
import dev.swiftshare.domain.TransferWireRecord
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLSocket
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

data class PersistedDocumentDraft(
    val itemId: UUID,
    val uri: String,
    val displayName: String,
    val mediaType: String,
    val byteCount: Long,
    val sha256: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PersistedDocumentDraft &&
        itemId == other.itemId && uri == other.uri && displayName == other.displayName &&
        mediaType == other.mediaType && byteCount == other.byteCount && sha256.contentEquals(other.sha256)
    override fun hashCode(): Int = itemId.hashCode()
}

data class AndroidOutboundSnapshot(
    val draft: PersistedDocumentDraft? = null,
    val onlinePeerIds: Set<String> = emptySet(),
    val selectedPeerId: String? = null,
    val phase: TransferActivityPhase? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val failure: String? = null,
    val preparing: Boolean = false,
) {
    val active: Boolean get() = preparing || phase in setOf(
        TransferActivityPhase.CONNECTING,
        TransferActivityPhase.AWAITING_APPROVAL,
        TransferActivityPhase.TRANSFERRING,
        TransferActivityPhase.VERIFYING,
        TransferActivityPhase.COMMITTING,
    )
}

private class DocumentDraftRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val file = AtomicFile(File(context.filesDir, "outbound-document-v1.json"))

    @Synchronized fun read(): PersistedDocumentDraft? = try {
        if (!file.baseFile.exists()) return null
        val value = file.openRead().use { JSONObject(it.readBytes().decodeToString()) }
        require(value.getInt("schema") == 1)
        PersistedDocumentDraft(
            UUID.fromString(value.getString("itemId")),
            value.getString("uri"),
            value.getString("displayName"),
            value.getString("mediaType"),
            value.getLong("byteCount"),
            Base64.getDecoder().decode(value.getString("sha256")),
        ).also { require(it.sha256.size == 32 && it.byteCount >= 0) }
    } catch (_: Exception) {
        null
    }

    @Synchronized fun prepare(uri: Uri): PersistedDocumentDraft {
        val prior = read()
        val preservesPriorGrant = prior?.uri == uri.toString()
        return withPersistableGrant(
            preservesPriorGrant = preservesPriorGrant,
            take = { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            release = { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        ) {
            val metadata = metadata(uri)
            requireSafeName(metadata.first)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    count += read
                }
            } ?: error("source_unavailable")
            if (metadata.second != null && metadata.second != count) error("source_changed")
            val value = PersistedDocumentDraft(
                UUID.randomUUID(), uri.toString(), metadata.first,
                resolver.getType(uri) ?: "application/octet-stream", count, digest.digest(),
            )
            write(value)
            if (prior != null && prior.uri != value.uri) runCatching {
                resolver.releasePersistableUriPermission(Uri.parse(prior.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            value
        }
    }

    fun validate(value: PersistedDocumentDraft) {
        val uri = Uri.parse(value.uri)
        val metadata = metadata(uri)
        require(metadata.first == value.displayName && (metadata.second == null || metadata.second == value.byteCount)) {
            "source_changed"
        }
        resolver.openInputStream(uri)?.use { } ?: error("source_unavailable")
    }

    fun open(value: PersistedDocumentDraft): InputStream =
        resolver.openInputStream(Uri.parse(value.uri)) ?: error("source_unavailable")

    private fun metadata(uri: Uri): Pair<String, Long?> {
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?: error("source_unavailable")
        return cursor.use {
            check(it.moveToFirst()) { "source_unavailable" }
            val name = it.stringOrNull(OpenableColumns.DISPLAY_NAME) ?: error("source_name_unavailable")
            val size = it.longOrNull(OpenableColumns.SIZE)?.takeIf { value -> value >= 0 }
            name to size
        }
    }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun write(value: PersistedDocumentDraft) {
        val document = JSONObject()
            .put("schema", 1)
            .put("itemId", value.itemId.toString())
            .put("uri", value.uri)
            .put("displayName", value.displayName)
            .put("mediaType", value.mediaType)
            .put("byteCount", value.byteCount)
            .put("sha256", Base64.getEncoder().encodeToString(value.sha256))
        val stream = file.startWrite()
        try {
            stream.write(document.toString().encodeToByteArray())
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun requireSafeName(name: String) {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
            name.toByteArray(Charsets.UTF_8).size <= 255) { "unsafe_source_name" }
    }
}

private class AndroidDocumentPayload(
    private val value: PersistedDocumentDraft,
    private val repository: DocumentDraftRepository,
) : OutboundTransferPayload {
    override val itemId = value.itemId
    override val displayName = value.displayName
    override val byteCount = value.byteCount
    override val sha256 = value.sha256.copyOf()
    override val mediaType = value.mediaType
    override suspend fun validateForTransfer() = repository.validate(value)
    override suspend fun openStream(): OutboundTransferPayloadStream = InputPayloadStream(repository.open(value))
}

private class InputPayloadStream(private val input: InputStream) : OutboundTransferPayloadStream {
    override suspend fun read(upToCount: Int): ByteArray? {
        val buffer = ByteArray(upToCount)
        val count = input.read(buffer)
        return if (count < 0) null else buffer.copyOf(count)
    }
    override suspend fun close() = input.close()
}

private data class AndroidPeerRoute(val address: InetAddress, val port: Int, val rotatingId: ByteArray)

private class AndroidLanBrowser(
    context: Context,
    private val repository: AndroidTrustRepository,
    private val routesChanged: (Map<String, AndroidPeerRoute>) -> Unit,
) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val tls = DevelopmentTlsContextFactory(AndroidDevelopmentIdentityStore()).create(repository)
    private val executor = Executors.newCachedThreadPool()
    private val lock = Any()
    private val routes = mutableMapOf<String, AndroidPeerRoute>()
    private val peerByService = mutableMapOf<String, String>()
    private var discovery: NsdManager.DiscoveryListener? = null

    @Synchronized fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = this@AndroidLanBrowser.close()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.startsWith(SWIFTSHARE_SERVICE_TYPE)) resolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    peerByService.remove(serviceInfo.serviceName)?.let(routes::remove)
                    routesChanged(routes.toMap())
                }
            }
        }
        discovery = listener
        nsd.discoverServices(SWIFTSHARE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Suppress("DEPRECATION")
    private fun resolve(candidate: NsdServiceInfo) {
        nsd.resolveService(candidate, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(info: NsdServiceInfo) {
                val version = info.attributes["v"]?.decodeToString()
                val rid = runCatching {
                    Base64.getUrlDecoder().decode(info.attributes["rid"]?.decodeToString())
                }.getOrNull()
                val address = info.hostAddresses.firstOrNull() ?: return
                if (version != "1" || rid?.size != 16 || info.port !in 1..65535) return
                executor.execute { probe(info.serviceName, address, info.port, rid) }
            }
        })
    }

    private fun probe(serviceName: String, address: InetAddress, port: Int, rotatingId: ByteArray) {
        runCatching {
            openSocket(address, port).use { socket ->
                val peerSpki = (socket.session.peerCertificates.first() as X509Certificate).publicKey.encoded
                val peer = repository.peerForSpki(peerSpki) ?: error("unpaired_peer")
                val probe = DiscoveryProbe(rotatingId, ByteArray(16).also(SecureRandom()::nextBytes))
                socket.outputStream.write(DiscoveryProbeCodec.encodeRequest(probe)); socket.outputStream.flush()
                val reply = DiscoveryProbeCodec.decodeReply(readExactly(socket.inputStream, 39) ?: error("probe_closed"))
                check(reply.first == probe && reply.second)
                synchronized(lock) {
                    peerByService[serviceName]?.let(routes::remove)
                    peerByService[serviceName] = peer.keyIdHex
                    routes[peer.keyIdHex] = AndroidPeerRoute(address, port, rotatingId.copyOf())
                    routesChanged(routes.toMap())
                }
            }
        }
    }

    fun locate(peerIdHex: String): AndroidPeerRoute? = synchronized(lock) { routes[peerIdHex] }

    fun openSocket(address: InetAddress, port: Int): SSLSocket {
        val socket = tls.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        socket.soTimeout = 60_000
        socket.connect(InetSocketAddress(address, port), 5_000)
        socket.startHandshake()
        return socket
    }

    @Synchronized override fun close() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        synchronized(lock) { routes.clear(); peerByService.clear(); routesChanged(emptyMap()) }
    }
}

private class AndroidOutboundLocator(
    private val browser: AndroidLanBrowser,
    private val repository: AndroidTrustRepository,
) : OutboundAuthenticatedPeerLocating {
    override suspend fun locate(peerId: ByteArray): OutboundAuthenticatedPeerLocation {
        val id = peerId.toOutboundHex()
        check(repository.peer(id) != null) { "peer_unpaired" }
        val route = browser.locate(id) ?: error("peer_offline")
        val socket = browser.openSocket(route.address, route.port)
        val actual = MessageDigest.getInstance("SHA-256").digest(
            (socket.session.peerCertificates.first() as X509Certificate).publicKey.encoded,
        )
        if (!MessageDigest.isEqual(actual, peerId)) {
            socket.close(); error("peer_identity_mismatch")
        }
        return OutboundAuthenticatedPeerLocation(SocketOutboundRecordChannel(socket))
    }
}

private class SocketOutboundRecordChannel(private val socket: SSLSocket) : OutboundTransferRecordChannel {
    override suspend fun sendRecord(
        type: TransferRecordType,
        payload: ByteArray,
        sessionId: UUID,
        protocolMajor: Int,
        protocolMinor: Int,
    ) = synchronized(socket.outputStream) {
        socket.outputStream.write(TransferRecordHeader(type, payload.size, sessionId, protocolMajor, protocolMinor).encode())
        socket.outputStream.write(payload)
        socket.outputStream.flush()
    }

    override suspend fun receiveRecord(): TransferWireRecord {
        val header = TransferRecordHeader.decode(readExactly(socket.inputStream, TRANSFER_HEADER_SIZE) ?: throw EOFException())
        return TransferWireRecord(header, readExactly(socket.inputStream, header.payloadLength) ?: throw EOFException())
    }
    override suspend fun close() = socket.close()
}

private class AndroidDeadlineScheduler : TransferDeadlineScheduler {
    private val executor = Executors.newCachedThreadPool()
    override suspend fun <T> run(timeoutMillis: Long, operation: suspend () -> T): T {
        val future = executor.submit<T> { runSuspend(operation) }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw error
        }
    }
}

class AndroidOutboundRuntime(
    context: Context,
    private val trustRepository: AndroidTrustRepository,
    scheduler: TransferSessionScheduler,
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val controlExecutor = Executors.newCachedThreadPool()
    private val observers = CopyOnWriteArraySet<(AndroidOutboundSnapshot) -> Unit>()
    private val documents = DocumentDraftRepository(context)
    private val stateStore = AndroidOutboundStateStore(AndroidOutboundSnapshot(draft = documents.read()))
    private val browser = AndroidLanBrowser(context, trustRepository) { routes ->
        mutate { state ->
            state.copy(onlinePeerIds = routes.keys.intersect(trustRepository.peers().map { it.keyIdHex }.toSet()))
        }
    }
    private val session = ProductionOutboundTransferSession(
        scheduler,
        AndroidOutboundLocator(browser, trustRepository),
        AndroidDeadlineScheduler(),
    )
    fun observe(observer: (AndroidOutboundSnapshot) -> Unit) {
        observers += observer
        main.post { observer(stateStore.snapshot()) }
    }
    fun removeObserver(observer: (AndroidOutboundSnapshot) -> Unit) { observers -= observer }
    fun snapshot(): AndroidOutboundSnapshot = stateStore.snapshot()
    fun startDiscovery() = browser.start()
    fun stopDiscovery() = browser.close()

    fun prepare(uri: Uri) {
        if (!stateStore.beginPreparation()) return
        notifyObservers()
        executor.execute {
            runCatching { documents.prepare(uri) }
                .onSuccess { draft -> mutate { it.copy(draft = draft, preparing = false, phase = null, failure = null) } }
                .onFailure { error -> mutate {
                    it.copy(
                        preparing = false,
                        phase = TransferActivityPhase.FAILED,
                        failure = error.message ?: "source_unavailable",
                    )
                } }
        }
    }

    fun selectPeer(peerId: String) = mutate { state ->
        if (state.active) state else state.copy(selectedPeerId = peerId, failure = null)
    }

    fun send() {
        if (!stateStore.beginSend()) return
        val starting = stateStore.snapshot()
        val draft = starting.draft ?: return
        val peer = starting.selectedPeerId ?: return
        notifyObservers()
        executor.execute {
            val outcome = runCatching {
                runSuspend {
                    session.execute(
                        OutboundTransferIntent(peer.outboundHexData(), AndroidDocumentPayload(draft, documents)),
                        TransferEventSink { event -> apply(event) },
                    )
                }
            }.getOrElse { error ->
                RoleTransferOutcome(
                    TransferTerminalResult.FAILED,
                    failure = dev.swiftshare.domain.TransferFailure(
                        dev.swiftshare.domain.TransferErrorCategory.NETWORK,
                        error.message ?: "transfer_failed",
                    ),
                )
            }
            if (outcome.result != TransferTerminalResult.COMPLETED) {
                mutate { state ->
                    state.copy(
                        phase = when (outcome.result) {
                            TransferTerminalResult.REJECTED -> TransferActivityPhase.REJECTED
                            TransferTerminalResult.CANCELLED -> TransferActivityPhase.CANCELLED
                            TransferTerminalResult.FAILED -> TransferActivityPhase.FAILED
                            TransferTerminalResult.COMPLETED -> TransferActivityPhase.COMPLETED
                        },
                        failure = outcome.failure?.let { "${it.category.name.lowercase()}: ${it.code}" }
                            ?: outcome.result.name.lowercase(),
                    )
                }
            }
        }
    }

    fun cancel() { controlExecutor.execute { runSuspend { session.cancel("user_cancelled") } } }
    fun unpair(peerId: String) { controlExecutor.execute { runSuspend { session.cancel("peer_unpaired", peerId.outboundHexData()) } } }

    private fun apply(event: TransferSessionEvent) = mutate { state ->
        state.copy(
            phase = event.phase,
            transferredBytes = event.transferredBytes,
            totalBytes = event.totalBytes,
            failure = if (event.phase == TransferActivityPhase.COMPLETED) null else state.failure,
        )
    }

    private fun mutate(transform: (AndroidOutboundSnapshot) -> AndroidOutboundSnapshot) {
        stateStore.mutate(transform)
        notifyObservers()
    }

    private fun notifyObservers() {
        main.post {
            val current = stateStore.snapshot()
            observers.forEach { it(current) }
        }
    }
}

private fun readExactly(input: InputStream, count: Int): ByteArray? {
    val result = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(result, offset, count - offset)
        if (read < 0) return if (offset == 0) null else throw EOFException("truncated_record")
        offset += read
    }
    return result
}

private fun ByteArray.toOutboundHex(): String = joinToString("") { "%02x".format(it) }
private fun String.outboundHexData(): ByteArray {
    require(length == 64)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

private fun <T> runSuspend(operation: suspend () -> T): T {
    val latch = java.util.concurrent.CountDownLatch(1)
    var outcome: Result<T>? = null
    operation.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result; latch.countDown() }
    })
    check(latch.await(10, TimeUnit.MINUTES)) { "operation_timeout" }
    return outcome!!.getOrThrow()
}
