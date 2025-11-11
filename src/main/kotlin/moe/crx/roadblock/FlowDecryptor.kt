package moe.crx.roadblock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.crx.ktnetstring.KtNetString
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4SafeDecompressor
import net.jpountz.xxhash.XXHash32
import net.jpountz.xxhash.XXHashFactory
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.collections.plus

fun ByteArray.toBigEndianInt(): Int {
    val bytes = take(4).map { it.toInt() and 0xFF }.reversed()
    val ch1 = bytes[0]
    val ch2 = bytes[1] shl 8
    val ch3 = bytes[2] shl 16
    val ch4 = bytes[3] shl 24
    return ch1 or ch2 or ch3 or ch4
}

fun ByteArray.toLittleEndianInt(): Int {
    val bytes = take(4).map { it.toInt() and 0xFF }
    val ch1 = bytes[0]
    val ch2 = bytes[1] shl 8
    val ch3 = bytes[2] shl 16
    val ch4 = bytes[3] shl 24
    return ch1 or ch2 or ch3 or ch4
}

fun evpBytesToKey(
    password: ByteArray,
    salt: ByteArray,
    keyLen: Int = 32,
    ivLen: Int = 16,
    digest: String = "SHA-256",
    count: Int = 1
): Pair<ByteArray, ByteArray> {
    val md = MessageDigest.getInstance(digest)
    val output = mutableListOf<Byte>()
    var prev = ByteArray(0)

    while (output.size < keyLen + ivLen) {
        val data = prev + password + salt
        var digestResult = md.digest(data)

        repeat(count - 1) {
            digestResult = md.digest(digestResult)
        }

        output.addAll(digestResult.toList())
        prev = digestResult
    }

    val key = output.take(keyLen).toByteArray()
    val iv = output.drop(keyLen).take(ivLen).toByteArray()
    return Pair(key, iv)
}

fun ByteArray.toHexString() = joinToString("") { it.toHexString() }

fun Byte.toHexString() = "%02x".format(this)

fun DataInputStream.readS(): String {
    val len = readByte().toInt()
    return String(readNBytes(len))
}

fun DataInputStream.readB(): ByteArray {
    val len = readInt()
    return readNBytes(len)
}

var roomId = ""
var slot = ""

val safeDecompressor: LZ4SafeDecompressor = LZ4Factory.fastestInstance().safeDecompressor()
val xxHash32: XXHash32 = XXHashFactory.fastestInstance().hash32()

val clientSalt = byteArrayOf(0x63, 0x6C, 0x69, 0x65, 0x6E, 0x74, 0x00, 0x00) // "client\0\0"
val serverSalt = byteArrayOf(0x73, 0x65, 0x72, 0x76, 0x65, 0x72, 0x00, 0x00) // "server\0\0"

var decrypt = ChaCha7539Engine()
var encrypt = ChaCha7539Engine()

fun setupEncryption() {
    decrypt = evpBytesToKey((roomId + slot).toByteArray(), clientSalt).let { (key, iv) ->
        ChaCha7539Engine().apply {
            init(false, ParametersWithIV(KeyParameter(key), iv.takeLast(12).toByteArray()))
            skip((iv.take(4).toByteArray().toLittleEndianInt().toLong() and 0xFFFFFFFFL) * 64.toLong())
        }
    }
    encrypt = evpBytesToKey((roomId + slot).toByteArray(), serverSalt).let { (key, iv) ->
        ChaCha7539Engine().apply {
            init(false, ParametersWithIV(KeyParameter(key), iv.takeLast(12).toByteArray()))
            skip((iv.take(4).toByteArray().toLittleEndianInt().toLong() and 0xFFFFFFFFL) * 64.toLong())
        }
    }
}

data class Packet(
    var index: Int,
    var sender: Int,
    var time: String,
    var transfer: List<Byte>,
)

var counter = 0

fun DataInputStream.readPacket(): Packet {
    ++counter

    readByte() // 04
    val sender = readByte() // 01 - client, 02 - server
    val time = readS()
    val packet = readB()

    return Packet(counter, sender.toInt(), time, packet.toList())
}

val exported = File("exported")

fun processHcyPacket(packet: Packet, dis: DataInputStream?) {
    val header = packet.transfer.take(4).toByteArray().toBigEndianInt()
    val length = header and 0xFFFFFFF
    val type = header ushr 0x1C
    var bytes = packet.transfer.drop(4).take(length).toByteArray()

    var leftover: Packet? = null

    while (bytes.size < length) {
        val another = dis?.readPacket() ?: throw IllegalStateException("another == null")

        if (another.sender != packet.sender) {
            throw IllegalStateException("another.sender != packet.sender")
        }

        bytes += another.transfer

        if (bytes.size > length) {
            another.transfer = bytes.takeLast(bytes.size - length)
            bytes = bytes.take(length).toByteArray()
            leftover = another
        }
    }

    println(bytes.take(100).toByteArray().toHexString())

    if (packet.sender == 2) {
        encrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)
    } else {
        decrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)
    }

    if (type == 1) {
        val hash = bytes.take(4).toByteArray().toBigEndianInt()
        val decompressedLength = bytes.drop(4).take(4).toByteArray().toBigEndianInt()
        val compressedBytes = bytes.drop(8).toByteArray()

        val calculatedHash = xxHash32.hash(compressedBytes, 0, compressedBytes.size, decompressedLength)
        check(hash == calculatedHash)

        bytes = safeDecompressor.decompress(compressedBytes, decompressedLength)
    }

    File(exported, "${packet.time}.${if (packet.sender == 2) "in" else "out"}").writeBytes(bytes)

    if (leftover != null) {
        processHcyPacket(leftover, dis)
    }
}

