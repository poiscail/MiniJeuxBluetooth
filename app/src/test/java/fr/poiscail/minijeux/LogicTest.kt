package fr.poiscail.minijeux

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class LogicTest {
    @Test
    fun auMoinsVingtJeux() {
        assertTrue(GameId.entries.size >= 20)
        assertEquals(3, DIFFS.size)
    }

    @Test
    fun protocoleAllerRetour() {
        val line = Proto.encode("M", "p", 4)
        assertEquals("M|p|4", line)
        assertEquals(listOf("M", "p", "4"), Proto.decode("$line\r"))
        assertNull(Proto.decode("   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun protocoleRefuseLeSeparateur() {
        Proto.encode("M", "a|b")
    }

    @Test
    fun morpionLigneEtDiagonale() {
        val b = Board(3, 3, 3, gravity = false, first = 0)
        listOf(0, 3, 1, 4, 2).forEach { assertTrue(b.play(it)) }
        assertEquals(0, b.result)
        assertEquals(setOf(0, 1, 2), b.winLine.toSet())
        assertFalse(b.play(5)) // partie finie

        val d = Board(3, 3, 3, gravity = false, first = 1)
        listOf(0, 1, 4, 2, 8).forEach { d.play(it) }
        assertEquals(1, d.result)
    }

    @Test
    fun morpionCaseOccupeeEtMatchNul() {
        val b = Board(3, 3, 3, gravity = false, first = 0)
        assertTrue(b.play(4))
        assertFalse(b.play(4))
        assertFalse(b.play(9))
        assertEquals(1, b.turn)

        val n = Board(3, 3, 3, gravity = false, first = 0)
        listOf(0, 1, 2, 4, 3, 5, 7, 6, 8).forEach { assertTrue(n.play(it)) }
        assertEquals(Board.DRAW, n.result)
    }

    @Test
    fun quatreEnLigneGraviteEtVictoire() {
        val b = Board(7, 6, 4, gravity = true, first = 0)
        assertTrue(b.play(3))
        assertEquals(0, b.cells[5 * 7 + 3]) // le jeton tombe en bas
        listOf(4, 3, 4, 3, 4, 3).forEach { assertTrue(b.play(it)) }
        assertEquals(0, b.result)

        val full = Board(7, 6, 4, gravity = true, first = 0)
        repeat(6) { assertTrue(full.play(0)) }
        assertFalse(full.play(0)) // colonne pleine
        assertFalse(full.play(7)) // hors grille
        assertFalse(full.play(-1))
    }

    @Test
    fun iaGrilleGagneOuBloque() {
        // L'IA (joueur 1) peut gagner tout de suite en 2.
        val win = Board(3, 3, 3, gravity = false, first = 0)
        listOf(3, 0, 4, 1, 8).forEach { win.play(it) }
        assertEquals(2, BoardAi.choose(win, 2, Random(1)))

        // Le joueur 0 menace la colonne 3 : l'IA bloque.
        val block = Board(7, 6, 4, gravity = true, first = 0)
        listOf(3, 0, 3, 0, 3).forEach { block.play(it) }
        assertEquals(3, BoardAi.choose(block, 1, Random(1)))
    }

    @Test
    fun iaDifficileNePerdJamaisAuMorpion() {
        repeat(20) { g ->
            val r = Random(g)
            val b = Board(3, 3, 3, gravity = false, first = g % 2)
            while (b.result == Board.ONGOING) {
                val moves = b.candidateMoves()
                b.play(if (b.turn == 1) BoardAi.choose(b, 2, r) else moves[r.nextInt(moves.size)])
            }
            assertNotEquals(0, b.result)
        }
    }

    @Test
    fun iaSurGrandesGrillesJoueUnCoupLegal() {
        for (g in listOf(GameId.GRAND_MORPION, GameId.CINQ)) {
            val b = Board(g.board!!, first = 0)
            b.play(b.cells.size / 2)
            val m = BoardAi.choose(b, 2, Random(3))
            assertEquals(-1, b.cells[m])
        }
    }

    @Test
    fun allumettes() {
        assertNull(nimBestTake(5)) // 5 = 1 mod 4 : position perdante
        assertEquals(3, nimBestTake(8))
        assertEquals(1, nimBestTake(2))
        repeat(50) { assertTrue(nimAi(2, 0, Random(it)) in 1..2) }
        assertEquals(3, nimAi(16, 2, Random(0)))
        assertTrue(nimStart(123) in 15..25)
    }

    @Test
    fun pierreFeuilleCiseaux() {
        assertEquals(0, rpsWinner(1, 0)) // feuille bat pierre
        assertEquals(0, rpsWinner(0, 2)) // pierre bat ciseaux
        assertEquals(0, rpsWinner(2, 1)) // ciseaux battent feuille
        assertEquals(1, rpsWinner(0, 1))
        assertEquals(-1, rpsWinner(2, 2))
        assertEquals(1, rpsAi(listOf(2, 0), 1, Random(0))) // bat le dernier coup (pierre) avec feuille
    }

    @Test
    fun scoresEtReflexes() {
        assertEquals(1, compareScores(10, 12))
        assertEquals(0, compareScores(200, 300, lowerIsBetter = true))
        assertEquals(1, compareScores(FALSE_START, 900, lowerIsBetter = true))
        assertEquals(-1, compareScores(250, 250, lowerIsBetter = true))
        assertTrue(reflexDelay(Long.MAX_VALUE) in 1500L until 5000L)
    }

    @Test
    fun scoreIaSelonDifficulte() {
        val r = Random(7)
        for (g in GameId.entries) {
            val easy = List(30) { botScore(g, 0, r) }.average()
            val hard = List(30) { botScore(g, 2, r) }.average()
            assertTrue(g.name, if (g.lowerIsBetter) hard < easy else hard > easy)
        }
    }

    @Test
    fun defisIdentiquesDesDeuxCotesEtValides() {
        val quick = GameId.entries.filter { it.board == null } -
            setOf(GameId.REFLEX, GameId.TAP, GameId.CHRONO, GameId.TAUPES, GameId.SUITE, GameId.MYSTERE, GameId.PFC, GameId.NIM)
        for (g in quick) {
            val a = ChallengeSource(g, 42)
            val b = ChallengeSource(g, 42)
            repeat(40) {
                val c = a.next()
                assertEquals(c, b.next())
                assertTrue(g.name, c.answer in c.options.indices)
                if (g == GameId.INTRUS) assertEquals(1, c.options.count { it == c.options[c.answer] })
                else assertEquals(g.name, c.options.size, c.options.toSet().size)
            }
        }
        Quiz.all.forEach { assertTrue(it.answer in it.choices.indices) }
    }
}
