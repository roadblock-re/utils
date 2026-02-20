package moe.crx.roadblock

import kotlinx.serialization.decodeFromByteArray
import moe.crx.roadblock.game.serialization.RoadblockFormat
import moe.crx.roadblock.game.serialization.SerializationVersion
import moe.crx.roadblock.rpc.base.PushMessagePacket
import moe.crx.roadblock.rpc.base.RequestPacket
import moe.crx.roadblock.rpc.base.ResponsePacket
import java.io.File
import kotlin.math.min

fun main() {
    val ver = SerializationVersion(47u, 1u, 0u)
    val format = RoadblockFormat(ver)
    val requests = mutableMapOf<UShort, MutableList<File>>()
    val responses = mutableMapOf<UShort, MutableList<File>>()
    val special = mutableMapOf<UShort, MutableList<File>>()

    print("Enter packets path: ")
    File(readln()).walkTopDown().forEach {
        if (!it.isFile) {
            return@forEach
        }

        val bytes = it.inputStream().use { stream ->
            stream.readNBytes(min(it.length(), 100).toInt())
        }

        runCatching { format.decodeFromByteArray<RequestPacket>(bytes).type }.getOrNull()?.let { id ->
            requests.getOrPut(id) { mutableListOf() }.add(it)
        }
        runCatching { format.decodeFromByteArray<ResponsePacket>(bytes).type }.getOrNull()?.let { id ->
            responses.getOrPut(id) { mutableListOf() }.add(it)
        }
        runCatching { format.decodeFromByteArray<PushMessagePacket>(bytes).type }.getOrNull()?.let { id ->
            special.getOrPut(id) { mutableListOf() }.add(it)
        }
    }

    listOf(requests, responses, special).forEach { dict ->
        dict.forEach { (packetId, files) ->
            println("%02x".format(packetId.toInt()) + ": " + files.joinToString())
        }
        println()
        println()
        println()
    }
}