package dev.iskyd.dok2.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import dev.iskyd.dok2.domain.model.TrackPoint
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

/**
 * Renders the "Share image" PNG for a finished track: a 1080x1350 canvas with a transparent
 * background, the recorded route as a polyline in the brand green, the total distance / elevation
 * gain / duration across the top, and the app glyph in the brand green above the "dok2" wordmark at
 * the bottom right. Brand-green elements carry a dark outline, text a soft shadow, so they read on
 * whatever background the transparent PNG is placed on. The caller owns recycling the returned
 * bitmap.
 */
object StatsImageRenderer {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val MARGIN = 80f
    private const val FG_COLOR = Color.WHITE
    private const val LOGO_GREEN = 0xFF1B4D3E.toInt()
    private const val OUTLINE_COLOR = 0xCC000000.toInt()
    private const val TEXT_SHADOW_COLOR = 0x99000000.toInt()

    fun render(
        points: List<TrackPoint>,
        distanceM: Double,
        gainM: Double?,
        elapsedTimeS: Long,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawStats(canvas, distanceM, gainM, elapsedTimeS)
        drawRoute(canvas, points)
        drawLogo(canvas)
        return bitmap
    }

    private fun drawStats(canvas: Canvas, distanceM: Double, gainM: Double?, elapsedTimeS: Long) {
        val colWidth = (WIDTH - 2 * MARGIN) / 3
        val labelPaint = textPaint(44f, Typeface.DEFAULT)
        val labels = listOf("DISTANCE", "ELEV GAIN", "DURATION")
        for (i in labels.indices) {
            val centerX = MARGIN + colWidth * (i + 0.5f)
            canvas.drawText(
                labels[i],
                centerX - labelPaint.measureText(labels[i]) / 2f,
                150f,
                labelPaint,
            )
        }

        val valuePaint = textPaint(88f, Typeface.DEFAULT_BOLD)
        val values =
            listOf(
                formatDistance(distanceM),
                gainM?.let { "▲ " + formatDistance(it) } ?: "—",
                formatDuration(elapsedTimeS),
            )
        for (i in values.indices) {
            val centerX = MARGIN + colWidth * (i + 0.5f)
            var textSize = 88f
            valuePaint.textSize = textSize
            while (valuePaint.measureText(values[i]) > colWidth - 40f && textSize > 24f) {
                textSize -= 4f
                valuePaint.textSize = textSize
            }
            canvas.drawText(
                values[i],
                centerX - valuePaint.measureText(values[i]) / 2f,
                240f,
                valuePaint,
            )
        }
    }

    private fun drawRoute(canvas: Canvas, points: List<TrackPoint>) {
        if (points.size < 2) return

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.latDeg < minLat) minLat = p.latDeg
            if (p.latDeg > maxLat) maxLat = p.latDeg
            if (p.lonDeg < minLon) minLon = p.lonDeg
            if (p.lonDeg > maxLon) maxLon = p.lonDeg
        }

        // Equirectangular projection: longitude is scaled by cos(midLat) so degrees map to a
        // roughly metric-aspect space. The clamped cos keeps the scale finite near the poles.
        val cosMid = cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        val spanX = (maxLon - minLon) * cosMid
        val spanY = maxLat - minLat
        if (spanX == 0.0 && spanY == 0.0) return

        val boxLeft = MARGIN
        val boxTop = 300f
        val boxRight = WIDTH - MARGIN
        val boxBottom = HEIGHT - 450f
        val boxWidth = boxRight - boxLeft
        val boxHeight = boxBottom - boxTop

        // Uniform scale so the track keeps its aspect; a zero span (a degenerate straight line)
        // just contributes its box dimension, yielding a centred vertical or horizontal line.
        val scale =
            when {
                spanX == 0.0 -> boxHeight.toDouble() / spanY
                spanY == 0.0 -> boxWidth.toDouble() / spanX
                else -> min(boxWidth.toDouble() / spanX, boxHeight.toDouble() / spanY)
            }
        val offsetX = (boxWidth.toDouble() - spanX * scale) / 2
        val offsetY = (boxHeight.toDouble() - spanY * scale) / 2

        val path = Path()
        for (i in points.indices) {
            val p = points[i]
            // screenY subtracts so latitude (which grows north) ends up at the top of the box; the
            // offsets are subtracted too so the fitted shape centres vertically when it does not
            // fill the box height.
            val screenX =
                (boxLeft + (p.lonDeg * cosMid - minLon * cosMid) * scale + offsetX).toFloat()
            val screenY = (boxBottom - (p.latDeg - minLat) * scale - offsetY).toFloat()
            if (i == 0) {
                path.moveTo(screenX, screenY)
            } else {
                path.lineTo(screenX, screenY)
            }
        }

        // Two passes: a thick dark outline underneath the brand-green stroke makes the track read
        // on any background the transparent PNG is placed on.
        canvas.drawPath(path, strokePaint(36f, OUTLINE_COLOR))
        canvas.drawPath(path, strokePaint(26f, LOGO_GREEN))
    }

    private fun drawLogo(canvas: Canvas) {
        val wordPaint = textPaint(40f, Typeface.DEFAULT_BOLD)
        val wordRight = WIDTH - MARGIN
        val baseline = HEIGHT - MARGIN
        val wordWidth = wordPaint.measureText("dok2")
        val wordLeft = wordRight - wordWidth
        canvas.drawText("dok2", wordLeft, baseline, wordPaint)

        val glyphScale = 200f / 108f
        val glyphPath =
            Path().apply {
                moveTo(30f * glyphScale, 72f * glyphScale)
                lineTo(42f * glyphScale, 48f * glyphScale)
                lineTo(52f * glyphScale, 58f * glyphScale)
                lineTo(66f * glyphScale, 34f * glyphScale)
                lineTo(78f * glyphScale, 44f * glyphScale)
            }
        // The glyph viewport spans x 30..78 and y 34..72, so 54 and 72 are its horizontal centre
        // and bottom. Centre it on the word and sit its bottom a fixed gap above the word's top.
        val glyphCenterX = wordLeft + wordWidth / 2f
        val glyphBottomY = baseline + wordPaint.fontMetrics.ascent - 24f
        glyphPath.offset(glyphCenterX - 54f * glyphScale, glyphBottomY - 72f * glyphScale)

        canvas.drawPath(glyphPath, strokePaint(8f * glyphScale, OUTLINE_COLOR))
        canvas.drawPath(glyphPath, strokePaint(5.5f * glyphScale, LOGO_GREEN))
    }

    private fun textPaint(size: Float, typeface: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            color = FG_COLOR
            setShadowLayer(6f, 0f, 3f, TEXT_SHADOW_COLOR)
        }

    private fun strokePaint(width: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private fun formatDistance(distanceM: Double): String =
        if (distanceM >= 1000) {
            String.format(Locale.ROOT, "%.2f km", distanceM / 1000)
        } else {
            String.format(Locale.ROOT, "%.0f m", distanceM)
        }

    private fun formatDuration(elapsedTimeS: Long): String {
        val hours = elapsedTimeS / 3600
        val minutes = (elapsedTimeS % 3600) / 60
        val seconds = elapsedTimeS % 60
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }
}
