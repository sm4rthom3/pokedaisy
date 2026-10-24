package com.pokedaisy.app.companion.data

/** German retail names are read from the player's cart, with the same ids as the English tables.
 * ROM offsets were checked against all three German carts; no game text is bundled here. */
object GermanGen2Text {
    private data class Cached(val rom: ByteArray, val code: String, val text: GameText)
    @Volatile private var cached: Cached? = null

    fun forCode(code: String): GameText? {
        if (code !in setOf("GOLD_DE", "SILVER_DE", "CRYSTAL_DE")) return null
        val rom = Gen1Dex.rom ?: return null
        cached?.let { if (it.rom === rom && it.code == code) return it.text }
        return runCatching { read(rom, code == "CRYSTAL_DE") }.getOrNull()?.also { cached = Cached(rom, code, it) }
    }

    private fun read(rom: ByteArray, crystal: Boolean): GameText {
        fun off(bank: Int, addr: Int) = bank * 0x4000 + addr - 0x4000
        fun u8(at: Int) = rom[at].toInt() and 0xFF
        fun word(at: Int) = u8(at) or (u8(at + 1) shl 8)
        val chars = Gen1Charset.DF.chars + mapOf(0xEA to "é", 0x5B to "PC", 0x5C to "TM")
        fun text(at: Int): String = buildString {
            for (i in at until minOf(at + 80, rom.size)) {
                if (u8(i) == 0x50) break
                append(chars[u8(i)] ?: "")
            }
        }
        fun list(bank: Int, addr: Int, count: Int): Map<Int, String> {
            var pos = off(bank, addr)
            return (1..count).associateWith {
                val name = text(pos)
                while (pos < rom.size && u8(pos++) != 0x50) Unit
                name
            }
        }
        val namesBank = if (crystal) 0x14 else 0x6C
        val names = off(namesBank, if (crystal) 0x736E else 0x4B6B)
        val species = (1..251).associateWith { text(names + (it - 1) * 10) }
        val moves = list(if (crystal) 0x72 else 0x6C, if (crystal) 0x5E9D else 0x556B, 251)
        val items = list(if (crystal) 0x72 else 0x6C, 0x4000, 255)
        val mapBank = if (crystal) 0x72 else 0x27
        val mapTable = off(mapBank, if (crystal) 0x68E5 else 0x402D)
        val maps = (0 until if (crystal) 0x60 else 0x5F).associateWith { text(off(mapBank, word(mapTable + 4 * it + 2))) }
        return GameText(
            species = { species }, moves = { moves }, items = { items }, natures = emptyList(),
            mapSecs = { maps }, smallFontWidths = { IntArray(0) },
            englishMapSecs = { if (crystal) mapSecDataCrystal else mapSecDataGoldSilver },
            englishMoves = { moveDataCrystal },
        )
    }
}
