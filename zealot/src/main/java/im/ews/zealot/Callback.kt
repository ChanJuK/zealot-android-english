package im.ews.zealot

import android.app.Activity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

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
            val body = response.body()?.string() ?: return
            val reader = JSONObject(body)
            val releaseArray = reader.getJSONArray("releases")

            if (releaseArray.length() == 0) { return }
            var version = ""
            var installUrl = ""
            var changelogList = ArrayList<String>()

            for (x in 0 until releaseArray.length()) {
                val releaseItem = releaseArray.optJSONObject(x) ?: continue
                if (x == 0) {
                    val releaseVersion = releaseItem.optString("release_version")
                    val buildVersion = releaseItem.optString("build_version")
                    installUrl = releaseItem.optString("install_url")
                    if (releaseVersion.isBlank() || buildVersion.isBlank() || installUrl.isBlank()) {
                        return
                    }
                    version = "${releaseVersion} (${buildVersion})"
                }

                val changelogArray = releaseItem.optJSONArray("changelog") ?: continue
                for (y in 0 until changelogArray.length()) {
                    val changelogItem = changelogArray.optJSONObject(y) ?: continue
                    val index = "${(y + 1).toString().padStart(2, '0')}"
                    val message = changelogItem.optString("message")
                    if (message.isBlank()) { continue }
                    changelogList.add("${index}. ${message}")
                }
            }
            val changelog = changelogList.joinToString("\n")

            if (version.isBlank() || installUrl.isBlank()) { return }

            val activity = zealot.context as? Activity ?: return
            activity.runOnUiThread {
                if (activity.isFinishing ||
                    (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 &&
                        activity.isDestroyed)) {
                    return@runOnUiThread
                }

                try {
                    zealot.showAlert(version, changelog, installUrl)
                } catch (_: Exception) {
                    // Do not let a dialog/window error close the host app.
                }
            }
        } catch (_: Exception) {
            // Ignore malformed or unexpected server responses.
        }
    }
}
