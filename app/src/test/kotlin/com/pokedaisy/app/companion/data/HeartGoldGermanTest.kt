package com.pokedaisy.app.companion.data

import com.pokedaisy.app.CompanionSupport
import com.pokedaisy.app.GameTitles
import com.pokedaisy.app.WideScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class HeartGoldGermanTest {
    @Test
    fun `German rev 0 is supported and other revisions and unmapped regions stay unsupported`() {
        fun supported(code: String, rev: Int): Boolean {
            val f = File.createTempFile("nds-header-", ".nds")
            try {
                val header = ByteArray(0x200)
                code.toByteArray(Charsets.US_ASCII).copyInto(header, 0x0C)
                header[0x1E] = rev.toByte()
                header[0x15C] = 0x56
                header[0x15D] = 0xCF.toByte()
                f.writeBytes(header)
                return CompanionSupport.isSupported(f)
            } finally { f.delete() }
        }
        assertTrue(supported("IPKD", 0))
        assertTrue(WideScreen.ndsKnows("IPKD", 0))
        for ((code, rev) in listOf("IPKD" to 1, "IPKD" to 255, "IPKF" to 0, "IPGD" to 0)) {
            assertFalse("$code rev $rev", supported(code, rev))
            assertFalse("$code rev $rev", WideScreen.ndsKnows(code, rev))
        }
        assertEquals("Pokémon Goldene Edition HeartGold", GameTitles.BY_SHA1["13b6853aa93121069df1d17446d3ecb36872aecf"])
    }

    @Test
    fun `field actions resolve the same regional config as the sampler`() {
        val oldCode = romGameCode
        try {
            romGameCode = "IPKD"
            assertSame(GEN4_HEARTGOLD_DE, gen4ConfigFor(GameKind.HEARTGOLD))
            assertSame(GEN4_HEARTGOLD_US, gen4ConfigFor(GameKind.HEARTGOLD, "IPKE"))
            assertSame(GEN4_SOULSILVER_US, gen4ConfigFor(GameKind.SOULSILVER, "IPGE"))
        } finally { romGameCode = oldCode }
    }

    @Test
    fun `the shared save layout reads through the German globals`() {
        // Relocate only the two globals in the existing US capture. This checks layout compatibility,
        // including the Pokegear's pointer chase; it is not a capture from the German game.
        val us = FixtureMemoryReader.load("heartgold")
        val german = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray = when (addr) {
                GEN4_HEARTGOLD_DE.saveDataPtr -> us.readCoreMemory(GEN4_HEARTGOLD_US.saveDataPtr, size)
                GEN4_HEARTGOLD_DE.pokegear!!.fieldSystemPtr -> us.readCoreMemory(GEN4_HEARTGOLD_US.pokegear!!.fieldSystemPtr, size)
                GEN4_HEARTGOLD_US.saveDataPtr, GEN4_HEARTGOLD_US.pokegear!!.fieldSystemPtr -> ByteArray(size)
                else -> us.readCoreMemory(addr, size)
            }
        }
        val oldGame = activeGame
        val oldNames = NdsArt.names
        try {
            activeGame = GameKind.HEARTGOLD
            NdsArt.names = null
            val expected = readGen4Telemetry(us, GEN4_HEARTGOLD_US)
            val actual = readGen4Telemetry(german, GEN4_HEARTGOLD_DE)
            assertEquals(expected.party.map { Triple(it.species, it.hp, it.level) }, actual.party.map { Triple(it.species, it.hp, it.level) })
            assertEquals(expected.items, actual.items)
            assertEquals(expected.money, actual.money)
            assertEquals(expected.mapNum, actual.mapNum)
            assertEquals(expected.regionMapTile, actual.regionMapTile)
            assertEquals(expected.pokegearState, actual.pokegearState)
            assertEquals(expected.pokedex, actual.pokedex)
        } finally { activeGame = oldGame; NdsArt.names = oldNames }
    }

    @Test
    fun `German cart names and art come from the player's ROM`() {
        val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Goldene Edition HeartGold (Germany).nds")
        assumeTrue("no German HeartGold ROM at $rom", rom.isFile)
        val names = NdsArt.readNames(rom)!!
        assertEquals('D', names.language)
        assertEquals("BISASAM", names.species(1))
        assertEquals("Tackle", names.move(33))
        assertEquals("Trank", names.item(17))
        assertEquals("Dukatia City", names.place(GEN4_HEARTGOLD_DE.mapPlaceIds.getValue(76)))
        val dir = kotlin.io.path.createTempDirectory("nds-german-art").toFile()
        try {
            assertTrue(NdsArt.extractTo(rom, dir))
            assertFalse(NdsArt.extractTo(rom, dir))
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_ICONS}/152.png").isFile)
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_ITEMS}/17.png").isFile)
            assertTrue(File(dir, PokegearMap.TILES).isFile)
            assertTrue(File(dir, "${NdsArt.HEARTGOLD_FRONT}/1.png").isFile)
            val lines = File(dir, NdsArt.HEARTGOLD_DEX).readLines()
            assertEquals(493, lines.size)
            val page = lines.first().split('\t')
            assertTrue(page[1].startsWith("Samen"))
            assertTrue(page[2].isNotBlank())
            assertEquals(256, Gen4Dex.parse(lines).regional.size)
        } finally { dir.deleteRecursively() }
    }
}
