package im.ews.zealot

import android.app.Activity
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

private const val TAG = "Zealot"

class Callback(val zealot: Zealot): Callback {

    override fun onResponse(call: Call, response: Response) {
        response.use {
            if (!response.isSuccessful) { return }

            generateChangelog(response)
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        // ignore
    }

    private fun generateChangelog(response: Response) {
        try {
            val body = zealot.readResponseBody(response) ?: return
            val envelope = JSONObject(body)
            val reader = zealot.verifiedPayload(
                envelope.optString("signed_payload"),
                envelope.optString("signature")
            ) ?: return
            if (!zealot.isExpectedPayload(reader)) { return }
            val releaseArray = reader.getJSONArray("releases")

            if (releaseArray.length() !in 1..Zealot.MAX_RELEASES) { return }
            var version = ""
            var installUri: android.net.Uri? = null
            var updateReleaseVersion: String? = null
            var updateBuildVersion: Long? = null
            val changelogList = ArrayList<String>()
            var changelogLength = 0

            for (x in 0 until releaseArray.length()) {
                val releaseItem = releaseArray.optJSONObject(x) ?: continue
                val releaseVersion = releaseItem.optString("release_version")
                val buildVersion = releaseItem.optString("build_version")
                Log.d(
                    TAG,
                    "Channel release[$x]: release_version=$releaseVersion, build_version=$buildVersion"
                )

                if (x == 0) {
                    val remoteBuildVersion = buildVersion.toLongOrNull()
                    val validatedInstallUri = zealot.validateInstallUri(releaseItem.optString("install_url"))
                    if (releaseVersion.isBlank() || releaseVersion.length > 128 ||
                        remoteBuildVersion == null || remoteBuildVersion <= zealot.currentBuildVersion() ||
                        validatedInstallUri == null) {
                        Log.d(
                            TAG,
                            "No update: installed_build_version=${zealot.currentBuildVersion()}, " +
                                "received_build_version=$buildVersion"
                        )
                        return
                    }
                    installUri = validatedInstallUri
                    version = "${releaseVersion} (${remoteBuildVersion})"
                    updateReleaseVersion = releaseVersion
                    updateBuildVersion = remoteBuildVersion
                }

                val changelogArray = releaseItem.optJSONArray("changelog") ?: continue
                for (y in 0 until changelogArray.length()) {
                    val changelogItem = changelogArray.optJSONObject(y) ?: continue
                    val index = "${(y + 1).toString().padStart(2, '0')}"
                    val message = changelogItem.optString("message")
                    if (message.isBlank()) { continue }
                    if (message.length > Zealot.MAX_CHANGELOG_ENTRY_LENGTH ||
                        changelogLength + message.length > Zealot.MAX_CHANGELOG_LENGTH) {
                        return
                    }
                    changelogList.add("${index}. ${message}")
                    changelogLength += message.length
                }
            }
            val changelog = changelogList.joinToString("\n")

            val safeInstallUri = installUri ?: return
            if (version.isBlank()) { return }

            val activity = zealot.context as? Activity ?: return
            activity.runOnUiThread {
                if (activity.isFinishing ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                        activity.isDestroyed)) {
                    return@runOnUiThread
                }

                try {
                    val releaseVersion = updateReleaseVersion ?: return@runOnUiThread
                    val buildVersion = updateBuildVersion ?: return@runOnUiThread
                    if (zealot.notifyUpdateAvailable(releaseVersion, buildVersion)) {
                        return@runOnUiThread
                    }
                    zealot.showAlert(version, changelog, safeInstallUri)
                } catch (_: Exception) {
                    // Do not let a dialog/window error close the host app.
                }
            }
        } catch (_: Exception) {
            // Ignore malformed or unexpected server responses.
        }
    }
}
