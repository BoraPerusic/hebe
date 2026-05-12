package com.hebe.security.receipts

import com.hebe.api.PartialReceipt
import com.hebe.api.Receipts as ReceiptsInterface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class Receipts(
    private val dir: Path,
    private val signingKey: Ed25519PrivateKey,
) : ReceiptsInterface {
    private val mutex = Mutex()
    private var lastHash: String = ZERO_HASH
    private var currentSeq: Long = 0
    private var fsyncCounter = 0
    private val fsyncBatchSize = 16

    companion object {
        private val ZERO_HASH = "sha256:" + "0".repeat(64)
        const val SIG_ALGORITHM = "ed25519:base64url"
    }

    suspend fun init(): Receipts {
        Files.createDirectories(dir)
        loadLastState()
        return this
    }

    private fun loadLastState() {
        val files = try {
            Files.list(dir).filter { it.fileName.toString().endsWith(".log") }.sorted().toList()
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

    override suspend fun append(partial: PartialReceipt): Long = mutex.withLock {
        val seq = currentSeq++
        val ts = java.time.Instant.now().toString()
        val canonical = buildCanonical(seq, ts, partial)
        val selfHash = "sha256:${sha256Hex(canonical.toByteArray())}"
        val sigBytes = signingKey.sign(hexToBytes(selfHash.removePrefix("sha256:")))
        val sig = "$SIG_ALGORITHM:${java.util.Base64.getUrlEncoder().encodeToString(sigBytes)}"

        val receipt = Receipt(
            seq = seq,
            ts = ts,
            sessionId = partial.sessionId,
            turnId = partial.turnId,
            tool = partial.tool,
            argsRedacted = partial.argsRedacted,
            risk = partial.risk,
            approval = ApprovalRecord(required = false),
            durationMs = partial.durationMs,
            ok = partial.ok,
            resultHash = "sha256:${sha256Hex(partial.argsRedacted.toByteArray())}",
            prevHash = lastHash,
            selfHash = selfHash,
            sig = sig,
        )

        val monthFile = dir.resolve("${ts.substring(0, 7)}.log")
        Files.writeString(
            monthFile,
            Json.encodeToString(receiptSerializer, receipt) + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )

        lastHash = receipt.selfHash
        fsyncCounter++
        if (fsyncCounter >= fsyncBatchSize) {
            fsyncCounter = 0
        }

        return seq
    }

    private fun buildCanonical(seq: Long, ts: String, partial: PartialReceipt): String {
        return buildString {
            append("seq:$seq")
            append(",ts:$ts")
            append(",sessionId:${partial.sessionId}")
            append(",turnId:${partial.turnId}")
            append(",tool:${partial.tool}")
            append(",argsRedacted:${partial.argsRedacted}")
            append(",risk:${partial.risk}")
            append(",approval:{required:false}")
            append(",durationMs:${partial.durationMs}")
            append(",ok:${partial.ok}")
            append(",resultHash:sha256:${sha256Hex(partial.argsRedacted.toByteArray())}")
            append(",prevHash:$lastHash")
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
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
