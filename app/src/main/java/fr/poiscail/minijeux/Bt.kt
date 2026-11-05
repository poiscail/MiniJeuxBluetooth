package fr.poiscail.minijeux

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Liaison Bluetooth classique (RFCOMM) avec un adversaire. Les messages sont des lignes de texte.
 * Tous les callbacks sont appelés sur le thread principal.
 */
@SuppressLint("MissingPermission") // l'activité vérifie les autorisations avant host()/join()
class Bt(
    private val onConnected: (String) -> Unit,
    private val onLine: (List<String>) -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var gen = 0 // incrémenté par close() : les threads d'une ancienne connexion se taisent

    fun host(adapter: BluetoothAdapter) {
        val my = reset()
        thread(name = "bt-host") {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord(SERVICE, APP_UUID)
                if (!claim(my) { server = srv }) {
                    srv.close()
                    return@thread
                }
                val s = srv.accept()
                srv.close() // un seul adversaire
                start(s, my)
            } catch (e: IOException) {
                fail(my, "Impossible d'attendre un joueur. Le Bluetooth est-il activé ?")
            }
        }
    }

    fun join(adapter: BluetoothAdapter, device: BluetoothDevice) {
        val my = reset()
        thread(name = "bt-join") {
            try {
                adapter.cancelDiscovery() // la recherche ralentit fortement la connexion
                val s = device.createRfcommSocketToServiceRecord(APP_UUID) // déclenche l'appairage si besoin
                if (!claim(my) { socket = s }) {
                    s.close()
                    return@thread
                }
                s.connect()
                start(s, my)
            } catch (e: IOException) {
                fail(my, "Connexion impossible. Vérifie que l'autre téléphone a lancé « Héberger une partie ».")
            }
        }
    }

    fun send(line: String) {
        val s = socket ?: return
        writer.execute {
            try {
                s.outputStream.write((line + "\n").toByteArray())
                s.outputStream.flush()
            } catch (e: IOException) {
                quiet { s.close() } // le thread de lecture signale la perte
            }
        }
    }

    fun close() {
        val (s, srv) = synchronized(this) {
            gen++
            (socket to server).also { socket = null; server = null }
        }
        quiet { s?.close() }
        quiet { srv?.close() }
    }

    private fun reset(): Int {
        close()
        return gen
    }

    @Synchronized
    private fun claim(my: Int, set: () -> Unit): Boolean = (my == gen).also { if (it) set() }

    private fun start(s: BluetoothSocket, my: Int) {
        if (!claim(my) { socket = s }) {
            quiet { s.close() }
            return
        }
        val name = s.remoteDevice.name ?: s.remoteDevice.address
        post(my) { onConnected(name) }
        try {
            s.inputStream.bufferedReader().forEachLine { line ->
                Proto.decode(line)?.let { msg -> post(my) { onLine(msg) } }
            }
        } catch (e: IOException) {
            // connexion coupée
        }
        if (my == gen) {
            close()
            main.post { onClosed() }
        }
    }

    private fun fail(my: Int, msg: String) {
        if (my == gen) {
            close()
            main.post { onError(msg) }
        }
    }

    private fun post(my: Int, f: () -> Unit) {
        main.post { if (my == gen) f() }
    }

    private inline fun quiet(f: () -> Unit) {
        try { f() } catch (e: IOException) { }
    }

    companion object {
        private const val SERVICE = "MiniJeuxBT"
        private val APP_UUID: UUID = UUID.fromString("8f3c2a61-5b7e-4d2a-9c1e-6a7b3d9e0f42")
    }
}
