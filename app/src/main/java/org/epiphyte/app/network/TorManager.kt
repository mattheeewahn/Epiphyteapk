package org.epiphyte.app.network

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tor manager for Android using Briar project's tor-android.
 * The tor-android AAR bundles libtor.so in native libs.
 * We extract and run it as a subprocess with a custom torrc.
 * 
 * This is the same approach used by Briar Messenger (Play Store approved).
 */
class TorManager {
    var socksPort: Int = 9150
    var controlPort: Int = 9151
    var hiddenServicePort: Int = 7777
    var onionAddress: String = ""
    var isRunning: Boolean = false
        private set

    private var statusCallback: ((String, Int) -> Unit)? = null
    private var context: Context? = null
    private var torProcess: Process? = null

    private val defaultBridges = listOf(
        "obfs4 193.11.166.194:27025 2D82C2E354D531A68469ADA8F719C297D76B9F5D cert=0RWSTSwuEqwd/7HNqs3eP/JDkMGr0hEUBINIoJ2A8iNpSMZaZi2AoIkjB4NI iat-mode=0",
        "obfs4 209.148.46.65:443 74FAD13168806246602538555B5521A0383A1875 cert=ssH+9rP8dG2NLDN2XuFw63hIO/9MNNnLZTjnDROfvzjyJkLknN+5vxGVY/VfhBq iat-mode=1",
        "obfs4 146.57.248.225:22 10A6CD36A537FCE513A322361547444B393989F0 cert=K1gDtDAIcUfeLqbstggjIw2rtgIKqdIhUlHp82XRqNSq/cB0dPbVDynpUhJsNEsFif iat-mode=0",
    )

    fun init(ctx: Context) {
        context = ctx
    }

    fun setStatusCallback(cb: (String, Int) -> Unit) {
        statusCallback = cb
    }

    private fun reportStatus(msg: String, progress: Int = -1) {
        statusCallback?.invoke(msg, progress)
    }

