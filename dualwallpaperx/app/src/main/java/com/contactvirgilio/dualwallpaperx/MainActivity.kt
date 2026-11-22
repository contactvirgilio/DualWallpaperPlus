package com.contactvirgilio.dualwallpaperx

import android.Manifest
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var p: Prefs
    private lateinit var root: View
    private lateinit var status: TextView
    private lateinit var timeCard: View
    private lateinit var darkFrom: TextView
    private lateinit var lightFrom: TextView
    private lateinit var lightPreview: ImageView
    private lateinit var darkPreview: ImageView

    private val io = Executors.newSingleThreadExecutor()

    /** The slot a picker is choosing for. */
    private var pendingDark = false
    private var pendingLock = false

    /** Open edit sheet: where Snackbars go and what to refresh after a save. */
    private var sheetHost: View? = null
    private var onSaved: (() -> Unit)? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) saveAsync(pendingDark, pendingLock) { Store.decode(this, uri) }
    }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useCurrent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        p = Prefs(this)
        pendingDark = savedInstanceState?.getBoolean("pd") ?: false
        pendingLock = savedInstanceState?.getBoolean("pl") ?: false

        root = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        status = findViewById(R.id.status)
        timeCard = findViewById(R.id.timeCard)
        darkFrom = findViewById(R.id.darkFrom)
        lightFrom = findViewById(R.id.lightFrom)
        lightPreview = findViewById(R.id.lightPreview)
        darkPreview = findViewById(R.id.darkPreview)

        val enabledSwitch = findViewById<MaterialSwitch>(R.id.enabledSwitch)
        enabledSwitch.isChecked = p.enabled
        enabledSwitch.setOnCheckedChangeListener { v, on ->
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            p.enabled = on
            p.lastApplied = -1
            if (on && p.timeMode) askExactIfNeeded()
            refresh()
        }

        val modeGroup = findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        modeGroup.check(if (p.timeMode) R.id.modeTime else R.id.modeTheme)
        timeCard.isVisible = p.timeMode
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            p.timeMode = id == R.id.modeTime
            timeCard.isVisible = p.timeMode
            if (p.timeMode && p.enabled) askExactIfNeeded()
            refresh()
        }

        findViewById<View>(R.id.darkRow).setOnClickListener { pickTime(p.darkStart) { p.darkStart = it; refresh() } }
        findViewById<View>(R.id.lightRow).setOnClickListener { pickTime(p.darkEnd) { p.darkEnd = it; refresh() } }
        findViewById<View>(R.id.lightCard).setOnClickListener { openEdit(false) }
        findViewById<View>(R.id.darkCard).setOnClickListener { openEdit(true) }
        findViewById<View>(R.id.switchNow).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (!p.enabled) {
                snack(getString(R.string.turn_on_first))
                return@setOnClickListener
            }
            refresh(force = true) { snack(getString(R.string.switched)) }
        }
        status.setOnClickListener { if (p.timeMode && !Switcher.canExact(this)) askExactIfNeeded() }

        updateTimes()
        updatePreviews()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pd", pendingDark)
        outState.putBoolean("pl", pendingLock)
    }

    // ---- switching and status

    private fun refresh(force: Boolean = false, done: (() -> Unit)? = null) {
        updateTimes()
        Switcher.refreshAsync(this, force) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updateStatus()
                done?.invoke()
            }
        }
    }

    private fun updateStatus() {
        val lines = mutableListOf<String>()
        if (!p.enabled) {
            lines += getString(R.string.status_off)
        } else {
            lines += getString(if (Switcher.isDarkNow(this)) R.string.status_now_dark else R.string.status_now_light)
            if (p.lastSwitch > 0) lines += getString(R.string.status_last, dayTime(p.lastSwitch))
            if (p.timeMode && p.nextSwitch > 0) {
                lines += getString(
                    if (p.nextExact) R.string.status_next_exact else R.string.status_next_inexact,
                    dayTime(p.nextSwitch),
                )
            }
            if (!p.timeMode) lines += getString(R.string.status_follows_theme)
            if (!Store.has(this, true) || !Store.has(this, false)) lines += getString(R.string.status_missing)
        }
        status.text = lines.joinToString("\n")
    }

    private fun dayTime(ms: Long) = DateUtils.formatDateTime(
        this, ms,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
    )

    private fun clock(minutes: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun updateTimes() {
        darkFrom.text = clock(p.darkStart)
        lightFrom.text = clock(p.darkEnd)
    }

    private fun pickTime(minutes: Int, set: (Int) -> Unit) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(minutes / 60)
            .setMinute(minutes % 60)
            .build()
        picker.addOnPositiveButtonClickListener { set(picker.hour * 60 + picker.minute) }
        picker.show(supportFragmentManager, "time")
    }

    private fun askExactIfNeeded() {
        if (Build.VERSION.SDK_INT < 31 || Switcher.canExact(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exact_title)
            .setMessage(R.string.exact_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // ---- previews and editing

    private fun updatePreviews() {
        val w = resources.displayMetrics.widthPixels / 3
        io.execute {
            val l = Store.thumb(Store.file(this, false, false), w)
            val d = Store.thumb(Store.file(this, true, false), w)
            runOnUiThread {
                setPreview(lightPreview, l)
                setPreview(darkPreview, d)
            }
        }
    }

    private fun setPreview(v: ImageView, b: Bitmap?) {
        if (b != null) {
            v.scaleType = ImageView.ScaleType.CENTER_CROP
            v.setImageBitmap(b)
        } else {
            v.scaleType = ImageView.ScaleType.CENTER
            v.setImageResource(R.drawable.ic_add)
        }
    }

    private fun openEdit(dark: Boolean) {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_edit, null)
        sheet.setContentView(v)
        v.findViewById<TextView>(R.id.sheetTitle)
            .setText(if (dark) R.string.dark_wallpaper else R.string.light_wallpaper)

        val homeThumb = v.findViewById<ImageView>(R.id.homeThumb)
        val lockThumb = v.findViewById<ImageView>(R.id.lockThumb)
        val lockRow = v.findViewById<View>(R.id.lockRow)
        val same = v.findViewById<MaterialSwitch>(R.id.sameSwitch)
        val tw = dp(44)
        val bindThumbs = {
            io.execute {
                val h = Store.thumb(Store.file(this, dark, false), tw)
                val l = Store.thumb(Store.file(this, dark, true), tw)
                runOnUiThread {
                    homeThumb.setImageBitmap(h)
                    lockThumb.setImageBitmap(l)
                }
            }
        }
        same.isChecked = p.lockSame(dark)
        lockRow.isVisible = !same.isChecked
        same.setOnCheckedChangeListener { _, on ->
            p.setLockSame(dark, on)
            lockRow.isVisible = !on
            io.execute { slotChanged(dark) }
        }
        v.findViewById<View>(R.id.homeChange).setOnClickListener { chooseSource(it, dark, false) }
        v.findViewById<View>(R.id.lockChange).setOnClickListener { chooseSource(it, dark, true) }

        sheetHost = v
        onSaved = bindThumbs
        sheet.setOnDismissListener {
            sheetHost = null
            onSaved = null
        }
        bindThumbs()
        sheet.show()
    }

    private fun chooseSource(anchor: View, dark: Boolean, lock: Boolean) {
        pendingDark = dark
        pendingLock = lock
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.src_photo)
            menu.add(0, 2, 1, R.string.src_current)
            menu.add(0, 3, 2, R.string.src_colour)
            menu.add(0, 4, 3, R.string.src_gradient)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    2 -> useCurrent()
                    3 -> pickColour(dark, lock)
                    4 -> openGradient(dark, lock)
                }
                true
            }
            show()
        }
    }

    private fun useCurrent() {
        if (Build.VERSION.SDK_INT < 33 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        saveAsync(pendingDark, pendingLock) { WallpaperManager.getInstance(this).drawable?.toBitmap() }
    }

    private val swatches = longArrayOf(
        0xFF000000, 0xFF263238, 0xFF607D8B, 0xFFECEFF1,
        0xFF006A63, 0xFF00B8D4, 0xFF2F80ED, 0xFF3949AB,
        0xFF7E57C2, 0xFFE8588B, 0xFFD32F2F, 0xFFF28C28,
        0xFFFFC107, 0xFF8BC34A, 0xFF2E9E5B, 0xFF795548,
    ).map { it.toInt() }

    private fun pickColour(dark: Boolean, lock: Boolean) {
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        lateinit var dialog: AlertDialog
        swatches.forEach { col ->
            val dot = View(this)
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col)
                setStroke(dp(1), 0x33808080)
            }
            dot.layoutParams = GridLayout.LayoutParams().apply {
                width = dp(48)
                height = dp(48)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            dot.setOnClickListener {
                dialog.dismiss()
                saveAsync(dark, lock) {
                    val s = Store.screenSize(this)
                    Bitmap.createBitmap(s.x, s.y, Bitmap.Config.ARGB_8888).apply { eraseColor(col) }
                }
            }
            grid.addView(dot)
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.src_colour)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tierLabel(t: Tier) = getString(
        when (t) {
            Tier.COMMON -> R.string.tier_common
            Tier.RARE -> R.string.tier_rare
            Tier.LEGENDARY -> R.string.tier_legendary
            Tier.UNIQUE -> R.string.tier_unique
        },
    )

    private fun openGradient(dark: Boolean, lock: Boolean) {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_gradient, null)
        sheet.setContentView(v)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val preview = v.findViewById<ImageView>(R.id.gradPreview)
        val tierChip = v.findViewById<Chip>(R.id.tierChip)
        val dirGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.dirGroup)
        val row = v.findViewById<LinearLayout>(R.id.collectionRow)
        val count = v.findViewById<TextView>(R.id.collectionCount)
        val screen = Store.screenSize(this)
        var current = Gradients.roll(dark)
        var dir = p.gradientDir

        fun show() {
            preview.setImageBitmap(Gradients.render(screen.x / 4, screen.y / 4, current, dir, dark))
            tierChip.text = if (current.tier == Tier.COMMON) {
                tierLabel(Tier.COMMON)
            } else {
                getString(R.string.tier_named, tierLabel(current.tier), current.name)
            }
        }

        fun fillCollection() {
            row.removeAllViews()
            val found = p.collection
            count.text = getString(R.string.collection_count, found.size, Gradients.specials.size)
            Gradients.specials.filter { it.name in found }.forEach { b ->
                val thumb = ImageView(this)
                thumb.layoutParams = LinearLayout.LayoutParams(dp(40), dp(68)).apply { marginEnd = dp(8) }
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                thumb.outlineProvider = ViewOutlineProvider.BACKGROUND
                thumb.clipToOutline = true
                thumb.contentDescription = b.name
                thumb.setImageBitmap(Gradients.render(dp(40), dp(68), b, dir, dark))
                thumb.setOnClickListener {
                    current = b
                    show()
                }
                row.addView(thumb)
            }
        }

        fun discovered(b: Blend) {
            if (b.tier == Tier.COMMON || b.name in p.collection) return
            p.collection = p.collection + b.name
            fillCollection()
            Snackbar.make(v, getString(R.string.new_find, tierLabel(b.tier), b.name), Snackbar.LENGTH_SHORT).show()
        }

        dirGroup.check(
            when (dir) {
                0 -> R.id.dirV
                2 -> R.id.dirH
                else -> R.id.dirD
            },
        )
        dirGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            dir = when (id) {
                R.id.dirV -> 0
                R.id.dirH -> 2
                else -> 1
            }
            p.gradientDir = dir
            show()
            fillCollection()
        }
        v.findViewById<View>(R.id.shuffle).setOnClickListener { b ->
            b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            current = Gradients.roll(dark)
            show()
            discovered(current)
        }
        v.findViewById<View>(R.id.useGradient).setOnClickListener {
            val blend = current
            val d = dir
            sheet.dismiss()
            saveAsync(dark, lock) { Gradients.render(screen.x, screen.y, blend, d, dark) }
        }

        show()
        fillCollection()
        sheet.show()
        v.post { discovered(current) }
    }

    // ---- saving

    private fun saveAsync(dark: Boolean, lock: Boolean, make: () -> Bitmap?) {
        io.execute {
            val bmp = try {
                make()
            } catch (e: SecurityException) {
                runOnUiThread { snack(getString(R.string.current_blocked)) }
                return@execute
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                runOnUiThread { snack(getString(R.string.cant_open)) }
                return@execute
            }
            Store.save(this, dark, lock, bmp)
            slotChanged(dark)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updatePreviews()
                onSaved?.invoke()
                updateStatus()
                snack(getString(if (dark) R.string.saved_dark else R.string.saved_light))
            }
        }
    }

    /** If the changed side is the one showing now, put the new version up straight away. */
    private fun slotChanged(dark: Boolean) {
        if (p.enabled && Switcher.isDarkNow(this) == dark) Switcher.sync(applicationContext, force = true)
    }

    private fun snack(msg: String) {
        Snackbar.make(sheetHost ?: root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).roundToInt()
}
