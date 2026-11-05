package fr.poiscail.minijeux

import kotlin.math.abs
import kotlin.random.Random

/** Protocole réseau : une ligne de texte par message, champs séparés par « | ». */
object Proto {
    fun encode(vararg parts: Any): String = parts.joinToString("|") { p ->
        p.toString().also { require('|' !in it && '\n' !in it) { "caractère interdit dans « $it »" } }
    }

    fun decode(line: String): List<String>? = line.trim().takeIf { it.isNotEmpty() }?.split('|')
}

data class BoardSpec(val cols: Int, val rows: Int, val need: Int, val gravity: Boolean)

enum class GameId(
    val title: String,
    val emoji: String,
    val desc: String,
    val board: BoardSpec? = null,
    val lowerIsBetter: Boolean = false,
) {
    REFLEX("Duel de réflexes", "⚡", "Touche dès que l'écran passe au vert", lowerIsBetter = true),
    TAP("Tapotage éclair", "👆", "Le plus de tapes en 10 secondes"),
    CHRONO("Chrono parfait", "⏱️", "Arrête le chrono pile à 10,00 s", lowerIsBetter = true),
    TAUPES("Chasse aux taupes", "🐹", "Tape un max de taupes en 20 s"),
    SUITE("Suite de couleurs", "🎨", "Retiens la suite la plus longue"),
    MYSTERE("Nombre mystère", "🔢", "Trouve-le en un minimum d'essais", lowerIsBetter = true),
    CALCUL("Calcul mental", "➗", "Un max de calculs justes en 30 s"),
    COULEUR("Couleur piège", "🌈", "Choisis la couleur de l'encre, pas le mot"),
    PLUSGRAND("Le plus grand", "⚖️", "Touche le plus grand nombre"),
    PAIRIMPAIR("Pair ou impair", "🎲", "Pair ou impair ? Vite !"),
    INTRUS("L'intrus", "🔍", "Repère l'emoji différent"),
    COMPTE("Compte vite", "🧮", "Combien d'objets à l'écran ?"),
    FLECHES("Flèches", "🧭", "Suis la flèche… à l'envers si elle est rouge"),
    MOTS("Mot mélangé", "🔤", "Retrouve le mot caché"),
    QUIZ("Quiz express", "❓", "Culture générale en 60 s"),
    MORPION("Morpion", "❌", "Aligne 3 symboles", BoardSpec(3, 3, 3, false)),
    GRAND_MORPION("Grand morpion", "🟦", "Grille 5×5, aligne 4", BoardSpec(5, 5, 4, false)),
    CINQ("Cinq en ligne", "⚫", "Grille 9×9, aligne 5", BoardSpec(9, 9, 5, false)),
    LIGNE4("Quatre en ligne", "🔴", "Fais tomber tes jetons, aligne-en 4", BoardSpec(7, 6, 4, true)),
    PFC("Pierre-feuille-ciseaux", "✊", "Le premier à 3 manches"),
    NIM("Allumettes", "🪵", "Prends 1 à 3 allumettes, pas la dernière !"),
}

val DIFFS = listOf("Facile", "Moyen", "Difficile")

const val RPS_WIN = 3
const val TAP_SECONDS = 10
const val MOLE_SECONDS = 20
const val QUICK_SECONDS = 30
const val QUIZ_SECONDS = 60
const val FALSE_START = 9999L // score d'un faux départ au duel de réflexes

// ---------- Grilles (morpion, quatre en ligne…) ----------

class Board(val cols: Int, val rows: Int, private val need: Int, private val gravity: Boolean, first: Int) {
    constructor(spec: BoardSpec, first: Int) : this(spec.cols, spec.rows, spec.need, spec.gravity, first)

    val cells = IntArray(cols * rows) { -1 }
    var turn = first; private set
    var result = ONGOING; private set
    var winLine: List<Int> = emptyList(); private set

    /** move = index de case (sans gravité) ou numéro de colonne (gravité). false si coup illégal. */
    fun play(move: Int): Boolean {
        if (result != ONGOING) return false
        val cell = target(move) ?: return false
        cells[cell] = turn
        checkEnd(cell)
        turn = 1 - turn
        return true
    }

