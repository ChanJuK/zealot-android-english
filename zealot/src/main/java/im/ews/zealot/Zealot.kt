package im.ews.zealot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.concurrent.TimeUnit

class Zealot private constructor(val context: Context) {
    companion object {
        const val VERSION = "0.2.0"
        const val BUILD_TYPE = "default"

        internal const val MAX_RESPONSE_BYTES = 256 * 1024
        internal const val MAX_RELEASES = 32
        internal const val MAX_CHANGELOG_ENTRY_LENGTH = 2_000
        internal const val MAX_CHANGELOG_LENGTH = 16_000
        private const val MAX_PAYLOAD_AGE_SECONDS = 31L * 24 * 60 * 60

        @JvmStatic fun create(context: Context): Zealot {
            return Zealot(context)
        }
    }

    enum class ScreenHeight {
        AUTOMATIC, HALFSCREEN
    }

    private var endpoint: String? = null

    private var channelKeys = hashMapOf<String, String>()
    private var buildType: String = BUILD_TYPE
    private var maxHeight = ScreenHeight.AUTOMATIC
    private val allowedUpdateHosts = hashSetOf<String>()
    private var updateVerificationKey: PublicKey? = null

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val packageName: String
        get() = context.packageName

    private val packageInfo: PackageInfo
        get() = context.packageManager.getPackageInfo(packageName, 0)

    private val releaseVersion: String
        get() = packageInfo.versionName

