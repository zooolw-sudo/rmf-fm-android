package pl.rmffm.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var userWantsPlaying = false
    private var isDarkTheme = true
    private var lastTrackQuery = ""
    private var coverFetchJob: Job? = null

    private val streams = listOf(
        Pair("RMF FM – Standard (HTTPS)", "https://rs101-krk.rmfstream.pl/rmf_fm"),
        Pair("RMF FM – Auto / Oszczędny (AAC+ 48k)", "https://rs101-krk.rmfstream.pl/RMFFM48"),
        Pair("RMF FM – Serwer zapasowy (HTTP)", "http://195.150.20.242:8000/rmf_fm")
    )
    private var currentStreamIndex = 0

    // Elementy widoku
    private lateinit var rootScroll: ScrollView
    private lateinit var mainContainer: LinearLayout
    private lateinit var leftCard: LinearLayout
    private lateinit var rightCard: LinearLayout
    private lateinit var ivCoverArt: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var tvNowPlayingArtist: TextView
    private lateinit var tvNextInQueue: TextView
    private lateinit var tvStreamLabel: TextView
    private lateinit var btnPlayStop: Button
    private lateinit var btnThemeToggle: Button
    private lateinit var spinnerStream: Spinner
    private lateinit var cbAutoPlay: CheckBox
    private lateinit var cbKeepScreenOn: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
        isDarkTheme = prefs.getBoolean("dark_theme", true)
        currentStreamIndex = prefs.getInt("stream_index", 0).coerceIn(0, streams.lastIndex)
        val autoPlay = prefs.getBoolean("auto_play", true)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", true)

        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)

        buildUserInterface(autoPlay, keepScreenOn)
        applyThemeColors()
        applyOrientationLayout(resources.configuration.orientation)
        updateKeepScreenOn(keepScreenOn)
        initPlayer()

        if (autoPlay) {
            startRadio()
        }
    }

    private fun buildUserInterface(autoPlay: Boolean, keepScreenOn: Boolean) {
        rootScroll = ScrollView(this).apply {
            isFillViewport = true
        }

        mainContainer = LinearLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
        }

        // --- LEWY PANEL: Okładka, Tytuł, Wykonawca, Następny w kolejce ---
        leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val badge = TextView(this).apply {
            text = "RMF FM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#002B5B"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#FFD700"))
            }
        }

        tvStatus = TextView(this).apply {
            text = "Gotowy do odtwarzania"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }

        // Okładka albumu
        ivCoverArt = ImageView(this).apply {
            val size = dp(170)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(createPlaceholderBitmap())
        }

        val labelNowPlaying = TextView(this).apply {
            text = "TERAZ GRAMY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFD700"))
            gravity = Gravity.CENTER
        }

        tvNowPlayingTitle = TextView(this).apply {
            text = "Najlepsza Muzyka"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        tvNowPlayingArtist = TextView(this).apply {
            text = "Radio RMF FM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), dp(12))
        }

        // Kafelek "Następny w kolejce"
        val nextQueueContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#22888888"))
            }
        }

        val labelNext = TextView(this).apply {
            text = "⏭  NASTĘPNY W KOLEJCE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        tvNextInQueue = TextView(this).apply {
            text = "Kolejny przebój z ramówki RMF FM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }

        nextQueueContainer.addView(labelNext)
        nextQueueContainer.addView(tvNextInQueue)

        btnThemeToggle = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            setOnClickListener {
                isDarkTheme = !isDarkTheme
                getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("dark_theme", isDarkTheme)
                    .apply()
                applyThemeColors()
            }
        }

        leftCard.addView(badge)
        leftCard.addView(tvStatus)
        leftCard.addView(ivCoverArt)
        leftCard.addView(labelNowPlaying)
        leftCard.addView(tvNowPlayingTitle)
        leftCard.addView(tvNowPlayingArtist)
        leftCard.addView(nextQueueContainer)
        leftCard.addView(btnThemeToggle)

        // --- PRAWY PANEL: Sterowanie, Wybór strumienia, Opcje samochodowe ---
        rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER
        }

        btnPlayStop = Button(this).apply {
            text = "▶  WŁĄCZ RADIO"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(84)
            setOnClickListener {
                if (userWantsPlaying) {
                    stopRadio()
                } else {
                    startRadio()
                }
            }
        }

        tvStreamLabel = TextView(this).apply {
            text = "Źródło strumienia internetowego:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(16), 0, dp(6))
        }

        spinnerStream = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                streams.map { it.first }
            )
            setSelection(currentStreamIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != currentStreamIndex) {
                        currentStreamIndex = position
                        getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("stream_index", currentStreamIndex)
                            .apply()
                        if (userWantsPlaying) {
                            startRadio()
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        cbAutoPlay = CheckBox(this).apply {
            text = "Autostart po uruchomieniu (do samochodu)"
            isChecked = autoPlay
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), dp(12), dp(6), dp(6))
            setOnCheckedChangeListener { _, isChecked ->
                getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_play", isChecked)
                    .apply()
            }
        }

        cbKeepScreenOn = CheckBox(this).apply {
            text = "Nie wygaszaj ekranu podczas jazdy"
            isChecked = keepScreenOn
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnCheckedChangeListener { _, isChecked ->
                getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("keep_screen_on", isChecked)
                    .apply()
                updateKeepScreenOn(isChecked)
            }
        }

        rightCard.addView(
            btnPlayStop,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rightCard.addView(tvStreamLabel)
        rightCard.addView(
            spinnerStream,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rightCard.addView(
            cbAutoPlay,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rightCard.addView(
            cbKeepScreenOn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        mainContainer.addView(leftCard)
        mainContainer.addView(rightCard)
        rootScroll.addView(mainContainer)
        setContentView(rootScroll)
    }

    private fun applyOrientationLayout(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mainContainer.orientation = LinearLayout.HORIZONTAL
            leftCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                marginEnd = dp(10)
            }
            rightCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        } else {
            mainContainer.orientation = LinearLayout.VERTICAL
            leftCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
            rightCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun applyThemeColors() {
        val bgColor = if (isDarkTheme) Color.parseColor("#10141D") else Color.parseColor("#F0F4F8")
        val cardColor = if (isDarkTheme) Color.parseColor("#1C2230") else Color.parseColor("#FFFFFF")
        val primaryText = if (isDarkTheme) Color.parseColor("#FFFFFF") else Color.parseColor("#102A43")
        val secondaryText = if (isDarkTheme) Color.parseColor("#A0AEC0") else Color.parseColor("#486581")
        val themeBtnColor = if (isDarkTheme) Color.parseColor("#2D3748") else Color.parseColor("#E2E8F0")

        rootScroll.setBackgroundColor(bgColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
        }

        leftCard.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(cardColor)
        }
        rightCard.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(cardColor)
        }

        tvNowPlayingTitle.setTextColor(primaryText)
        tvNowPlayingArtist.setTextColor(secondaryText)
        tvNextInQueue.setTextColor(primaryText)
        tvStreamLabel.setTextColor(secondaryText)
        cbAutoPlay.setTextColor(primaryText)
        cbKeepScreenOn.setTextColor(primaryText)

        btnThemeToggle.text = if (isDarkTheme) "☀️  Tryb JASNY" else "🌙  Tryb CIEMNY"
        btnThemeToggle.setTextColor(primaryText)
        btnThemeToggle.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(themeBtnColor)
        }

        updatePlayButtonAppearance()
    }

    private fun updatePlayButtonAppearance() {
        btnPlayStop.text = if (userWantsPlaying) "⏹  ZATRZYMAJ" else "▶  WŁĄCZ RADIO"
        btnPlayStop.setTextColor(if (userWantsPlaying) Color.WHITE else Color.parseColor("#002B5B"))
        btnPlayStop.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (userWantsPlaying) Color.parseColor("#E53E3E") else Color.parseColor("#FFD700"))
        }

        tvStatus.setTextColor(
            if (userWantsPlaying) Color.parseColor("#38A169")
            else if (isDarkTheme) Color.parseColor("#A0AEC0")
            else Color.parseColor("#486581")
        )
    }

    private fun initPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> tvStatus.text = "⏳ Buforowanie..."
                            Player.STATE_READY -> if (isPlaying) tvStatus.text = "🟢 NA ŻYWO: RMF FM"
                            Player.STATE_ENDED -> if (userWantsPlaying) scheduleReconnect()
                            Player.STATE_IDLE -> {}
                        }
                        updatePlayButtonAppearance()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            tvStatus.text = "🟢 NA ŻYWO: RMF FM"
                        }
                        updatePlayButtonAppearance()
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        handleMetadataUpdate(mediaMetadata)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (userWantsPlaying) {
                            tvStatus.text = "⚠️ Brak sieci – ponawiam za 3 s..."
                            scheduleReconnect()
                        }
                    }
                })
            }
    }

    private fun handleMetadataUpdate(mediaMetadata: MediaMetadata) {
        val artist = mediaMetadata.artist?.toString()?.trim()
        val title = mediaMetadata.title?.toString()?.trim()

        val fullText = when {
            !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist - $title"
            !title.isNullOrBlank() -> title
            !artist.isNullOrBlank() -> artist
            else -> ""
        }

        if (fullText.isNotBlank() && fullText != lastTrackQuery) {
            lastTrackQuery = fullText

            var displayArtist = artist ?: "RMF FM"
            var displayTitle = title ?: fullText

            // Jeżeli metadane przyszły w jednym ciągu "Artysta - Tytuł"
            if (displayTitle.contains(" - ") && displayArtist == "RMF FM") {
                val parts = displayTitle.split(" - ", limit = 2)
                displayArtist = parts[0].trim()
                displayTitle = parts[1].trim()
            }

            tvNowPlayingTitle.text = displayTitle
            tvNowPlayingArtist.text = displayArtist
            tvNextInQueue.text = "Kolejny utwór w paśmie RMF FM"

            fetchCoverArt(displayArtist, displayTitle)
        }
    }

    private fun fetchCoverArt(artist: String, title: String) {
        coverFetchJob?.cancel()
        coverFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val searchQuery = "$artist $title"
                val encoded = URLEncoder.encode(searchQuery, "UTF-8")
                val apiUrl = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=1"

                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }

                val jsonResponse = conn.inputStream.bufferedReader().use { it.readText() }
                val rootJson = JSONObject(jsonResponse)
                val results = rootJson.optJSONArray("results")

                var artworkUrl: String? = null
                if (results != null && results.length() > 0) {
                    val firstItem = results.getJSONObject(0)
                    artworkUrl = firstItem.optString("artworkUrl100")?.replace("100x100bb.jpg", "600x600bb.jpg")
                }

                var bitmap: Bitmap? = null
                if (!artworkUrl.isNullOrEmpty()) {
                    val imgConn = (URL(artworkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                    }
                    val rawBitmap = BitmapFactory.decodeStream(imgConn.inputStream)
                    if (rawBitmap != null) {
                        bitmap = getRoundedCornerBitmap(rawBitmap, dp(16))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        ivCoverArt.setImageBitmap(bitmap)
                    } else {
                        ivCoverArt.setImageBitmap(createPlaceholderBitmap())
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    ivCoverArt.setImageBitmap(createPlaceholderBitmap())
                }
            }
        }
    }

    private fun createPlaceholderBitmap(): Bitmap {
        val size = dp(170)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#002B5B")
        val rectF = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rectF, dp(16).toFloat(), dp(16).toFloat(), paint)

        paint.color = Color.parseColor("#FFD700")
        paint.textSize = dp(32).toFloat()
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

        val text = "RMF"
        val yPos = (canvas.height / 2 - (paint.descent() + paint.ascent()) / 2)
        canvas.drawText(text, (canvas.width / 2).toFloat(), yPos, paint)

        return bitmap
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Int): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        canvas.drawRoundRect(rectF, cornerRadius.toFloat(), cornerRadius.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun startRadio() {
        userWantsPlaying = true
        reconnectHandler.removeCallbacksAndMessages(null)
        val url = streams[currentStreamIndex].second
        tvStatus.text = "⏳ Łączenie z RMF FM..."
        updatePlayButtonAppearance()

        player?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
    }

    private fun stopRadio() {
        userWantsPlaying = false
        reconnectHandler.removeCallbacksAndMessages(null)
        player?.stop()
        coverFetchJob?.cancel()
        tvStatus.text = "⏹ Zatrzymano"
        tvNowPlayingTitle.text = "Najlepsza Muzyka"
        tvNowPlayingArtist.text = "Radio RMF FM"
        tvNextInQueue.text = "Kolejny utwór w paśmie RMF FM"
        ivCoverArt.setImageBitmap(createPlaceholderBitmap())
        updatePlayButtonAppearance()
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (userWantsPlaying) {
                startRadio()
            }
        }, 3000)
    }

    private fun updateKeepScreenOn(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout(newConfig.orientation)
    }

    override fun onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null)
        coverFetchJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
