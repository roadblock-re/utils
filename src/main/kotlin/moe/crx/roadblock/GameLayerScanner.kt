package moe.crx.roadblock

import moe.crx.roadblock.game.GameLayer
import moe.crx.roadblock.game.serialization.SerializationVersion
import java.io.File

fun scanBinary(binaryPath: String, start: String): List<String> {
    val startSequence = start.toByteArray()
    val binaryFile = File(binaryPath)
    val bytes = binaryFile.readBytes()

    var fromIndex = -1
    for (i in 0..<bytes.size - startSequence.size) {
        var matched = false
        for (j in 0..<startSequence.size) {
            if (bytes[i + j] != startSequence[j]) {
                matched = false
                break
            }
            matched = true
        }

        if (matched) {
            fromIndex = i
            break
        }
    }

    if (fromIndex == -1) return listOf()

    var toIndex = -1
    for (i in fromIndex..<bytes.size) {
        if (bytes[i] == 0.toByte()) {
            toIndex = i
            break
        }
    }

    if (toIndex == -1) return listOf()

    val requestsString = bytes.copyOfRange(fromIndex, toIndex).toString(Charsets.UTF_8)
    return requestsString.split(", ")
}

fun main() {
    print("Game version: ")
    val serVersion = SerializationVersion(readln()) // TODO Autodetect from binary
    val gameLayer = GameLayer(".", serVersion)
    val layerRequests = gameLayer.handlers.map { it.requestName }.toMutableList()

    print("Binary path: ")
    val binaryPath = readln()
    var binaryRequests = scanBinary(binaryPath, "LoginRequest, ")

    if (binaryRequests.isEmpty()) {
        binaryRequests = scanBinary(binaryPath, "LoginResult, ")
            .map { it.substringBeforeLast("Result") + "Request" }
    }

    if (binaryRequests.size != layerRequests.size) {
        println("wrong size (${binaryRequests.size} / ${layerRequests.size})")
        return
    }

    for (i in 0..<binaryRequests.size) {
        if (binaryRequests[i] != layerRequests[i]) {
            println(binaryRequests[i] + " != " + layerRequests[i])
        }
    }
}