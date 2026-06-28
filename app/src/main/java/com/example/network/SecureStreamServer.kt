package com.example.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureStreamServer(
    private val port: Int,
    private val onClientConnected: (count: Int) -> Unit,
    private val onPairingGenerated: (code: String) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)

    private val clients = ConcurrentHashMap<String, ConnectedClient>()
    private val secureRandom = SecureRandom()
    private var pairingCode: String = ""

    companion object {
        private const val TAG = "SecureStreamServer"

        // Custom Binary Protocol Constants
        const val PACKET_TYPE_VIDEO = 1
        const val PACKET_TYPE_HANDSHAKE_REQ = 2
        const val PACKET_TYPE_HANDSHAKE_RESP = 3
        const val PACKET_TYPE_INPUT_EVENT = 4
        const val PACKET_TYPE_HEARTBEAT = 5

        // Encryption Configuration (AES-256-GCM)
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_LEN_BYTES = 32 // 256 bits
        private const val IV_LEN_BYTES = 12 // Standard GCM IV size
        private const val TAG_LEN_BITS = 128
    }

    private data class ConnectedClient(
        val id: String,
        val socket: Socket,
        val outputStream: DataOutputStream,
        var isPaired: Boolean = false,
        var sessionKey: SecretKeySpec? = null
    )

    fun start() {
        // Generate cryptographically secure pairing code (alphanumeric, readable)
        pairingCode = generatePairingCode()
        onPairingGenerated(pairingCode)

        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Secure Mirroring Server listening on port $port")

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    val clientId = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                    Log.i(TAG, "Inbound connection attempt from $clientId")

                    launch {
                        handleClientConnection(clientId, clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket Server encountered an exception", e)
            }
        }
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Excluded confusing characters (I, O, 0, 1)
        return (1..6)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }

    private fun deriveMasterKey(pairingCode: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val masterKeyBytes = digest.digest(pairingCode.trim().toByteArray(Charsets.UTF_8))
        return SecretKeySpec(masterKeyBytes, "AES")
    }

    private suspend fun CoroutineScope.handleClientConnection(clientId: String, socket: Socket) {
        socket.tcpNoDelay = true // Disable Nagle's algorithm for instant low-latency packet delivery
        socket.soTimeout = 5000 // Handshake timeout

        val inputStream = DataInputStream(socket.getInputStream())
        val outputStream = DataOutputStream(socket.getOutputStream())

        val client = ConnectedClient(clientId, socket, outputStream)
        clients[clientId] = client

        try {
            // Wait for custom packet type: Handshake
            val packetType = inputStream.readInt()
            val payloadLength = inputStream.readInt()
            val timestamp = inputStream.readLong()
            val flags = inputStream.readInt()

            if (packetType != PACKET_TYPE_HANDSHAKE_REQ || payloadLength > 1024) {
                Log.w(TAG, "Aborting $clientId: Invalid initial packet type or payload size.")
                disconnectClient(clientId)
                return
            }

            val payload = ByteArray(payloadLength)
            inputStream.readFully(payload)

            // Step 1: Secure Handshake Auth Check & Ephemeral Session Key Exchange
            val masterKeySpec = deriveMasterKey(pairingCode)
            val decryptedHandshake = decryptPayload(payload, masterKeySpec)
            
            val sessionKeyBytes: ByteArray
            val responsePayload: ByteArray

            if (decryptedHandshake != null && decryptedHandshake.size >= 16) {
                // High-Security Path: Cryptographic AES-GCM Handshake request received
                Log.i(TAG, "Cryptographic AES-GCM handshake request received and validated from $clientId")
                
                // Handshake payload format: magic "NEXUS_HANDSHAKE" (15 bytes) + 16 bytes challenge
                val magicStr = "NEXUS_HANDSHAKE"
                val magicBytes = magicStr.toByteArray(Charsets.UTF_8)
                val isMagicValid = decryptedHandshake.take(magicBytes.size).toByteArray().contentEquals(magicBytes)
                
                if (!isMagicValid) {
                    Log.w(TAG, "Aborting $clientId: Decrypted payload but Handshake Magic was invalid.")
                    writePacketHeader(outputStream, PACKET_TYPE_HANDSHAKE_RESP, 0, 0, 0)
                    disconnectClient(clientId)
                    return
                }

                val clientChallenge = decryptedHandshake.copyOfRange(magicBytes.size, decryptedHandshake.size)
                
                // Step 2: Ephemeral Session Key Generation (AES-256 key)
                sessionKeyBytes = ByteArray(KEY_LEN_BYTES)
                secureRandom.nextBytes(sessionKeyBytes)
                
                // Step 3: Response payload structure: clientChallenge (16 bytes) + ephemeral session key (32 bytes)
                val rawResponse = clientChallenge + sessionKeyBytes
                val encryptedResponse = encryptPayload(rawResponse, masterKeySpec)
                
                if (encryptedResponse == null) {
                    Log.e(TAG, "Failed to encrypt secure handshake response for $clientId.")
                    writePacketHeader(outputStream, PACKET_TYPE_HANDSHAKE_RESP, 0, 0, 0)
                    disconnectClient(clientId)
                    return
                }
                
                responsePayload = encryptedResponse
                client.sessionKey = SecretKeySpec(sessionKeyBytes, "AES")
                client.isPaired = true
                Log.d(TAG, "Cryptographic session parameters established with $clientId.")
            } else {
                // Backward-Compatibility Path: Fallback to standard plain-text pairing token check
                val clientAuthToken = String(payload, Charsets.UTF_8).trim()
                if (clientAuthToken != pairingCode) {
                    Log.w(TAG, "Aborting $clientId: Authentication failure. Invalid pairing token received.")
                    writePacketHeader(outputStream, PACKET_TYPE_HANDSHAKE_RESP, 0, 0, 0)
                    disconnectClient(clientId)
                    return
                }
                
                Log.i(TAG, "Legacy plain-text handshake validated successfully for $clientId")
                
                // Generate fallback ephemeral session key
                sessionKeyBytes = ByteArray(KEY_LEN_BYTES)
                secureRandom.nextBytes(sessionKeyBytes)
                
                responsePayload = sessionKeyBytes
                client.sessionKey = SecretKeySpec(sessionKeyBytes, "AES")
                client.isPaired = true
            }

            // Step 4: Transmit handshake response packet to the remote client
            writePacketHeader(outputStream, PACKET_TYPE_HANDSHAKE_RESP, responsePayload.size, System.currentTimeMillis(), 0)
            outputStream.write(responsePayload)
            outputStream.flush()

            // Handshake completed successfully. Update status and expand socket timeout for keep-alive
            socket.soTimeout = 0 // Remove timeout for continuous stream
            onClientConnected(clients.filter { it.value.isPaired }.size)
            Log.i(TAG, "Client $clientId authenticated successfully. Secure session established.")

            // Step 4: Stream Input command Listener Loop
            while (isActive) {
                val inputPacketType = inputStream.readInt()
                val length = inputStream.readInt()
                val ts = inputStream.readLong()
                val packetFlags = inputStream.readInt()

                if (length < 0 || length > 16384) break

                val dataPayload = ByteArray(length)
                inputStream.readFully(dataPayload)

                when (inputPacketType) {
                    PACKET_TYPE_HEARTBEAT -> {
                        // Keep alive verification
                        writePacketHeader(outputStream, PACKET_TYPE_HEARTBEAT, 0, System.currentTimeMillis(), 0)
                        outputStream.flush()
                    }
                    PACKET_TYPE_INPUT_EVENT -> {
                        // Decrypt input events sent by client
                        val key = client.sessionKey
                        if (key != null) {
                            val decryptedPayload = decryptPayload(dataPayload, key)
                            if (decryptedPayload != null) {
                                handleInputEvent(clientId, decryptedPayload)
                            }
                        } else {
                            Log.w(TAG, "Cannot decrypt input event for $clientId: Session key not established.")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.d(TAG, "Client socket $clientId disconnected: ${e.localizedMessage}")
        } finally {
            disconnectClient(clientId)
        }
    }

    private fun encryptPayload(payload: ByteArray, secretKey: SecretKeySpec): ByteArray? {
        return try {
            val iv = ByteArray(IV_LEN_BYTES)
            secureRandom.nextBytes(iv)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LEN_BITS, iv))
            val cipherText = cipher.doFinal(payload)
            iv + cipherText // Prepend IV for decryption
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failure", e)
            null
        }
    }

    private fun decryptPayload(encryptedData: ByteArray, secretKey: SecretKeySpec): ByteArray? {
        if (encryptedData.size < IV_LEN_BYTES) return null
        return try {
            val iv = encryptedData.copyOfRange(0, IV_LEN_BYTES)
            val cipherText = encryptedData.copyOfRange(IV_LEN_BYTES, encryptedData.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LEN_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failure", e)
            null
        }
    }

    private fun handleInputEvent(clientId: String, decryptedPayload: ByteArray) {
        val eventStr = String(decryptedPayload, Charsets.UTF_8)
        Log.v(TAG, "Received decrypted remote input action from $clientId: $eventStr")
        // Route input parameters dynamically to the Input Injection engine (ADB bridge system)
        com.example.input.InputInjector.inject(eventStr)
    }

    /**
     * Broadcasts a video frame slice securely to all paired client sockets.
     */
    fun broadcastVideoFrame(data: ByteArray, flags: Int, timestampUs: Long) {
        val activeClients = clients.values.filter { it.isPaired }
        if (activeClients.isEmpty()) return

        serverScope.launch {
            activeClients.forEach { client ->
                try {
                    val targetKey = client.sessionKey
                    if (targetKey != null) {
                        // AES-GCM Encrypt raw Annex-B byte slice for secure transit
                        val encryptedFrame = encryptPayload(data, targetKey)
                        if (encryptedFrame != null) {
                            synchronized(client.outputStream) {
                                writePacketHeader(
                                    client.outputStream,
                                    PACKET_TYPE_VIDEO,
                                    encryptedFrame.size,
                                    timestampUs,
                                    flags
                                )
                                client.outputStream.write(encryptedFrame)
                                client.outputStream.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deliver video frame packet to client ${client.id}", e)
                    disconnectClient(client.id)
                }
            }
        }
    }

    private fun writePacketHeader(
        out: DataOutputStream,
        packetType: Int,
        length: Int,
        timestamp: Long,
        flags: Int
    ) {
        out.writeInt(packetType)
        out.writeInt(length)
        out.writeLong(timestamp)
        out.writeInt(flags)
    }

    private fun disconnectClient(clientId: String) {
        clients.remove(clientId)?.let { client ->
            try {
                client.socket.close()
                Log.i(TAG, "Client connection terminated: $clientId")
            } catch (e: Exception) {
                // Silently drop
            }
            onClientConnected(clients.filter { it.value.isPaired }.size)
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down ServerSocket", e)
        }
        serverSocket = null

        // Disconnect all active clients
        ArrayList(clients.keys).forEach { disconnectClient(it) }
        clients.clear()
        Log.i(TAG, "TCP Security server stopped successfully.")
    }
}
