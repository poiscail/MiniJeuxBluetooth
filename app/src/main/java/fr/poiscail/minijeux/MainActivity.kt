@file:OptIn(ExperimentalMaterial3Api::class)

package fr.poiscail.minijeux

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.random.Random

sealed interface Screen {
    data object Home : Screen
    data class Lobby(val hosting: Boolean) : Screen
    data object Solo : Screen
    data object Menu : Screen
    data class Play(val game: GameId, val seed: Long) : Screen
}

private val PERMS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION) // la recherche Bluetooth exige la localisation avant Android 12

private val PLAYABLE_CLASSES = setOf(
    BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.COMPUTER,
    BluetoothClass.Device.Major.MISC, BluetoothClass.Device.Major.UNCATEGORIZED,
)

@SuppressLint("MissingPermission") // withBluetooth() obtient les autorisations avant tout appel Bluetooth
class MainActivity : ComponentActivity() {
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var isHost = false
    private var solo = false
    private var diff = 0
    private var opponent by mutableStateOf("")
    private var alert by mutableStateOf<String?>(null)
    private var hostInfo by mutableStateOf("")
    private var connecting by mutableStateOf<String?>(null)
    private var scanning by mutableStateOf(false)
    private val devices = mutableStateListOf<BluetoothDevice>()

    // Messages de jeu reçus avant que l'écran du jeu soit prêt à les écouter.
    private var listener: ((List<String>) -> Unit)? = null
    private val pending = ArrayDeque<List<String>>()

    private val bt = Bt(
        onConnected = { name ->
            opponent = name
            connecting = null
            stopScan()
            screen = Screen.Menu
        },
        onLine = { onLine(it) },
        onError = { msg ->
            alert = msg
            connecting = null
            if (screen == Screen.Lobby(hosting = true)) screen = Screen.Home
        },
        onClosed = {
            resetMsgs()
            if (screen != Screen.Home) {
                alert = "Connexion perdue avec $opponent."
                screen = Screen.Home
            }
        },
    )

