package fr.poiscail.minijeux

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Ce qu'un mini-jeu voit de la partie. me = 0 pour l'hôte (ou en solo), 1 pour l'invité. */
class GameCtx(
    val me: Int,
    val seed: Long,
    val opponent: String,
    private val sendLine: (String) -> Unit,
    val again: () -> Unit,
    val quit: () -> Unit,
    val listen: ((List<String>) -> Unit) -> Unit,
    val unlisten: ((List<String>) -> Unit) -> Unit,
    val solo: Boolean = false,
    val diff: Int = 0,
) {
    val opp get() = 1 - me
    val rnd = Random(seed xor 0x5DEECE66DL) // hasard de l'IA
    fun send(vararg parts: Any) {
        if (!solo) sendLine(Proto.encode("M", *parts))
    }
}

/** Reçoit les messages « M » de l'adversaire tant que le jeu est affiché. */
@Composable
private fun OnMsg(ctx: GameCtx, handler: (List<String>) -> Unit) {
    val h by rememberUpdatedState(handler)
    DisposableEffect(ctx) {
        val l: (List<String>) -> Unit = { h(it) }
        ctx.listen(l)
        onDispose { ctx.unlisten(l) }
    }
}

@Composable
fun GameScreen(game: GameId, ctx: GameCtx) {
    val spec = game.board
    when {
        spec != null -> BoardGame(ctx, game, spec)
        game == GameId.PFC -> RpsGame(ctx)
        game == GameId.NIM -> NimGame(ctx)
        game == GameId.REFLEX -> ReflexGame(ctx)
        game == GameId.TAP -> TapGame(ctx)
        game == GameId.CHRONO -> ChronoGame(ctx)
        game == GameId.TAUPES -> MoleGame(ctx)
        game == GameId.SUITE -> SequenceGame(ctx)
        game == GameId.MYSTERE -> MysteryGame(ctx)
        else -> QuickGame(ctx, game)
    }
}

// ---------- Grilles : morpion, grand morpion, cinq en ligne, quatre en ligne ----------

