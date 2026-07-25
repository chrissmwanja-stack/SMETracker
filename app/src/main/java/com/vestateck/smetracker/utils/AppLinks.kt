package com.vestateck.smetracker.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * External links shown to users (LoginScreen's footer, AboutScreen). Kept in
 * one place so there's a single spot to update once the policy is actually
 * published, rather than a URL string duplicated across screens.
 */
object AppLinks {
    // TODO: replace with the live URL once the policy is published on
    // vestateck.com (see SMETracker-Privacy-Policy.md).
    const val PRIVACY_POLICY_URL = "https://vestateck.com/smetracker/privacy"
    // TODO: replace once published (see SMETracker-Account-Deletion.md).
    // This is also the URL to register in Play Console under
    // App content -> Account deletion - a separate, mandatory field from
    // the Data safety section's privacy policy URL above.
    const val ACCOUNT_DELETION_URL = "https://vestateck.com/smetracker/delete-account"
    const val SUPPORT_EMAIL = "support@vestateck.com"

    /** Opens a URL in the user's browser. No-ops quietly if nothing can handle it
     * (e.g. no browser installed) rather than crashing the app. */
    fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}