    private var afterPerms: (() -> Unit)? = null
    private var afterEnable: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val next = afterPerms
        afterPerms = null
        if (res.isNotEmpty() && res.values.all { it }) next?.invoke()
        else alert = "Autorise l'accès Bluetooth (« Appareils à proximité ») pour jouer à plusieurs.\n" +
            "Si la demande ne s'affiche plus : Paramètres › Applications › Mini-Jeux BT › Autorisations."
    }

    private val enableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val next = afterEnable
        afterEnable = null
        if (adapter?.isEnabled == true) next?.invoke() else alert = "Active le Bluetooth pour jouer."
    }

    private val discoverableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_CANCELED)
            hostInfo = "Ton téléphone n'est pas visible : seuls les appareils déjà appairés pourront te rejoindre."
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND ->
                    IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.let { addDevice(it) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
                    Box(Modifier.fillMaxSize().background(BG).systemBarsPadding()) { App() }
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        bt.close()
        stopScan()
        super.onDestroy()
    }

    // ---------- Bluetooth ----------

    private fun withBluetooth(action: () -> Unit) {
        val a = adapter ?: run { alert = "Ce téléphone n'a pas de Bluetooth."; return }
        val missing = PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            afterPerms = { withBluetooth(action) }
            permLauncher.launch(missing.toTypedArray())
            return
        }
        if (!a.isEnabled) {
            afterEnable = action
            enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    private fun host() = withBluetooth {
        isHost = true
        solo = false
        hostInfo = "Visible sous le nom « ${adapter?.name} »"
        screen = Screen.Lobby(hosting = true)
        bt.host(adapter!!)
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        )
    }

    private fun join() = withBluetooth {
        isHost = false
        solo = false
        connecting = null
        devices.clear()
        adapter!!.bondedDevices.forEach { addDevice(it) }
        screen = Screen.Lobby(hosting = false)
        scan()
    }

    private fun addDevice(d: BluetoothDevice) {
        val major = d.bluetoothClass?.majorDeviceClass
        val playable = major == null || major in PLAYABLE_CLASSES // écarte casques, montres, etc.
        if (d.name != null && playable && devices.none { it.address == d.address }) devices.add(d)
    }

    private fun scan() {
        val a = adapter ?: return
        a.cancelDiscovery()
        scanning = a.startDiscovery()
    }

    private fun stopScan() {
        scanning = false
        try { adapter?.cancelDiscovery() } catch (e: SecurityException) { } // autorisation jamais accordée
    }

    private fun connect(d: BluetoothDevice) {
        stopScan()
        connecting = d.name ?: d.address
        bt.join(adapter!!, d)
    }

    private fun leave() {
        solo = false
        bt.close()
        stopScan()
        connecting = null
        resetMsgs()
        screen = Screen.Home
    }

    // ---------- Messages ----------
    // G|jeu|graine : lancer un jeu (seul l'hôte l'envoie) · R|jeu : l'invité demande un jeu
    // Q : retour au menu · M|… : coup propre au jeu en cours

    private fun onLine(m: List<String>) {
        when (m[0]) {
            "G" -> {
                val g = GameId.entries.find { it.name == m.getOrNull(1) }
                val seed = m.getOrNull(2)?.toLongOrNull()
                if (g != null && seed != null && seed > 0) openGame(g, seed)
            }
            "R" -> if (isHost) GameId.entries.find { it.name == m.getOrNull(1) }?.let(this@MainActivity::startGame)
            "Q" -> if (screen is Screen.Play) {
                resetMsgs()
                screen = Screen.Menu
                alert = "$opponent est revenu au menu."
            }
            "M" -> {
                val p = m.drop(1)
                val l = listener
                if (l != null) l(p) else pending.addLast(p)
            }
        }
    }

    private fun resetMsgs() {
        listener = null
        pending.clear()
    }

    private fun listen(l: (List<String>) -> Unit) {
        listener = l
        while (listener === l && pending.isNotEmpty()) l(pending.removeFirst())
    }

    private fun unlisten(l: (List<String>) -> Unit) {
        if (listener === l) listener = null
    }

    private fun openGame(g: GameId, seed: Long) {
        resetMsgs()
        screen = Screen.Play(g, seed)
    }

    // L'hôte seul décide du jeu : évite que deux choix simultanés désynchronisent les téléphones.
    private fun startGame(g: GameId) {
        val seed = Random.nextLong(1, Long.MAX_VALUE)
        if (!solo) bt.send(Proto.encode("G", g.name, seed))
        openGame(g, seed)
    }

    private fun pick(g: GameId) {
        if (isHost || solo) startGame(g) else bt.send(Proto.encode("R", g.name))
    }

    private fun quitGame() {
        if (!solo) bt.send(Proto.encode("Q"))
        resetMsgs()
        screen = Screen.Menu
    }

    // ---------- Écrans ----------

    @Composable
    private fun App() {
        BackHandler(enabled = screen != Screen.Home) { if (screen is Screen.Play) quitGame() else leave() }
        when (val s = screen) {
            Screen.Home -> HomeScreen()
            is Screen.Lobby -> if (s.hosting) HostLobby() else JoinLobby()
            Screen.Solo -> SoloScreen()
            Screen.Menu -> MenuScreen()
            is Screen.Play -> {
                val ctx = remember(s) {
                    GameCtx(
                        me = if (isHost) 0 else 1, seed = s.seed, opponent = opponent,
                        sendLine = bt::send, again = { pick(s.game) }, quit = this@MainActivity::quitGame,
                        listen = this@MainActivity::listen, unlisten = this@MainActivity::unlisten,
                        solo = solo, diff = diff,
                    )
                }
                key(s) { GameScreen(s.game, ctx) }
            }
        }
        alert?.let {
            AlertDialog(
                onDismissRequest = { alert = null },
                confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } },
                text = { Text(it) },
            )
        }
    }

    @Composable
    private fun HomeScreen() {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🎮", fontSize = 72.sp)
            Text("Mini-Jeux", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Text("Bluetooth · sans Internet", color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(48.dp))
            BigButton("Héberger une partie", "Ton téléphone attend un adversaire", MaterialTheme.colorScheme.primary, this@MainActivity::host)
            Spacer(Modifier.height(16.dp))
            BigButton("Rejoindre une partie", "Cherche un téléphone à proximité", Color(0xFF0E9F9C), this@MainActivity::join)
            Spacer(Modifier.height(16.dp))
            BigButton("Jouer en solo", "Contre l'IA · 3 difficultés", Color(0xFFE08A1E)) { screen = Screen.Solo }
            Spacer(Modifier.height(32.dp))
            Text("${GameId.entries.size} mini-jeux · solo ou 2 joueurs", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun SoloScreen() {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Header("Jouer en solo", this@MainActivity::leave)
            Text("Choisis la difficulté de l'IA", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            val colors = listOf(Win, Color(0xFFE08A1E), Lose)
            val subs = listOf("Pour s'échauffer", "Un vrai adversaire", "Bonne chance…")
            DIFFS.forEachIndexed { i, name ->
                BigButton(name, subs[i], colors[i]) {
                    solo = true
                    isHost = true
                    diff = i
                    opponent = "IA $name"
                    resetMsgs()
                    screen = Screen.Menu
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun HostLobby() {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Header("Héberger une partie", this@MainActivity::leave)
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 5.dp)
            Spacer(Modifier.height(24.dp))
            Text("En attente d'un joueur…", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(hostInfo, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Sur l'autre téléphone : « Rejoindre une partie », puis choisis ce téléphone.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = this@MainActivity::leave, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
        }
    }

    @Composable
    private fun JoinLobby() {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Header("Rejoindre une partie", this@MainActivity::leave)
            Text("Sur l'autre téléphone, appuie sur « Héberger une partie », puis choisis-le ci-dessous.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Appareils à proximité", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = this@MainActivity::scan, enabled = connecting == null) { Text("Rechercher") }
            }
            connecting?.let {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = Surface2)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Connexion à $it…\nAccepte la demande d'appairage si elle s'affiche.")
                    }
                }
            }
            if (devices.isEmpty() && !scanning) {
                Text("Aucun appareil trouvé. Vérifie que l'autre téléphone héberge une partie, puis relance la recherche.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices, key = { it.address }) { d ->
                    Card(
                        onClick = { connect(d) },
                        enabled = connecting == null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface2),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📱", fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(d.name ?: d.address, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (d.bondState == BluetoothDevice.BOND_BONDED) "Déjà appairé" else "Nouvel appareil",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuScreen() {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choisis un jeu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (solo) "🤖 $opponent" else "● Connecté à $opponent", color = Win)
                }
                TextButton(onClick = this@MainActivity::leave) { Text("Quitter") }
            }
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(GameId.entries) { g -> GameCard(g) { pick(g) } }
            }
        }
    }
}
