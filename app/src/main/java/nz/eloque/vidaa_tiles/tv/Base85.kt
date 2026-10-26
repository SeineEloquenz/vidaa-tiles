package nz.eloque.vidaa_tiles.tv

/** Decoder for the RFC 1924 Base85 alphabet used by Python's `base64.b85decode`. */
object Base85 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~"
    private val values = IntArray(128) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    fun decode(input: String): ByteArray {
        val text = input.filterNot { it.isWhitespace() }
        val padding = (5 - text.length % 5) % 5
        val padded = text + "~".repeat(padding)
        val out = ByteArray(padded.length / 5 * 4)
        for (chunk in 0 until padded.length / 5) {
            var acc = 0L
            for (i in 0 until 5) {
                val c = padded[chunk * 5 + i]
                val v = if (c.code < 128) values[c.code] else -1
                require(v >= 0) { "Invalid Base85 character '$c'" }
                acc = acc * 85 + v
            }
            require(acc <= 0xFFFFFFFFL) { "Base85 overflow" }
            for (i in 0 until 4) {
                out[chunk * 4 + i] = (acc shr (24 - 8 * i)).toByte()
            }
        }
        return out.copyOf(out.size - padding)
    }
}

internal fun ByteArray.xor(key: ByteArray): ByteArray = ByteArray(size) { i -> (this[i].toInt() xor key[i % key.size].toInt()).toByte() }
