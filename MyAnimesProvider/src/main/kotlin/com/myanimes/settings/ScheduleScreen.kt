package com.myanimes.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap

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
    @JsonProperty("episode_schedule") val episodeSchedule: List<String>? = null,
    @JsonProperty("episodes_per_release") val episodesPerRelease: Int? = null,
    @JsonProperty("frequency") val frequency: String? = null,
    @JsonProperty("is_new") val isNew: Int? = null,
    @JsonProperty("is_paused") val isPaused: Int? = null,
    @JsonProperty("extra_note") val extraNote: String? = null
)

/**
 * Full screen schedule viewer opened from the provider settings.
 * Shows the weekly episode release calendar of the site.
 *
 * - A weekday selector picks which day is displayed; the current day
 *   is selected by default.
 * - The per-episode release dates (episode_schedule) drive everything:
 *   index 0 = episode 1, a date repeated several times = several
 *   episodes that day, and current_ep marks how many episodes are out.
 * - Every card carries a live countdown chip on top, counting to the
 *   episodes of the selected tab date:
 *     today            -> "HH:MM:SS", turning into "Released" once the
 *                         release time has passed
 *     any other day    -> "Xd HH:MM:SS" until that day's release
 * - The whole UI is built in code, no XML layouts.
 */
class ScheduleScreen(context: Context) : Dialog(context, android.R.style.Theme_Black_NoTitleBar) {

    companion object {
        private const val SCHEDULE_URL = "https://myanimes.in/wp-json/myanimes/v1/schedule"
        private val ACCENT = Color.parseColor("#4F8CFF")
        private val ACCENT_SOFT = Color.parseColor("#1D2B45")
        private val CARD = Color.parseColor("#1C1C1E")
        private val CARD_2 = Color.parseColor("#2A2A2D")
        private val TEXT_PRIMARY = Color.WHITE
        private val TEXT_SECONDARY = Color.parseColor("#A0A0A5")
        private val RELEASED_GREEN = Color.parseColor("#5AD07A")
        private val RELEASED_BG = Color.parseColor("#1D3326")

        private val MONTHS = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )

