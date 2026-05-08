package com.hebe.memory.chunker

data class ChunkerConfig(
    val targetWords: Int = 800,
    val overlapPercent: Double = 0.15,
    val minWords: Int = 50,
)

data class Chunk(
    val index: Int,
    val content: String,
    val tokenCount: Int,
)

object Chunker {
    private const val HEADING_SEARCH_THRESHOLD = 0.7
    private val HEADING_PATTERN = Regex("^#{1,6}\\s+")

    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    fun chunk(
        text: String,
        cfg: ChunkerConfig = ChunkerConfig(),
    ): List<Chunk> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        if (tokens.size < cfg.minWords) {
            return listOf(Chunk(0, text.trim(), tokens.size))
        }

        val step = (cfg.targetWords * (1 - cfg.overlapPercent)).toInt().coerceAtLeast(1)
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0

        while (start < tokens.size) {
            val end = (start + cfg.targetWords).coerceAtMost(tokens.size)
            val chunkTokens = tokens.subList(start, end)

            val breakAt = findHeadingBreakPoint(chunkTokens)
            val actualEnd = breakAt ?: end

            val actualChunkTokens = tokens.subList(start, actualEnd)
            chunks.add(Chunk(index, actualChunkTokens.joinToString(" "), actualChunkTokens.size))
            index++

            if (actualEnd >= tokens.size) break
            start += step
            if (start >= tokens.size) break
        }

        val last = chunks.lastOrNull()
        if (last != null && last.tokenCount < cfg.minWords && chunks.size > 1) {
            val secondLast = chunks[chunks.size - 2]
            val mergedContent = "${secondLast.content} ${last.content}".trim()
            val mergedTokens = mergedContent.split(Regex("\\s+")).filter { it.isNotEmpty() }
            chunks.removeAt(chunks.lastIndex)
            chunks.removeAt(chunks.lastIndex)
            chunks.add(Chunk(secondLast.index, mergedContent, mergedTokens.size))
        }

        return chunks.mapIndexed { i, c -> c.copy(index = i) }
    }

    private fun findHeadingBreakPoint(tokens: List<String>): Int? {
        val threshold = (tokens.size * HEADING_SEARCH_THRESHOLD).toInt().coerceAtLeast(1)
        for (i in threshold until tokens.size) {
            if (HEADING_PATTERN.containsMatchIn(tokens[i])) {
                return i
            }
        }
        return null
    }
}
