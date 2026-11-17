package im.ews.zealot.example

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Configure Zealot only with a production HTTPS host, a server-controlled
        // channel identifier, and a bundled RSA public key. See README.md for the
        // signed response format; no production credentials belong in this sample.
    }
}
