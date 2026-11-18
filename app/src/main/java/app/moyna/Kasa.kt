package app.moyna

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Talks to TP-Link Kasa smart plugs directly over the home Wi-Fi (no cloud, no Alexa).
 *
 * Newer Kasa firmware uses the "KLAP" protocol: the app proves it knows the Kasa account login
 * (as a hash, BuildConfig.KASA_AUTH_HASH, made at build time from private/kasa-login.properties),
 * then sends AES-encrypted JSON commands. This follows python-kasa's KLAP v2 transport exactly.
 */
object Kasa {
    private const val TAG = "Moyna"
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private val pool = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()
    private val octet = "application/octet-stream".toMediaType()

    private class Session(val cookie: String, val key: ByteArray, val iv12: ByteArray, var seq: Int,
                          val sig: ByteArray, val madeAt: Long)
    private val sessions = HashMap<String, Session>()

    private val auth: ByteArray by lazy {
        BuildConfig.KASA_AUTH_HASH.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun configured() = BuildConfig.KASA_AUTH_HASH.length == 64

    /** Send a JSON command to the plug at [ip]; [done] gets the reply, or null if it failed (on a background thread). */
    fun send(ip: String, json: String, done: (JSONObject?) -> Unit) = pool.execute {
        var reply: JSONObject? = null
        for (attempt in 1..2) {          // a stale session is re-made once
            try { reply = request(ip, json); break }
            catch (e: Exception) {
                sessions.remove(ip)
                Log.w(TAG, "plug $ip: ${e.javaClass.simpleName}: ${e.message} (attempt $attempt)")
            }
        }
        done(reply)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    private fun int4(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    private fun post(url: String, body: ByteArray, cookie: String?): Pair<Int, Pair<ByteArray, String?>> {
        val req = Request.Builder().url(url).post(body.toRequestBody(octet))
            .apply { if (cookie != null) header("Cookie", cookie) }.build()
        http.newCall(req).execute().use { r ->
            return r.code to ((r.body?.bytes() ?: ByteArray(0)) to r.headers("Set-Cookie").joinToString(";"))
        }
    }

    private fun session(ip: String): Session {
        sessions[ip]?.let { if (System.currentTimeMillis() - it.madeAt < 12 * 3_600_000L) return it }
        val local = ByteArray(16).also { random.nextBytes(it) }
        val (c1, r1) = post("http://$ip/app/handshake1", local, null)
        val body = r1.first
        if (c1 != 200 || body.size != 48) throw IllegalStateException("handshake1 answered $c1 with ${body.size} bytes")
        val remote = body.copyOfRange(0, 16)
        if (!sha256(local, remote, auth).contentEquals(body.copyOfRange(16, 48)))
            throw IllegalStateException("the Kasa login does not match this plug (check private/kasa-login.properties)")
        val cookie = Regex("TP_SESSIONID=([^;]+)").find(r1.second ?: "")?.groupValues?.get(1)
            ?.let { "TP_SESSIONID=$it" } ?: throw IllegalStateException("no session cookie")
        val (c2, _) = post("http://$ip/app/handshake2", sha256(remote, local, auth), cookie)
        if (c2 != 200) throw IllegalStateException("handshake2 answered $c2")
        val fullIv = sha256("iv".toByteArray(), local, remote, auth)
        return Session(
            cookie = cookie,
            key = sha256("lsk".toByteArray(), local, remote, auth).copyOfRange(0, 16),
            iv12 = fullIv.copyOfRange(0, 12),
            seq = ByteBuffer.wrap(fullIv, 28, 4).int,
            sig = sha256("ldk".toByteArray(), local, remote, auth).copyOfRange(0, 28),
            madeAt = System.currentTimeMillis(),
        ).also { sessions[ip] = it }
    }

    private fun request(ip: String, json: String): JSONObject {
        val s = session(ip)
        s.seq += 1
        val iv = IvParameterSpec(s.iv12 + int4(s.seq))
        val key = SecretKeySpec(s.key, "AES")
        val enc = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, key, iv) }
            .doFinal(json.toByteArray(Charsets.UTF_8))
        val signature = sha256(s.sig, int4(s.seq), enc)
        val (code, r) = post("http://$ip/app/request?seq=${s.seq}", signature + enc, s.cookie)
        if (code != 200) throw IllegalStateException("request answered $code")
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, key, iv) }
            .doFinal(r.first.copyOfRange(32, r.first.size))
        return JSONObject(String(plain, Charsets.UTF_8))
    }
}
