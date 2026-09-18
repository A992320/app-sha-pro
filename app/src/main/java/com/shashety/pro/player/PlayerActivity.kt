package com.shashety.pro.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shashety.pro.AppLog
import org.json.JSONArray
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var source: String
    private var artwork: String? = null
    private lateinit var errorPanel: Button
    private var mediaSession: MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 5_000
            controllerHideOnTouch = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(playerView, FrameLayout.LayoutParams(-1, -1)) }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        artwork = intent.getStringExtra(EXTRA_ARTWORK)
        root.addView(TextView(this).apply { text = title; textSize = 20f; setTextColor(Color.WHITE); setPadding(28, 18, 28, 18); setBackgroundColor(Color.argb(150, 0, 0, 0)) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { topMargin = 28; rightMargin = 28 })
        errorPanel = Button(this).apply { text = "تعذر تشغيل البث — إعادة المحاولة"; textSize = 18f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(165, 27, 39)); visibility = View.GONE; setOnClickListener { visibility = View.GONE; player.prepare(); player.play() } }
        root.addView(errorPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); setContentView(root)
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                AppLog.error("Playback failed: ${error.errorCodeName}", error)
                errorPanel.visibility = View.VISIBLE
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
        source = requireNotNull(intent.getStringExtra(EXTRA_URL))
        AppLog.info("Starting player for $source")
        val item = MediaItem.Builder().setUri(Uri.parse(source)).setMediaId(source).apply {
            if (source.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        val savedPosition = getSharedPreferences("playback", Context.MODE_PRIVATE).getLong(source, 0)
        if (savedPosition > 10_000) player.seekTo(savedPosition)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> {
            AppLog.info("Remote back: returning to library")
            finish(); true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            AppLog.info("Remote play/pause")
            if (player.isPlaying) player.pause() else player.play()
            playerView.showController(); true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
            AppLog.info("Remote seek back")
            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); playerView.showController(); true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            AppLog.info("Remote seek forward")
            player.seekTo((player.currentPosition + 30_000).coerceAtMost(player.duration.coerceAtLeast(0))); playerView.showController(); true
        }
        KeyEvent.KEYCODE_MEDIA_STOP -> { player.stop(); finish(); true }
        KeyEvent.KEYCODE_Z -> { cycleResizeMode(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AppLog.info("Back pressed: returning to library")
        finish()
    }

    private fun cycleResizeMode() {
        playerView.resizeMode = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.showController()
    }

    override fun onStop() {
        val prefs = getSharedPreferences("playback", Context.MODE_PRIVATE)
        prefs.edit().putLong(source, player.currentPosition).apply()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val list = runCatching { JSONArray(prefs.getString("resume_list", "[]") ?: "[]") }.getOrElse { JSONArray() }
        val updated = JSONArray(); updated.put(JSONObject().put("url", source).put("title", title).put("artwork", artwork ?: ""))
        // Keep the resume row short and practical for a TV remote.
        for (i in 0 until list.length()) { val entry = list.getJSONObject(i); if (entry.optString("url") != source && updated.length() < 7) updated.put(entry) }
        prefs.edit().putString("resume_list", updated.toString()).apply()
        super.onStop(); player.pause()
    }
    override fun onDestroy() { mediaSession?.release(); player.release(); super.onDestroy() }

    companion object {
        private const val EXTRA_URL = "video_url"
        private const val EXTRA_TITLE = "video_title"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val EXTRA_ARTWORK = "video_artwork"
        fun intent(context: Context, url: String, title: String?, mimeType: String?, artwork: String? = null) = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url); putExtra(EXTRA_TITLE, title); putExtra(EXTRA_MIME_TYPE, mimeType); putExtra(EXTRA_ARTWORK, artwork)
        }
    }
}
