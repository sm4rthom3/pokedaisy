package com.pokedaisy.app.companion.data

import com.pokedaisy.app.CompanionSupport
import com.pokedaisy.app.GameTitles
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Routing / menu regressions run everywhere. Cart-content tests run only with the player's local ROMs. */
class GermanRetailTest {
    private val root = File(RETAIL_ROM_DIR).parentFile!!
    private fun cart(platform: String, name: String): File = File(root, "$platform/$name").also {
        assumeTrue("local ROM missing: ${it.name}", it.isFile)
    }

    @Test
    fun `German Game Boy hashes are explicitly supported retail games`() {
        for (hash in TelemetrySampler.GERMAN_GB_SHA1S) {
            assertTrue(hash, hash in TelemetrySampler.SUPPORTED_GB_SHA1S)
            assertTrue(hash, GameTitles.isRetail(hash))
            assertNotNull(GameTitles.BY_SHA1[hash])
        }
    }

    @Test
    fun `German Red reads party bag and dex through relocated RAM`() {
        val us = FixtureMemoryReader.load("red")
        val german = object : MemoryReader {
            override fun readCoreMemory(addr: Long, size: Int): ByteArray =
                us.readCoreMemory(if (addr in 0xCFE9L..0xDFFFL) addr - 5 else addr, size)
        }
        val expected = readGen1Telemetry(us, GEN1_RED)
        val actual = readGen1Telemetry(german, GEN1_RED_DE)
        assertEquals(expected.party.map { Triple(it.species, it.level, it.hp) }, actual.party.map { Triple(it.species, it.level, it.hp) })
        assertEquals(expected.items, actual.items)
        assertEquals(expected.money, actual.money)
        assertEquals(expected.mapNum, actual.mapNum)
        assertEquals(expected.pokedex!!.caught, actual.pokedex!!.caught)
        assertEquals(expected.pokedex!!.seen, actual.pokedex!!.seen)
    }

    @Test
    fun `German Gen 2 battle menu reads all four wider columns`() {
        for (cfg in listOf(GEN2_GOLD_DE, GEN2_SILVER_DE, GEN2_CRYSTAL_DE)) {
            val memory = HashMap<Long, Byte>()
            fun put(addr: Long, vararg bytes: Int) = bytes.forEachIndexed { i, b -> memory[addr + i] = b.toByte() }
            val reader = object : MemoryReader {
                override fun readCoreMemory(addr: Long, size: Int) = ByteArray(size) { memory[addr + it] ?: 0 }
            }
            put(cfg.battleMode, 1)
            put(cfg.menu2D, 14, 5, 2, 2, 0, 0, 0x27)
            for (pick in 0..3) {
                val x = pick % 2; val y = pick / 2
                put(cfg.menuCursor, y + 1, x + 1)
                put(cfg.tileMap + (14 + y * 2) * 20 + 5 + x * 7, 0xED)
                assertEquals(BATTLE_INPUT_ACTION_SELECT to pick, readGen2BattleInput(reader, cfg))
            }
            put(cfg.menuCursor, 1, 1)
            put(cfg.tileMap + 14 * 20 + 5, 0xEC) // A consumed: hollow cursor must not accept another action
            assertEquals(BATTLE_INPUT_BUSY to -1, readGen2BattleInput(reader, cfg))
        }
    }

    @Test
    fun `German Red and Blue rebuild art and all dex pages from the cart`() {
        for ((name, cfg, art) in listOf(
            Triple("Pokemon - Rote Edition (Germany) (SGB Enhanced).gb", GEN1_RED_DE, Gen1Art.RED_DE),
            Triple("Pokemon - Blaue Edition (Germany) (SGB Enhanced).gb", GEN1_BLUE_DE, Gen1Art.BLUE_DE),
        )) {
            val file = cart("gb", name)
            assertTrue(CompanionSupport.isSupported(file))
            val rom = file.readBytes()
            val previous = Gen1Dex.rom
            try {
                Gen1Dex.rom = rom
                val t = cfg.pokedex!!.gen1!!
                val first = Gen1Dex.entry(t, 1)!!
                assertEquals("SAMEN", first.category)
                assertEquals(7 to 69, first.heightDm to first.weightHg)
                for (species in 1..151) {
                    assertTrue("$name entry $species", Gen1Dex.entry(t, species)!!.description.isNotBlank())
                    assertNotNull("$name pic $species", Gen1Dex.frontPixels(t, species))
                }
                assertEquals(151, Gen1Art.icons(rom, art).size)
                assertEquals(160, Gen1Art.townMap(rom, art).width)
                assertTrue(GB_FIELD_RED_BLUE_DE.codeMatches { off, size -> rom.copyOfRange(off.toInt(), off.toInt() + size) })
                assertTrue(Gen1Art.font(rom, art).size > 1000)
            } finally { Gen1Dex.rom = previous }
        }
    }