fun processHcy(filePath: String) {
    exported.mkdirs()
    exported.listFiles()?.forEach { it.delete() }

    val dis = DataInputStream(File(filePath).inputStream())
    dis.readByte() // 01
    val host = dis.readS()
    dis.readByte() // 02
    val port = dis.readS()
    dis.readByte() // 00

    var serverData = ByteArray(0)
    var clientData = ByteArray(0)

    println("$host:$port")

    while (dis.available() != 0) {
        val packet = dis.readPacket()

        print("${packet.time} ")
        if (packet.sender == 2) {
            print("server: ")
        } else {
            print("client: ")
        }
        println(packet.transfer.size)

        if (counter == 1) {
            continue
        } else if (counter == 2) {
            roomId = Json.parseToJsonElement(String(packet.transfer.toByteArray())).jsonObject["room_id"]?.jsonPrimitive?.contentOrNull ?: ""
            continue
        } else if (counter == 3) {
            slot = Json.parseToJsonElement(String(packet.transfer.toByteArray())).jsonObject["slot"]?.jsonPrimitive?.contentOrNull ?: ""
            setupEncryption()
            continue
        }

        if (packet.sender == 2) {
            serverData += packet.transfer.toByteArray()
        } else {
            clientData += packet.transfer.toByteArray()
        }

        processHcyPacket(packet, dis)
    }

    File(exported, "encrypted_client.out").writeBytes(clientData)
    File(exported, "encrypted_server.in").writeBytes(serverData)
}

fun processFlows(fileName: String) {
    exported.mkdirs()
    exported.listFiles()?.forEach { it.delete() }

    var serverData = ByteArray(0)
    var clientData = ByteArray(0)

    val flows = File(fileName).readBytes()
    var parsed = KtNetString.parseList(flows).filter {
        it is Map<*, *>
    }.let {
        it as List<Map<String, Any?>>
    }
    parsed = parsed.filter {
        it["type"] == "tcp"
    }
    parsed = parsed.filter {
        val messages = it["messages"] as List<List<Any?>>
        messages.find { message ->
            (message[1] as ByteArray).toString(Charsets.UTF_8).contains("connect game")
        } != null
    }
    val messages = parsed.last()["messages"] as List<List<Any?>>
    messages.forEachIndexed { index, it ->
        if (index == 0) {
            File(exported, "unencrypted_$index").writeBytes(it[1] as ByteArray)
            return@forEachIndexed
        } else if (index == 1) {
            File(exported, "unencrypted_$index").writeBytes(it[1] as ByteArray)
            roomId = Json.parseToJsonElement((it[1] as ByteArray).toString(Charsets.UTF_8)).jsonObject["room_id"]?.jsonPrimitive?.contentOrNull ?: ""
            return@forEachIndexed
        } else if (index == 2) {
            File(exported, "unencrypted_$index").writeBytes(it[1] as ByteArray)
            slot = Json.parseToJsonElement((it[1] as ByteArray).toString(Charsets.UTF_8)).jsonObject["slot"]?.jsonPrimitive?.contentOrNull ?: ""
            setupEncryption()
            return@forEachIndexed
        }

        if (!(it[0] as Boolean)) {
            serverData += (it[1] as ByteArray)
        } else {
            clientData += (it[1] as ByteArray)
        }
    }

    File(exported, "encrypted_client.out").writeBytes(clientData)
    File(exported, "encrypted_server.in").writeBytes(serverData)
}

fun processFlowPacket(input: InputStream, index: Int, fromClient: Boolean): Boolean {
    val header = input.readNBytes(4).toBigEndianInt()
    val length = header and 0xFFFFFFF
    val type = header ushr 0x1C
    var bytes = input.readNBytes(length)

    if (fromClient) {
        decrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)
    } else {
        encrypt.processBytes(bytes.copyOf(), 0, bytes.size, bytes, 0)
    }

    if (type != 0 && type != 1) {
        return false
    }

    if (type == 1) {
        val hash = bytes.take(4).toByteArray().toBigEndianInt()
        val decompressedLength = bytes.drop(4).take(4).toByteArray().toBigEndianInt()
        val compressedBytes = bytes.drop(8).toByteArray()

        val calculatedHash = xxHash32.hash(compressedBytes, 0, compressedBytes.size, if (fromClient) {
            bytes.drop(4).take(4).toByteArray().toBigEndianInt()
        } else {
            bytes.drop(4).take(4).toByteArray().toLittleEndianInt()
        })

        if (hash != calculatedHash) {
            // FIXME
            println("Hash mismatch: $hash != $calculatedHash")
        }

        bytes = safeDecompressor.decompress(compressedBytes, decompressedLength)
    }

    File(exported, "dumped_$index${if (fromClient) ".out" else ".in"}").writeBytes(bytes)
    return true
}

fun processEncrypted() {
    runCatching {
        val encServer = File(exported, "encrypted_server.in").inputStream()
        var i = 0
        while (encServer.available() != 0) {
            if (!processFlowPacket(encServer, i++, false)) {
                --i
            }
        }
    }.onFailure {
        it.printStackTrace()
    }
    runCatching {
        val encClient = File(exported, "encrypted_client.out").inputStream()
        var i = 0
        while (encClient.available() != 0) {
            if (!processFlowPacket(encClient, i++, true)) {
                --i
            }
        }
    }.onFailure {
        it.printStackTrace()
    }
}

fun main() {
    print("File path: ")
    val filePath = readln()
    if (filePath.endsWith(".hcy")) {
        processHcy(filePath)
    } else {
        processFlows(filePath)
    }
    processEncrypted()
}

