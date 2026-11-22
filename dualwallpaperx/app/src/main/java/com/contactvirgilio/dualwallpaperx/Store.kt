package com.contactvirgilio.dualwallpaperx

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Wallpapers are cropped to the screen once, when saved, so a switch only hands a ready file to Android.
 */
object Store {
    fun file(c: Context, dark: Boolean, lock: Boolean) =
        File(c.filesDir, (if (dark) "dark" else "light") + (if (lock) "_lock.jpg" else "_home.jpg"))

    fun has(c: Context, dark: Boolean) = file(c, dark, false).exists()

    /** Portrait screen size in pixels. */
    @Suppress("DEPRECATION")
    fun screenSize(c: Context): Point {
        val wm = c.getSystemService(WindowManager::class.java)
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            w = p.x; h = p.y
        }
        return Point(min(w, h), max(w, h))
    }

    /** Decodes a picked photo at just enough resolution to cover the screen. */
    fun decode(c: Context, uri: Uri): Bitmap? {
        val size = screenSize(c)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size.x && bounds.outHeight / (sample * 2) >= size.y) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun cropToScreen(c: Context, src: Bitmap): Bitmap {
        val size = screenSize(c)
        if (src.width == size.x && src.height == size.y) return src
        val scale = max(size.x.toFloat() / src.width, size.y.toFloat() / src.height)
        val out = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size.x - src.width * scale) / 2f, (size.y - src.height * scale) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun save(c: Context, dark: Boolean, lock: Boolean, bmp: Bitmap) {
        val out = cropToScreen(c, bmp)
        val f = file(c, dark, lock)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        tmp.renameTo(f)
    }

    /** Sets the home (and lock) wallpaper for one side. Does file I/O: call off the main thread. */
    fun apply(c: Context, dark: Boolean): Boolean {
        val home = file(c, dark, false)
        if (!home.exists()) return false
        val wm = WallpaperManager.getInstance(c)
        val lock = file(c, dark, true)
        if (Prefs(c).lockSame(dark) || !lock.exists()) {
            home.inputStream().use {
                wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            }
        } else {
            home.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
            lock.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_LOCK) }
        }
        return true
    }

    fun thumb(f: File, width: Int): Bitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = max(1, bounds.outWidth / max(1, width)) }
        return BitmapFactory.decodeFile(f.path, opts)
    }
}
