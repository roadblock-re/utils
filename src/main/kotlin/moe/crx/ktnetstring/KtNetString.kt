package moe.crx.ktnetstring

object KtNetString {

    // TODO Serialization

    const val SEMICOLON: Byte = ':'.code.toByte()
    const val ZERO: Byte = '0'.code.toByte()
    const val MINUS: Byte = '-'.code.toByte()
    const val STRING_TYPE: Byte = ','.code.toByte()
    const val DICTIONARY_KEY_TYPE: Byte = ';'.code.toByte()
    const val INTEGER_TYPE: Byte = '#'.code.toByte()
    const val FLOAT_TYPE: Byte = '^'.code.toByte()
    const val BOOLEAN_TYPE: Byte = '!'.code.toByte()
    const val NULL_TYPE: Byte = '~'.code.toByte()
    const val DICTIONARY_TYPE: Byte = '}'.code.toByte()
    const val LIST_TYPE: Byte = ']'.code.toByte()
    const val T_CODE: Byte = 't'.code.toByte()
    const val R_CODE: Byte = 'r'.code.toByte()
    const val U_CODE: Byte = 'u'.code.toByte()
    const val E_CODE: Byte = 'e'.code.toByte()

    fun parse(str: String) = parse(str.toByteArray())

    fun parse(bytes: ByteArray) = parse(bytes, 0)

    fun parseList(str: String) = parseList(str.toByteArray())

    fun parseList(bytes: ByteArray) = parseList(bytes, 0, bytes.size)

    fun parse(bytes: ByteArray, offset: Int): Any? {
        val colon = findColon(bytes, offset)
        if (colon == -1) throw IllegalStateException("No semicolon found")
        val size = parseLong(bytes, offset, colon).toInt()
        val typeOffset = colon + 1 + size
        val type = bytes[typeOffset]

        return when (type) {
            STRING_TYPE -> bytes.copyOfRange(colon + 1, colon + 1 + size)
            DICTIONARY_KEY_TYPE -> String(bytes, colon + 1, size)
            INTEGER_TYPE -> parseLong(bytes, colon + 1, colon + 1 + size)
            // TODO Simple X.Y decoder
            FLOAT_TYPE -> String(bytes, colon + 1, size).toDouble()
            BOOLEAN_TYPE -> {
                bytes[colon + 1] == T_CODE
                        && bytes[colon + 2] == R_CODE
                        && bytes[colon + 3] == U_CODE
                        && bytes[colon + 4] == E_CODE
            }

            NULL_TYPE -> {
                if (size != 0) throw IllegalStateException("Null must have nil size")
                null
            }

            DICTIONARY_TYPE -> parseDictionary(bytes, colon + 1, size)
            LIST_TYPE -> parseList(bytes, colon + 1, size)
            else -> throw IllegalStateException("Unknown type character")
        }
    }

    fun nextOffset(bytes: ByteArray, offset: Int): Int {
        val colon = findColon(bytes, offset)
        if (colon == -1) throw IllegalStateException("No semicolon found")
        val size = parseLong(bytes, offset, colon).toInt()
        val typeOffset = colon + 1 + size
        return typeOffset + 1
    }

    fun parseDictionary(bytes: ByteArray, offset: Int, size: Int): Map<String, Any?> {
        var i = offset
        val to = offset + size
        val map = linkedMapOf<String, Any?>()

        while (i < to) {
            val key = parse(bytes, i)
            if (key !is String) throw IllegalStateException("Key is not a string")
            i = nextOffset(bytes, i)
            val value = parse(bytes, i)
            i = nextOffset(bytes, i)
            map[key] = value
        }

        return map
    }

    fun parseList(bytes: ByteArray, offset: Int, size: Int): List<Any?> {
        var i = offset
        val to = offset + size
        val list = mutableListOf<Any?>()

        while (i < to) {
            val value = parse(bytes, i)
            i = nextOffset(bytes, i)
            list.add(value)
        }

        return list
    }

    fun parseLong(bytes: ByteArray, from: Int, to: Int): Long {
        var digitFrom = from
        var signum = 1
        if (bytes[from] == MINUS) {
            digitFrom += 1
            signum = -1
        }

        var result = 0L
        for (i in digitFrom..<to) {
            result *= 10
            result += (bytes[i] - ZERO)
        }

        return result * signum
    }

    fun findColon(bytes: ByteArray, offset: Int): Int {
        // TNetStrings reference says that maximum length of size is 9 digits long.
        for (i in offset..<bytes.size) {
            if (bytes[i] == SEMICOLON) {
                return i
            }
        }

        return -1
    }
}