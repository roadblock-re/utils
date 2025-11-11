package moe.crx.roadblock

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import moe.crx.roadblock.utils.*
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

fun main() {
    val line = Json.parseToJsonElement(readln()).jsonObject
    val originalHost = line["controller_host"]?.jsonPrimitive?.contentOrNull ?: ""
    val originalPort = line["controller_tcp_port"]?.jsonPrimitive?.intOrNull ?: 0
    val result = line.toMutableMap().apply {
        this["controller_host"] = JsonPrimitive("127.0.0.1")
        this["controller_tcp_port"] = JsonPrimitive(4447)
    }.let { JsonObject(it) }.toString()
    println(result)
    tcpMitmServer(originalHost, originalPort, true)
}

fun tcpMitmServer(originalHost: String, originalPort: Int, wait: Boolean): Job {
    val socket = ServerSocket(4447)

    val job = scope.launch {
        while (true) {
            val client = socket.accept()

            val server = Socket(originalHost, originalPort)

            val serverOutput = server.outputStream
            val serverInput = server.inputStream

            val dir = File("dumped", System.currentTimeMillis().toString())
            dir.mkdirs()

            scope.launch {
                val output = client.outputStream
                val input = client.inputStream

                runCatching {
                    val nonceJson = BufferedReader(InputStreamReader(serverInput)).readLine()
                    output.write("$nonceJson\n".toByteArray())
                    output.flush()

                    // Contains action, room_id, access_token
                    val roomJson = BufferedReader(InputStreamReader(input)).readLine()
                    serverOutput.write("$roomJson\n".toByteArray())
                    serverOutput.flush()

                    val roomId = roomJson
                        .let { Json.parseToJsonElement(it) }.jsonObject["room_id"]?.jsonPrimitive?.contentOrNull
                    checkNotNull(roomId)

                    val slotJson = BufferedReader(InputStreamReader(serverInput)).readLine()
                    output.write("$slotJson\n".toByteArray())
                    output.flush()

                    val slot = slotJson
                        .let { Json.parseToJsonElement(it) }.jsonObject["slot"]?.jsonPrimitive?.contentOrNull
                    checkNotNull(slot)

                    val decrypt = evpBytesToKey((roomId + slot).toByteArray(), clientSalt).let { (key, iv) ->
                        ChaCha7539Engine().apply {
                            init(false, ParametersWithIV(KeyParameter(key), iv.takeLast(12).toByteArray()))
                            skip((iv.take(4).toByteArray().toLittleEndianInt().toLong() and 0xFFFFFFFFL) * 64.toLong())
                        }
                    }
                    val clientEncrypt = evpBytesToKey((roomId + slot).toByteArray(), clientSalt).let { (key, iv) ->
                        ChaCha7539Engine().apply {
                            init(true, ParametersWithIV(KeyParameter(key), iv.takeLast(12).toByteArray()))
                            skip((iv.take(4).toByteArray().toLittleEndianInt().toLong() and 0xFFFFFFFFL) * 64.toLong())
                        }
                    }
                    val serverDecrypt = evpBytesToKey((roomId + slot).toByteArray(), serverSalt).let { (key, iv) ->
                        ChaCha7539Engine().apply {
                            init(false, ParametersWithIV(KeyParameter(key), iv.takeLast(12).toByteArray()))
                            skip((iv.take(4).toByteArray().toLittleEndianInt().toLong() and 0xFFFFFFFFL) * 64.toLong())
                        }
                    }

                    fun sendPacket(bytes: ByteArray, type: Int) {
                        println("CUSTOM: ${bytes.toHexString()}")
                        File(dir, "${System.currentTimeMillis()}.custom").writeBytes(bytes)

                        val trimmedLength = bytes.size and 0xFFFFFFF
                        check(trimmedLength == bytes.size)

                        val shiftedType = type shl 0x1C
                        val header = trimmedLength or shiftedType

                        clientEncrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)

                        serverOutput.write(header.toBigEndianBytes())
                        serverOutput.write(bytes)
                        serverOutput.flush()
                    }

                    CoroutineScope(Dispatchers.Default).launch {
                        while (true) {
                            val line = readln()

                            if (line.isBlank()) {
                                continue
                            }

                            val bytes = line.fromHexString()

                            sendPacket(bytes, 0)
                        }
                    }

                    while (!client.isClosed) {
                        var headerBytes = ByteArray(0)

                        while (headerBytes.size < 4) {
                            headerBytes += input.readNBytes(4 - headerBytes.size)
                        }

                        var header = headerBytes.toBigEndianInt()
                        var length = header and 0xFFFFFFF
                        var type = header ushr 0x1C
                        var bytes = ByteArray(0)

                        while (bytes.size < length) {
                            bytes += input.readNBytes(length - bytes.size)
                        }

                        decrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)

                        val toSend = bytes.copyOf()
                        clientEncrypt.processBytes(toSend.copyOf(), 0, toSend.size, toSend, 0)

                        serverOutput.write(headerBytes)
                        serverOutput.write(toSend.copyOf())
                        serverOutput.flush()

                        if (type == 1) {
                            val hash = bytes.take(4).toByteArray().toBigEndianInt()
                            val decompressedLength = bytes.drop(4).take(4).toByteArray().toBigEndianInt()
                            val compressedBytes = bytes.drop(8).toByteArray()

                            val calculatedHash =
                                xxHash32.hash(compressedBytes, 0, compressedBytes.size, decompressedLength)
                            check(hash == calculatedHash)

                            bytes = safeDecompressor.decompress(compressedBytes, decompressedLength)
                        }

                        println("CLIENT: ${bytes.toHexString()}")
                        if (type == 1 || type == 0) {
                            File(dir, "${System.currentTimeMillis()}.out").writeBytes(bytes)
                        }

                        headerBytes = ByteArray(0)

                        while (headerBytes.size < 4) {
                            headerBytes += serverInput.readNBytes(4 - headerBytes.size)
                        }

                        header = headerBytes.toBigEndianInt()
                        length = header and 0xFFFFFFF
                        type = header ushr 0x1C
                        bytes = ByteArray(0)

                        while (bytes.size < length) {
                            bytes += serverInput.readNBytes(length - bytes.size)
                        }

                        output.write(headerBytes)
                        output.write(bytes.copyOf())
                        output.flush()

                        serverDecrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)

                        if (type == 1) {
                            val hash = bytes.take(4).toByteArray().toBigEndianInt()
                            val decompressedLength = bytes.drop(4).take(4).toByteArray().toBigEndianInt()
                            val compressedBytes = bytes.drop(8).toByteArray()

                            val calculatedHash =
                                xxHash32.hash(
                                    compressedBytes,
                                    0,
                                    compressedBytes.size,
                                    bytes.drop(4).take(4).toByteArray().toLittleEndianInt()
                                )
                            check(hash == calculatedHash)

                            bytes = safeDecompressor.decompress(compressedBytes, decompressedLength)
                        }

                        println("SERVER: ${bytes.toHexString()}")
                        if (type == 1 || type == 0) {
                            File(dir, "${System.currentTimeMillis()}.in").writeBytes(bytes)
                        }
                    }

                    output.close()
                    input.close()

                    serverOutput.close()
                    serverInput.close()
                }
            }
        }
    }

    if (wait) {
        runBlocking {
            job.join()
        }
    }

    return job
}