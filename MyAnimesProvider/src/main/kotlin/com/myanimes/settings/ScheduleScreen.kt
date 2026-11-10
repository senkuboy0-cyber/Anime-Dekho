package com.myanimes.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScheduleResponse(
    @JsonProperty("generated_at") val generatedAt: String? = null,
    @JsonProperty("timezone") val timezone: String? = null,
    @JsonProperty("items") val items: List<ScheduleItem>? = null
)

data class ScheduleItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season_name") val seasonName: String? = null,
    @JsonProperty("release_day") val releaseDay: String? = null,
    @JsonProperty("release_time") val releaseTime: String? = null,
    @JsonProperty("dub_languages") val dubLanguages: List<String>? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("total_eps") val totalEps: Int? = null,
    @JsonProperty("current_ep") val currentEp: Int? = null,
    @JsonProperty("frequency") val frequency: String? = null,
    @JsonProperty("is_new") val isNew: Int? = null,
    @JsonProperty("is_paused") val isPaused: Int? = null,
    @JsonProperty("extra_note") val extraNote: String? = null
)

/**
 * Full screen schedule viewer opened from the provider settings.
 * Shows the weekly episode release calendar of the site, grouped by day.
 * The whole UI is built in code, no XML layouts.
 */
class ScheduleScreen(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar) {

