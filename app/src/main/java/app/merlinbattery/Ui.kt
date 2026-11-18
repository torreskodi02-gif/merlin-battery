package app.merlinbattery

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Shared colours / builders so all five tabs look like one app. Dark-first. */
object Ui {
    val BG = Color.parseColor("#0B0F14")
    val SURFACE = Color.parseColor("#151C24")
    val SURFACE_RAISED = Color.parseColor("#1D2632")
    // Borders are ALWAYS shown — Kodi's rule: never a borderless card, pill,
    // gauge track or nav bar, in any state (selected, unselected, empty,
    // loading, dark or light). Border is 2dp and bright enough to see.
    val BORDER = Color.parseColor("#3A4A5C")
    const val BORDER_DP = 2
    val TEXT = Color.parseColor("#F2F5F7")
    val TEXT2 = Color.parseColor("#A8B3BF")
    val MUTED = Color.parseColor("#6B7885")
    val GREEN = Color.parseColor("#00E5A0")
    val BLUE = Color.parseColor("#38BDF8")
    val AMBER = Color.parseColor("#FFB020")
    val RED = Color.parseColor("#FF5C5C")
    val ORANGE = Color.parseColor("#FF8A65")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun card(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            background = GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(ctx, 20).toFloat()
                setStroke(dp(ctx, BORDER_DP), BORDER) // always shown, never removed
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(ctx, 12))
        l.layoutParams = lp
        return l
    }

    fun title(ctx: Context, text: String, sizeSp: Float = 18f): TextView =
        TextView(ctx).apply {
            this.text = text; textSize = sizeSp
            setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(ctx, 8))
        }

    fun label(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text; textSize = 12f; setTextColor(MUTED)
            isAllCaps = true
        }

    fun value(ctx: Context, text: String, sizeSp: Float = 28f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            this.text = text; textSize = sizeSp; setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
        }

    fun body(ctx: Context, text: String, sizeSp: Float = 15f, color: Int = TEXT2): TextView =
        TextView(ctx).apply { this.text = text; textSize = sizeSp; setTextColor(color) }

    fun row(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    fun statCard(ctx: Context, labelText: String, valueText: String, subText: String, valueColor: Int = TEXT): LinearLayout {
        val c = card(ctx)
        c.addView(label(ctx, labelText))
        c.addView(value(ctx, valueText, 24f, valueColor))
        c.addView(body(ctx, subText, 12f, MUTED))
        val lp = c.layoutParams as LinearLayout.LayoutParams
        lp.weight = 1f; lp.width = 0
        lp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 12))
        c.layoutParams = lp
        return c
    }

    fun pill(ctx: Context, text: String, color: Int): TextView =
        TextView(ctx).apply {
            this.text = text; textSize = 13f; setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            background = GradientDrawable().apply {
                setColor(SURFACE_RAISED); cornerRadius = dp(ctx, 20).toFloat()
                setStroke(dp(ctx, BORDER_DP), color) // always shown, never removed
            }
        }

    fun healthColor(healthPct: Float?): Int = when {
        healthPct == null -> MUTED
        healthPct >= 85f -> GREEN
        healthPct >= 70f -> AMBER
        else -> RED
    }

    fun healthWord(healthPct: Float?): String = when {
        healthPct == null -> "Learning"
        healthPct >= 85f -> "Good"
        healthPct >= 70f -> "Fair"
        else -> "Poor"
    }

    fun tempColor(tempC: Float?): Int = when {
        tempC == null -> MUTED
        tempC < 30f -> BLUE
        tempC < 40f -> GREEN
        tempC < 45f -> AMBER
        else -> RED
    }

    fun formatMinutes(min: Int?): String = when {
        min == null -> "—"
        min < 60 -> "$min min"
        else -> "${min / 60}h ${min % 60}m"
    }
}