    fun copy(): Board = Board(cols, rows, need, gravity, turn).also {
        cells.copyInto(it.cells)
        it.result = result
    }

    /** Coups à étudier pour l'IA, du centre vers les bords. Sur les grandes grilles, seulement près des pions. */
    fun candidateMoves(): List<Int> {
        if (result != ONGOING) return emptyList()
        if (gravity) return (0 until cols).filter { cells[it] == -1 }.sortedBy { abs(it - cols / 2) }
        val empty = cells.indices.filter { cells[it] == -1 }
        if (empty.size == cells.size) return listOf(cells.size / 2)
        val pool = if (cells.size <= 25) empty else empty.filter { i -> near(i) }
        return pool.sortedBy { abs(it % cols - cols / 2) + abs(it / cols - rows / 2) }
    }

    fun hardDepth(): Int = when {
        gravity -> 6
        cells.size <= 9 -> 9
        else -> 3
    }

    /** Valeur de la position pour le joueur p : fenêtres de « need » cases encore jouables. */
    fun evaluate(p: Int): Long {
        var s = 0L
        for (w in windowsFor(cols, rows, need)) {
            var mine = 0
            var theirs = 0
            for (i in w) {
                val v = cells[i]
                if (v == p) mine++ else if (v != -1) theirs++
            }
            if (theirs == 0) s += WEIGHT[mine] else if (mine == 0) s -= WEIGHT[theirs]
        }
        return s
    }

    private fun near(i: Int): Boolean {
        val c = i % cols
        val r = i / cols
        for (dr in -1..1) for (dc in -1..1) {
            val cc = c + dc
            val rr = r + dr
            if (cc in 0 until cols && rr in 0 until rows && cells[rr * cols + cc] != -1) return true
        }
        return false
    }

    private fun target(move: Int): Int? =
        if (gravity) {
            if (move !in 0 until cols) null
            else (rows - 1 downTo 0).map { it * cols + move }.firstOrNull { cells[it] == -1 }
        } else move.takeIf { it in cells.indices && cells[it] == -1 }

    private fun checkEnd(cell: Int) {
        val c0 = cell % cols
        val r0 = cell / cols
        val p = cells[cell]
        for ((dc, dr) in DIRS) {
            val line = mutableListOf(cell)
            for (sign in listOf(1, -1)) {
                var c = c0 + dc * sign
                var r = r0 + dr * sign
                while (c in 0 until cols && r in 0 until rows && cells[r * cols + c] == p) {
                    line += r * cols + c
                    c += dc * sign
                    r += dr * sign
                }
            }
            if (line.size >= need) {
                result = p
                winLine = line
                return
            }
        }
        if (cells.none { it == -1 }) result = DRAW
    }

    companion object {
        const val ONGOING = -1
        const val DRAW = 2
        private val DIRS = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        private val WEIGHT = longArrayOf(0, 1, 10, 100, 1_000, 10_000, 100_000)
        private val windowCache = HashMap<Triple<Int, Int, Int>, List<IntArray>>()

        private fun windowsFor(cols: Int, rows: Int, need: Int): List<IntArray> = synchronized(windowCache) {
            windowCache.getOrPut(Triple(cols, rows, need)) {
                val list = mutableListOf<IntArray>()
                for (r in 0 until rows) for (c in 0 until cols) for ((dc, dr) in DIRS) {
                    val ec = c + dc * (need - 1)
                    val er = r + dr * (need - 1)
                    if (ec in 0 until cols && er in 0 until rows)
                        list += IntArray(need) { k -> (r + dr * k) * cols + (c + dc * k) }
                }
                list
            }
        }
    }
}

/** IA des grilles : negamax avec élagage alpha-bêta. */
object BoardAi {
    private const val INF = Long.MAX_VALUE / 4
    private const val WIN = 1_000_000_000L

