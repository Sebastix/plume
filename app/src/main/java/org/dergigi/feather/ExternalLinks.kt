package org.dergigi.feather

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale

/** Explicit app routing also works when Android has not verified NewPipe's web links. */
internal fun Context.openWebLink(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) return
    fun launch(packageName: String? = null, nonBrowser: Boolean = false): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (packageName != null) setPackage(packageName)
            if (nonBrowser && Build.VERSION.SDK_INT >= 30) addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        })
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }

    val host = uri.host?.lowercase(Locale.ROOT)
    val youtube = host == "youtu.be" || host == "youtube.com" || host?.endsWith(".youtube.com") == true ||
        host == "youtube-nocookie.com" || host?.endsWith(".youtube-nocookie.com") == true
    if (host == "zapstore.dev" && uri.path?.startsWith("/apps/") == true) {
        if (launch("dev.zapstore.app")) return
    }
    if (youtube) {
        // Prefer the user's requested lightweight client, then the official app.
        if (launch("org.schabi.newpipe") || launch("com.google.android.youtube")) return
        if (Build.VERSION.SDK_INT >= 30 && launch(nonBrowser = true)) return
    }
    if (!launch()) AppMessages.show("No app can open this link.")
}