    companion object {
        private const val SCHEDULE_URL = "https://myanimes.in/wp-json/myanimes/v1/schedule"
        private val ACCENT = Color.parseColor("#4F8CFF")
        private val CARD = Color.parseColor("#1C1C1E")
        private val CARD_2 = Color.parseColor("#2A2A2D")
        private val TEXT_PRIMARY = Color.WHITE
        private val TEXT_SECONDARY = Color.parseColor("#A0A0A5")

        private val DAY_ORDER = mapOf(
            "Daily" to 0,
            "Monday" to 1, "Tuesday" to 2, "Wednesday" to 3, "Thursday" to 4,
            "Friday" to 5, "Saturday" to 6, "Sunday" to 7
        )
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var loader: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chip(label: String, bg: Int, fg: Int): TextView = TextView(context).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTypeface(typeface, Typeface.BOLD)
        background = round(bg, 8)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Top bar: back button + title
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.BLACK)
        }
        bar.addView(ImageView(context).apply {
            setImageDrawable(BackIcon())
            background = round(CARD, 24)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titleCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(text("MY ANIMES", 11f, TEXT_SECONDARY, true).apply { letterSpacing = 0.12f })
        titleCol.addView(text("Schedule", 21f, TEXT_PRIMARY, true))
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
        root.addView(bar)

        root.addView(View(context).apply { setBackgroundColor(Color.parseColor("#222226")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val body = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(listContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        loader = ProgressBar(context).apply { visibility = View.GONE }
        body.addView(loader, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        errorBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        errorText = text("", 14f, TEXT_SECONDARY).apply { gravity = Gravity.CENTER }
        errorBox.addView(errorText)
        errorBox.addView(text("Retry", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(ACCENT, 16)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { loadSchedule() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        body.addView(errorBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        setCancelable(true)
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss(); true
            } else keyCode == KeyEvent.KEYCODE_BACK
        }
    }

    override fun show() {
        super.show()
        window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        loadSchedule()
    }

    private fun setLoading(loading: Boolean) {
        loader.visibility = if (loading) View.VISIBLE else View.GONE
        errorBox.visibility = View.GONE
        listContainer.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        loader.visibility = View.GONE
        listContainer.visibility = View.GONE
        errorText.text = message
        errorBox.visibility = View.VISIBLE
    }

    private fun loadSchedule() {
        setLoading(true)
        CoroutineScope(Dispatchers.Main).launch {
            val response = runCatching {
                app.get(SCHEDULE_URL, referer = "https://myanimes.in/").parsedSafe<ScheduleResponse>()
            }.getOrNull()
            if (!isShowing) return@launch
            val items = response?.items
            if (items.isNullOrEmpty()) {
                showError("Could not load the schedule.\nCheck your connection and try again.")
            } else {
                renderSchedule(items, response.timezone)
                setLoading(false)
            }
        }
    }

    private fun renderSchedule(items: List<ScheduleItem>, timezone: String?) {
        listContainer.removeAllViews()

        timezone?.takeIf { it.isNotBlank() }?.let {
            listContainer.addView(text("Times shown in $it", 12f, TEXT_SECONDARY).apply {
                setPadding(dp(4), dp(2), dp(4), dp(10))
            })
        }

        val grouped = items.groupBy { it.releaseDay?.trim()?.ifBlank { null } ?: "Other" }
            .toSortedMap(compareBy { key -> DAY_ORDER[key] ?: 99 })

        grouped.forEach { (day, dayItems) ->
            listContainer.addView(text(day.uppercase(), 13f, ACCENT, true).apply {
                letterSpacing = 0.1f
                setPadding(dp(4), dp(8), dp(4), dp(10))
            })
            dayItems.forEach { item ->
                listContainer.addView(scheduleCard(item), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun scheduleCard(item: ScheduleItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(CARD_2, 18)
        }

        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(CARD, 12)
        }
        card.addView(poster, LinearLayout.LayoutParams(dp(58), dp(86)))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Title row with optional NEW badge
        col.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(item.title ?: "Unknown", 15f, TEXT_PRIMARY, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (item.isNew == 1) {
                addView(chip("NEW", ACCENT, Color.WHITE), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val seasonPart = item.seasonName?.takeIf { it.isNotBlank() }
        val epPart = buildString {
            append("EP ")
            append(item.currentEp ?: 0)
            val total = item.totalEps ?: 0
            if (total > 0) append("/$total")
        }
        col.addView(text(listOfNotNull(seasonPart, epPart).joinToString(" • "), 12f, TEXT_SECONDARY).apply {
            setPadding(0, dp(3), 0, 0)
        })

        val timeLine = buildString {
            append(item.releaseTime?.takeIf { it.isNotBlank() } ?: "Time N/A")
            if (item.frequency?.equals("daily", true) == true) append("  •  Daily")
        }
        col.addView(text(timeLine, 13f, ACCENT, true).apply {
            setPadding(0, dp(5), 0, 0)
        })

        val langLine = buildString {
            val langs = item.dubLanguages.orEmpty()
                .filter { it.isNotBlank() }
                .map { it.replaceFirstChar { c -> c.uppercase() } }
            if (langs.isNotEmpty()) append(langs.joinToString(" • "))
            if (item.isPaused == 1) {
                if (isNotEmpty()) append("  •  ")
                append("Paused")
            }
        }
        if (langLine.isNotBlank()) {
            col.addView(text(langLine, 11f, TEXT_SECONDARY).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }

        item.extraNote?.takeIf { it.isNotBlank() }?.let {
            col.addView(text(it, 11f, Color.parseColor("#C79A4B")).apply {
                setPadding(0, dp(4), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(12)
        })

        item.posterUrl?.takeIf { it.isNotBlank() }?.let { url -> loadPoster(url, poster) }

        return card
    }

    private fun loadPoster(url: String, target: ImageView) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val response = app.get(url)
                val bytes = response.body.bytes()
                val bitmap: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                withContext(Dispatchers.Main) {
                    if (isShowing && bitmap != null) target.setImageBitmap(bitmap)
                }
            }
        }
    }
}

/** Code-drawn back arrow icon (no XML or vector resources needed). */
class BackIcon : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.strokeWidth = b.width() * 0.09f
        canvas.drawPath(Path().apply {
            moveTo(b.width() * 0.62f, b.height() * 0.22f)
            quadTo(b.width() * 0.55f, b.height() * 0.44f, b.width() * 0.32f, b.height() * 0.5f)
            lineTo(b.width() * 0.62f, b.height() * 0.78f)
        }, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