    fun choose(b: Board, diff: Int, r: Random): Int {
        val moves = b.candidateMoves()
        if (diff == 0 && r.nextInt(100) < 40) return moves[r.nextInt(moves.size)]
        val depth = when (diff) {
            0 -> 1
            1 -> 2
            else -> b.hardDepth()
        }
        var best = moves[0]
        var bestScore = -INF
        for (m in moves.shuffled(r)) { // mélange : varie les parties à score égal
            val c = b.copy()
            c.play(m)
            val s = -negamax(c, depth - 1, -INF, -bestScore)
            if (s > bestScore) {
                bestScore = s
                best = m
            }
        }
        return best
    }

    /** Score pour le joueur qui doit jouer sur b. */
    private fun negamax(b: Board, depth: Int, alpha: Long, beta: Long): Long {
        if (b.result == Board.DRAW) return 0
        if (b.result != Board.ONGOING) return -(WIN + depth) // l'adversaire vient de gagner ; plus tôt = pire
        if (depth <= 0) return b.evaluate(b.turn)
        var a = alpha
        var best = -INF
        for (m in b.candidateMoves()) {
            val c = b.copy()
            c.play(m)
            val s = -negamax(c, depth - 1, -beta, -a)
            if (s > best) best = s
            if (best > a) a = best
            if (a >= beta) break
        }
        return best
    }
}

// ---------- Allumettes ----------

fun nimStart(seed: Long): Int = 15 + (seed % 11).toInt()

/** Coup gagnant : laisser un nombre d'allumettes égal à 1 modulo 4 (null si la position est perdante). */
fun nimBestTake(left: Int): Int? = ((left - 1) % 4).takeIf { it in 1..3 }

fun nimAi(left: Int, diff: Int, r: Random): Int {
    val best = nimBestTake(left)
    val useBest = when (diff) {
        0 -> false
        1 -> r.nextBoolean()
        else -> true
    }
    return if (useBest && best != null) best else 1 + r.nextInt(minOf(3, left))
}

// ---------- Pierre-feuille-ciseaux ----------

/** Les fonctions de verdict renvoient 0 si a gagne, 1 si b gagne, -1 en cas d'égalité. */
fun rpsWinner(a: Int, b: Int): Int = when ((a - b + 3) % 3) { // 0 pierre, 1 feuille, 2 ciseaux
    0 -> -1
    1 -> 0
    else -> 1
}

/** L'IA ne voit que les coups déjà joués par l'adversaire. */
fun rpsAi(history: List<Int>, diff: Int, r: Random): Int = when {
    diff == 0 || history.isEmpty() -> r.nextInt(3)
    diff == 1 -> (history.last() + 1) % 3 // bat le dernier coup adverse
    r.nextInt(100) < 25 -> r.nextInt(3)
    else -> (history.groupingBy { it }.eachCount().maxBy { it.value }.key + 1) % 3 // bat le coup favori
}

// ---------- Jeux à score ----------

fun compareScores(a: Long, b: Long, lowerIsBetter: Boolean = false): Int = when {
    a == b -> -1
    (a < b) == lowerIsBetter -> 0
    else -> 1
}

fun fmtScore(game: GameId, v: Long): String = when (game) {
    GameId.REFLEX -> if (v >= FALSE_START) "faux départ" else "$v ms"
    GameId.CHRONO -> "écart $v ms"
    GameId.MYSTERE -> "$v essai" + if (v > 1) "s" else ""
    else -> "$v pt" + if (v > 1) "s" else ""
}

/** Même graine des deux côtés → même délai, sans synchroniser d'horloge. */
fun reflexDelay(seed: Long): Long = 1500 + seed % 3500

/** Score simulé de l'IA pour les jeux où chacun joue de son côté. */
fun botScore(game: GameId, diff: Int, r: Random): Long {
    val ranges = when (game) {
        GameId.REFLEX -> listOf(380..560, 280..380, 190..270)
        GameId.TAP -> listOf(35..50, 50..65, 65..85)
        GameId.CHRONO -> listOf(150..600, 50..180, 0..60)
        GameId.TAUPES -> listOf(10..16, 16..23, 23..30)
        GameId.SUITE -> listOf(3..5, 5..8, 8..12)
        GameId.MYSTERE -> listOf(8..12, 6..8, 4..6)
        GameId.QUIZ -> listOf(4..7, 7..11, 11..16)
        else -> listOf(6..10, 10..16, 16..24)
    }
    val range = ranges[diff]
    return (range.first + r.nextInt(range.last - range.first + 1)).toLong()
}

