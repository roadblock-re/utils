package moe.crx.roadblock

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import moe.crx.roadblock.serialization.*

enum class TestEnum {
    Test1,
    Test2,
    Test3,
}

@Serializable
sealed class TestItemVariant {
    companion object : VariantCompanion<TestItemVariant> {
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
data class Apple(var amount: Int) : TestItemVariant()

@Serializable
data class Sword(var enchanted: Boolean) : TestItemVariant()

@Serializable
data class Chestplate(var color: String) : TestItemVariant()

@Serializable
data class SerializationTest(
    var test1: Int = 0,
    var test2: Int = 0,
    @ByteEnum
    var test3: TestEnum = TestEnum.Test1,
    @FromVersion("4.9.5") @UntilVersion("47.1.0")
    var test4: Boolean? = false,
    var test5: TestItemVariant,
    var test6: List<Byte>,
    var test7: Instant,
    var test8: Map<Int, Long>,
    var test9: Blob,
    @ByteEnum
    var test10: List<TestEnum>,
)

fun main() {
    val test = SerializationTest(
        10,
        20,
        TestEnum.Test2,
        false,
        Chestplate("red"),
        "helloworld!".toByteArray().toList(),
        Instant.fromEpochSeconds(Clock.System.now().epochSeconds, 0),
        mapOf(0 to 0, 1 to 1, 2 to 2),
        Blob("test".toByteArray()),
        listOf(TestEnum.Test3),
    )

    val protocol = RoadblockFormat(SerializationVersion(47, 1, 0))

    val bytes = protocol.encodeToByteArray(test)
    println(bytes.toHexString())

    val deserialized = protocol.decodeFromByteArray<SerializationTest>(bytes)
    check(test == deserialized)
}