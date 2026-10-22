package pl.rmffm.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var userWantsPlaying = false
    private var isDarkTheme = true

    private val streams = listOf(
        Pair("RMF FM – Standard (HTTPS)", "https://rs101-krk.rmfstream.pl/rmf_fm"),
        Pair("RMF FM – Auto / Oszczędny (AAC+ 48k)", "https://rs101-krk.rmfstream.pl/RMFFM48"),
        Pair("RMF FM – Serwer zapasowy (HTTP)", "http://195.150.20.242:8000/rmf_fm")
    )
    private var currentStreamIndex = 0

    private lateinit var rootScroll: ScrollView
    private lateinit var mainContainer: LinearLayout
    private lateinit var leftCard: LinearLayout
    private lateinit var rightCard: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMetadata: TextView
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
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.CENTER
        }

        // Panel informacyjny (lewa strona w aucie / góra na telefonie)
        leftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER
        }

        val badge = TextView(this).apply {
            text = "RMF FM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#002B5B"))
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(10), dp(28), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FFD700"))
            }
        }

        tvTitle = TextView(this).apply {
            text = "Radio Internetowe"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }

        tvSubtitle = TextView(this).apply {
            text = "Telefon & Radio Samochodowe Android"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

        tvStatus = TextView(this).apply {
            text = "Gotowy do odtwarzania"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }

        tvMetadata = TextView(this).apply {
            text = "🎵 Najlepsza muzyka"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(18))
        }

        btnThemeToggle = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isAllCaps = false
            setPadding(dp(18), dp(12), dp(18), dp(12))
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
        leftCard.addView(tvTitle)
        leftCard.addView(tvSubtitle)
        leftCard.addView(tvStatus)
        leftCard.addView(tvMetadata)
        leftCard.addView(btnThemeToggle)

        // Panel sterowania (prawa strona w aucie / dół na telefonie)
        rightCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.CENTER
        }

        btnPlayStop = Button(this).apply {
            text = "▶  ODTWARZAJ RMF FM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(92)
            setOnClickListener {
                if (userWantsPlaying) {
                    stopRadio()
                } else {
                    startRadio()
                }
            }
        }

        tvStreamLabel = TextView(this).apply {
            text = "Wybierz źródło strumienia:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(18), 0, dp(6))
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
            text = "Autostart po uruchomieniu aplikacji (w aucie)"
            isChecked = autoPlay
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), dp(14), dp(8), dp(8))
            setOnCheckedChangeListener { _, isChecked ->
                getSharedPreferences("rmf_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_play", isChecked)
                    .apply()
            }
        }

        cbKeepScreenOn = CheckBox(this).apply {
            text = "Nie wygaszaj ekranu (Tryb Samochodowy)"
            isChecked = keepScreenOn
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
            leftCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
            rightCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                marginStart = dp(12)
            }
        } else {
            mainContainer.orientation = LinearLayout.VERTICAL
            leftCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
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
            cornerRadius = dp(20).toFloat()
            setColor(cardColor)
        }
        rightCard.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(cardColor)
        }

        tvTitle.setTextColor(primaryText)
        tvSubtitle.setTextColor(secondaryText)
        tvMetadata.setTextColor(primaryText)
        tvStreamLabel.setTextColor(secondaryText)
        cbAutoPlay.setTextColor(primaryText)
        cbKeepScreenOn.setTextColor(primaryText)

        btnThemeToggle.text = if (isDarkTheme) "☀️  Przełącz na motyw JASNY" else "🌙  Przełącz na motyw CIEMNY"
        btnThemeToggle.setTextColor(primaryText)
        btnThemeToggle.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(themeBtnColor)
        }

        updatePlayButtonAppearance()
    }

    private fun updatePlayButtonAppearance() {
        val isPlayingNow = userWantsPlaying
        btnPlayStop.text = if (isPlayingNow) "⏹  ZATRZYMAJ RADIO" else "▶  ODTWARZAJ RMF FM"
        btnPlayStop.setTextColor(if (isPlayingNow) Color.WHITE else Color.parseColor("#002B5B"))
        btnPlayStop.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (isPlayingNow) Color.parseColor("#E53E3E") else Color.parseColor("#FFD700"))
        }

        tvStatus.setTextColor(
            if (isPlayingNow) Color.parseColor("#38A169")
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
                            Player.STATE_BUFFERING -> tvStatus.text = "⏳ Buforowanie strumienia..."
                            Player.STATE_READY -> if (isPlaying) tvStatus.text = "🟢 Gra na żywo: RMF FM"
                            Player.STATE_ENDED -> if (userWantsPlaying) scheduleReconnect()
                            Player.STATE_IDLE -> {}
                        }
                        updatePlayButtonAppearance()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            tvStatus.text = "🟢 Gra na żywo: RMF FM"
                        }
                        updatePlayButtonAppearance()
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        val artist = mediaMetadata.artist?.toString()
                        val title = mediaMetadata.title?.toString()
                        val info = listOfNotNull(artist, title).joinToString(" - ")
                        if (info.isNotBlank()) {
                            tvMetadata.text = "🎵 $info"
                        }
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
        tvStatus.text = "⏹ Zatrzymano"
        tvMetadata.text = "🎵 Najlepsza muzyka"
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
