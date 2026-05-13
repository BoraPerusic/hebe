package com.hebe.security.receipts

import com.hebe.api.PartialReceipt
import com.hebe.api.Receipts as ReceiptsInterface
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class Receipts(
    private val dir: Path,
    private val signingKey: Ed25519PrivateKey,
) : ReceiptsInterface {
    private val mutex = Mutex()
    private var lastHash: String = ZERO_HASH
    private var currentSeq: Long = 0
    private var fsyncCounter = 0
    private val fsyncBatchSize = 16
    private var currentChannel: java.nio.channels.FileChannel? = null
    private var currentMonth: String = ""

    companion object {
        private val ZERO_HASH = "sha256:" + "0".repeat(64)
        const val SIG_ALGORITHM = "ed25519:base64url"
    }

    suspend fun init(): Receipts {
        Files.createDirectories(dir)
        writePublicKey()
        loadLastState()
        return this
    }

    private fun writePublicKey() {
        val pubKeyPath = dir.resolve("public.key")
        if (Files.notExists(pubKeyPath)) {
            val pubKeyBytes = signingKey.publicKeyBytes()
            Files.writeString(
                pubKeyPath,
                java.util.Base64
                    .getEncoder()
                    .encodeToString(pubKeyBytes),
            )
        }
    }

    private fun loadLastState() {
        val files =
            try {
                Files
                    .list(dir)
                    .filter { it.fileName.toString().endsWith(".log") }
                    .sorted()
                    .toList()
            } catch (e: Exception) {
                return
            }
        if (files.isEmpty()) return
        val lastFile = files.last()
        try {
            val lastLine = Files.readAllLines(lastFile).lastOrNull() ?: return
            val lastReceipt = Receipt.fromJson(lastLine)
            currentSeq = lastReceipt.seq + 1
            lastHash = lastReceipt.selfHash
        } catch (e: Exception) {
            currentSeq = 0
            lastHash = ZERO_HASH
        }
    }

    override suspend fun append(partial: PartialReceipt): Long =
        mutex.withLock {
            val seq = currentSeq++
            val ts =
                java.time.Instant
                    .now()
                    .toString()
            val month = ts.substring(0, 7)
            val argsRedactedJson = partial.argsRedacted
            val argsRedactedStr = argsRedactedJson.toString()

            val canonicalEntries =
                listOf(
                    "seq" to seq,
                    "ts" to ts,
                    "sessionId" to partial.sessionId,
                    "turnId" to partial.turnId,
                    "tool" to partial.tool,
                    "argsRedacted" to argsRedactedStr,
                    "risk" to partial.risk,
                    "approval" to mapOf("required" to false),
                    "durationMs" to partial.durationMs,
                    "ok" to partial.ok,
                    "resultHash" to "sha256:${sha256Hex(argsRedactedStr.toByteArray())}",
                    "prevHash" to lastHash,
                )
            val canonical = CanonicalJson.serializeCanonical(canonicalEntries)
            val selfHash = "sha256:${sha256Hex(canonical.toByteArray())}"
            val sigBytes = signingKey.sign(hexToBytes(selfHash.removePrefix("sha256:")))
            val sig = "$SIG_ALGORITHM:${java.util.Base64.getUrlEncoder().encodeToString(sigBytes)}"

            val receipt =
                Receipt(
                    seq = seq,
                    ts = ts,
                    sessionId = partial.sessionId,
                    turnId = partial.turnId,
                    tool = partial.tool,
                    argsRedacted = argsRedactedStr,
                    risk = partial.risk,
                    approval = ApprovalRecord(required = false),
                    durationMs = partial.durationMs,
                    ok = partial.ok,
                    resultHash = "sha256:${sha256Hex(argsRedactedStr.toByteArray())}",
                    prevHash = lastHash,
                    selfHash = selfHash,
                    sig = sig,
                )

            val monthFile = dir.resolve("$month.log")

            if (month != currentMonth) {
                currentChannel?.close()
                currentChannel = null
                currentMonth = month
            }

            var channel = currentChannel
            if (channel == null) {
                channel =
                    FileChannel.open(
                        monthFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE,
                    )
                currentChannel = channel
            }

            val line = Json.encodeToString(receiptSerializer, receipt) + "\n"
            channel!!.write(java.nio.ByteBuffer.wrap(line.toByteArray()))

            lastHash = receipt.selfHash
            fsyncCounter++
            if (fsyncCounter >= fsyncBatchSize) {
                channel!!.force(true)
                fsyncCounter = 0
            }

            return seq
        }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private val receiptSerializer: KSerializer<Receipt> = serializer<Receipt>()

@Serializable
data class Receipt(
    val seq: Long,
    val ts: String,
    val sessionId: String,
    val turnId: String,
    val tool: String,
    val argsRedacted: String,
    val risk: String,
    val approval: ApprovalRecord,
    val durationMs: Long,
    val ok: Boolean,
    val resultHash: String,
    val prevHash: String,
    val selfHash: String,
    val sig: String,
) {
    companion object {
        fun fromJson(json: String): Receipt = Json.decodeFromString(receiptSerializer, json)
    }
}

@Serializable
data class ApprovalRecord(
    val required: Boolean,
    val approved: Boolean? = null,
    val denied: Boolean? = null,
)
