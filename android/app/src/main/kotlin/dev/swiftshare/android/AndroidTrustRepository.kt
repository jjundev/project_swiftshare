package dev.swiftshare.android

import android.content.Context
import android.util.AtomicFile
import dev.swiftshare.domain.PairingDeviceDescriptor
import dev.swiftshare.domain.PeerApprovalPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

data class TrustGenerationToken(
    val keyIdHex: String,
    val canonicalSpki: ByteArray,
    val generation: Long,
)

data class StoredPeer(
    val keyIdHex: String,
    val canonicalSpki: ByteArray,
    val displayName: String,
    val platform: String,
    val pairedAtEpochSeconds: Long,
    val approvalPolicy: PeerApprovalPolicy = PeerApprovalPolicy.REQUIRE_APPROVAL,
) {
    override fun equals(other: Any?): Boolean = other is StoredPeer && keyIdHex == other.keyIdHex &&
        canonicalSpki.contentEquals(other.canonicalSpki) && displayName == other.displayName &&
        platform == other.platform && pairedAtEpochSeconds == other.pairedAtEpochSeconds &&
        approvalPolicy == other.approvalPolicy
    override fun hashCode(): Int = keyIdHex.hashCode()
}

class PeerStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class AndroidTrustRepository(context: Context) {
    private data class Document(
        val trustGeneration: Long,
        val policyRevision: Long,
        val peers: List<StoredPeer>,
    )

    private val file = AtomicFile(File(context.filesDir, "paired-peers-v2.json"))
    private val legacyFile = AtomicFile(File(context.filesDir, "paired-peers-v1.json"))
    private val snapshot = AtomicReference(Document(0, 0, emptyList()))
    @Volatile private var loadFailure: PeerStorageException? = null

    init {
        synchronized(this) {
            try {
                val loaded = when {
                    file.baseFile.exists() -> readDocument(file)
                    legacyFile.baseFile.exists() -> readLegacyAndMigrate()
                    else -> Document(0, 0, emptyList())
                }
                snapshot.set(loaded)
            } catch (error: Exception) {
                loadFailure = PeerStorageException("peer_storage_unavailable", error)
            }
        }
    }

    fun storageError(): String? = loadFailure?.message

    @Synchronized fun peers(): List<StoredPeer> = snapshot.get().peers.sortedBy { it.keyIdHex }

    @Synchronized fun peer(keyIdHex: String): StoredPeer? = snapshot.get().peers.firstOrNull { it.keyIdHex == keyIdHex }

    @Synchronized fun upsert(descriptor: PairingDeviceDescriptor, now: Instant = Instant.now()): StoredPeer {
        requireHealthy()
        val document = snapshot.get()
        val keyId = descriptor.keyId.toTrustHex()
        val prior = document.peers.firstOrNull { it.keyIdHex == keyId }
        val record = StoredPeer(
            keyId, descriptor.canonicalSpki.copyOf(), descriptor.displayName, descriptor.platform,
            prior?.pairedAtEpochSeconds ?: now.epochSecond,
            prior?.approvalPolicy ?: PeerApprovalPolicy.REQUIRE_APPROVAL,
        )
        val peers = document.peers.filterNot { it.keyIdHex == keyId } + record
        val trustChanged = prior == null || !MessageDigest.isEqual(prior.canonicalSpki, record.canonicalSpki)
        persist(document.copy(
            trustGeneration = document.trustGeneration + if (trustChanged) 1 else 0,
            peers = peers.sortedBy { it.keyIdHex },
        ))
        return record
    }

    @Synchronized fun setApprovalPolicy(keyIdHex: String, policy: PeerApprovalPolicy): StoredPeer {
        requireHealthy()
        val document = snapshot.get()
        val prior = document.peers.firstOrNull { it.keyIdHex == keyIdHex }
            ?: throw PeerStorageException("peer_not_found")
        if (prior.approvalPolicy == policy) return prior
        val updated = prior.copy(approvalPolicy = policy)
        persist(document.copy(
            policyRevision = document.policyRevision + 1,
            peers = document.peers.map { if (it.keyIdHex == keyIdHex) updated else it },
        ))
        return updated
    }

    @Synchronized fun remove(keyIdHex: String): Boolean {
        requireHealthy()
        val document = snapshot.get()
        val peers = document.peers.filterNot { it.keyIdHex == keyIdHex }
        if (peers.size == document.peers.size) return false
        persist(document.copy(
            trustGeneration = document.trustGeneration + 1,
            policyRevision = document.policyRevision + 1,
            peers = peers,
        ))
        return true
    }

