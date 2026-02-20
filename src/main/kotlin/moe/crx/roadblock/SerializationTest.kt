package moe.crx.roadblock

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import moe.crx.roadblock.game.serialization.*

enum class TestEnum {
    Test1,
    Test2,
    Test3,
}

@Serializable
sealed class TestItem {
    companion object : Variant<TestItem> {
        override fun variants(version: SerializationVersion) = buildList {
            add(Apple::class)
            if (version newer "24.0.0") {
                add(Sword::class)
            }
            add(Chestplate::class)
        }
    }
}

@Serializable
data class Apple(var amount: Int) : TestItem()

@Serializable
data class Sword(var enchanted: Boolean) : TestItem()

@Serializable
data class Chestplate(var color: String) : TestItem()

@Serializable
data class SerializationTest(
    var firstInt: Int = 0,
    var secondInt: UInt,
    @ByteEnum
    var byteEnum: TestEnum = TestEnum.Test1,
    @FromVersion("4.9.5") @UntilVersion("47.1.0")
    var ignoredVariable: Boolean? = false,
    var variantItem: TestItem,
    var byteList: List<Byte>,
    var optionalInstant: Instant?,
    var justMap: Map<Int, Long>,
    var byteArray: Blob,
    @ByteEnum
    var listOfByteEnums: List<TestEnum>,
    @Contextual @VariantOf(TestItem.Companion::class)
    val contextualVariant: Any,
)

fun main() {
    val test = SerializationTest(
        10,
        4294967295u,
        TestEnum.Test2,
        false,
        Chestplate("red"),
        "helloworld!".toByteArray().toList(),
        Instant.fromEpochSeconds(Clock.System.now().epochSeconds, 0),
        mapOf(0 to 0, 1 to 1, 2 to 2),
        Blob("test".toByteArray()),
        listOf(TestEnum.Test3),
        Apple(13),
    )

    val protocol = RoadblockFormat(SerializationVersion(47u, 1u, 0u))

    val bytes = protocol.encodeToByteArray(test)
    println(bytes.toHexString())

    val deserialized = protocol.decodeFromByteArray<SerializationTest>(bytes)
    check(test == deserialized)
}