// ---------- Défis à choix rapide ----------

data class Challenge(val prompt: String, val options: List<String>, val answer: Int, val promptColor: Long? = null)

private val COLORS = listOf("Rouge" to 0xFFEF4444, "Bleu" to 0xFF3B82F6, "Vert" to 0xFF22C55E, "Jaune" to 0xFFFACC15)
private val ARROWS = listOf("←", "↑", "→", "↓")
private val LOOKALIKES = listOf(
    "🍎" to "🍏", "😀" to "😃", "🐶" to "🐱", "🌕" to "🌝", "⚽" to "🏀",
    "🚗" to "🚕", "❤️" to "🧡", "🐻" to "🐼", "🌲" to "🌳", "⭐" to "🌟",
)
private val COUNTABLE = listOf("🍓", "🐟", "⭐", "🎈", "🍪")
val WORDS = listOf(
    "MAISON", "JARDIN", "SOLEIL", "BATEAU", "CHEVAL", "FROMAGE", "MUSIQUE", "VOITURE", "PLANETE", "DRAGON",
    "CITRON", "FENETRE", "OISEAU", "NUAGE", "LAPIN", "GUITARE", "PIRATE", "CHATEAU", "MONTAGNE", "ORANGE",
    "TOMATE", "FLEUR", "ROBOT", "CAHIER", "BALLON", "TIGRE", "SOURIS", "ETOILE", "CRAYON", "PIZZA",
)

/** Suite de défis tirée de la graine : les deux joueurs reçoivent les mêmes. */
class ChallengeSource(private val game: GameId, seed: Long) {
    private val r = Random(seed)
    private val quiz = Quiz.all.shuffled(r)
    private var qi = 0

    fun next(): Challenge = when (game) {
        GameId.CALCUL -> {
            val op = r.nextInt(3)
            val a = if (op == 2) 2 + r.nextInt(8) else 5 + r.nextInt(45)
            val b = if (op == 2) 2 + r.nextInt(8) else 2 + r.nextInt(30)
            when (op) {
                0 -> numberChoice("$a + $b", a + b)
                1 -> numberChoice("${maxOf(a, b)} − ${minOf(a, b)}", abs(a - b))
                else -> numberChoice("$a × $b", a * b)
            }
        }
        GameId.COULEUR -> {
            val word = r.nextInt(4)
            val ink = (word + 1 + r.nextInt(3)) % 4
            choice(COLORS[word].first.uppercase(), COLORS.map { it.first }, COLORS[ink].first, COLORS[ink].second)
        }
        GameId.PLUSGRAND -> {
            val a = 100 + r.nextInt(900)
            var b = a + r.nextInt(-30, 31)
            if (b == a) b = a + 1
            Challenge("Le plus grand ?", listOf("$a", "$b"), if (a > b) 0 else 1)
        }
        GameId.PAIRIMPAIR -> {
            val n = r.nextInt(1, 1000)
            Challenge("$n", listOf("Pair", "Impair"), n % 2)
        }
        GameId.INTRUS -> {
            val pair = LOOKALIKES[r.nextInt(LOOKALIKES.size)]
            val (same, odd) = if (r.nextBoolean()) pair else pair.second to pair.first
            val pos = r.nextInt(6)
            Challenge("Trouve l'intrus", List(6) { if (it == pos) odd else same }, pos)
        }
        GameId.COMPTE -> {
            val n = 3 + r.nextInt(10)
            numberChoice(COUNTABLE[r.nextInt(COUNTABLE.size)].repeat(n), n, spread = 3)
        }
        GameId.FLECHES -> {
            val d = r.nextInt(4)
            val red = r.nextInt(100) < 35
            Challenge(ARROWS[d], ARROWS, if (red) (d + 2) % 4 else d, if (red) 0xFFEF4444 else 0xFFFFFFFF)
        }
        GameId.MOTS -> {
            val w = WORDS[r.nextInt(WORDS.size)]
            var s: String
            do { s = w.toList().shuffled(r).joinToString("") } while (s == w)
            val others = (WORDS - w).sortedBy { abs(it.length - w.length) * 3 + r.nextInt(3) }.take(3)
            choice(s, others + w, w)
        }
        else -> {
            val q = quiz[qi++ % quiz.size]
            Challenge(q.text, q.choices, q.answer)
        }
    }