    @Test
    fun `German Gen 2 names metric pages sprites and descriptions come from each cart`() {
        for ((name, cfg, art) in listOf(
            Triple("Pokemon - Goldene Edition (Germany) (SGB Enhanced) (GB Compatible).gbc", GEN2_GOLD_DE, Gen2Art.GOLD_DE),
            Triple("Pokemon - Silberne Edition (Germany) (SGB Enhanced) (GB Compatible).gbc", GEN2_SILVER_DE, Gen2Art.SILVER_DE),
            Triple("Pokemon - Kristall-Edition (Germany).gbc", GEN2_CRYSTAL_DE, Gen2Art.CRYSTAL_DE),
        )) {
            val file = cart("gbc", name)
            assertTrue(CompanionSupport.isSupported(file))
            val rom = file.readBytes()
            val previous = Gen1Dex.rom
            try {
                Gen1Dex.rom = rom
                val text = GermanGen2Text.forCode(cfg.gameCode)!!
                assertEquals("BISASAM", text.species[1])
                assertEquals("ENDIVIE", text.species[152])
                assertEquals("DONNERBLITZ", text.moveData[85]!!.name)
                assertEquals("TRANK", text.items[18])
                assertEquals("NEUBORKIA", text.mapSecData[1]!!.name)
                val t = cfg.pokedex!!.gen2!!
                for (species in 1..251) {
                    val entry = Gen2Dex.entry(t, species)!!
                    assertTrue("$name entry $species", entry.description.isNotBlank())
                    assertTrue("$name height $species: ${entry.heightDm}", entry.heightDm in 1..150)
                    assertNotNull("$name pic $species", Gen2Dex.frontPixels(t, species))
                }
                val first = Gen2Dex.entry(t, 1)!!
                assertEquals("SAMEN", first.category)
                assertEquals(7 to 69, first.heightDm to first.weightHg)
                assertTrue(Gen2Dex.itemDescription(t, 18).isNotBlank())
                assertTrue(Gen2Dex.itemDescription(t, 0xBF).isNotBlank())
                assertEquals(252, Gen2Art.icons(rom, art).size)
                assertEquals(160, Gen2Art.townMap(rom, art, 0)!!.width)
                assertEquals(160, Gen2Art.townMap(rom, art, 1)!!.width)
                assertTrue(Gen2Art.font(rom, art).size > 1000)
                if (cfg === GEN2_CRYSTAL_DE) assertTrue(GbField.CrystalGerman.codeMatches { off, size -> rom.copyOfRange(off.toInt(), off.toInt() + size) })
            } finally { Gen1Dex.rom = previous }
        }
    }

    @Test
    fun `German SoulSilver and Platinum names and art use their own carts`() {
        for ((name, platinum) in listOf(
            "Pokemon - Silberne Edition SoulSilver (Germany).nds" to false,
            "Pokemon - Platin-Edition (Germany).nds" to true,
        )) {
            val file = cart("nds", name)
            assertTrue(CompanionSupport.isSupported(file))
            val names = NdsArt.readNames(file)!!
            assertEquals('D', names.language)
            assertEquals("BISASAM", names.species(1))
            assertEquals("Tackle", names.move(33))
            assertEquals("Trank", names.item(17))
            val dir = kotlin.io.path.createTempDirectory("nds-de-test-").toFile()
            try {
                assertTrue(NdsArt.extractTo(file, dir))
                val front = if (platinum) NdsArt.PLATINUM_FRONT else NdsArt.HEARTGOLD_FRONT
                assertTrue(File(dir, "$front/1.png").isFile)
                val dex = File(dir, if (platinum) NdsArt.PLATINUM_DEX else NdsArt.HEARTGOLD_DEX).readLines()
                assertEquals(493, dex.size)
                assertTrue(dex.first().split('\t')[1].startsWith("Samen"))
            } finally { dir.deleteRecursively() }
        }
    }
}
