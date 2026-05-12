package com.hebe.security.estop

import java.nio.file.Path

object EstopIpc {
    private const val SOCKET_NAME = ".estop.sock"

    fun getSocketPath(dataDir: Path): Path {
        return dataDir.resolve(SOCKET_NAME)
    }

    fun sendStop(socketPath: Path): Boolean {
        return try {
            val address = java.net.UnixDomainSocketAddress.of(socketPath)
            java.net.Socket().use { socket ->
                socket.connect(address)
                socket.getOutputStream().use { out ->
                    out.write("STOP\n".toByteArray())
                    out.flush()
                }
                socket.getInputStream().use { input ->
                    val response = input.readBytes().toString(Charsets.UTF_8).trim()
                    return response == "OK"
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