    private fun numberChoice(prompt: String, ans: Int, spread: Int = 10): Challenge {
        val opts = mutableSetOf(ans)
        while (opts.size < 4) {
            val d = ans + r.nextInt(-spread, spread + 1)
            if (d >= 0) opts += d
        }
        return choice(prompt, opts.map { it.toString() }, ans.toString())
    }

    private fun choice(prompt: String, opts: List<String>, good: String, color: Long? = null): Challenge {
        val s = opts.shuffled(r)
        return Challenge(prompt, s, s.indexOf(good), color)
    }
}

data class Question(val text: String, val choices: List<String>, val answer: Int)

object Quiz {
    val all = listOf(
        Question("Quelle est la capitale de l'Australie ?", listOf("Sydney", "Canberra", "Melbourne", "Perth"), 1),
        Question("Combien de pattes a une araignée ?", listOf("6", "8", "10", "12"), 1),
        Question("Quelle planète est la plus proche du Soleil ?", listOf("Vénus", "Mars", "Mercure", "Terre"), 2),
        Question("Combien de minutes y a-t-il dans une journée ?", listOf("1 440", "1 200", "3 600", "2 400"), 0),
        Question("Quel est le plus grand océan ?", listOf("Atlantique", "Indien", "Arctique", "Pacifique"), 3),
        Question("Quel gaz les plantes absorbent-elles ?", listOf("Oxygène", "Azote", "CO₂", "Hélium"), 2),
        Question("En quelle année l'homme a-t-il marché sur la Lune ?", listOf("1959", "1969", "1979", "1989"), 1),
        Question("Quel est le symbole chimique de l'or ?", listOf("Or", "Ag", "Au", "Go"), 2),
        Question("Combien de côtés a un hexagone ?", listOf("5", "6", "7", "8"), 1),
        Question("Quelle langue parle-t-on au Brésil ?", listOf("Espagnol", "Portugais", "Brésilien", "Anglais"), 1),
        Question("Quel est le plus long fleuve de France ?", listOf("Seine", "Rhône", "Loire", "Garonne"), 2),
        Question("Combien font 7 × 8 ?", listOf("54", "56", "58", "64"), 1),
        Question("Quel animal terrestre est le plus rapide ?", listOf("Lion", "Guépard", "Antilope", "Cheval"), 1),
        Question("Quel organe pompe le sang ?", listOf("Foie", "Poumon", "Cœur", "Rein"), 2),
        Question("Dans quel pays se trouve le Machu Picchu ?", listOf("Mexique", "Chili", "Pérou", "Bolivie"), 2),
        Question("Combien de joueurs par équipe sur un terrain de football ?", listOf("9", "10", "11", "12"), 2),
        Question("Bleu + jaune donnent…", listOf("Vert", "Violet", "Orange", "Marron"), 0),
        Question("Quel est l'os le plus long du corps humain ?", listOf("Tibia", "Humérus", "Fémur", "Radius"), 2),
        Question("Quelle est la capitale du Canada ?", listOf("Toronto", "Ottawa", "Montréal", "Vancouver"), 1),
        Question("À quelle température l'eau bout-elle au niveau de la mer ?", listOf("90 °C", "100 °C", "110 °C", "120 °C"), 1),
        Question("Combien de continents compte-t-on habituellement ?", listOf("5", "6", "7", "8"), 2),
        Question("Quel est le plus grand mammifère ?", listOf("Éléphant", "Baleine bleue", "Girafe", "Orque"), 1),
        Question("Combien y a-t-il de jours dans une année bissextile ?", listOf("364", "365", "366", "367"), 2),
        Question("Quelle est la capitale du Japon ?", listOf("Kyoto", "Osaka", "Tokyo", "Séoul"), 2),
        Question("Quel instrument a 88 touches ?", listOf("Piano", "Orgue", "Accordéon", "Harpe"), 0),
    )
}