    private val buildVersion: Long
        get() {
            val versionCode: Long
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = packageInfo.longVersionCode
            } else {
                versionCode = packageInfo.versionCode.toLong()
            }
            return versionCode
        }

    private val sdkInfo: String
        get() {
            return "android-$VERSION"
        }

    fun setEndpoint(endpoint: String): Zealot {
        this.endpoint = endpoint
        return this
    }

    /**
     * Restricts both the update API and APK download URLs to these exact HTTPS hosts.
     * Do not populate this list from remote configuration.
     */
    fun setAllowedUpdateHosts(vararg hosts: String): Zealot {
        allowedUpdateHosts.clear()
        hosts.forEach { host ->
            val normalizedHost = host.trim().lowercase(Locale.US)
            require(normalizedHost.isNotBlank() &&
                !normalizedHost.contains('/') &&
                !normalizedHost.contains(':')) { "Invalid update host" }
            allowedUpdateHosts.add(normalizedHost)
        }
        return this
    }

    /**
     * Configures the Base64-encoded X.509 RSA public key used to verify update metadata.
     * The matching private key must remain only on the update service.
     */
    fun setUpdateVerificationKey(x509RsaPublicKeyBase64: String): Zealot {
        val normalized = x509RsaPublicKeyBase64
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        require(normalized.isNotBlank() && normalized.length <= 8_192) { "Invalid update verification key" }

        val keyBytes = try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid update verification key")
        }
        val key = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid update verification key")
        }
        require(key is RSAPublicKey && key.modulus.bitLength() >= 2_048) {
            "Update verification key must be an RSA key of at least 2048 bits"
        }
        updateVerificationKey = key
        return this
    }

    fun setChannelKey(channelKey: String): Zealot {
        return setChannelKey(channelKey, BUILD_TYPE)
    }

    fun setChannelKey(channelKey: String, buildType: String): Zealot {
        this.channelKeys[buildType] = channelKey
        return this
    }

    fun setBuildType(buildType: String): Zealot {
        this.buildType = buildType
        return this
    }

    fun setAlertMaxHeight(height: ScreenHeight): Zealot {
        this.maxHeight = height
        return this
    }

    fun launch() {
        // Fail closed: update metadata must come from a configured HTTPS host and be signed.
        val normalizedEndpoint = endpoint?.trim()?.trimEnd('/') ?: return
        val endpointUrl = normalizedEndpoint.toHttpUrlOrNull() ?: return
        if (!isAllowedHttpsUrl(endpointUrl) || updateVerificationKey == null) { return }
        val baseUrl = "$normalizedEndpoint/api/apps/latest".toHttpUrlOrNull() ?: return
        if (!isAllowedHttpsUrl(baseUrl)) { return }

        try {
            val urlBuilder = baseUrl.newBuilder()
            urlBuilder.addQueryParameter("release_version", releaseVersion)
            urlBuilder.addQueryParameter("build_version", buildVersion.toString())
            urlBuilder.addQueryParameter("bundle_id", packageName)
            urlBuilder.addQueryParameter("sdk", sdkInfo)

            val request = Request.Builder()
                .url(urlBuilder.build())
                // A channel key is an identifier, not a secret. Keeping it out of the URL
                // reduces accidental disclosure through URL logs and referrers.
                .header("X-Zealot-Channel-Key", channelKey())
                .header("Accept", "application/json")
                .build()

            client.newCall(request).enqueue(Callback(this))
        } catch (_: Exception) {
            // Keep the update check best-effort when its setup cannot complete.
        }
    }

    internal fun showAlert(version: String, changelog: String, installUri: Uri)  {
        val dialog  = AlertDialog.Builder(context)
            .setTitle("${version}️버전으로 업데이트 되었습니다")
            .setMessage(changelog)
            .setNegativeButton("취소", null)
            .setPositiveButton("업데이트") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, installUri)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    context.startActivity(intent)
                } catch (_: Exception) {
                    // A bad install URL must not close the host app.
                }
            }
            .create()

        dialog.show()

        if (maxHeight == ScreenHeight.HALFSCREEN) {
            dialog.setMaxHeight((context.resources.displayMetrics.heightPixels / 2.0f).toInt())
        }
    }

    private fun channelKey(): String {
        if (channelKeys.size == 0 ) {
            throw IllegalArgumentException("channelKey is empty")
        }

        var channelKey: String? = null
        if (channelKeys.containsKey(buildType)) {
            channelKey = channelKeys[buildType]
        } else if (channelKeys.containsKey(BUILD_TYPE)) {
            channelKey = channelKeys[BUILD_TYPE]
        }

        if (channelKey == null) {
            throw IllegalArgumentException("channelKey is empty")
        }

        return channelKey
    }

    internal fun validateInstallUri(installUrl: String): Uri? {
        if (installUrl.length > 2_048) { return null }
        val url = installUrl.toHttpUrlOrNull() ?: return null
        if (!isAllowedHttpsUrl(url)) { return null }
        return Uri.parse(url.toString())
    }

    internal fun readResponseBody(response: Response): String? {
        val body = response.body ?: return null
        if (body.contentLength() > MAX_RESPONSE_BYTES) { return null }

        return try {
            body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var totalBytes = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) { break }
                    totalBytes += read
                    if (totalBytes > MAX_RESPONSE_BYTES) { return null }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The API response is a JSON envelope with Base64URL fields `signed_payload` and
     * `signature`. The signature is SHA256withRSA over the decoded UTF-8 payload bytes.
     */
    internal fun verifiedPayload(signedPayload: String, signature: String): JSONObject? {
        val key = updateVerificationKey ?: return null
        if (signedPayload.length !in 1..MAX_RESPONSE_BYTES || signature.length !in 1..1_024) {
            return null
        }
        if (!signedPayload.matches(Regex("[A-Za-z0-9_-]+")) ||
            !signature.matches(Regex("[A-Za-z0-9_-]+"))) {
            return null
        }

        return try {
            val payloadBytes = Base64.decode(signedPayload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val signatureBytes = Base64.decode(signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if (payloadBytes.size > MAX_RESPONSE_BYTES || signatureBytes.isEmpty()) { return null }

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(payloadBytes)
            if (!verifier.verify(signatureBytes)) { return null }

            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    internal fun isExpectedPayload(payload: JSONObject): Boolean {
        val expiry = payload.optLong("expires_at", Long.MIN_VALUE)
        val now = System.currentTimeMillis() / 1_000
        return payload.optString("bundle_id") == packageName &&
            payload.optString("build_type") == buildType &&
            payload.optString("channel_key") == channelKey() &&
            expiry > now && expiry <= now + MAX_PAYLOAD_AGE_SECONDS
    }

    internal fun currentBuildVersion(): Long = buildVersion

    private fun isAllowedHttpsUrl(url: HttpUrl): Boolean {
        return url.scheme == "https" && allowedUpdateHosts.contains(url.host.lowercase(Locale.US))
    }

    private fun AlertDialog.setMaxHeight(height: Int) {
        val dialogHeight = window?.attributes?.height
        if (dialogHeight != null && dialogHeight <= height) { return }

        val layoutParams = WindowManager.LayoutParams()
        layoutParams.copyFrom(window?.attributes)
        layoutParams.height = height
        window?.attributes = layoutParams
    }
}