    fun start(useBridges: Boolean = true, transport: String = "obfs4"): Boolean {
        val ctx = context ?: return false

        val dataDir = File(ctx.filesDir, "tor_data")
        dataDir.mkdirs()
        val hsDir = File(ctx.filesDir, "hidden_service")
        hsDir.mkdirs()
        // Tor needs a cache dir
        val cacheDir = File(ctx.cacheDir, "tor_cache")
        cacheDir.mkdirs()

        socksPort = findFreePort(9150)
        controlPort = findFreePort(9151)
        hiddenServicePort = findFreePort(7777)

        // Find tor binary (shipped by org.briarproject:tor-android)
        val torBinary = findTorBinary(ctx)
        if (torBinary == null) {
            reportStatus("Tor binary not found in native libs", -1)
            return false
        }
        torBinary.setExecutable(true, false)

        // Find obfs4proxy binary (shipped by org.briarproject:obfs4proxy-android)
        val obfs4Binary = findObfs4Binary(ctx)
        obfs4Binary?.setExecutable(true, false)

        // Write torrc
        val torrc = buildTorrc(dataDir, hsDir, useBridges, obfs4Binary)
        val torrcFile = File(dataDir, "torrc")
        torrcFile.writeText(torrc)

        reportStatus("Starting Tor...", 10)

        try {
            val env = mutableMapOf<String, String>()
            env["HOME"] = dataDir.absolutePath
            env["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir

            val pb = ProcessBuilder(torBinary.absolutePath, "-f", torrcFile.absolutePath)
            pb.directory(dataDir)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            torProcess = pb.start()

            // Read initial output in background
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(torProcess!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // Log tor output for debugging
                        val l = line ?: continue
                        if (l.contains("Bootstrapped") && l.contains("%")) {
                            val pctMatch = Regex("(\\d+)%").find(l)
                            pctMatch?.let {
                                val pct = it.groupValues[1].toInt()
                                reportStatus("Bootstrapping... $pct%", 20 + pct * 7 / 10)
                            }
                        }
                    }
                } catch (e: Exception) { /* process ended */ }
            }.start()

            // Check immediate crash
            Thread.sleep(3000)
            if (torProcess?.isAlive != true) {
                reportStatus("Tor process died immediately", -1)
                return false
            }

            reportStatus("Bootstrapping...", 20)

            // Wait for bootstrap via control port
            if (!waitForBootstrap(180_000L)) {
                reportStatus("Bootstrap timeout", -1)
                stop()
                return false
            }

            // Read hidden service address
            readOnionAddress(hsDir)
            isRunning = true
            reportStatus("Connected", 100)
            return true
        } catch (e: Exception) {
            reportStatus("Failed: ${e.message}", -1)
            return false
        }
    }

    /**
     * Find the tor binary from native libraries.
     * org.briarproject:tor-android packages it as libtor.so
     */
    private fun findTorBinary(ctx: Context): File? {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        // Briar's tor-android ships as libtor.so
        val candidates = listOf("libtor.so", "libTor.so")
        for (name in candidates) {
            val f = File(nativeDir, name)
            if (f.exists()) return f
        }
        return null
    }

    /**
     * Find obfs4proxy binary from native libraries.
     * org.briarproject:obfs4proxy-android packages it as libobfs4proxy.so
     */
    private fun findObfs4Binary(ctx: Context): File? {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val candidates = listOf("libobfs4proxy.so", "libObfs4proxy.so", "liblyrebird.so")
        for (name in candidates) {
            val f = File(nativeDir, name)
            if (f.exists()) return f
        }
        return null
    }

    private fun buildTorrc(dataDir: File, hsDir: File, useBridges: Boolean, obfs4Binary: File?): String {
        val lines = mutableListOf(
            "SocksPort $socksPort",
            "ControlPort $controlPort",
            "DataDirectory ${dataDir.absolutePath}",
            "CookieAuthentication 1",
            "AvoidDiskWrites 1",
            "HiddenServiceDir ${hsDir.absolutePath}",
            "HiddenServicePort 80 127.0.0.1:$hiddenServicePort",
            "HiddenServiceVersion 3",
            "CircuitBuildTimeout 60",
            "LearnCircuitBuildTimeout 0",
            "NumEntryGuards 3",
            // Android specific: disable seccomp sandbox (not supported on all devices)
            "Sandbox 0"
        )

        if (useBridges && obfs4Binary != null) {
            lines.add("UseBridges 1")
            lines.add("ClientTransportPlugin obfs4 exec ${obfs4Binary.absolutePath}")
            for (bridge in defaultBridges) {
                lines.add("Bridge $bridge")
            }
        }

        return lines.joinToString("\n")
    }

    private fun waitForBootstrap(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", controlPort), 2000)
                sock.soTimeout = 5000

                val writer = sock.getOutputStream().bufferedWriter()
                val reader = sock.getInputStream().bufferedReader()

                writer.write("AUTHENTICATE \"\"\r\n")
                writer.flush()
                val authResp = reader.readLine() ?: ""
                if (!authResp.startsWith("250")) {
                    sock.close()
                    Thread.sleep(2000)
                    continue
                }

                writer.write("GETINFO status/bootstrap-phase\r\n")
                writer.flush()
                val statusResp = reader.readLine() ?: ""
                sock.close()

                if (statusResp.contains("PROGRESS=100")) {
                    return true
                }

                Regex("PROGRESS=(\\d+)").find(statusResp)?.let {
                    val pct = it.groupValues[1].toInt()
                    reportStatus("Bootstrapping... $pct%", 20 + pct * 7 / 10)
                }
            } catch (e: Exception) { /* not ready */ }

            if (torProcess?.isAlive != true) return false
            Thread.sleep(2000)
        }
        return false
    }

    private fun readOnionAddress(hsDir: File) {
        val hostnameFile = File(hsDir, "hostname")
        repeat(30) {
            if (hostnameFile.exists()) {
                var addr = hostnameFile.readText().trim()
                if (addr.endsWith(".onion")) addr = addr.removeSuffix(".onion")
                if (addr.isNotEmpty()) { onionAddress = addr; return }
            }
            Thread.sleep(1000)
        }
    }

    fun connectToOnion(onionAddress: String, port: Int = 80): Socket? {
        val fullAddress = if (onionAddress.endsWith(".onion")) onionAddress else "$onionAddress.onion"
        return try {
            val sock = Socket()
            connectViaSocks5(sock, fullAddress, port)
            sock
        } catch (e: Exception) {
            null
        }
    }

    private fun connectViaSocks5(sock: Socket, host: String, port: Int) {
        sock.connect(InetSocketAddress("127.0.0.1", socksPort), 90_000)
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()

        // SOCKS5 greeting
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greetResp = readExactly(inp, 2)
        if (greetResp[0] != 0x05.toByte() || greetResp[1] != 0x00.toByte())
            throw Exception("SOCKS5 auth failed")

        // Connect request (domain)
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(4 + 1 + hostBytes.size + 2)
        request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, request, 5, hostBytes.size)
        request[request.size - 2] = (port shr 8).toByte()
        request[request.size - 1] = (port and 0xFF).toByte()
        out.write(request)
        out.flush()

        val resp = readExactly(inp, 10)
        if (resp[1] != 0x00.toByte()) throw Exception("SOCKS5 connect failed: ${resp[1]}")
        sock.soTimeout = 90_000
    }

    private fun readExactly(inp: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = inp.read(buf, offset, n - offset)
            if (read == -1) throw Exception("Stream closed")
            offset += read
        }
        return buf
    }

    fun stop() {
        isRunning = false
        torProcess?.destroyForcibly()
        torProcess = null
    }

    private fun findFreePort(preferred: Int): Int {
        return try {
            java.net.ServerSocket(preferred).use { it.localPort }
        } catch (e: Exception) {
            java.net.ServerSocket(0).use { it.localPort }
        }
    }
}
