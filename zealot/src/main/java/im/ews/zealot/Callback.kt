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
        Log.d(TAG, "zealot /api/apps/latest HTTP 응답: ${response.code()} ${response.message()}")
        response.use {
            if (!response.isSuccessful) {
                Log.e(TAG, "/api/apps/latest 요청 실패: HTTP ${response.code()} ${response.message()}")
                return
            }

            generateChangelog(response)
        }
    }

    override fun onFailure(call: Call, e: IOException) {
        Log.e(TAG, "/api/apps/latest 네트워크 요청 실패", e)
    }

    private fun generateChangelog(response: Response) {
        try {
            val body = response.body()!!.string()
            logResponseBody(body)
            val reader = JSONObject(body)
            val releaseArray = reader.getJSONArray("releases")
            Log.d(TAG, "zealot /api/apps/latest releases 수: ${releaseArray.length()}")

            if (releaseArray.length() == 0) { return }
            var installUrl = ""
            var changelogList = ArrayList<String>()
            val responseVersions = ArrayList<String>()

            for (x in 0 until releaseArray.length()) {
                val releaseItem = releaseArray[x] as JSONObject
                val releaseVersion = releaseItem.getString("release_version")
                val buildVersion = releaseItem.getString("build_version")
                Log.d(
                    TAG,
                    "zealot release[$x]: release_version=$releaseVersion, build_version=$buildVersion"
                )
                responseVersions.add(
                    "${x + 1}. 앱 버전: ${releaseVersion}\n" +
                        "   빌드 버전: ${buildVersion}"
                )
                if (x == 0) {
                    installUrl = releaseItem.getString("install_url")
                }

                val changelogArray = releaseItem.getJSONArray("changelog")
                for (y in 0 until changelogArray.length()) {
                    val changelogItem = changelogArray[y] as JSONObject
                    val index = "${(y + 1).toString().padStart(2, '0')}"
                    val message = changelogItem.getString("message")
                    changelogList.add("${index}. ${message}")
                }
            }
            val changelog = changelogList.joinToString("\n")

            (zealot.context as? Activity)?.runOnUiThread {
                zealot.showResponseAlert(responseVersions.joinToString("\n\n"), changelog, installUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "/api/apps/latest 응답 처리 실패", e)
        }
    }

    private fun logResponseBody(body: String) {
        if (body.isEmpty()) {
            Log.d(TAG, "zealot /api/apps/latest 응답 본문: <empty>")
            return
        }

        val chunkSize = 3_000
        var start = 0
        var index = 1
        while (start < body.length) {
            val end = minOf(start + chunkSize, body.length)
            Log.d(TAG, "zealot /api/apps/latest 응답 본문 [$index]: ${body.substring(start, end)}")
            start = end
            index += 1
        }
    }
}
