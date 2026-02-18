package moe.crx.roadblock

import moe.crx.roadblock.game.io.ObjectIO.readObject
import moe.crx.roadblock.game.serialization.SerializationVersion
import moe.crx.roadblock.rpc.push.PushMessagePacket
import moe.crx.roadblock.rpc.base.RequestPacket
import moe.crx.roadblock.rpc.base.ResponsePacket
import moe.crx.roadblock.core.utils.sink
import java.io.File
import kotlin.math.min

fun main() {
    val ver = SerializationVersion(3, 9, 2)
    val requests = mutableMapOf<Short, MutableList<File>>()
    val responses = mutableMapOf<Short, MutableList<File>>()
    val special = mutableMapOf<Short, MutableList<File>>()

    print("Enter packets path: ")
    File(readln()).walkTopDown().forEach {
        if (!it.isFile) {
            return@forEach
        }

        val bytes = it.inputStream().use { stream ->
            stream.readNBytes(min(it.length(), 100).toInt())
        }

        runCatching { bytes.sink(ver).readObject<RequestPacket>().type }.getOrNull()?.let { id ->
            requests.getOrPut(id) { mutableListOf() }.add(it)
        }
        runCatching { bytes.sink(ver).readObject<ResponsePacket>().type }.getOrNull()?.let { id ->
            responses.getOrPut(id) { mutableListOf() }.add(it)
        }
        runCatching { bytes.sink(ver).readObject<PushMessagePacket>().type }.getOrNull()?.let { id ->
            special.getOrPut(id) { mutableListOf() }.add(it)
        }
    }

    listOf(requests, responses, special).forEach { dict ->
        dict.forEach { packetId, files ->
            println("%02x".format(packetId) + ": " + files.joinToString())
        }
        println()
        println()
        println()
    }
}