    @Synchronized fun clear() {
        requireHealthy()
        val document = snapshot.get()
        if (document.peers.isEmpty()) return
        persist(Document(document.trustGeneration + 1, document.policyRevision + 1, emptyList()))
    }

    fun trustedSpkiSnapshot(): Map<String, ByteArray> = snapshot.get().peers.associate {
        it.keyIdHex to it.canonicalSpki.copyOf()
    }

    fun containsSpki(spki: ByteArray): Boolean {
        val id = MessageDigest.getInstance("SHA-256").digest(spki).toTrustHex()
        return snapshot.get().peers.firstOrNull { it.keyIdHex == id }
            ?.canonicalSpki?.let { MessageDigest.isEqual(it, spki) } == true
    }

    fun peerForSpki(spki: ByteArray): StoredPeer? {
        val id = MessageDigest.getInstance("SHA-256").digest(spki).toTrustHex()
        return snapshot.get().peers.firstOrNull {
            it.keyIdHex == id && MessageDigest.isEqual(it.canonicalSpki, spki)
        }
    }

    fun authenticationToken(spki: ByteArray): TrustGenerationToken? {
        val document = snapshot.get()
        val id = MessageDigest.getInstance("SHA-256").digest(spki).toTrustHex()
        val trusted = document.peers.firstOrNull { it.keyIdHex == id } ?: return null
        if (!MessageDigest.isEqual(trusted.canonicalSpki, spki)) return null
        return TrustGenerationToken(id, spki.copyOf(), document.trustGeneration)
    }

    fun validate(token: TrustGenerationToken): StoredPeer? {
        val document = snapshot.get()
        if (document.trustGeneration != token.generation) return null
        return document.peers.firstOrNull {
            it.keyIdHex == token.keyIdHex && MessageDigest.isEqual(it.canonicalSpki, token.canonicalSpki)
        }
    }

    private fun readLegacyAndMigrate(): Document {
        val root = legacyFile.openRead().use { JSONObject(it.readBytes().decodeToString()) }
        require(root.getInt("schema") == 1) { "unknown_peer_schema" }
        val peers = decodePeers(root.getJSONArray("peers"), legacy = true)
        val migrated = Document(if (peers.isEmpty()) 0 else 1, 0, peers)
        persist(migrated)
        return migrated
    }

    private fun readDocument(source: AtomicFile): Document {
        val root = source.openRead().use { JSONObject(it.readBytes().decodeToString()) }
        require(root.getInt("schema") == 2) { "unknown_peer_schema" }
        return Document(
            root.getLong("trustGeneration"),
            root.getLong("policyRevision"),
            decodePeers(root.getJSONArray("peers"), legacy = false),
        )
    }

    private fun decodePeers(array: JSONArray, legacy: Boolean): List<StoredPeer> = buildList {
        val ids = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val value = array.getJSONObject(i)
            val spki = Base64.getDecoder().decode(value.getString("spki"))
            val descriptor = PairingDeviceDescriptor(spki, value.getString("name"), value.getString("platform"))
            val id = descriptor.keyId.toTrustHex()
            require(id == value.getString("keyId") && ids.add(id)) { "invalid_peer_identity" }
            val policy = if (legacy) PeerApprovalPolicy.REQUIRE_APPROVAL else when (value.getString("approval")) {
                "require_approval" -> PeerApprovalPolicy.REQUIRE_APPROVAL
                "auto_accept" -> PeerApprovalPolicy.AUTO_ACCEPT
                else -> error("invalid_approval_policy")
            }
            add(StoredPeer(id, spki, descriptor.displayName, descriptor.platform, value.getLong("pairedAt"), policy))
        }
    }

    private fun persist(document: Document) {
        val peers = JSONArray()
        document.peers.forEach { peer ->
            peers.put(JSONObject().apply {
                put("keyId", peer.keyIdHex)
                put("spki", Base64.getEncoder().encodeToString(peer.canonicalSpki))
                put("name", peer.displayName)
                put("platform", peer.platform)
                put("pairedAt", peer.pairedAtEpochSeconds)
                put("approval", when (peer.approvalPolicy) {
                    PeerApprovalPolicy.REQUIRE_APPROVAL -> "require_approval"
                    PeerApprovalPolicy.AUTO_ACCEPT -> "auto_accept"
                })
            })
        }
        val bytes = JSONObject()
            .put("schema", 2)
            .put("trustGeneration", document.trustGeneration)
            .put("policyRevision", document.policyRevision)
            .put("peers", peers)
            .toString().toByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
            snapshot.set(document)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw PeerStorageException("peer_storage_write_failed", error)
        }
    }

    private fun requireHealthy() { loadFailure?.let { throw it } }
}

internal fun ByteArray.toTrustHex(): String = joinToString("") { "%02x".format(it) }
