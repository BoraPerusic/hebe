package com.talos.memory.embeddings

interface EmbeddingProvider {
    val model: String
    val dim: Int

    suspend fun embed(texts: List<String>): List<FloatArray>
}
