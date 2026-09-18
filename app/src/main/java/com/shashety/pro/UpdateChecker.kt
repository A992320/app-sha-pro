package com.shashety.pro

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Checks official GitHub Releases. A newer signed APK blocks continued use until installed. */
object UpdateChecker {
    private const val REPOSITORY = "A992320/app-sha-pro"
    private val executor = Executors.newSingleThreadExecutor()

    fun check(activity: AppCompatActivity) {
        executor.execute {
            val release = runCatching { latestRelease() }.getOrNull() ?: return@execute
            if (!isNewer(release.version, BuildConfig.VERSION_NAME) || activity.isFinishing || activity.isDestroyed) return@execute
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) showRequiredUpdate(activity, release) }
        }
    }

    private data class Release(val version: String, val apkUrl: String)

    private fun latestRelease(): Release? {
        val connection = (URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SHASHETY-PRO-Android-TV")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val assets = json.optJSONArray("assets") ?: return null
            val url = (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?.optString("browser_download_url")?.takeIf { it.isNotBlank() } ?: return null
            Release(json.optString("tag_name", ""), url)
        } finally { connection.disconnect() }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
        val a = parts(remote); val b = parts(local)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }; val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun showRequiredUpdate(activity: AppCompatActivity, release: Release) {
        val dialog = Dialog(activity).apply { setCancelable(false); setCanceledOnTouchOutside(false) }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(activity, 52), dp(activity, 38), dp(activity, 52), dp(activity, 34))
            background = metalSurface(); minimumWidth = dp(activity, 720)
        }
        panel.addView(label(activity, "تحديث مطلوب", 32, Color.WHITE, Typeface.BOLD), LinearLayout.LayoutParams(-1, dp(activity, 58)))
        panel.addView(label(activity, "يتوفر إصدار أحدث من SHASHETY PRO (${release.version}). ثبّت التحديث للمتابعة.", 19, Color.rgb(235, 238, 242), Typeface.NORMAL), LinearLayout.LayoutParams(-1, dp(activity, 86)))
        val update = Button(activity).apply {
            text = "تنزيل وتثبيت التحديث"; textSize = 19f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isAllCaps = false; setTextColor(Color.WHITE)
            background = metalButton(); isFocusable = true
            setOnFocusChangeListener { v, focused -> v.scaleX = if (focused) 1.05f else 1f; v.scaleY = if (focused) 1.05f else 1f }
            setOnClickListener {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl))) }
                    .onFailure { AppLog.error("Could not open update APK", it) }
            }
        }
        panel.addView(update, LinearLayout.LayoutParams(dp(activity, 360), dp(activity, 64)).apply { topMargin = dp(activity, 18) })
        dialog.setContentView(panel); dialog.show(); update.requestFocus()
    }

    private fun label(activity: AppCompatActivity, value: String, size: Int, color: Int, style: Int) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); typeface = Typeface.create("sans-serif", style); gravity = Gravity.CENTER; includeFontPadding = false
    }
    private fun metalSurface() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(211, 216, 221), Color.rgb(76, 84, 95), Color.rgb(42, 47, 57))).apply { cornerRadius = 34f; setStroke(2, Color.WHITE) }
    private fun metalButton() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(107, 117, 130), Color.rgb(46, 53, 65))).apply { cornerRadius = 28f; setStroke(2, Color.rgb(228, 233, 240)) }
    private fun dp(activity: AppCompatActivity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
