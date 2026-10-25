package com.vindovinou.ilook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** Camera settings, keys and defaults (iOS defaults where iOS has the same option). */
object CameraSettings {
    const val VIDEO = "video_format"
    const val SLOMO = "slomo_fps"
    const val KEEP_MODE = "keep_mode"
    const val KEEP_CREATIVE = "keep_creative"
    const val KEEP_STYLES = "keep_styles"
    const val KEEP_EXPOSURE = "keep_exposure"
    const val VOLUME_SHUTTER = "volume_shutter"
    const val GRID = "grid"
    const val LEVEL = "level"
    const val MIRROR = "mirror_front"

    /** id, label, quality (720/1080/2160), fps. */
    val VIDEO_FORMATS = listOf(
        VideoFormat("720p30", "720p HD à 30 i/s", 720, 30),
        VideoFormat("1080p30", "1080p HD à 30 i/s", 1080, 30),
        VideoFormat("1080p60", "1080p HD à 60 i/s", 1080, 60),
        VideoFormat("4k24", "4K à 24 i/s", 2160, 24),
        VideoFormat("4k30", "4K à 30 i/s", 2160, 30),
        VideoFormat("4k60", "4K à 60 i/s", 2160, 60),
    )

    data class VideoFormat(val id: String, val label: String, val height: Int, val fps: Int)

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("ilook", Context.MODE_PRIVATE)

    fun videoFormat(p: SharedPreferences) = VIDEO_FORMATS.firstOrNull { it.id == p.getString(VIDEO, "1080p30") } ?: VIDEO_FORMATS[1]
}

/** iOS Settings > Camera, in the iOS grouped dark style. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private var page: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CameraSettings.prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != null) mainPage() else finish()
            }
        })
        mainPage()
    }

    private fun mainPage() {
        page = null
        content.removeAllViews()
        header("Appareil photo", back = "‹ Caméra") { finish() }

        group(null) {
            choiceRow("Enregistrer une vidéo", CameraSettings.videoFormat(prefs).label) {
                choicePage(
                    "Enregistrer une vidéo",
                    CameraSettings.VIDEO_FORMATS.map { it.label },
                    CameraSettings.VIDEO_FORMATS.indexOf(CameraSettings.videoFormat(prefs)),
                    "Le 4K à 60 i/s produit des fichiers plus lourds. Le 1080p HD à 30 i/s est le réglage " +
                        "par défaut de l'iPhone.",
                ) { i -> prefs.edit().putString(CameraSettings.VIDEO, CameraSettings.VIDEO_FORMATS[i].id).apply() }
            }
            val slomo = prefs.getInt(CameraSettings.SLOMO, 240)
            choiceRow("Enregistrer au ralenti", "1080p HD à $slomo i/s") {
                choicePage(
                    "Enregistrer au ralenti",
                    listOf("1080p HD à 120 i/s", "1080p HD à 240 i/s"),
                    if (slomo == 120) 0 else 1,
                    "Si le téléphone ne propose pas 240 i/s à l'appli, le ralenti utilise 120 i/s.",
                ) { i -> prefs.edit().putInt(CameraSettings.SLOMO, if (i == 0) 120 else 240).apply() }
            }
            choiceRow("Conserver les réglages", "") { keepPage() }
            switchRow("Bouton de volume pour déclencher", CameraSettings.VOLUME_SHUTTER, true)
        }

        sectionTitle("COMPOSITION")
        group(null) {
            switchRow("Grille", CameraSettings.GRID, false)
            switchRow("Niveau", CameraSettings.LEVEL, false)
            switchRow("Miroir caméra avant", CameraSettings.MIRROR, false)
        }
        footnote("Enregistre les photos prises avec la caméra avant telles qu'elles apparaissent dans l'aperçu.")

        sectionTitle("CAPTURE PHOTO")
        group(null) {
            choiceRow("Styles photographiques", Styles.byId(prefs.getString("style", null)).label) {
                choicePage(
                    "Styles photographiques",
                    Styles.ALL.map { it.label },
                    Styles.ALL.indexOf(Styles.byId(prefs.getString("style", null))),
                    "Le style choisi s'applique à toutes les photos et vidéos.",
                ) { i -> prefs.edit().putString("style", Styles.ALL[i].id).apply() }
            }
            switchRow("Smart HDR", "hdr", true)
        }
        footnote(
            "Les photos et vidéos sont enregistrées dans l'album Appareil photo de la Galerie Samsung " +
                "(DCIM/Camera).",
        )
    }

    private fun keepPage() {
        page = { keepPage() }
        content.removeAllViews()
        header("Conserver les réglages", back = "‹ Appareil photo") { mainPage() }
        group(null) {
            switchRow("Mode appareil photo", CameraSettings.KEEP_MODE, false)
            switchRow("Commandes créatives", CameraSettings.KEEP_CREATIVE, false)
            switchRow("Styles photographiques", CameraSettings.KEEP_STYLES, true)
            switchRow("Réglage de l'exposition", CameraSettings.KEEP_EXPOSURE, false)
        }
        footnote(
            "Conserver le dernier mode utilisé (Vidéo, Portrait…), le format et le retardateur, le style " +
                "photographique et le réglage d'exposition plutôt que de les réinitialiser à l'ouverture.",
        )
    }

    private fun choicePage(title: String, options: List<String>, selected: Int, note: String?, onPick: (Int) -> Unit) {
        page = { choicePage(title, options, selected, note, onPick) }
        content.removeAllViews()
        header(title, back = "‹ Appareil photo") { mainPage() }
        group(null) {
            options.forEachIndexed { i, text ->
                val row = row(text)
                if (i == selected) {
                    row.addView(TextView(this@SettingsActivity).apply {
                        this.text = "✓"
                        setTextColor(0xFF0A84FF.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                }
                row.setOnClickListener {
                    onPick(i)
                    mainPage()
                }
            }
        }
        note?.let { footnote(it) }
    }

    // ---------------- iOS grouped-list building blocks ----------------

    private var currentGroup: LinearLayout? = null

    private fun header(title: String, back: String, onBack: () -> Unit) {
        content.addView(TextView(this).apply {
            text = back
            setTextColor(0xFF0A84FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onBack() }
        })
        content.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(16))
        })
    }

    private fun sectionTitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(24), dp(16), dp(6))
        })
    }

    private fun footnote(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(6), dp(16), dp(4))
        })
    }

    private fun group(title: String?, build: () -> Unit) {
        title?.let { sectionTitle(it) }
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = dp(10).toFloat()
            }
            clipToOutline = true
        }
        content.addView(g, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        currentGroup = g
        build()
        currentGroup = null
    }

    private fun row(title: String): LinearLayout {
        val g = currentGroup!!
        if (g.childCount > 0) {
            g.addView(View(this).apply { setBackgroundColor(0xFF38383A.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isClickable = true
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        g.addView(row)
        return row
    }

    private fun choiceRow(title: String, valueText: String, onClick: () -> Unit) {
        val row = row(title)
        row.addView(TextView(this).apply {
            text = if (valueText.isEmpty()) "›" else "$valueText  ›"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        row.setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, key: String, default: Boolean) {
        val row = row(title)
        val sw = SwitchCompat(this).apply {
            // iOS green switches.
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(0xFF34C759.toInt(), 0xFF39393D.toInt()))
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
