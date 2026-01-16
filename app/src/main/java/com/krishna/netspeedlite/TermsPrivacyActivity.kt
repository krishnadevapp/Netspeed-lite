package com.krishna.netspeedlite

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TermsPrivacyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val TYPE_TERMS = "terms"
        const val TYPE_PRIVACY = "privacy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms_privacy)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        val btnBack = findViewById<android.widget.ImageButton>(R.id.btnBack)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvLastUpdated = findViewById<TextView>(R.id.tvLastUpdated)
        val textContent = findViewById<TextView>(R.id.textContent)

        // Set up toolbar (no action bar navigation needed)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        
        // Custom back button handler
        btnBack.setOnClickListener { finish() }

        tvTitle.text = getString(R.string.privacy_policy)
        textContent.text = Html.fromHtml(getPrivacyBody(), Html.FROM_HTML_MODE_COMPACT)
        
        // Dynamic last updated date
        tvLastUpdated.text = "Last Updated: January 15, 2026"
        
        textContent.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun getPrivacyBody(): String {
        return """
            <p>Welcome to <strong>Netspeed Lite</strong>. We respect your privacy and represent that we do not collect any personal data through this application.</p>
            <br>

            <h4 style="color:#2196F3">1. Data Collection & Usage</h4>
            <ul>
                <li><strong>No Personal Data:</strong> We do not collect, store, or share any personally identifiable information (PII), browsing history, or IP addresses.</li>
                <li><strong>Network Usage Stats:</strong> We use the <em>Usage Access</em> permission solely to read your device's historical network traffic logs. This processing happens 100% locally on your device.</li>
                <li><strong>Local Storage:</strong> Your preferences and manually tracked data counters are stored locally using Android SharedPreferences and are deleted if you uninstall the app.</li>
            </ul>
            <br>

            <h4 style="color:#2196F3">2. Permissions</h4>
            <ul>
                <li><strong>FOREGROUND_SERVICE:</strong> Required to keep the speed meter active in your status bar.</li>
                <li><strong>PACKAGE_USAGE_STATS:</strong> Required to display historical data usage (Today, Last 7 Days, etc.).</li>
                <li><strong>POST_NOTIFICATIONS:</strong> Required to show the live speed indicator.</li>
            </ul>
            <br>

            <h4 style="color:#2196F3">3. Disclaimer</h4>
            <p><strong>Netspeed Lite</strong> is provided "AS IS". the data usage statistics provided by this app are estimates based on your device's internal logs and interpolation algorithms. These figures may differ from your mobile carrier's official billing records. We do not guarantee 100% accuracy and are not liable for any data overage charges incurred.</p>
            <br>

            <h4 style="color:#2196F3">4. Contact Us</h4>
            <p>If you have questions about this policy, please contact us via the developer email on the Google Play Store.</p>
        """.trimIndent()
    }
}