@Composable
private fun BoardGame(ctx: GameCtx, game: GameId, spec: BoardSpec) {
    val board = remember { Board(spec, first = (ctx.seed % 2).toInt()) }
    var cells by remember { mutableStateOf(board.cells.toList()) }
    fun play(move: Int, mine: Boolean) {
        if (board.turn == (if (mine) ctx.me else ctx.opp) && board.play(move)) {
            if (mine) ctx.send("p", move)
            cells = board.cells.toList()
        }
    }
    OnMsg(ctx) { m -> if (m.getOrNull(0) == "p") m.getOrNull(1)?.toIntOrNull()?.let { play(it, mine = false) } }
    LaunchedEffect(cells) {
        if (ctx.solo && board.result == Board.ONGOING && board.turn == ctx.opp) {
            delay(400)
            val snap = board.copy()
            play(withContext(Dispatchers.Default) { BoardAi.choose(snap, ctx.diff, ctx.rnd) }, mine = false)
        }
    }

    val status = when {
        board.result != Board.ONGOING -> ""
        board.turn == ctx.me -> "À toi de jouer !"
        else -> "${ctx.opponent} réfléchit…"
    }
    val result = when (board.result) {
        Board.ONGOING -> null
        Board.DRAW -> "Match nul"
        ctx.me -> "Victoire ! 🎉"
        else -> "Défaite…"
    }
    val cols = spec.cols
    val small = cols > 5
    GameFrame(ctx, game, status, result) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tu joues  ")
            Box(Modifier.size(22.dp)) { Piece(ctx.me, spec.gravity, 18.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / spec.rows)
                .clip(RoundedCornerShape(24.dp)).background(Surface2).padding(if (small) 4.dp else 6.dp)
        ) {
            for (r in 0 until spec.rows) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(if (small) 1.dp else 4.dp)
                            .clip(if (spec.gravity) CircleShape else RoundedCornerShape(20))
                            .background(if (i in board.winLine) Color.White.copy(alpha = .3f) else Dark)
                            .clickable { play(if (spec.gravity) c else i, mine = true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cells[i] >= 0) Piece(cells[i], spec.gravity, (150 / cols).sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Piece(p: Int, disc: Boolean, fontSize: TextUnit) {
    if (disc) Box(Modifier.fillMaxSize(0.8f).clip(CircleShape).background(playerColor(p)))
    else Text(if (p == 0) "✕" else "◯", color = playerColor(p), fontWeight = FontWeight.Bold, fontSize = fontSize)
}

// ---------- Allumettes ----------

@Composable
private fun NimGame(ctx: GameCtx) {
    var left by remember { mutableIntStateOf(nimStart(ctx.seed)) }
    var turn by remember { mutableIntStateOf((ctx.seed % 2).toInt()) }
    var last by remember { mutableStateOf("") }
    fun take(n: Int, mine: Boolean) {
        if (left == 0 || turn != (if (mine) ctx.me else ctx.opp) || n !in 1..minOf(3, left)) return
        left -= n
        turn = 1 - turn
        last = (if (mine) "Tu as pris" else "${ctx.opponent} a pris") + " $n"
        if (mine) ctx.send("n", n)
    }
    OnMsg(ctx) { m -> if (m.getOrNull(0) == "n") m.getOrNull(1)?.toIntOrNull()?.let { take(it, mine = false) } }
    LaunchedEffect(turn, left) {
        if (ctx.solo && left > 0 && turn == ctx.opp) {
            delay(800)
            take(nimAi(left, ctx.diff, ctx.rnd), mine = false)
        }
    }
    // Celui qui prend la dernière perd : le joueur dont c'est le tour quand il n'en reste plus a gagné.
    val result = if (left == 0) verdictText(if (turn == ctx.me) 0 else 1) else null
    val status = when {
        left == 0 -> last
        turn == ctx.me -> "À toi : prends 1, 2 ou 3"
        else -> "${ctx.opponent} réfléchit…"
    }
    GameFrame(ctx, GameId.NIM, status, result) {
        Text("$left", fontSize = 64.sp, fontWeight = FontWeight.Black)
        Text("allumettes restantes", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(List(left) { "🪵" }.joinToString(" "), fontSize = 26.sp, textAlign = TextAlign.Center, lineHeight = 34.sp)
        Spacer(Modifier.height(8.dp))
        if (left > 0) Text(last, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (n in 1..3) Button(
                onClick = { take(n, mine = true) },
                enabled = left > 0 && turn == ctx.me && n <= left,
                modifier = Modifier.weight(1f).height(72.dp),
                shape = RoundedCornerShape(20.dp),
            ) { Text("$n", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Pierre-feuille-ciseaux ----------

private val RPS = listOf("✊" to "Pierre", "✋" to "Feuille", "✌️" to "Ciseaux")

@Composable
private fun RpsGame(ctx: GameCtx) {
    var round by remember { mutableIntStateOf(0) }
    var mine by remember { mutableStateOf<Int?>(null) }
    val theirs = remember { mutableStateMapOf<Int, Int>() }
    val history = remember { mutableListOf<Int>() } // mes coups passés, seule info de l'IA
    var myScore by remember { mutableIntStateOf(0) }
    var oppScore by remember { mutableIntStateOf(0) }
    var verdict by remember { mutableStateOf("") }
    OnMsg(ctx) { m ->
        val r = m.getOrNull(1)?.toIntOrNull()
        val c = m.getOrNull(2)?.toIntOrNull()
        if (m.getOrNull(0) == "c" && r != null && c != null && c in 0..2) theirs[r] = c
    }
    val opp = theirs[round]
    val over = myScore >= RPS_WIN || oppScore >= RPS_WIN
    LaunchedEffect(round, mine, opp) {
        val a = mine ?: return@LaunchedEffect
        val b = opp ?: return@LaunchedEffect
        history += a
        when (rpsWinner(a, b)) {
            0 -> { myScore++; verdict = "Manche gagnée !" }
            1 -> { oppScore++; verdict = "Manche perdue" }
            else -> verdict = "Égalité, on rejoue"
        }
        if (myScore < RPS_WIN && oppScore < RPS_WIN) {
            delay(1600)
            verdict = ""
            mine = null
            round++
        }
    }
    val status = when {
        over -> ""
        mine == null -> "Choisis ton coup · premier à $RPS_WIN"
        opp == null -> "En attente de ${ctx.opponent}…"
        else -> verdict
    }
    val result = if (over) verdictText(if (myScore > oppScore) 0 else 1) else null
    GameFrame(ctx, GameId.PFC, status, result) {
        Scores(ctx, "$myScore", "$oppScore")
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BigEmoji(mine?.let { RPS[it].first } ?: "❔", playerColor(ctx.me))
            Text("VS", fontWeight = FontWeight.Black, fontSize = 20.sp)
            BigEmoji(
                when {
                    opp == null -> "⏳"
                    mine == null -> "✔️" // l'adversaire a choisi, on ne révèle pas encore
                    else -> RPS[opp].first
                },
                playerColor(ctx.opp),
            )
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RPS.forEachIndexed { i, (emoji, label) ->
                Button(
                    onClick = {
                        mine = i
                        ctx.send("c", round, i)
                        if (ctx.solo) theirs[round] = rpsAi(history, ctx.diff, ctx.rnd)
                    },
                    enabled = mine == null && !over,
                    modifier = Modifier.weight(1f).height(96.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emoji, fontSize = 32.sp)
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigEmoji(e: String, color: Color) {
    Box(
        Modifier.size(110.dp).clip(CircleShape).background(color.copy(alpha = .18f)).border(3.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(e, fontSize = 52.sp) }
}

// ---------- Jeux à score : chacun joue de son côté, puis on compare ----------
// M|t|score : score en direct · M|f|score : score final. En solo, l'IA tire un score selon la difficulté.

private class Duel(val ctx: GameCtx, val game: GameId) {
    var mine by mutableStateOf<Long?>(null)
    var theirs by mutableStateOf<Long?>(null)
    var oppLive by mutableStateOf<Long?>(null)

    fun live(v: Number) = ctx.send("t", v.toLong())

    fun finish(v: Number) {
        if (mine != null) return
        mine = v.toLong()
        ctx.send("f", v.toLong())
        if (ctx.solo) theirs = botScore(game, ctx.diff, ctx.rnd)
    }

    val result: String?
        get() {
            val a = mine ?: return null
            val b = theirs ?: return null
            return verdictText(compareScores(a, b, game.lowerIsBetter))
        }

    fun status(playing: String) = when {
        mine == null -> playing
        theirs == null -> "En attente de ${ctx.opponent}…"
        else -> ""
    }
}

@Composable
private fun rememberDuel(ctx: GameCtx, game: GameId): Duel {
    val d = remember { Duel(ctx, game) }
    OnMsg(ctx) { m ->
        val v = m.getOrNull(1)?.toLongOrNull() ?: return@OnMsg
        when (m[0]) {
            "t" -> d.oppLive = v
            "f" -> d.theirs = v
        }
    }
    return d
}

/** current : mon score en cours de partie (null si le jeu n'en affiche pas). */
@Composable
private fun DuelScores(d: Duel, current: Long?) {
    val fmt = { v: Long -> fmtScore(d.game, v) }
    val them = if (d.mine != null) d.theirs ?: d.oppLive else d.oppLive // score final caché tant que je joue
    Scores(d.ctx, (d.mine ?: current)?.let(fmt) ?: "…", them?.let(fmt) ?: "…")
}

@Composable
private fun ReflexGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.REFLEX)
    var go by remember { mutableStateOf(false) }
    var goAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        delay(reflexDelay(ctx.seed))
        if (d.mine == null) {
            goAt = SystemClock.uptimeMillis()
            go = true
        }
    }
    val me = d.mine
    val color = when {
        me == FALSE_START -> Lose
        me != null -> Surface2
        go -> Win
        else -> Color(0xFFB91C1C)
    }
    GameFrame(ctx, GameId.REFLEX, d.status(if (go) "MAINTENANT !" else "Attends le vert… pas trop tôt !"), d.result) {
        DuelScores(d, null)
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp)
                .clip(RoundedCornerShape(32.dp)).background(color)
                .pointerInput(Unit) { // au toucher, pas au relâchement
                    detectTapGestures(onPress = { d.finish(if (go) SystemClock.uptimeMillis() - goAt else FALSE_START) })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    me == null && go -> "TOUCHE !"
                    me == null -> "…"
                    else -> fmtScore(GameId.REFLEX, me)
                },
                fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White,
            )
        }
    }
}

@Composable
private fun TapGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.TAP)
    var countdown by remember { mutableIntStateOf(3) }
    var left by remember { mutableIntStateOf(TAP_SECONDS) }
    var running by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (countdown > 0) { delay(1000); countdown-- }
        running = true
        while (left > 0) { delay(1000); left-- }
        running = false
        d.finish(count)
    }
    val playing = if (countdown > 0) "Prépare-toi…" else "Tape le plus vite possible ! ${left}s"
    GameFrame(ctx, GameId.TAP, d.status(playing), d.result) {
        DuelScores(d, count.toLong())
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(240.dp).clip(CircleShape)
                .background(if (running) playerColor(ctx.me) else Surface2)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (running) {
                            count++
                            d.live(count)
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    countdown > 0 -> "$countdown"
                    running -> "TAPE !"
                    else -> "Fini"
                },
                fontSize = 44.sp, fontWeight = FontWeight.Black,
            )
        }
    }
}

private fun chronoText(ms: Long) = "%d,%02d s".format(ms / 1000, ms % 1000 / 10)

@Composable
private fun ChronoGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.CHRONO)
    var startAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val running = startAt > 0 && d.mine == null
    LaunchedEffect(running) {
        while (running) {
            elapsed = SystemClock.uptimeMillis() - startAt
            delay(16)
        }
    }
    fun press() {
        if (startAt == 0L) startAt = SystemClock.uptimeMillis()
        else if (d.mine == null) {
            elapsed = SystemClock.uptimeMillis() - startAt
            d.finish(abs(elapsed - 10_000))
        }
    }
    val playing = if (startAt == 0L) "Lance le chrono, puis arrête-le à 10,00 s" else "Le chrono se cache après 3 s…"
    GameFrame(ctx, GameId.CHRONO, d.status(playing), d.result) {
        DuelScores(d, null)
        Spacer(Modifier.height(24.dp))
        Text(
            if (running && elapsed >= 3000) "?,?? s" else chronoText(elapsed),
            fontSize = 64.sp, fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(200.dp).clip(CircleShape).background(if (running) Lose else playerColor(ctx.me))
                .pointerInput(Unit) { detectTapGestures(onPress = { press() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    startAt == 0L -> "DÉPART"
                    running -> "STOP"
                    else -> "Fini"
                },
                fontSize = 32.sp, fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun MoleGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.TAUPES)
    var mole by remember { mutableIntStateOf(-1) }
    var hits by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(MOLE_SECONDS) }
    LaunchedEffect(Unit) {
        val r = Random(ctx.seed)
        delay(1000)
        val end = SystemClock.uptimeMillis() + MOLE_SECONDS * 1000L
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now >= end) break
            left = ((end - now + 999) / 1000).toInt()
            var next: Int
            do { next = r.nextInt(9) } while (next == mole)
            mole = next
            delay(minOf(700L, end - now))
        }
        mole = -1
        left = 0
        d.finish(hits)
    }
    GameFrame(ctx, GameId.TAUPES, d.status("Tape les taupes ! ${left}s"), d.result) {
        DuelScores(d, hits.toLong())
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until 3) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in 0 until 3) {
                    val i = r * 3 + c
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(Surface2)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    if (mole == i && d.mine == null) {
                                        hits++
                                        mole = -1
                                        d.live(hits)
                                    }
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) { if (mole == i) Text("🐹", fontSize = 52.sp) }
                }
            }
        }
    }
}

