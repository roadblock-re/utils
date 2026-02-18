package moe.crx.roadblock

import moe.crx.roadblock.game.GameLayer
import moe.crx.roadblock.game.io.ObjectIO.readObject
import moe.crx.roadblock.game.serialization.SerializationVersion
import moe.crx.roadblock.rpc.auth.LoginResponse
import moe.crx.roadblock.core.utils.sink
import java.io.File

fun main() {
    val ver = SerializationVersion(47, 1, 0)
    println("Current version: $ver")
    val layer = GameLayer(".", ver)
    println(layer.handlers.size)

    println("Dump file path: ")
    val bytes = File(readln()).readBytes()
    val response = bytes.sink(ver).readObject<LoginResponse>()
    println(response)

    val dir = File(File("exported-save"), ver.toString())
    dir.mkdirs()
    File(dir, "clientconfig.json").writeBytes(response.configData.data)
    response.serverDBs.gameDb?.let { File(dir, "A9-business.gdb").writeBytes(it.data) }
}