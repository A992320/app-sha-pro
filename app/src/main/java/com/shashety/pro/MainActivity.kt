package com.shashety.pro

import android.content.Context
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.shashety.pro.player.PlayerActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Native Android TV client for the Shashety Pro media API. */
class MainActivity : AppCompatActivity() {
    private enum class Screen { HOME, SETUP, EPISODES, SEARCH, CATEGORY }
    private data class Media(val title: String, val streamUrl: String? = null, val seriesId: Int? = null, val artworkUrl: String? = null)
    private val prefs by lazy { getSharedPreferences("connection", Context.MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newFixedThreadPool(4)
    private lateinit var root: FrameLayout
    private var screen = Screen.HOME
    private var featuredItems = emptyList<Media>()
    private var featuredIndex = 0
    private var featuredHost: FrameLayout? = null
    private var cachedLibrary: List<Pair<String, List<Media>>>? = null
    private var homeScroll: ScrollView? = null
    private var homeScrollY = 0
    private var lastHomeFocusTitle: String? = null
    private val sectionSources = mutableMapOf<String, Pair<Int, String>>()
    private val launchHandler = Handler(Looper.getMainLooper())
    private val isGridMode: Boolean get() = prefs.getString("library_view", "grid") != "list"
    private val cardsPerRow: Int get() = prefs.getInt("cards_per_row", 3).coerceIn(3, 7)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        root = FrameLayout(this).apply { background = cinemaBackground() }
        setContentView(root)
        AppLog.info("Application started; hasConnection=${baseUrl() != null}")
        showLaunchLogo()
    }

    /** A short branded launch screen, then the saved library or connection setup. */
    private fun showLaunchLogo() {
        root.removeAllViews()
        val splash = FrameLayout(this).apply { background = cinemaBackground() }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.shashety_tv_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "SHASHETY TV"
            setPadding(dp(180), dp(80), dp(180), dp(80))
        }
        splash.addView(logo, FrameLayout.LayoutParams(-1, -1))
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))
        launchHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                if (baseUrl() == null) setup() else home()
                UpdateChecker.check(this)
            }
        }, 1800)
    }

    private fun setup() {
        screen = Screen.SETUP
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(140), 0, dp(140), 0) }
        page.addView(text("⚙ إعدادات SHASHETY PRO", 40)); page.addView(text("الخادم والمظهر محفوظان على هذا التلفاز", 22))
        val host = field("المضيف أو IP", prefs.getString("host", "") ?: "")
        val port = field("المنفذ", prefs.getInt("port", 80).toString())
        val notice = text("", 16).apply { setTextColor(Color.RED); visibility = View.GONE }
        page.addView(host, lp(66, 14)); page.addView(port, lp(66, 18))
        page.addView(metallicTextButton("حفظ وفتح المكتبة") {
            val p = port.text.toString().toIntOrNull()
            val h = host.text.toString().trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (p == null || p !in 1..65535 || h.isBlank() || h.contains('/') || h.contains(':')) {
                AppLog.info("Rejected invalid connection settings"); notice.text = "تحقق من عنوان الخادم والمنفذ"; notice.visibility = View.VISIBLE
            } else {
                // The API has one fixed internal library path; users only configure host and port.
                AppLog.info("Saved connection host=$h port=$p")
                cachedLibrary = null; homeScrollY = 0; lastHomeFocusTitle = null
                prefs.edit().putString("host", h).putInt("port", p).putString("path", "iptv").apply(); home()
            }
        }, lp(68, 12)); page.addView(notice); root.addView(page, FrameLayout.LayoutParams(-1, -1)); host.requestFocus()
    }

    private fun home() {
        rememberHomePosition()
        screen = Screen.HOME
        root.removeAllViews()
        val compact = isCompactTv
        val sidePadding = if (compact) 24 else 56
        val toolbarHeight = if (compact) 58 else 68
        val toolbarIconSize = if (compact) 48 else 58
        val navigationGap = if (compact) 8 else 14
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(sidePadding), dp(if (compact) 18 else 28), dp(sidePadding), dp(if (compact) 18 else 28)) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = toolbarBackground()
            elevation = dp(8).toFloat()
            clipChildren = false; clipToPadding = false
            setPadding(dp(if (compact) 6 else 10), dp(if (compact) 5 else 7), dp(if (compact) 6 else 10), dp(if (compact) 5 else 7))
        }
        // Toolbar controls are deliberately grouped with generous gaps: this prevents
        // the remote controls, title, and settings button from reading as one crowded strip.
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        navigation.addView(iconButton(R.drawable.ic_home_filled, "الرئيسية") { home() }, LinearLayout.LayoutParams(dp(toolbarIconSize), dp(toolbarIconSize)).apply { marginEnd = dp(navigationGap) })
        navigation.addView(iconButton(R.drawable.ic_search_metal, "البحث") { searchScreen() }, LinearLayout.LayoutParams(dp(toolbarIconSize), dp(toolbarIconSize)).apply { marginEnd = dp(navigationGap) })
        navigation.addView(iconButton(R.drawable.ic_grid_metal, "تغيير العرض") { prefs.edit().putString("library_view", if (isGridMode) "list" else "grid").apply(); home() }, LinearLayout.LayoutParams(dp(toolbarIconSize), dp(toolbarIconSize)))
        header.addView(navigation, LinearLayout.LayoutParams(-2, dp(toolbarIconSize)).apply { marginEnd = dp(if (compact) 16 else 34) })
        if (isGridMode) {
            val density = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                background = toolbarInnerBackground(); setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val controlSize = if (compact) 44 else 52
            val labelWidth = if (compact) 84 else 96
            val controlHeight = if (compact) 40 else 46
            density.addView(controlButton("−", "تقليل البطاقات", Color.rgb(101, 45, 54)) { changeCards(-1) }, LinearLayout.LayoutParams(dp(controlSize), dp(controlHeight)).apply { marginEnd = dp(8) })
            density.addView(text("$cardsPerRow بطاقات", if (compact) 15 else 17).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(dp(labelWidth), dp(controlHeight)).apply { marginEnd = dp(8) })
            density.addView(controlButton("+", "زيادة البطاقات", Color.rgb(190, 37, 54)) { changeCards(1) }, LinearLayout.LayoutParams(dp(controlSize), dp(controlHeight)))
            // 224dp is the exact content width including internal gaps; reserve extra
            // space so the plus-button focus ring and its right edge never clip.
            header.addView(density, LinearLayout.LayoutParams(dp(if (compact) 196 else 236), dp(toolbarIconSize)).apply { marginEnd = dp(if (compact) 16 else 30) })
        }
        // Keep the settings button inside the safe area on narrow TV launchers.
        // The title takes only the remaining header space instead of forcing it off-screen.
        header.addView(text("SHASHETY PRO", if (compact) 24 else 30).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(toolbarIconSize), 1f).apply { marginEnd = dp(if (compact) 12 else 22) })
        header.addView(iconButton(R.drawable.ic_settings_metal, "الإعدادات") { setup() }, LinearLayout.LayoutParams(dp(toolbarIconSize), dp(toolbarIconSize)))
        val status = text("جارٍ تحميل المكتبة…", 18).apply { setTextColor(Color.rgb(175, 195, 235)); setPadding(0, dp(18), 0, dp(18)) }
        val scroll = ScrollView(this); val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(rows); homeScroll = scroll
        page.addView(header, LinearLayout.LayoutParams(-1, dp(toolbarHeight)).apply { bottomMargin = dp(2) }); page.addView(status); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(page, FrameLayout.LayoutParams(-1, -1))
        cachedLibrary?.let { renderLibrary(it, status, rows, scroll) } ?: load(status, rows, scroll)
    }

    private fun rememberHomePosition() {
        homeScroll?.let { if (it.isAttachedToWindow) homeScrollY = it.scrollY }
    }

    private fun load(status: TextView, rows: LinearLayout, scroll: ScrollView) = executor.execute {
        val server = requireNotNull(baseUrl()); AppLog.info("Loading library from $server")
        val outcome = runCatching { fetchRows(server) }
        runOnUiThread { outcome.onSuccess { data ->
            cachedLibrary = data; renderLibrary(data, status, rows, scroll)
        }.onFailure { error ->
            AppLog.error("Library request failed", error); status.text = "تعذر تحميل المكتبة من الخادم"; rows.removeAllViews()
            rows.addView(text("تحقق من إعدادات الخادم، ثم أعد المحاولة.", 20)); rows.addView(metallicTextButton("إعادة المحاولة") { home() }, LinearLayout.LayoutParams(dp(260), dp(64)).apply { topMargin = dp(28) })
        } }
    }

    private fun renderLibrary(data: List<Pair<String, List<Media>>>, status: TextView, rows: LinearLayout, scroll: ScrollView) {
        val server = requireNotNull(baseUrl())
        status.text = "موصى به لك"; rows.removeAllViews()
        val host = FrameLayout(this); rows.addView(host, LinearLayout.LayoutParams(-1, dp(145)).apply { bottomMargin = dp(18) })
        // Do not let the hero claim focus while restoring a position lower in the library.
        startFeaturedRotation(host, data.flatMap { it.second }, server, requestFocus = homeScrollY == 0)
        val resume = resumeItems()
        if (resume.isNotEmpty()) {
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(12), dp(24), dp(12), dp(12)) }
            heading.addView(metallicTextButton("مسح القائمة") {
                getSharedPreferences("playback", Context.MODE_PRIVATE).edit().remove("resume_list").apply()
                home()
            }, LinearLayout.LayoutParams(dp(180), dp(52)))
            heading.addView(text("استئناف المشاهدة", 25).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
            rows.addView(heading)
            addMediaGrid(rows, resume, 5) { openMedia(server, it) }
        }
        data.forEach { row ->
            val source = sectionSources[row.first]
            rows.addView(sectionHeader(row.first) { source?.let { showCategory(server, row.first, it.first, it.second) } })
            if (isGridMode) addMediaGrid(rows, row.second, cardsPerRow) { openMedia(server, it) } else addMediaList(rows, row.second) { openMedia(server, it) }
        }
        if (data.isEmpty()) status.text = "لا توجد مواد ظاهرة في المكتبة"
        if (homeScrollY > 0) scroll.postDelayed({ scroll.scrollTo(0, homeScrollY) }, 80)
    }

    private fun changeCards(delta: Int) {
        prefs.edit().putInt("cards_per_row", (cardsPerRow + delta).coerceIn(3, 7)).apply(); home()
    }

    private fun startFeaturedRotation(host: FrameLayout, items: List<Media>, server: String, requestFocus: Boolean = true) {
        featuredHost = host; featuredItems = items.shuffled(); featuredIndex = 0
        renderFeatured(host, server, requestFocus)
    }

    private fun renderFeatured(host: FrameLayout, server: String, requestFocus: Boolean = false) {
        // Delayed rotations can arrive just after this Activity has been closed.
        // Never rebuild UI or submit image work for an obsolete screen.
        if (isFinishing || isDestroyed || !host.isAttachedToWindow || featuredHost !== host || featuredItems.isEmpty() || screen != Screen.HOME) return
        val item = featuredItems[featuredIndex % featuredItems.size]
        val card = heroCard(item) { openMedia(server, item) }
        host.removeAllViews(); host.addView(card, FrameLayout.LayoutParams(-1, -1))
        if (requestFocus) card.post { card.requestFocus() }
        scheduleFeaturedRotation(host, server)
    }

    private fun scheduleFeaturedRotation(host: FrameLayout, server: String): Unit {
        host.postDelayed({
            if (isFinishing || isDestroyed || !host.isAttachedToWindow || featuredHost !== host || screen != Screen.HOME) return@postDelayed
            if (host.hasFocus()) scheduleFeaturedRotation(host, server) else { featuredIndex = (featuredIndex + 1) % featuredItems.size; renderFeatured(host, server) }
        }, 8000)
    }

    private fun searchScreen() {
        screen = Screen.SEARCH
        root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(42), dp(28), dp(42), dp(28)) }
        page.addView(metallicPageHeader("البحث", { home() }), LinearLayout.LayoutParams(-1, dp(68)))
        val query = metallicSearchField("ابحث عن فيلم أو مسلسل أو قناة")
        val search = metallicTextButton("بحث") { searchLibrary(query.text.toString(), page) }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(22), 0, dp(18)); addView(query, LinearLayout.LayoutParams(0, dp(68), 1f)); addView(search, LinearLayout.LayoutParams(dp(180), dp(68)).apply { marginStart = dp(16) }) }
        val status = text("اكتب اسم ما تريد مشاهدته", 18).apply { setTextColor(Color.rgb(175, 195, 235)) }
        val scroll = ScrollView(this); val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(results)
        page.addView(controls); page.addView(status); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(page); query.requestFocus()
    }

    private fun searchLibrary(rawQuery: String, page: LinearLayout) {
        val query = rawQuery.trim(); if (query.isBlank()) return
        val status = page.getChildAt(2) as TextView; val scroll = page.getChildAt(3) as ScrollView; val results = scroll.getChildAt(0) as LinearLayout
        status.text = "جارٍ البحث…"; results.removeAllViews(); val base = requireNotNull(baseUrl())
        executor.execute { runCatching { fetchSearch(base, query) }.onSuccess { items -> runOnUiThread {
            status.text = if (items.isEmpty()) "لا توجد نتائج لـ: $query" else "نتائج البحث: ${items.size}"
            if (isGridMode) addMediaGrid(results, items, cardsPerRow) { openMedia(base, it) } else addMediaList(results, items) { openMedia(base, it) }
        } }.onFailure { error -> AppLog.error("Search failed", error); runOnUiThread { status.text = "تعذر تنفيذ البحث" } } }
    }

    private fun fetchSearch(base: String, query: String): List<Media> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val data = getJson("$base/api.php?action=search&q=$encoded&limit=50"); val results = mutableListOf<Media>()
        val channels = data.optJSONArray("channels") ?: org.json.JSONArray()
        for (i in 0 until channels.length()) { val item = channels.getJSONObject(i); val stream = item.optString("stream_url"); if (stream.isNotBlank()) results += Media(item.optString("name", "قناة"), absoluteUrl(base, stream), artworkUrl = item.optString("logo_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) }) }
        val series = data.optJSONArray("series") ?: org.json.JSONArray()
        for (i in 0 until series.length()) { val item = series.getJSONObject(i); results += Media(item.optString("name", "بدون عنوان"), seriesId = item.getInt("id"), artworkUrl = item.optString("poster_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) }) }
        return results
    }

    private fun resumeItems(): List<Media> = runCatching {
        val raw = getSharedPreferences("playback", Context.MODE_PRIVATE).getString("resume_list", "[]") ?: "[]"
        val saved = org.json.JSONArray(raw); buildList {
            for (i in 0 until minOf(saved.length(), 7)) { val item = saved.getJSONObject(i); val url = item.optString("url"); if (url.isNotBlank()) add(Media(item.optString("title", "متابعة المشاهدة"), url, artworkUrl = item.optString("artwork").takeIf { it.isNotBlank() })) }
        }
    }.getOrDefault(emptyList())

    private fun fetchRows(base: String): List<Pair<String, List<Media>>> {
        val catalog = getJson("$base/api.php?action=all_content"); val categories = catalog.getJSONArray("categories")
        val rows = mutableListOf<Pair<String, List<Media>>>()
        sectionSources.clear()
        for (i in 0 until categories.length()) {
            if (rows.size >= 14) break
            val category = categories.getJSONObject(i); val id = category.getInt("id")
            val action = when { category.optInt("channel_count") > 0 -> "channels"; category.optInt("series_count") > 0 -> "series"; else -> continue }
            val data = runCatching { getJson("$base/api.php?action=$action&category_id=$id&limit=12") }.getOrNull() ?: continue
            val values = data.optJSONArray(if (action == "channels") "channels" else "series") ?: continue
            val items = mutableListOf<Media>()
            for (j in 0 until values.length()) {
                val item = values.getJSONObject(j)
                if (action == "channels") {
                    val stream = item.optString("stream_url")
                    if (stream.isNotBlank()) items += Media(item.optString("name", "قناة"), absoluteUrl(base, stream), artworkUrl = item.optString("logo_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) })
                } else items += Media(item.optString("name", "بدون عنوان"), seriesId = item.getInt("id"), artworkUrl = item.optString("poster_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) })
            }
            if (items.isNotEmpty()) {
                val name = category.optString("name", "مكتبة")
                sectionSources[name] = id to action
                rows += name to items
            }
        }
        return rows
    }

    /** Full, remote-friendly view for a single home section. */
    private fun showCategory(base: String, title: String, categoryId: Int, action: String) {
        rememberHomePosition(); screen = Screen.CATEGORY; root.removeAllViews()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(42), dp(28), dp(42), dp(28)) }
        val header = metallicPageHeader(title, { home() }, "ترتيب") { AppLog.info("Sort control selected for $title") }
        val status = text("جارٍ تحميل عناصر القسم…", 18).apply { setTextColor(Color.rgb(175, 195, 235)) }
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(content)
        page.addView(header); page.addView(status); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(page)
        val pageSize = 60
        var loaded = 0; var loading = false; var reachedEnd = false
        lateinit var loadNextPage: () -> Unit
        loadNextPage = load@{
            if (loading || reachedEnd || screen != Screen.CATEGORY) return@load
            loading = true; status.text = "جارٍ تحميل المزيد…"
            val offset = loaded
            executor.execute {
                runCatching { getJson("$base/api.php?action=$action&category_id=$categoryId&limit=$pageSize&offset=$offset") }
                    .onSuccess { data -> runOnUiThread {
                        if (screen != Screen.CATEGORY) return@runOnUiThread
                        val values = data.optJSONArray(if (action == "channels") "channels" else "series") ?: org.json.JSONArray()
                        val items = mutableListOf<Media>()
                        for (i in 0 until values.length()) {
                            val item = values.getJSONObject(i)
                            if (action == "channels") {
                                val stream = item.optString("stream_url")
                                if (stream.isNotBlank()) items += Media(item.optString("name", "قناة"), absoluteUrl(base, stream), artworkUrl = item.optString("logo_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) })
                            } else items += Media(item.optString("name", "بدون عنوان"), seriesId = item.getInt("id"), artworkUrl = item.optString("poster_url").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) })
                        }
                        loaded += values.length(); loading = false; reachedEnd = values.length() < pageSize
                        if (items.isNotEmpty()) { if (isGridMode) addMediaGrid(content, items, cardsPerRow) { openMedia(base, it) } else addMediaList(content, items) { openMedia(base, it) } }
                        status.text = if (reachedEnd) "تم عرض جميع العناصر: $loaded" else "تم عرض $loaded عنصرًا — تابع النزول للمزيد"
                    } }
                    .onFailure { error -> AppLog.error("Full category request failed", error); runOnUiThread { loading = false; status.text = "تعذر تحميل المزيد، حاول النزول مرة أخرى" } }
            }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> if (scroll.getChildAt(0).height - (y + scroll.height) < dp(900)) loadNextPage() }
        loadNextPage()
    }

    private fun openMedia(base: String, media: Media) {
        media.streamUrl?.let { startActivity(PlayerActivity.intent(this, it, media.title, null, media.artworkUrl)); return }
        val id = media.seriesId ?: return; showEpisodes(base, id, media.title)
    }

    private fun showEpisodes(base: String, seriesId: Int, title: String) {
        rememberHomePosition()
        screen = Screen.EPISODES
        root.removeAllViews(); val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(42), dp(28), dp(42), dp(28)) }
        val header = metallicPageHeader(title, { home() }); page.addView(header, LinearLayout.LayoutParams(-1, dp(68)))
        val status = text("جارٍ تحميل الحلقات…", 18); page.addView(status); val scroll = ScrollView(this); val episodes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(episodes); page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(page)
        executor.execute {
            runCatching { getJson("$base/api.php?action=episodes&series_id=$seriesId") }
                .onSuccess { data -> runOnUiThread {
                    status.text = "اختر حلقة"
                    val values = data.getJSONArray("episodes")
                    val cover = data.optString("series_poster").takeIf { it.isNotBlank() }?.let { absoluteUrl(base, it) }
                    val items = mutableListOf<Media>()
                    // Some library imports reuse episode_number (1, 1, 2, 2 …) while
                    // the uploaded filename/title still contains the real sequence (e.g. AHLAHM-16).
                    // Sort and label by that real suffix whenever it exists.
                    val orderedEpisodes = (0 until values.length()).map { values.getJSONObject(it) }
                        .sortedWith(compareBy<JSONObject> { episodeSequence(it) }.thenBy { it.optInt("id") })
                    for ((index, episode) in orderedEpisodes.withIndex()) {
                        val label = "الحلقة ${episodeSequence(episode).takeIf { it != Int.MAX_VALUE } ?: (index + 1)}"
                        val stream = absoluteUrl(base, episode.getString("stream_url"))
                        val episodeArtwork = listOf("poster_url", "thumbnail_url", "image_url", "artwork_url", "cover_url")
                            .asSequence().map { episode.optString(it) }.firstOrNull { it.isNotBlank() }
                            ?.let { absoluteUrl(base, it) } ?: cover
                        items += Media(label, stream, artworkUrl = episodeArtwork)
                    }
                    addMediaGrid(episodes, items, 4) { episode -> startActivity(PlayerActivity.intent(this, requireNotNull(episode.streamUrl), "$title — ${episode.title}", null, episode.artworkUrl)) }
                    // A TV remote needs a deterministic first target when a series opens.
                    episodes.post { (episodes.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
                } }
                .onFailure { error -> AppLog.error("Episode request failed", error); runOnUiThread { status.text = "تعذر تحميل الحلقات" } }
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { connection.disconnect() }
    }
    private fun episodeSequence(episode: JSONObject): Int {
        val title = episode.optString("title")
        val suffix = Regex("\\d+(?!.*\\d)").find(title)?.value?.toIntOrNull()
        return suffix ?: episode.optInt("display_order", episode.optInt("episode_number", Int.MAX_VALUE))
    }

    private fun absoluteUrl(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val origin = URL(base).let { "${it.protocol}://${it.authority}" }; return if (path.startsWith('/')) origin + path else "$base/$path"
    }

    private fun baseUrl(): String? { val h = prefs.getString("host", null) ?: return null; val p = prefs.getInt("port", -1); val path = prefs.getString("path", "iptv")?.trim('/') ?: "iptv"; return if (p in 1..65535 && path.isNotBlank()) "http://$h:$p/$path" else null }
    private fun field(h: String, v: String) = EditText(this).apply {
        hint = h; setText(v); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.rgb(181, 184, 197))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 19f
        setBackgroundColor(Color.rgb(25, 29, 42)); setPadding(dp(22), 0, dp(22), 0)
    }
    /** Keeps Arabic and Latin titles visually consistent while retaining their natural direction. */
    private fun text(v: String, s: Int) = TextView(this).apply {
        text = v; textSize = s.toFloat(); setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false; letterSpacing = 0.008f
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private fun cinemaBackground() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(10, 12, 20), Color.rgb(36, 9, 22), Color.rgb(9, 12, 19)))
    private fun sectionTitle(title: String) = text(title, 25).apply {
        setPadding(dp(12), dp(24), dp(12), dp(12)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private fun sectionHeader(title: String, showAll: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(dp(12), dp(24), dp(12), dp(12))
        addView(metallicTextButton("عرض الكل") { AppLog.info("Opening full section: $title"); showAll() }, LinearLayout.LayoutParams(dp(150), dp(52)))
        addView(text(title, 25).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
    }
    /** Shared silver bar used by search, categories, and episode screens. */
    private fun metallicPageHeader(title: String, back: () -> Unit, secondaryLabel: String? = null, secondaryClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
        background = toolbarBackground(); elevation = dp(8).toFloat(); setPadding(dp(10), dp(7), dp(10), dp(7))
        addView(iconButton(R.drawable.ic_back_metal, "رجوع", back), LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(14) })
        if (secondaryLabel != null) addView(metallicTextButton(secondaryLabel) { secondaryClick?.invoke() }, LinearLayout.LayoutParams(dp(116), dp(50)).apply { marginEnd = dp(18) })
        addView(text(title, 29).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, dp(54), 1f))
    }
    private fun metallicSearchField(hintValue: String) = EditText(this).apply {
        hint = hintValue; setSingleLine(); textSize = 20f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(Color.WHITE); setHintTextColor(Color.rgb(194, 198, 204)); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        background = toolbarInnerBackground(); setPadding(dp(24), 0, dp(20), 0)
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_search_metal, 0); compoundDrawablePadding = dp(14)
    }
    private fun metallicTextButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 17f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isAllCaps = false; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; background = metalIconButtonBackground(false); isFocusable = true
        setOnFocusChangeListener { view, focused ->
            view.background = metalIconButtonBackground(focused); view.scaleX = if (focused) 1.05f else 1f; view.scaleY = if (focused) 1.05f else 1f
            if (focused) AppLog.info("Focus moved to: $label")
        }
        setOnClickListener { AppLog.info("Selected: $label"); click() }
    }
    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(11), dp(11), dp(11), dp(11))
        contentDescription = description; background = metalIconButtonBackground(false); isFocusable = true
        setOnFocusChangeListener { view, focused ->
            view.background = metalIconButtonBackground(focused)
            view.scaleX = if (focused) 1.07f else 1f; view.scaleY = if (focused) 1.07f else 1f
            view.elevation = if (focused) dp(10).toFloat() else 0f
            if (focused) AppLog.info("Focus moved to: $description")
        }
        setOnClickListener { AppLog.info("Selected: $description"); click() }
    }
    private fun controlButton(symbol: String, description: String, color: Int, click: () -> Unit) = Button(this).apply {
        text = symbol; textSize = 31f; contentDescription = description; isAllCaps = false; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        includeFontPadding = false; setPadding(0, 0, 0, 0)
        background = toolbarButtonBackground(false, color); isFocusable = true
        setOnFocusChangeListener { view, focused ->
            view.background = toolbarButtonBackground(focused, color)
            view.scaleX = if (focused) 1.07f else 1f; view.scaleY = if (focused) 1.07f else 1f
            view.elevation = if (focused) dp(10).toFloat() else 0f
            if (focused) AppLog.info("Focus moved to: $description")
        }
        setOnClickListener { AppLog.info("Selected: $description"); click() }
    }
    private fun heroCard(media: Media, click: () -> Unit) = FrameLayout(this).apply {
        background = focusBackground(false, Color.rgb(42, 20, 24)); isFocusable = true; isClickable = true
        val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(48, 28, 32)); contentDescription = media.title }
        addView(image, FrameLayout.LayoutParams(-1, -1)); media.artworkUrl?.let { loadArtwork(image, it) }
        val shade = View(this@MainActivity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(235, 5, 5, 8), Color.argb(100, 5, 5, 8), Color.TRANSPARENT)) }; addView(shade, FrameLayout.LayoutParams(-1, -1))
        val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), 0, 0, 0); addView(text(media.title, 23).apply { setTypeface(typeface, 1); gravity = Gravity.START }); addView(text("OK  ▶  مشاهدة الآن", 15).apply { gravity = Gravity.START; setPadding(0, dp(6), 0, 0) }) }
        val infoWidth = minOf(dp(600), (resources.displayMetrics.widthPixels * if (isCompactTv) 0.55f else 0.50f).toInt())
        addView(info, FrameLayout.LayoutParams(infoWidth, -1, Gravity.LEFT)); setOnFocusChangeListener { view, focused -> view.background = focusBackground(focused, Color.rgb(42, 20, 24)); view.scaleX = if (focused) 1.015f else 1f; view.scaleY = if (focused) 1.015f else 1f; if (focused) { view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false) }; AppLog.info("Focus moved to: ${media.title}") } }; setOnClickListener { click() }
    }
    private fun addMediaGrid(parent: LinearLayout, items: List<Media>, columns: Int, click: (Media) -> Unit) {
        // Allow the remote-focus outline to extend beyond a card on every edge.
        parent.clipChildren = false; parent.clipToPadding = false
        val grid = GridLayout(this).apply {
            columnCount = columns; rowCount = (items.size + columns - 1) / columns; useDefaultMargins = false; alignmentMode = GridLayout.ALIGN_MARGINS; clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(10), dp(8), dp(28))
        }
        // The page itself has 56dp margins on each side; include them so the last card never clips at the edge.
        // Reserve the grid's 8dp safe inset on both sides before computing card width.
        // This keeps the focus outline fully visible on the first and last card.
        val safeSideInsets = if (isCompactTv) 84 else 112
        val width = (resources.displayMetrics.widthPixels - dp(safeSideInsets) - dp(16) - dp(18) * (columns - 1)) / columns
        // Library items are movie posters, not landscape video frames. Preserve a
        // portrait artwork area so faces and the top of the original cover are not cut.
        // Episode cards deliberately remain wider because they represent individual videos.
        // A slightly wider portrait proportion keeps the covers elegant on a TV grid.
        val height = if (columns == 4) dp(250) else (width * 1.18f + dp(62)).toInt()
        items.forEachIndexed { index, item ->
            grid.addView(mediaCard(item, columns == 4) { click(item) }, GridLayout.LayoutParams().apply {
                rowSpec = GridLayout.spec(index / columns); columnSpec = GridLayout.spec(index % columns)
                this.width = width; this.height = height; if (index % columns != columns - 1) rightMargin = dp(18); bottomMargin = dp(18)
            })
        }
        parent.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }
    private fun addMediaList(parent: LinearLayout, items: List<Media>, click: (Media) -> Unit) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(22)) }
        items.forEach { item -> list.addView(mediaListItem(item) { click(item) }, LinearLayout.LayoutParams(-1, dp(116)).apply { bottomMargin = dp(12) }) }
        parent.addView(list, LinearLayout.LayoutParams(-1, -2))
    }
    private fun focusBackground(focused: Boolean, color: Int = Color.rgb(25, 30, 40)) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat(); setColor(color)
        // Gold remains visible over bright posters as well as the red cinema background.
        setStroke(dp(if (focused) 5 else 1), if (focused) Color.rgb(255, 210, 55) else Color.rgb(93, 76, 81))
    }
    /** Semi-transparent layered surfaces emulate glass without sacrificing TV performance. */
    private fun toolbarBackground() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(222, 227, 231), Color.rgb(113, 122, 132), Color.rgb(59, 65, 75), Color.rgb(142, 149, 157), Color.rgb(212, 216, 220))).apply {
        cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.rgb(239, 243, 246))
    }
    private fun toolbarInnerBackground() = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(205, 23, 25, 33), Color.argb(175, 72, 77, 88))).apply {
        cornerRadius = dp(27).toFloat(); setStroke(dp(1), Color.argb(135, 220, 230, 245))
    }
    private fun toolbarButtonBackground(focused: Boolean, color: Int) = GradientDrawable().apply {
        cornerRadius = dp(27).toFloat(); setColor(color)
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.rgb(255, 212, 77) else Color.argb(170, 221, 228, 240))
    }
    private fun metalIconButtonBackground(focused: Boolean) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(99, 108, 120), Color.rgb(42, 48, 58), Color.rgb(86, 94, 104))).apply {
        cornerRadius = dp(27).toFloat()
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.rgb(255, 216, 92) else Color.rgb(211, 217, 224))
    }
    private fun mediaCard(media: Media, episode: Boolean = false, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; background = focusBackground(false); setPadding(dp(5), dp(5), dp(5), dp(5))
        val artwork = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(45, 50, 60)); contentDescription = media.title }
        addView(artwork, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(text(media.title, if (episode) 17 else 18).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(5), dp(8), dp(4))
        }, LinearLayout.LayoutParams(-1, dp(if (episode) 52 else 58)))
        media.artworkUrl?.let { loadArtwork(artwork, it) }
        setOnFocusChangeListener { view, focused ->
            view.background = focusBackground(focused); view.animate().cancel()
            view.animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f).setDuration(90).start()
            view.elevation = if (focused) dp(12).toFloat() else 0f
            if (focused) { if (screen == Screen.HOME) lastHomeFocusTitle = media.title; view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false) }; AppLog.info("Focus moved to: ${media.title}") }
        }
        setOnClickListener { AppLog.info("Selected: ${media.title}"); click() }
    }
    private fun mediaListItem(media: Media, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; background = focusBackground(false); setPadding(dp(12), dp(8), dp(12), dp(8))
        val artwork = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(45, 50, 60)); contentDescription = media.title }
        addView(artwork, LinearLayout.LayoutParams(dp(150), -1).apply { marginEnd = dp(18) })
        addView(text(media.title, 21).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -1, 1f))
        media.artworkUrl?.let { loadArtwork(artwork, it) }
        setOnFocusChangeListener { view, focused ->
            view.background = focusBackground(focused); view.animate().cancel()
            view.animate().scaleX(if (focused) 1.015f else 1f).scaleY(if (focused) 1.015f else 1f).setDuration(90).start()
            view.elevation = if (focused) dp(10).toFloat() else 0f
            if (focused) { view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false) }; AppLog.info("Focus moved to: ${media.title}") }
        }
        setOnClickListener { AppLog.info("Selected: ${media.title}"); click() }
    }
    private fun loadArtwork(view: ImageView, address: String) {
        // A card may be rebuilt while an image request is still in flight.  Ignore
        // late requests rather than allowing an executor shutdown to crash the app.
        if (isFinishing || isDestroyed || imageExecutor.isShutdown) return
        runCatching {
            imageExecutor.execute {
                val image = runCatching { URL(address).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return@execute
                runOnUiThread { if (!isFinishing && !isDestroyed && view.isAttachedToWindow) view.setImageBitmap(image) }
            }
        }.onFailure { AppLog.info("Skipped late artwork request") }
    }
    private fun button(v: String, click: () -> Unit) = Button(this).apply { text = v; textSize = 17f; isAllCaps = false; setTextColor(Color.WHITE); background = focusBackground(false, Color.rgb(47, 113, 246)); isFocusable = true; setOnFocusChangeListener { x, f -> x.background = focusBackground(f, Color.rgb(47, 113, 246)); x.scaleX = if (f) 1.06f else 1f; x.scaleY = if (f) 1.06f else 1f; if (f) AppLog.info("Focus moved to: $v") }; setOnClickListener { AppLog.info("Selected: $v"); click() } }
    private fun lp(h: Int, b: Int) = LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(b) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val isCompactTv: Boolean get() = resources.configuration.screenWidthDp in 1..719

    /** TV-friendly confirmation instead of closing the application on one Back press. */
    private fun showExitConfirmation() {
        val dialog = Dialog(this).apply { setCanceledOnTouchOutside(false) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(44), dp(34), dp(44), dp(30))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat(); setColor(Color.rgb(30, 18, 24))
                setStroke(dp(2), Color.rgb(188, 43, 59))
            }
        }
        panel.addView(text("هل تريد الخروج؟", 30).apply { gravity = Gravity.CENTER; setTypeface(typeface, 1) }, LinearLayout.LayoutParams(-1, dp(58)))
        panel.addView(text("يمكنك المتابعة من حيث توقفت في أي وقت", 17).apply { gravity = Gravity.CENTER; setTextColor(Color.rgb(210, 195, 205)) }, LinearLayout.LayoutParams(-1, dp(46)))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(22), 0, 0) }
        val stay = metallicTextButton("متابعة المشاهدة") { dialog.dismiss() }
        val exit = Button(this).apply {
            text = "خروج"; textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE); isFocusable = true
            background = focusBackground(false, Color.rgb(142, 38, 48))
            setOnFocusChangeListener { view, focused ->
                view.background = focusBackground(focused, Color.rgb(177, 43, 56))
                view.scaleX = if (focused) 1.06f else 1f; view.scaleY = if (focused) 1.06f else 1f
            }
            setOnClickListener { dialog.dismiss(); finishAffinity() }
        }
        actions.addView(stay, LinearLayout.LayoutParams(dp(240), dp(62)).apply { marginEnd = dp(18) })
        actions.addView(exit, LinearLayout.LayoutParams(dp(160), dp(62)))
        panel.addView(actions)
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(dp(620), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setOnShowListener { stay.requestFocus() }
        dialog.show()
        dialog.window?.setLayout(dp(620), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.EPISODES, Screen.SEARCH, Screen.CATEGORY -> { AppLog.info("Back: returning to library"); home() }
            Screen.SETUP -> if (baseUrl() != null) home() else super.onBackPressed()
            Screen.HOME -> showExitConfirmation()
        }
    }
    override fun onDestroy() { launchHandler.removeCallbacksAndMessages(null); executor.shutdownNow(); imageExecutor.shutdownNow(); super.onDestroy() }
}
