package dev.swiftshare.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

enum class PairingRecordType(val code: Int) { CLIENT_START(1), SERVER_CHALLENGE(2), CLIENT_PROOF(3), SERVER_PROOF(4), CLIENT_DECISION(5), COMMIT_RECEIPT(6), COMMIT_ACK(7), ERROR(8) }
data class PairingWireDevice(val certificateDer: ByteArray, val canonicalSpki: ByteArray, val displayName: String, val platform: String) {
    val descriptor get() = PairingDeviceDescriptor(canonicalSpki, displayName, platform)
}
sealed interface PairingWireMessage { val type: PairingRecordType }
data class ClientStart(val sessionId: UUID, val token: ByteArray, val device: PairingWireDevice, val nonce: ByteArray) : PairingWireMessage { override val type = PairingRecordType.CLIENT_START }
data class ServerChallenge(val device: PairingWireDevice, val nonce: ByteArray) : PairingWireMessage { override val type = PairingRecordType.SERVER_CHALLENGE }
data class ClientProof(val signature: ByteArray) : PairingWireMessage { override val type = PairingRecordType.CLIENT_PROOF }
data class ServerProof(val signature: ByteArray) : PairingWireMessage { override val type = PairingRecordType.SERVER_PROOF }
data class ClientDecision(val accepted: Boolean, val signature: ByteArray) : PairingWireMessage { override val type = PairingRecordType.CLIENT_DECISION }
data class CommitReceipt(val sessionId: UUID, val transcriptSha256: ByteArray, val macKeyId: ByteArray, val androidKeyId: ByteArray, val committedAt: Instant, val signature: ByteArray) : PairingWireMessage { override val type = PairingRecordType.COMMIT_RECEIPT }
data class CommitAck(val receiptSha256: ByteArray) : PairingWireMessage { override val type = PairingRecordType.COMMIT_ACK }
data class PairingWireError(val code: String) : PairingWireMessage { override val type = PairingRecordType.ERROR }

object PairingWireCodec {
    fun encode(message: PairingWireMessage): ByteArray = PWriter().apply {
        when (message) {
            is ClientStart -> { bytes(1, message.sessionId.toBytes()); bytes(2, message.token); bytes(3, device(message.device)); bytes(4, message.nonce) }
            is ServerChallenge -> { bytes(1, device(message.device)); bytes(2, message.nonce) }
            is ClientProof -> bytes(1, message.signature); is ServerProof -> bytes(1, message.signature)
            is ClientDecision -> { variable(1, if (message.accepted) 1 else 0); bytes(2, message.signature) }
            is CommitReceipt -> { bytes(1, message.sessionId.toBytes()); bytes(2, message.transcriptSha256); bytes(3, message.macKeyId); bytes(4, message.androidKeyId); variable(5, message.committedAt.epochSecond); bytes(6, message.signature) }
            is CommitAck -> bytes(1, message.receiptSha256); is PairingWireError -> string(1, message.code)
        }
    }.data

    fun decode(type: PairingRecordType, data: ByteArray): PairingWireMessage {
        require(data.size <= PairingLimits().maximumFrameBytes)
        val r = PReader(data); val bytes = mutableMapOf<Int, ByteArray>(); val ints = mutableMapOf<Int, Long>()
        while (true) { val f = r.next() ?: break; if (f.wire == 0) ints[f.number] = r.variable(f) else bytes[f.number] = r.bytes(f) }
        fun required(n: Int) = requireNotNull(bytes[n])
        fun sized(n: Int, size: Int) = required(n).also { require(it.size == size) }
        return when (type) {
            PairingRecordType.CLIENT_START -> ClientStart(sized(1,16).toUuid(), required(2), decodeDevice(required(3)), sized(4,32))
            PairingRecordType.SERVER_CHALLENGE -> ServerChallenge(decodeDevice(required(1)), sized(2,32))
            PairingRecordType.CLIENT_PROOF -> ClientProof(sized(1,64)); PairingRecordType.SERVER_PROOF -> ServerProof(sized(1,64))
            PairingRecordType.CLIENT_DECISION -> ClientDecision(ints[1] == 1L, sized(2,64))
            PairingRecordType.COMMIT_RECEIPT -> CommitReceipt(sized(1,16).toUuid(), sized(2,32), sized(3,32), sized(4,32), Instant.ofEpochSecond(requireNotNull(ints[5])), sized(6,64))
            PairingRecordType.COMMIT_ACK -> CommitAck(sized(1,32))
            PairingRecordType.ERROR -> PairingWireError(required(1).decodeToString().also { require(it.toByteArray().size <= 128) })
        }
    }
    private fun device(d: PairingWireDevice) = PWriter().apply { bytes(1,d.certificateDer); bytes(2,d.canonicalSpki); string(3,d.displayName); string(4,d.platform) }.data
    private fun decodeDevice(data: ByteArray): PairingWireDevice { val r=PReader(data); val v=mutableMapOf<Int,ByteArray>(); while(true){val f=r.next()?:break; require(f.wire==2); v[f.number]=r.bytes(f)}; return PairingWireDevice(requireNotNull(v[1]).also{require(it.size<=8192)},requireNotNull(v[2]).also{require(it.size<=512)},requireNotNull(v[3]).decodeToString(),requireNotNull(v[4]).decodeToString()).also{it.descriptor} }
}
private class PWriter { private val out=ByteArrayOutputStream(); val data get()=out.toByteArray(); fun variable(f:Int,v:Long){varint((f shl 3).toLong());varint(v)}; fun bytes(f:Int,v:ByteArray){varint(((f shl 3)or 2).toLong());varint(v.size.toLong());out.write(v)};fun string(f:Int,v:String)=bytes(f,v.toByteArray());private fun varint(input:Long){var v=input;while(v and -128L!=0L){out.write(((v and 127)or 128).toInt());v=v ushr 7};out.write(v.toInt())} }
private class PReader(private val data:ByteArray){data class Field(val number:Int,val wire:Int);private var i=0;fun next():Field?{if(i==data.size)return null;val t=varint();return Field((t ushr 3).toInt(),(t and 7).toInt()).also{require(it.number>0&&it.wire in setOf(0,2))}};fun variable(f:Field):Long{require(f.wire==0);return varint()};fun bytes(f:Field):ByteArray{require(f.wire==2);val n=varint().toInt();require(n>=0&&i+n<=data.size);return data.copyOfRange(i,i+n).also{i+=n}};private fun varint():Long{var v=0L;for(s in 0..63 step 7){require(i<data.size);val b=data[i++].toInt()and 255;v=v or((b and 127).toLong()shl s);if(b and 128==0)return v};error("varint")}}
