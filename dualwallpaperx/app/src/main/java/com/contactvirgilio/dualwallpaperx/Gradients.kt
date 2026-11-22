package com.contactvirgilio.dualwallpaperx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

enum class Tier { COMMON, RARE, LEGENDARY, UNIQUE }

class Blend(val name: String, val tier: Tier, val colors: IntArray) {
    val sparkle get() = tier == Tier.LEGENDARY || tier == Tier.UNIQUE
}

object Gradients {
    private fun c(vararg v: Long) = v.map { it.toInt() }.toIntArray()

    val specials = listOf(
        Blend("Peach fizz", Tier.RARE, c(0xFFFFB199, 0xFFFF6F91, 0xFFC86DD7)),
        Blend("Lagoon", Tier.RARE, c(0xFF43CEA2, 0xFF185A9D, 0xFF0B2A4A)),
        Blend("Berry", Tier.RARE, c(0xFFF953C6, 0xFFB91D73, 0xFF4A0E4E)),
        Blend("Mint tea", Tier.RARE, c(0xFFD4FC79, 0xFF96E6A1, 0xFF3BB78F)),
        Blend("Dusk", Tier.RARE, c(0xFF2C3E50, 0xFFFD746C, 0xFFFFC371)),
        Blend("Citrus", Tier.RARE, c(0xFFF7FF00, 0xFFFFB347, 0xFFFF5F6D)),
        Blend("Golden hour", Tier.LEGENDARY, c(0xFFFFE259, 0xFFFF7043, 0xFF5D1A3A)),
        Blend("Nebula", Tier.LEGENDARY, c(0xFF0F0C29, 0xFF6A3093, 0xFFF06292)),
        Blend("Northern lights", Tier.LEGENDARY, c(0xFF00F5A0, 0xFF00D9F5, 0xFF1A1446)),
        Blend("Deep sea", Tier.UNIQUE, c(0xFF0B1E3F, 0xFF1B5E8C, 0xFF6FE3C1, 0xFFE8F7A1)),
        Blend("Sakura", Tier.UNIQUE, c(0xFFFFF1F5, 0xFFFFB7C5, 0xFFE0607E, 0xFF3D1E3A)),
    )

    /** 75% common, 18% rare, 5% legendary, 2% unique. */
    fun roll(dark: Boolean, rnd: Random = Random.Default): Blend {
        val r = rnd.nextInt(100)
        val tier = when {
            r < 2 -> Tier.UNIQUE
            r < 7 -> Tier.LEGENDARY
            r < 25 -> Tier.RARE
            else -> Tier.COMMON
        }
        return if (tier == Tier.COMMON) common(dark, rnd) else specials.filter { it.tier == tier }.random(rnd)
    }

    /** Two nearby hues, so the pair blends cleanly. Bright for Light, deep for Dark. */
    private fun common(dark: Boolean, rnd: Random): Blend {
        val h = rnd.nextFloat() * 360f
        val h2 = (h + (30 + rnd.nextInt(60)) * (if (rnd.nextBoolean()) 1 else -1) + 360f) % 360f
        fun col(hue: Float) = Color.HSVToColor(
            floatArrayOf(
                hue,
                0.5f + rnd.nextFloat() * 0.35f,
                if (dark) 0.22f + rnd.nextFloat() * 0.2f else 0.78f + rnd.nextFloat() * 0.2f,
            ),
        )
        return Blend("", Tier.COMMON, intArrayOf(col(h), col(h2)))
    }

    private fun forSlot(color: Int, dark: Boolean): Int {
        if (!dark) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= 0.6f
        return Color.HSVToColor(hsv)
    }

    fun render(w: Int, h: Int, blend: Blend, dir: Int, dark: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = if (blend.tier == Tier.COMMON) blend.colors else blend.colors.map { forSlot(it, dark) }.toIntArray()
        val (x1, y1) = when (dir) {
            0 -> 0f to h.toFloat()
            2 -> w.toFloat() to 0f
            else -> w.toFloat() to h.toFloat()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, x1, y1, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        if (blend.sparkle) drawSparkles(canvas, w, h, blend.name.hashCode())
        return bmp
    }

    /** Drawn into the image once, so sparkles cost nothing when switching. */
    private fun drawSparkles(canvas: Canvas, w: Int, h: Int, seed: Int) {
        val rnd = Random(seed)
        val unit = w / 400f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        repeat(70) {
            p.alpha = 90 + rnd.nextInt(140)
            canvas.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.8f + rnd.nextFloat() * 2f) * unit, p)
        }
        p.strokeWidth = 1.2f * unit
        p.strokeCap = Paint.Cap.ROUND
        repeat(8) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = (5 + rnd.nextFloat() * 7) * unit
            p.alpha = 210
            canvas.drawLine(x - r, y, x + r, y, p)
            canvas.drawLine(x, y - r, x, y + r, p)
            canvas.drawCircle(x, y, 1.6f * unit, p)
        }
    }
}