        private val WEEK_DAYS = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
        )

        private val DAY_TO_CALENDAR = mapOf(
            "Sunday" to Calendar.SUNDAY,
            "Monday" to Calendar.MONDAY,
            "Tuesday" to Calendar.TUESDAY,
            "Wednesday" to Calendar.WEDNESDAY,
            "Thursday" to Calendar.THURSDAY,
            "Friday" to Calendar.FRIDAY,
            "Saturday" to Calendar.SATURDAY
        )
    }

    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var loader: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var tabsRow: LinearLayout
    private lateinit var backButton: ImageView
    private lateinit var retryButton: TextView

    private val tabViews = linkedMapOf<String, TextView>()

    // --- Android TV (D-pad) focus feedback state ---
    // The visuals are applied only outside touch mode, so phones and
    // tablets look and behave exactly as before; a remote is required
    // to ever see them.
    private class TvFocusSpec(val cornerRadius: Int, val scale: Float, val borderColor: Int)

    private val tvFocusSpecs = WeakHashMap<View, TvFocusSpec>()

    /** Normal background of a focused view, restored when focus leaves. */
    private val tvFocusBase = WeakHashMap<View, Drawable>()
    private var allItems: List<ScheduleItem> = emptyList()
    private var siteTimeZone: TimeZone = TimeZone.getDefault()
    private var selectedDay: String = currentDayName()

    /** Concrete yyyy-MM-dd date (site timezone) shown by the selected tab. */
    private var selectedDateKey: String = todayKey()
    private var timeZoneNote: String? = null

    /** Countdown chip currently on screen, refreshed once per second. */
    private class TimerChip(val view: TextView, val targetMillis: Long, val isToday: Boolean)

    private val timerChips = mutableListOf<TimerChip>()
    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTimerChips()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    /**
     * Registers a view for Android TV D-pad feedback: while focused it
     * gets a colored border plus an optional slight scale-up so the user
     * always sees which element the remote is pointing at. The listener
     * is a no-op in touch mode, so the phone UI stays untouched.
     */
    private fun enableTvFocus(
        view: View,
        cornerRadius: Int,
        scale: Float = 0f,
        borderColor: Int = ACCENT
    ) {
        tvFocusSpecs[view] = TvFocusSpec(cornerRadius, scale, borderColor)
        view.setOnFocusChangeListener { v, hasFocus ->
            if (v.isInTouchMode) return@setOnFocusChangeListener
            if (hasFocus) applyTvFocus(v) else clearTvFocus(v)
        }
    }

    private fun applyTvFocus(view: View) {
        val spec = tvFocusSpecs[view] ?: return
        val base = tvFocusBase.getOrPut(view) { view.background }
        val border = GradientDrawable().apply {
            cornerRadius = dp(spec.cornerRadius).toFloat()
            setStroke(dp(3), spec.borderColor)
        }
        view.background = if (base == null) border else LayerDrawable(arrayOf(base, border))
        if (spec.scale > 1f) {
            view.animate().scaleX(spec.scale).scaleY(spec.scale).setDuration(120).start()
        }
    }

    private fun clearTvFocus(view: View) {
        tvFocusBase[view]?.let { view.background = it }
        val spec = tvFocusSpecs[view]
        if (spec != null && spec.scale > 1f) {
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
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

    /** Current weekday name in the site timezone. */
    private fun currentDayName(): String {
        val dow = Calendar.getInstance(siteTimeZone).get(Calendar.DAY_OF_WEEK)
        return DAY_TO_CALENDAR.entries.firstOrNull { it.value == dow }?.key ?: "Monday"
    }

    /** Parses "08:45 PM" / "8:00 PM" / "20:45" into (hour, minute). */
    private fun parseReleaseTime(raw: String?): Pair<Int, Int>? {
        val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val m = Regex("""(\d{1,2})[:.](\d{2})\s*([AaPp][Mm])?""").find(s) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        when (m.groupValues[3]?.uppercase()) {
            "PM" -> if (hour < 12) hour += 12
            "AM" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** yyyy-MM-dd of "now" in the site timezone. */
    private fun todayKey(): String {
        val cal = Calendar.getInstance(siteTimeZone)
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** yyyy-MM-dd (site timezone) of the next occurrence of a weekday tab, today included. */
    private fun dateKeyForDay(day: String): String {
        val cal = Calendar.getInstance(siteTimeZone)
        val targetDow = DAY_TO_CALENDAR[day] ?: return todayKey()
        cal.add(Calendar.DAY_OF_YEAR, (targetDow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7)
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Epoch millis of a yyyy-MM-dd date at the given (hour, minute), site timezone. */
    private fun targetMillisFor(dateKey: String, time: Pair<Int, Int>?): Long? {
        time ?: return null
        val p = dateKey.trim().split("-")
        val year = p.getOrNull(0)?.toIntOrNull() ?: return null
        val month = p.getOrNull(1)?.toIntOrNull() ?: return null
        val day = p.getOrNull(2)?.toIntOrNull() ?: return null
        val cal = Calendar.getInstance(siteTimeZone)
        cal.clear()
        cal.set(year, month - 1, day, time.first, time.second, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Episode numbers (1-based) releasing on a yyyy-MM-dd date; index 0 = episode 1. */
    private fun episodesOnDate(item: ScheduleItem, dateKey: String): List<Int> {
        val schedule = item.episodeSchedule ?: return emptyList()
        return schedule.mapIndexedNotNull { index, d ->
            if (d.trim().take(10) == dateKey) index + 1 else null
        }
    }

    /** First upcoming (today or later) release date that still has an unreleased episode. */
    private fun nextUnreleasedDateKey(item: ScheduleItem, currentEp: Int): String? {
        val schedule = item.episodeSchedule ?: return null
        val today = todayKey()
        for (i in schedule.indices) {
            if (i + 1 > currentEp && schedule[i].trim().take(10) >= today) {
                return schedule[i].trim().take(10)
            }
        }
        return null
    }

    /**
     * Epoch millis of the next release for the given weekday at the given
     * time. Returns the target together with whether that weekday is the
     * current day (today uses the plain clock format and can be "Released").
     */
    private fun releaseTarget(dayName: String, timeRaw: String?): Pair<Long, Boolean>? {
        val time = parseReleaseTime(timeRaw) ?: return null
        val targetDow = DAY_TO_CALENDAR[dayName] ?: return null
        val nowCal = Calendar.getInstance(siteTimeZone)
        val daysAhead = (targetDow - nowCal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        nowCal.add(Calendar.DAY_OF_YEAR, daysAhead)
        nowCal.set(Calendar.HOUR_OF_DAY, time.first)
        nowCal.set(Calendar.MINUTE, time.second)
        nowCal.set(Calendar.SECOND, 0)
        nowCal.set(Calendar.MILLISECOND, 0)
        return nowCal.timeInMillis to (daysAhead == 0)
    }

    /** True when the item matches the weekly release_day pattern (daily shows every day). */
    private fun matchesReleaseDay(item: ScheduleItem, day: String): Boolean {
        val d = item.releaseDay?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return d.equals(day, true) || d.equals("Daily", true)
    }

    /**
     * True when the item belongs on the given weekday tab. The per-episode
     * release dates (episode_schedule) are authoritative; only items without
     * them fall back to the weekly release_day pattern. Tabs beyond the last
     * scheduled date keep the weekly pattern visible.
     */
    private fun showsOnDay(item: ScheduleItem, day: String, dateKey: String): Boolean {
        val daily = item.releaseDay?.equals("Daily", true) == true ||
            item.frequency?.equals("daily", true) == true
        val schedule = item.episodeSchedule
        if (schedule.isNullOrEmpty()) return matchesReleaseDay(item, day) || daily
        if (episodesOnDate(item, dateKey).isNotEmpty()) return true
        if (daily) return true
        val last = schedule.maxOfOrNull { it.trim().take(10) }
        return last != null && dateKey > last && matchesReleaseDay(item, day)
    }

    private fun startTicks() {
        stopTicks()
        tickHandler.post(tickRunnable)
    }

    private fun stopTicks() {
        tickHandler.removeCallbacks(tickRunnable)
    }

    /** Refreshes every countdown chip on screen. */
    @SuppressLint("SetTextI18n")
    private fun updateTimerChips() {
        val now = System.currentTimeMillis()
        timerChips.forEach { chip ->
            val seconds = (chip.targetMillis - now) / 1000
            if (seconds <= 0) {
                chip.view.text = "Released"
                chip.view.setTextColor(RELEASED_GREEN)
                chip.view.background = round(RELEASED_BG, 8)
                return@forEach
            }
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            // Day prefix only when at least a full day is left; under 24 h
            // the chip shows a plain clock instead of an ugly "0d" prefix
            chip.view.text = when {
                chip.isToday || h < 24 -> String.format("%02d:%02d:%02d", h, m, s)
                else -> String.format("%dd %02d:%02d:%02d", h / 24, h % 24, m, s)
            }
            chip.view.setTextColor(ACCENT)
            chip.view.background = round(ACCENT_SOFT, 8)
        }
    }

    /** "Nov 10" style label of a yyyy-MM-dd key. */
    private fun prettyDateKey(key: String): String {
        val p = key.split("-")
        val month = p.getOrNull(1)?.toIntOrNull() ?: return key
        val day = p.getOrNull(2)?.toIntOrNull() ?: return key
        val name = MONTHS.getOrNull(month - 1) ?: return key
        return "$name $day"
    }

    /**
     * Binds the countdown chip of a card. Everything is derived from the
     * per-episode release dates (episode_schedule) and current_ep:
     *   - episodes of the selected tab date above current_ep are pending
     *     -> live countdown to that date at release_time
     *   - when those episodes are already out -> "Released"
     *   - items without episode_schedule keep the weekly pattern countdown
     */
    private fun bindTimer(timerView: TextView, item: ScheduleItem) {
        fun static(label: String, fg: Int, bg: Int) {
            timerView.text = label
            timerView.setTextColor(fg)
            timerView.background = round(bg, 8)
        }

        if (item.isPaused == 1) {
            static("Paused", TEXT_SECONDARY, CARD)
            return
        }

        val currentEp = item.currentEp ?: 0
        val schedule = item.episodeSchedule
        val total = maxOf(item.totalEps ?: 0, schedule?.size ?: 0)
        if (total > 0 && currentEp >= total) {
            static("Released", RELEASED_GREEN, RELEASED_BG)
            return
        }

        val eps = episodesOnDate(item, selectedDateKey)
        when {
            eps.isNotEmpty() -> {
                if (eps.all { it <= currentEp }) {
                    // every episode of this date is already out
                    static("Released", RELEASED_GREEN, RELEASED_BG)
                } else {
                    val target = targetMillisFor(selectedDateKey, parseReleaseTime(item.releaseTime))
                    if (target != null) {
                        timerChips.add(TimerChip(timerView, target, selectedDateKey == todayKey()))
                    } else {
                        static("-", TEXT_SECONDARY, CARD)
                    }
                }
            }
            schedule.isNullOrEmpty() -> {
                // no per-episode dates -> fall back to the weekly pattern
                val target = releaseTarget(selectedDay, item.releaseTime)
                if (target != null) {
                    timerChips.add(TimerChip(timerView, target.first, target.second))
                } else {
                    static("-", TEXT_SECONDARY, CARD)
                }
            }
            else -> {
                // nothing scheduled on this tab date -> count down to the next release
                val next = nextUnreleasedDateKey(item, currentEp)
                val last = schedule.maxOfOrNull { it.trim().take(10) }
                when {
                    next != null -> {
                        val target = targetMillisFor(next, parseReleaseTime(item.releaseTime))
                        if (target != null) {
                            timerChips.add(TimerChip(timerView, target, next == todayKey()))
                        } else {
                            static("-", TEXT_SECONDARY, CARD)
                        }
                    }
                    last != null && selectedDateKey > last -> {
                        // tab lies beyond the scheduled range -> weekly pattern
                        val target = releaseTarget(selectedDay, item.releaseTime)
                        if (target != null) {
                            timerChips.add(TimerChip(timerView, target.first, target.second))
                        } else {
                            static("-", TEXT_SECONDARY, CARD)
                        }
                    }
                    else -> static("Released", RELEASED_GREEN, RELEASED_BG)
                }
            }
        }
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
        backButton = ImageView(context).apply {
            setImageDrawable(BackIcon())
            background = round(CARD, 24)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        // Android TV: visible D-pad focus on the back button
        enableTvFocus(backButton, 24, scale = 1.08f)
        bar.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titleCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(text("MY ANIMES", 11f, TEXT_SECONDARY, true).apply { letterSpacing = 0.12f })
        titleCol.addView(text("Schedule", 21f, TEXT_PRIMARY, true))
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
        root.addView(bar)

        root.addView(View(context).apply { setBackgroundColor(Color.parseColor("#222226")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // Weekday selector
        tabsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.BLACK)
        }
        tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        WEEK_DAYS.forEach { day ->
            val tab = TextView(context).apply {
                text = day.take(3).uppercase()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { showDay(day) }
            }
            // Android TV: white border reads well on both the selected
            // (accent) and the idle (dark) tab background
            enableTvFocus(tab, 12, scale = 1.08f, borderColor = Color.WHITE)
            tabViews[day] = tab
            tabsRow.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        tabsScroll.addView(tabsRow)
        root.addView(tabsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(View(context).apply { setBackgroundColor(Color.parseColor("#222226")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val body = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        listScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        listScroll.addView(listContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(listScroll, FrameLayout.LayoutParams(
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
        retryButton = text("Retry", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(ACCENT, 16)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { loadSchedule() }
        }
        // Android TV: visible D-pad focus on the retry action
        enableTvFocus(retryButton, 16, scale = 1.06f, borderColor = Color.WHITE)
        errorBox.addView(retryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        body.addView(errorBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        WEEK_DAYS.forEach { styleTab(it) }

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
        // Android TV: start with the focus on the back button so the user
        // immediately sees where the remote is; in touch mode this is a
        // no-op and nothing is highlighted
        backButton.post { backButton.requestFocus() }
        loadSchedule()
    }

    override fun dismiss() {
        stopTicks()
        super.dismiss()
    }

    private fun styleTab(day: String) {
        val tv = tabViews[day] ?: return
        val selected = day == selectedDay
        tv.setTextColor(if (selected) Color.WHITE else TEXT_SECONDARY)
        tv.background = round(if (selected) ACCENT else CARD_2, 12)
        tv.setPadding(dp(14), dp(7), dp(14), dp(7))
        // Android TV: keep the focus border fresh when the focused tab
        // gets re-styled after a selection change
        if (tv.isFocused && !tv.isInTouchMode) {
            tvFocusBase[tv] = tv.background
            applyTvFocus(tv)
        }
    }

    private fun setLoading(loading: Boolean) {
        loader.visibility = if (loading) View.VISIBLE else View.GONE
        errorBox.visibility = View.GONE
        listContainer.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showError(message: String) {
        stopTicks()
        loader.visibility = View.GONE
        listContainer.visibility = View.GONE
        errorText.text = message
        errorBox.visibility = View.VISIBLE
        // Android TV: hand the remote focus to Retry so it is reachable
        retryButton.requestFocus()
    }

    private fun loadSchedule() {
        setLoading(true)
        stopTicks()
        CoroutineScope(Dispatchers.Main).launch {
            val response = runCatching {
                app.get(SCHEDULE_URL, referer = "https://myanimes.in/").parsedSafe<ScheduleResponse>()
            }.getOrNull()
            if (!isShowing) return@launch
            val items = response?.items
            if (items.isNullOrEmpty()) {
                showError("Could not load the schedule.\nCheck your connection and try again.")
            } else {
                response.timezone?.takeIf { it.isNotBlank() }?.let {
                    siteTimeZone = TimeZone.getTimeZone(it.trim())
                }
                timeZoneNote = response.timezone?.takeIf { it.isNotBlank() }
                    ?.let { "Times shown in $it" }
                allItems = items
                selectedDay = currentDayName()
                WEEK_DAYS.forEach { styleTab(it) }
                setLoading(false)
                showDay(selectedDay)
                startTicks()
                // keep the selected weekday tab in view
                tabViews[selectedDay]?.post {
                    tabViews[selectedDay]?.let { tv ->
                        tabsScroll.smoothScrollTo(maxOf(0, tv.left - dp(60)), 0)
                    }
                }
            }
        }
    }

    /** Shows the releases of the selected weekday. */
    private fun showDay(day: String) {
        if (allItems.isEmpty()) return
        selectedDay = day
        selectedDateKey = dateKeyForDay(day)
        WEEK_DAYS.forEach { styleTab(it) }

        timerChips.clear()
        listContainer.removeAllViews()
        listScroll.scrollTo(0, 0)

        listContainer.addView(text("${day.uppercase()}  •  ${prettyDateKey(selectedDateKey)}", 13f, ACCENT, true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4), dp(2), dp(4), dp(2))
        })
        timeZoneNote?.let {
            listContainer.addView(text(it, 12f, TEXT_SECONDARY).apply {
                setPadding(dp(4), 0, dp(4), dp(10))
            })
        }

        val dayItems = allItems
            .filter { showsOnDay(it, day, selectedDateKey) }
            // earliest release first; items without a time go last
            .sortedBy { item ->
                parseReleaseTime(item.releaseTime)?.let { (h, m) -> h * 60 + m }
                    ?: Int.MAX_VALUE
            }
        if (dayItems.isEmpty()) {
            listContainer.addView(text("No releases scheduled for this day.", 13f, TEXT_SECONDARY).apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        } else {
            dayItems.forEach { item ->
                listContainer.addView(scheduleCard(item), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })
            }
        }
        updateTimerChips()
    }

    @SuppressLint("SetTextI18n")
    private fun scheduleCard(item: ScheduleItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(CARD_2, 18)
            // Android TV: rows are D-pad focusable so the whole list can
            // be browsed with the remote; touch devices never show this
            isFocusable = true
        }

        // Poster with rounded corners (bitmap clipped by the view outline)
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            background = round(CARD, 10)
        }
        card.addView(poster, LinearLayout.LayoutParams(dp(58), dp(86)))

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Live countdown chip on top of the card
        val timerView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        bindTimer(timerView, item)
        col.addView(timerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

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
        // EP line follows the selected tab date: one episode, a range when
        // several episodes share the date, or the released count as fallback
        val epsOnDay = episodesOnDate(item, selectedDateKey)
        val epPart = buildString {
            when {
                epsOnDay.isEmpty() -> append("EP ${item.currentEp ?: 0}")
                epsOnDay.size == 1 -> append("EP ${epsOnDay.first()}")
                else -> append("EP ${epsOnDay.first()}–${epsOnDay.last()}")
            }
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

        // wrap content so a long two-line title grows the card instead of
        // pushing the release time out of the visible area
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })

        item.posterUrl?.takeIf { it.isNotBlank() }?.let { url -> loadPoster(url, poster) }

        // Android TV: clear accent border while the row is focused
        enableTvFocus(card, 18)

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
