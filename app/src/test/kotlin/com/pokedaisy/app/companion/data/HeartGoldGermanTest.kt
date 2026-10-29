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
        assertTrue(supported("IPGD", 0))
        assertTrue(WideScreen.ndsKnows("IPGD", 0))
        for ((code, rev) in listOf("IPKD" to 1, "IPKD" to 255, "IPKF" to 0, "CPUD" to 0, "CPUF" to 0, "IPGD" to 1, "IPGD" to 255)) {
            assertFalse("$code rev $rev", supported(code, rev))
            assertFalse("$code rev $rev", WideScreen.ndsKnows(code, rev))
        }
        assertEquals("Pokémon Goldene Edition HeartGold", GameTitles.BY_SHA1["13b6853aa93121069df1d17446d3ecb36872aecf"])
        assertEquals("Pokémon Silberne Edition SoulSilver", GameTitles.BY_SHA1["576cdb7eec728c49aed85b9dd7e1e084ba1f08f9"])
    }

    @Test
    fun `field actions resolve the same regional config as the sampler`() {
        val oldCode = romGameCode
        try {
            romGameCode = "IPKD"
            assertSame(GEN4_HEARTGOLD_DE, gen4ConfigFor(GameKind.HEARTGOLD))
            assertSame(GEN4_HEARTGOLD_US, gen4ConfigFor(GameKind.HEARTGOLD, "IPKE"))
            assertSame(GEN4_SOULSILVER_DE, gen4ConfigFor(GameKind.SOULSILVER, "IPGD"))
            assertSame(GEN4_SOULSILVER_US, gen4ConfigFor(GameKind.SOULSILVER, "IPGE"))
        } finally { romGameCode = oldCode }
    }

    @Test
    fun `the shared save layout reads through the German globals`() {
        // Relocate only the two globals in the existing US capture. This checks layout compatibility,
        // including the Pokegear's pointer chase; it is not a capture from the German game.
        val oldGame = activeGame
        val oldNames = NdsArt.names
        val oldCode = romGameCode
        val oldLanguage = romLanguage
        try {
            NdsArt.names = null
            romLanguage = 'E'
            data class Case(val kind: GameKind, val fixture: String, val source: Gen4Config, val target: Gen4Config)
            for ((kind, fixture, source, target) in listOf(
                Case(GameKind.HEARTGOLD, "heartgold", GEN4_HEARTGOLD_US, GEN4_HEARTGOLD_DE),
                Case(GameKind.SOULSILVER, "soulsilver", GEN4_SOULSILVER_US, GEN4_SOULSILVER_DE),
            )) {
                val us = FixtureMemoryReader.load(fixture)
                activeGame = kind
                romGameCode = if (kind == GameKind.HEARTGOLD) "IPKD" else "IPGD"
                val german = object : MemoryReader {
                    override fun readCoreMemory(addr: Long, size: Int): ByteArray = when (addr) {
                        target.saveDataPtr -> us.readCoreMemory(source.saveDataPtr, size)
                        target.pokegear!!.fieldSystemPtr -> us.readCoreMemory(source.pokegear!!.fieldSystemPtr, size)
                        source.saveDataPtr, source.pokegear!!.fieldSystemPtr -> ByteArray(size)
                        else -> us.readCoreMemory(addr, size)
                    }
                }
                val expected = readGen4Telemetry(us, source)
                val actual = readGen4Telemetry(german, target)
                assertEquals(fixture, expected.party, actual.party)
                assertEquals(fixture, expected.items, actual.items)
                assertEquals(expected.money, actual.money)
                assertEquals(expected.mapNum, actual.mapNum)
                assertEquals(expected.regionMapTile, actual.regionMapTile)
                assertEquals(expected.pokegearState, actual.pokegearState)
                assertEquals(expected.pokedex, actual.pokedex)
                assertEquals(expected.repelSteps, actual.repelSteps)
            }
        } finally {
            activeGame = oldGame; NdsArt.names = oldNames
            romGameCode = oldCode; romLanguage = oldLanguage
        }
    }

    @Test
    fun `Repels write only the German save and refuse a busy field or an active Repel`() {
        for ((fixture, cfg) in listOf("heartgold" to GEN4_HEARTGOLD_DE, "soulsilver" to GEN4_SOULSILVER_DE)) {
            for ((item, duration) in GEN4_REPEL_KINDS) {
                val main = javaClass.classLoader!!.getResourceAsStream("fixtures/$fixture/mainram.bin")!!.readBytes()
                fun offset(addr: Long) = (addr - 0x02000000L).toInt()
                val usCfg = if (fixture == "heartgold") GEN4_HEARTGOLD_US else GEN4_SOULSILVER_US
                val ptrBytes = main.copyOfRange(offset(usCfg.saveDataPtr), offset(usCfg.saveDataPtr) + 4)
                ptrBytes.copyInto(main, offset(cfg.saveDataPtr))
                main.fill(0, offset(usCfg.saveDataPtr), offset(usCfg.saveDataPtr) + 4)
                val calls = object : GameCalls {
                    override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int): Long = error("Repels don't call code")
                    override fun read(addr: Long, len: Int) = main.copyOfRange(offset(addr), offset(addr) + len)
                    override fun write(addr: Long, bytes: ByteArray): Boolean {
                        bytes.copyInto(main, offset(addr)); return true
                    }
                }
                val body = u32(ptrBytes, 0) + cfg.bodyOff
                val pocket = body + cfg.bagOff
                val steps = body + cfg.repelStepsOff!!
                calls.write(pocket, ByteArray(cfg.pocketSizes[0] * 4))
                calls.write(pocket, byteArrayOf(item.toByte(), 0, 1, 0, 17, 0, 2, 0))
                calls.write(steps, byteArrayOf(0))
                val before = main.copyOf()
                assertEquals(ItemUseResult.NOT_NOW, useGen4Repel(cfg, calls, item, duration, false).result)
                assertArrayEquals(before, main)
                assertEquals(ItemUseResult.USED, useGen4Repel(cfg, calls, item, duration, true).result)
                val expected = before.copyOf()
                byteArrayOf(17, 0, 2, 0, 0, 0, 0, 0).copyInto(expected, offset(pocket))
                expected[offset(steps)] = duration.toByte()
                assertArrayEquals("$fixture item $item: no unrelated RAM changed", expected, main)
                assertEquals(ItemUseResult.STILL_ACTIVE, useGen4Repel(cfg, calls, item, duration, true).result)
                assertArrayEquals(expected, main)
                calls.write(steps, byteArrayOf(0))
                val empty = main.copyOf()
                assertEquals(ItemUseResult.NONE_LEFT, useGen4Repel(cfg, calls, item, duration, true).result)
                assertArrayEquals(empty, main)
            }
        }
    }

    @Test
    fun `German cart names and art come from the player's ROM`() {
        val roms = listOf(
            "Pokemon - Goldene Edition HeartGold (Germany).nds",
            "Pokemon - Silberne Edition SoulSilver (Germany).nds",
        ).map { File(File(RETAIL_ROM_DIR).parentFile, "nds/$it") }
        assumeTrue("German HG/SS ROMs unavailable", roms.all { it.isFile })
        for (rom in roms) {
            assertTrue(CompanionSupport.isSupported(rom))
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
}
