@file:OptIn(ExperimentalMaterial3Api::class)

package fr.poiscail.minijeux

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val P0 = Color(0xFFFF5C7A) // hôte
val P1 = Color(0xFF3DDBD9) // invité
val Win = Color(0xFF22C55E)
val Lose = Color(0xFFEF4444)
val Surface2 = Color(0xFF241E4A)
val Dark = Color(0xFF0B0A16)
val BG = Brush.verticalGradient(listOf(Color(0xFF1D1640), Dark))

fun playerColor(p: Int) = if (p == 0) P0 else P1

/** w : 0 = moi, 1 = l'adversaire, autre = égalité. */
fun verdictText(w: Int) = when (w) {
    0 -> "Victoire ! 🎉"
    1 -> "Défaite…"
    else -> "Égalité"
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color(0xFF8B7CFF), onPrimary = Color.White,
        secondary = P1, onSecondary = Dark,
        surface = Color(0xFF17132E), onSurface = Color.White,
        surfaceVariant = Surface2, onSurfaceVariant = Color(0xFFB4B0D0),
        background = Dark,
    ),
    content = content,
)

@Composable
fun Header(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Retour") }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun BigButton(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 13.sp, color = Color.White.copy(alpha = .8f))
        }
    }
}

@Composable
fun GameCard(g: GameId, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface2)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(16.dp)) {
            Text(g.emoji, fontSize = 36.sp)
            Spacer(Modifier.height(8.dp))
            Text(g.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(g.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GameFrame(ctx: GameCtx, game: GameId, status: String, result: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ctx.quit) { Text("‹ Menu") }
            Spacer(Modifier.weight(1f))
            Text("contre ${ctx.opponent}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${game.emoji} ${game.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        if (result != null) ResultCard(result, ctx)
    }
}

@Composable
private fun ResultCard(text: String, ctx: GameCtx) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface2)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ctx.again) { Text("Rejouer") }
                OutlinedButton(onClick = ctx.quit) { Text("Menu") }
            }
        }
    }
}

@Composable
fun Scores(ctx: GameCtx, mine: String, theirs: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ScoreBox("Toi", mine, playerColor(ctx.me))
        ScoreBox(ctx.opponent, theirs, playerColor(ctx.opp))
    }
}

@Composable
private fun ScoreBox(label: String, value: String, color: Color) {
    Column(Modifier.widthIn(max = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 36.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
