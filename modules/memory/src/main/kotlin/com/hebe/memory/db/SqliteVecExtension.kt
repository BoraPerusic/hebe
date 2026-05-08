package com.hebe.memory.db

import java.lang.reflect.Method
import java.sql.Connection

object SqliteVecExtension {
    private var loadedPlatform: String? = null

    fun load(connection: Connection) {
        val (os, arch) = detectPlatform()
        val libName = libName(os)
        val resource = "/native/sqlite-vec/$os-$arch/$libName"

        val url =
            SqliteVecExtension::class.java.getResource(resource)
                ?: error("sqlite-vec not found for $os-$arch at $resource")

        val tmpFile =
            if (url.protocol == "file") {
                java.io.File(url.path)
            } else {
                val tmpDir =
                    java.nio.file.Files
                        .createTempDirectory("sqlite-vec")
                val file = tmpDir.resolve(libName)
                java.nio.file.Files
                    .copy(url.openStream(), file)
                file.toFile().setExecutable(true)
                file.toFile()
            }

        val enableLoadExtension: Method =
            connection.javaClass.getMethod("enableLoadExtension", Boolean::class.java)
        enableLoadExtension.invoke(connection, true)
        connection.createStatement().use { st ->
            st.execute("SELECT load_extension('${tmpFile.absolutePath}')")
        }
        loadedPlatform = "$os-$arch"
    }

    private fun detectPlatform(): Pair<String, String> {
        val os =
            when {
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin"
                System.getProperty("os.name").lowercase().contains("linux") -> "linux"
                else -> error("Unsupported OS: ${System.getProperty("os.name")}")
            }
        val arch =
            when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> error("Unsupported arch: ${System.getProperty("os.arch")}")
            }
        return os to arch
    }

    private fun libName(os: String): String =
        when (os) {
            "darwin" -> "vec0.dylib"
            "linux" -> "vec0.so"
            else -> error("Unsupported OS: $os")
        }
}