private val PADS = listOf(Color(0xFFEF4444), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFFACC15))

@Composable
private fun SequenceGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.SUITE)
    val r = remember { Random(ctx.seed) }
    val seq = remember { mutableStateListOf(r.nextInt(4)) }
    var lit by remember { mutableIntStateOf(-1) }
    var showing by remember { mutableStateOf(true) }
    var pos by remember { mutableIntStateOf(0) }
    LaunchedEffect(seq.size) {
        showing = true
        delay(800)
        val on = maxOf(250L, 550L - seq.size * 25L) // de plus en plus vite
        for (c in seq.toList()) {
            lit = c
            delay(on)
            lit = -1
            delay(180)
        }
        pos = 0
        showing = false
    }
    fun press(c: Int) {
        if (showing || d.mine != null) return
        if (c == seq[pos]) {
            pos++
            if (pos == seq.size) {
                d.live(seq.size)
                seq.add(r.nextInt(4))
            }
        } else d.finish(seq.size - 1)
    }
    val playing = if (showing) "Regarde bien…" else "À toi : ${pos}/${seq.size}"
    GameFrame(ctx, GameId.SUITE, d.status(playing), d.result) {
        DuelScores(d, (seq.size - 1).toLong())
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in 0 until 2) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                            .background(PADS[i].copy(alpha = if (lit == i) 1f else .35f))
                            .clickable(enabled = !showing && d.mine == null) { press(i) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MysteryGame(ctx: GameCtx) {
    val d = rememberDuel(ctx, GameId.MYSTERE)
    val target = remember { Random(ctx.seed).nextInt(1, 101) }
    var lo by remember { mutableIntStateOf(1) }
    var hi by remember { mutableIntStateOf(100) }
    var guess by remember { mutableIntStateOf(50) }
    var tries by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf("Entre 1 et 100") }
    fun propose() {
        if (d.mine != null) return
        tries++
        when {
            guess < target -> { lo = guess + 1; hint = "C'est plus ! ⬆" }
            guess > target -> { hi = guess - 1; hint = "C'est moins ! ⬇" }
            else -> { hint = "Trouvé : $target"; d.finish(tries) }
        }
        guess = guess.coerceIn(lo, hi)
    }
    GameFrame(ctx, GameId.MYSTERE, d.status("Essai n° ${tries + 1} · entre $lo et $hi"), d.result) {
        DuelScores(d, tries.toLong())
        Spacer(Modifier.height(12.dp))
        Text(hint, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("$guess", fontSize = 72.sp, fontWeight = FontWeight.Black, color = playerColor(ctx.me))
        if (hi > lo && d.mine == null) {
            Slider(
                value = guess.toFloat(),
                onValueChange = { guess = it.roundToInt().coerceIn(lo, hi) },
                valueRange = lo.toFloat()..hi.toFloat(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { guess = (guess - 1).coerceIn(lo, hi) }, enabled = d.mine == null) { Text("−", fontSize = 24.sp) }
            Button(onClick = { propose() }, enabled = d.mine == null, modifier = Modifier.height(56.dp)) {
                Text("Proposer", fontSize = 18.sp)
            }
            OutlinedButton(onClick = { guess = (guess + 1).coerceIn(lo, hi) }, enabled = d.mine == null) { Text("+", fontSize = 24.sp) }
        }
    }
}

// ---------- Défis rapides : calcul, couleur piège, intrus, quiz… ----------

private fun quickHint(game: GameId) = when (game) {
    GameId.COULEUR -> "Touche la couleur de l'ENCRE"
    GameId.FLECHES -> "Flèche rouge = sens inverse !"
    GameId.MOTS -> "Remets les lettres dans l'ordre"
    GameId.COMPTE -> "Combien ?"
    GameId.INTRUS -> "Touche l'emoji différent"
    else -> "Bonne réponse +1, erreur −1"
}

@Composable
private fun QuickGame(ctx: GameCtx, game: GameId) {
    val d = rememberDuel(ctx, game)
    val src = remember { ChallengeSource(game, ctx.seed) }
    var ch by remember { mutableStateOf(src.next()) }
    var score by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf<Boolean?>(null) }
    var answers by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(if (game == GameId.QUIZ) QUIZ_SECONDS else QUICK_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) { delay(1000); left-- }
        d.finish(score)
    }
    LaunchedEffect(answers) {
        delay(300)
        flash = null
    }
    fun answer(i: Int) {
        if (d.mine != null) return
        val ok = i == ch.answer
        score = if (ok) score + 1 else maxOf(0, score - 1)
        flash = ok
        answers++
        d.live(score)
        ch = src.next()
    }
    GameFrame(ctx, game, d.status("${left}s · ${quickHint(game)}"), d.result) {
        DuelScores(d, score.toLong())
        if (d.mine == null) {
            val bg = when (flash) {
                true -> Win.copy(alpha = .45f)
                false -> Lose.copy(alpha = .45f)
                null -> Surface2
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(24.dp)).background(bg).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ch.prompt,
                    color = ch.promptColor?.let { Color(it) } ?: Color.White,
                    fontSize = if (ch.prompt.length <= 10) 48.sp else 22.sp,
                    lineHeight = if (ch.prompt.length <= 10) 54.sp else 30.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            val perRow = if (ch.options.size == 6) 3 else 2
            ch.options.withIndex().chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (i, o) ->
                        Button(
                            onClick = { answer(i) },
                            modifier = Modifier.weight(1f).padding(vertical = 5.dp).height(64.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Color.White),
                            contentPadding = PaddingValues(4.dp),
                        ) { Text(o, fontSize = if (o.length <= 3) 30.sp else 16.sp, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
}
