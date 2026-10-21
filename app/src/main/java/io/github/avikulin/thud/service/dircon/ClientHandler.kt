package io.github.avikulin.thud.service.dircon

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles a single DirCon client connection.
 */
class ClientHandler(
    private val socket: Socket,
    private val server: DirConServer
) {
    companion object {
        private const val TAG = "DirConClient"
    }

    private val input: InputStream = socket.getInputStream()
    private val clientAddress: String = socket.inetAddress.hostAddress ?: "unknown"

    /** Characteristics with notifications enabled */
    val enabledNotifications = ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var isClosed = false
    @Volatile private var lastActivityNanos = System.nanoTime()
    private val writer = DirConSocketWriter(socket,
        onClose = { isClosed = true },
        onSent = { lastActivityNanos = System.nanoTime() })

    /**
     * Run the client handler loop.
     * Reads packets and dispatches to server for handling.
     */
    suspend fun run() {
        try {
            val reader = DirConInput(input, lastActivity = { lastActivityNanos },
                beforeRead = { socket.soTimeout = it })
            while (!isClosed && !socket.isClosed) {
                val packet = reader.readPacket() ?: break
                lastActivityNanos = System.nanoTime()
                Log.d(TAG, "[$clientAddress] Received: $packet")
                val response = server.handlePacket(packet, this)
                if (response != null) {
                    sendPacket(response)
                }
            }
        } catch (e: IOException) {
            if (!isClosed) {
                Log.d(TAG, "[$clientAddress] Connection error: ${e.message}")
            }
        } finally {
            close()
        }
    }

    /**
     * Send a packet to the client.
     */
    fun sendPacket(packet: DirConPacket) {
        if (isClosed || socket.isClosed) return

        writer.send(packet.encode())
    }

    /**
     * Send a notification if the client has enabled it for this characteristic.
     */
    fun sendNotificationIfEnabled(characteristicUuid: Int, data: ByteArray) {
        if (!enabledNotifications.contains(characteristicUuid)) return

        // Build notification packet
        val payload = DirConPacket.encodeUuid16(characteristicUuid) + data

        val packet = DirConPacket(
            identifier = DirConPacket.MSG_UNSOLICITED_NOTIFICATION,
            sequenceNumber = 0,  // Notifications use sequence 0
            responseCode = DirConPacket.RESPONSE_SUCCESS,
            payload = payload
        )

        sendPacket(packet)
    }

    /**
     * Close the client connection.
     */
    fun close() {
        writer.close()
    }

    internal fun awaitWriterTermination(): Boolean = writer.awaitTermination(6_000)
}
