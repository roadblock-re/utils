package moe.crx.roadblock

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import moe.crx.roadblock.game.GameLayer
import moe.crx.roadblock.game.serialization.RoadblockFormat
import moe.crx.roadblock.game.serialization.SerializationVersion
import moe.crx.roadblock.rpc.base.GameLoginResponse
import java.io.File

val json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
    allowStructuredMapKeys = true
}

fun main() {
    val ver = SerializationVersion(47u, 1u, 0u)
    val format = RoadblockFormat(ver)
    println("Current version: $ver")
    val layer = GameLayer(".", ver)
    println("${layer.handlers.size} packets")

    println("Dump file path: ")
    val path = readln()
    val bytes = File(path).readBytes()
    val response = format.decodeFromByteArray<GameLoginResponse>(bytes)
    println()
    println(response)

    val dir = File(File("exported-save"), ver.toString())
    dir.mkdirs()

    json.encodeToString(response).let { File(dir, "savedgame.json").writeText(it) }

    File(dir, "clientconfig.json").writeBytes(response.configData.data.bytes)
    response.serverDBs.gameDb?.let { File(dir, "A9-business.gdb").writeBytes(it.data.bytes) }
    println("Success")
}