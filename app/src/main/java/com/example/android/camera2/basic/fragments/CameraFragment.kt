/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reilandeubank.unprocess.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.content.res.ColorStateList
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.navigation.fragment.navArgs
import com.reilandeubank.unprocess.utils.computeExifOrientation
import com.reilandeubank.unprocess.utils.getPreviewOutputSize
import com.reilandeubank.unprocess.utils.OrientationLiveData
import com.reilandeubank.unprocess.CameraActivity
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.Date
import java.util.Locale
import kotlin.RuntimeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.params.RggbChannelVector

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the active camera */
    private lateinit var characteristics: CameraCharacteristics

    /** Currently open camera id */
    private lateinit var activeCameraId: String

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Performs recording animation of flashing screen */
    private val animationTask: Runnable by lazy {
        Runnable {
            // Flash white animation
            fragmentCameraBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            // Wait for ANIMATION_FAST_MILLIS
            fragmentCameraBinding.overlay.postDelayed({
                // Remove white flash animation
                fragmentCameraBinding.overlay.background = null
            }, CameraActivity.ANIMATION_FAST_MILLIS)
        }
    }

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    /** Reader for HAL JPEG stills (used when the user picks JPEG output) */
    private lateinit var jpegReader: ImageReader

    // ---- manual control state, applied to preview AND still requests ----
    private var zoomRatio = 1f
    private var wbKelvin = 0           // 0 = auto
    private var focusDiopter = 0f      // 0 = continuous AF
    private var isoValue: Int? = null      // null = auto ISO
    private var shutterDenom: Int? = null  // null = auto shutter
    private var formatJpeg = false     // default: RAW (unprocess's spirit)
    private var capturing = false
    private var switching = false
    private var focusRegion: MeteringRectangle? = null

    private val proMode: Boolean get() = isoValue != null || shutterDenom != null
    private val iso: Int get() = isoValue ?: 100

    // ---- Catppuccin theming (Mocha, Macchiato, Frappe, Latte) ----
    private var paletteIndex = 0

    private data class Palette(
        val name: String, val panel: Int, val chip: Int, val chipText: Int,
        val accent: Int, val onAccent: Int, val text: Int, val subtext: Int, val crust: Int
    )

    private val palettes = listOf(
        // Mocha — darkest, yellow
        Palette("MOCHA", 0xE61E1E2E.toInt(), 0xCC313244.toInt(), 0xFFA6ADC8.toInt(),
            0xFFF9E2AF.toInt(), 0xFF11111B.toInt(), 0xFFCDD6F4.toInt(),
            0xFF9399B2.toInt(), 0xFF11111B.toInt()),
        // Macchiato — deep slate, mauve
        Palette("MACCHIATO", 0xE624273A.toInt(), 0xCC363A4F.toInt(), 0xFFA5ADCB.toInt(),
            0xFFC6A0F6.toInt(), 0xFF181926.toInt(), 0xFFCAD3F5.toInt(),
            0xFF939AB7.toInt(), 0xFF181926.toInt()),
        // Frappe — lighter slate, green
        Palette("FRAPPE", 0xE6303446.toInt(), 0xCC414559.toInt(), 0xFFA5ADCE.toInt(),
            0xFFA6D189.toInt(), 0xFF232634.toInt(), 0xFFC6D3F5.toInt(),
            0xFF838BA7.toInt(), 0xFF232634.toInt()),
        // Latte — light, blue
        Palette("LATTE", 0xF2EFF1F5.toInt(), 0xCCDCE0E8.toInt(), 0xFF6C6F85.toInt(),
            0xFF1E66F5.toInt(), 0xFFFFFFFF.toInt(), 0xFF4C4F69.toInt(),
            0xFF6C6F85.toInt(), 0xFFDCE0E8.toInt())
    )

    private val pal: Palette get() = palettes[paletteIndex]
    private val exposureNs: Long
        get() = shutterDenom?.let { (1e9 / it).toLong() } ?: 8_000_000L

    /** One selectable physical camera (lens) */
    private data class LensInfo(val id: String, val label: String)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission", "ClickableViewAccessibility")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Resolve which camera to open: nav argument wins, else the best back lens
        activeCameraId = args.cameraId ?: pickDefaultCamera()
        // Load persisted Catppuccin palette and wire the theme cycler
        val prefs = requireContext().getSharedPreferences("ui", Context.MODE_PRIVATE)
        paletteIndex = prefs.getInt("palette", 0).coerceIn(0, palettes.size - 1)
        applyTheme()
        fragmentCameraBinding.themeChip.setOnClickListener {
            paletteIndex = (paletteIndex + 1) % palettes.size
            prefs.edit().putInt("palette", paletteIndex).apply()
            applyTheme()
            wireControls()
        }
        characteristics = cameraManager.getCameraCharacteristics(activeCameraId)

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    fragmentCameraBinding.viewFinder.display,
                    characteristics,
                    SurfaceHolder::class.java
                )
                Log.d(TAG, "Selected preview size: $previewSize")
                fragmentCameraBinding.viewFinder.setAspectRatio(
                    previewSize.width,
                    previewSize.height
                )

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(requireContext(), characteristics).apply {
            observe(viewLifecycleOwner, Observer { orientation ->
                Log.d(TAG, "Orientation changed: $orientation")
            })
        }

        // Pinch to zoom, tap to focus
        val scaleDetector = ScaleGestureDetector(requireContext(),
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val range = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        ?: Range(1f, 1f)
                } else {
                    Range(1f, 1f)
                }
                zoomRatio = (zoomRatio * detector.scaleFactor).coerceIn(range.lower, range.upper)
                fragmentCameraBinding.zoomChip.text =
                    String.format(Locale.US, "%.1fX", zoomRatio)
                refreshPreview()
                return true
            }
        })
        fragmentCameraBinding.viewFinder.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                tapToFocus(e.x, e.y)
                true
            } else {
                true
            }
        }

        // Pad chrome by system bar insets so it clears notches/gesture bars on any device
        val d = resources.displayMetrics.density
        fragmentCameraBinding.root.setOnApplyWindowInsetsListener { v, insets ->
            val top = insets.systemWindowInsetTop
            val bottom = insets.systemWindowInsetBottom
            fragmentCameraBinding.topBar.setPadding(
                fragmentCameraBinding.topBar.paddingLeft,
                (18 * d).toInt() + top,
                fragmentCameraBinding.topBar.paddingRight,
                0
            )
            fragmentCameraBinding.bottomBar.setPadding(
                fragmentCameraBinding.bottomBar.paddingLeft,
                0,
                fragmentCameraBinding.bottomBar.paddingRight,
                (22 * d).toInt() + bottom
            )
            (fragmentCameraBinding.lensBar.layoutParams as? android.widget.FrameLayout.LayoutParams)
                ?.let { lp ->
                    lp.bottomMargin = (112 * d).toInt() + bottom
                    fragmentCameraBinding.lensBar.layoutParams = lp
                }
            (fragmentCameraBinding.controlsScroll.layoutParams as? android.widget.FrameLayout.LayoutParams)
                ?.let { lp ->
                    lp.bottomMargin = (148 * d).toInt() + bottom
                    fragmentCameraBinding.controlsScroll.layoutParams = lp
                }
            insets.consumeSystemWindowInsets()
        }
    }

    /** Picks the best default camera: back RAW > back > first available. */
    @SuppressLint("MissingPermission")
    private fun pickDefaultCamera(): String {
        val ids = cameraManager.cameraIdList.filter {
            cameraManager.getCameraCharacteristics(it).get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
        }
        fun facing(id: String) = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING)
        fun raw(id: String) = cameraManager.getCameraCharacteristics(id).get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        return ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK && raw(it) }
            ?: ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.first()
    }


    /** Wires the zero-cam style UI: format toggle, chip panel, lens bar, zoom, pro sliders. */
    private fun wireControls() {
        fragmentCameraBinding.fmtToggle.setOnClickListener {
            formatJpeg = !formatJpeg
            updateFormatChip()
        }
        updateFormatChip()

        fragmentCameraBinding.isoChip.setOnClickListener {
            togglePanel(fragmentCameraBinding.controlsScroll.visibility == View.GONE)
        }

        // Manual WB gains need the MANUAL_POST_PROCESSING capability; hide otherwise
        val manualPost = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
        )?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
        fragmentCameraBinding.wbScroll.visibility =
            if (manualPost) View.VISIBLE else View.GONE

        buildControlChips()
        buildLensBar()

        fragmentCameraBinding.zoomChip.setOnClickListener {
            zoomRatio = 1f
            fragmentCameraBinding.zoomChip.text = "1.0X"
            refreshPreview()
        }

        // Pro sliders — optional fine control on top of the quick chips
        val sliderRows = listOf(
            fragmentCameraBinding.isoSliderRow, fragmentCameraBinding.expSliderRow,
            fragmentCameraBinding.wbSliderRow, fragmentCameraBinding.focusSliderRow
        )
        fragmentCameraBinding.proSwitch.setOnCheckedChangeListener { _, checked ->
            sliderRows.forEach { row ->
                if (checked) {
                    row.visibility = View.VISIBLE
                    row.alpha = 0f
                    row.animate().alpha(1f).setDuration(150).start()
                } else {
                    row.visibility = View.GONE
                }
            }
        }
        fragmentCameraBinding.isoSlider.setOnSeekBarChangeListener(simpleSeek { bar ->
            val v = (50.0 * Math.pow(128.0, bar.progress / 1000.0)).toInt()
                .coerceIn(50, 6400)
            isoValue = v
            fragmentCameraBinding.isoSliderVal.text = v.toString()
            fragmentCameraBinding.isoChip.text = "ISO $v"
            buildControlChips()
            refreshPreview()
        })
        fragmentCameraBinding.expSlider.setOnSeekBarChangeListener(simpleSeek { bar ->
            val denom = (30.0 * Math.pow(133.3333, bar.progress / 1000.0)).toInt()
                .coerceIn(30, 4000)
            shutterDenom = denom
            fragmentCameraBinding.expSliderVal.text = "1/$denom"
            buildControlChips()
            refreshPreview()
        })
        fragmentCameraBinding.wbSlider.setOnSeekBarChangeListener(simpleSeek { bar ->
            wbKelvin = 2500 + (bar.progress * 5.5f).toInt()
            fragmentCameraBinding.wbSliderVal.text = "${wbKelvin}K"
            buildControlChips()
            refreshPreview()
        })
        val maxFocus = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f
        if (maxFocus <= 0f) fragmentCameraBinding.focusSlider.isEnabled = false
        fragmentCameraBinding.focusSlider.setOnSeekBarChangeListener(simpleSeek { bar ->
            focusDiopter = maxFocus * bar.progress / bar.max.toFloat()
            fragmentCameraBinding.focusSliderVal.text =
                if (focusDiopter <= 0.01f) "AF"
                else String.format(Locale.US, "%.1fD", focusDiopter)
            refreshPreview()
        })
        pressScale(fragmentCameraBinding.fmtToggle)
        pressScale(fragmentCameraBinding.themeChip)
        pressScale(fragmentCameraBinding.zoomChip)
        pressScale(fragmentCameraBinding.isoChip)
        pressScale(fragmentCameraBinding.captureButton)
    }

    /** Slides the control panel in/out with a fade. */
    private fun togglePanel(show: Boolean) {
        val p = fragmentCameraBinding.controlsScroll
        val d = resources.displayMetrics.density
        if (show) {
            p.visibility = View.VISIBLE
            p.alpha = 0f
            p.translationY = 48f * d
            p.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        } else {
            p.animate().alpha(0f).translationY(48f * d).setDuration(160)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { p.visibility = View.GONE }.start()
        }
    }

    /** Subtle press-down scale feedback for tappable views. */
    private fun pressScale(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            false
        }
    }

    /** Rebuilds all chip rows so selection highlights match current state. */
    private fun buildControlChips() {
        // ISO row — highlights also match nearby slider-derived values
        buildChips(
            fragmentCameraBinding.isoChips,
            listOf("AUTO" to null, "100" to 100, "200" to 200, "400" to 400,
                "800" to 800, "1600" to 1600, "3200" to 3200),
            { val cur = isoValue; it == cur || (it != null && cur != null &&
                kotlin.math.abs(kotlin.math.ln(it.toDouble() / cur.toDouble())) < 0.18) }
        ) { value ->
            isoValue = value
            fragmentCameraBinding.isoChip.text = if (value == null) "ISO A" else "ISO $value"
            if (value != null) {
                fragmentCameraBinding.isoSlider.progress =
                    (Math.log(value / 50.0) / Math.log(128.0) * 1000).toInt()
                        .coerceIn(0, 1000)
                fragmentCameraBinding.isoSliderVal.text = value.toString()
            }
            refreshPreview()
        }

        // Shutter row
        buildChips(
            fragmentCameraBinding.shutterChips,
            listOf("AUTO" to null, "1/1000" to 1000, "1/500" to 500, "1/250" to 250,
                "1/125" to 125, "1/60" to 60, "1/30" to 30),
            { val cur = shutterDenom; it == cur || (it != null && cur != null &&
                kotlin.math.abs(kotlin.math.ln(it.toDouble() / cur.toDouble())) < 0.18) }
        ) { value ->
            shutterDenom = value
            if (value != null) {
                fragmentCameraBinding.expSlider.progress =
                    (Math.log(value / 30.0) / Math.log(4000.0 / 30.0) * 1000).toInt()
                        .coerceIn(0, 1000)
                fragmentCameraBinding.expSliderVal.text = "1/$value"
            }
            refreshPreview()
        }

        // White balance row — highlights within ±150K of slider-derived kelvin
        buildChips(
            fragmentCameraBinding.wbChips,
            listOf("AUTO" to 0, "3200K" to 3200, "4000K" to 4000,
                "5200K" to 5200, "6000K" to 6000),
            { it == wbKelvin || (it != 0 && wbKelvin > 0 && kotlin.math.abs(it - wbKelvin) <= 150) }
        ) { value ->
            wbKelvin = value
            if (value == 0) {
                fragmentCameraBinding.wbSliderVal.text = "AUTO"
            } else {
                fragmentCameraBinding.wbSlider.progress =
                    ((value - 2500) / 5.5f).toInt().coerceIn(0, 1000)
                fragmentCameraBinding.wbSliderVal.text = "${value}K"
            }
            refreshPreview()
        }
    }

    /** Seek listener that only reacts to user drags, not programmatic sync. */
    private inline fun simpleSeek(crossinline block: (SeekBar) -> Unit):
            SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Ignore programmatic updates so chip->slider sync doesn't feed back
                if (fromUser) block(seekBar)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
    }
    /** Applies the active Catppuccin palette to all chrome. */
    private fun applyTheme() {
        val b = fragmentCameraBinding
        b.controlsScroll.background = roundRect(pal.panel, 16f)
        styleChip(b.wordmark, false)
        b.isoChip.setTextColor(pal.text)
        b.zoomChip.setTextColor(pal.text)
        b.proLabel.setTextColor(pal.subtext)
        b.isoSliderVal.setTextColor(pal.text)
        b.expSliderVal.setTextColor(pal.text)
        b.proSwitch.thumbTintList = ColorStateList.valueOf(pal.accent)
        b.proSwitch.trackTintList = ColorStateList.valueOf(pal.chip)
        listOf(b.isoSlider, b.expSlider, b.wbSlider, b.focusSlider).forEach { s ->
            s.thumbTintList = ColorStateList.valueOf(pal.accent)
            s.progressTintList = ColorStateList.valueOf(pal.accent)
            s.progressBackgroundTintList = ColorStateList.valueOf(pal.chip)
        }
        // Shutter pill with an inner dot so it reads as a shutter button
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(pal.crust)
        }
        val d = resources.displayMetrics.density
        val shutter = LayerDrawable(arrayOf(roundRect(pal.accent, 31f), dot))
        shutter.setLayerInset(
            1, (49 * d).toInt(), (18 * d).toInt(), (49 * d).toInt(), (18 * d).toInt()
        )
        b.captureButton.background = shutter
        b.themeChip.text = pal.name
        styleChip(b.themeChip, false)
        updateFormatChip()
    }

    private fun roundRect(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
        }

    /** Updates the RAW/JPEG format chip styling. */
    private fun updateFormatChip() {
        fragmentCameraBinding.fmtToggle.text = if (formatJpeg) "JPEG" else "RAW+"
        styleChip(fragmentCameraBinding.fmtToggle, !formatJpeg)
    }

    /** Applies the selected/unselected chip look from the active palette. */
    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = roundRect(if (selected) pal.accent else pal.chip, 8f)
        chip.setTextColor(if (selected) pal.onAccent else pal.chipText)
    }

    /** Populates a row of selectable chips; [items] pairs labels with values. */
    private fun <T> buildChips(
        row: LinearLayout,
        items: List<Pair<String, T>>,
        isSelected: (T) -> Boolean,
        onPick: (T) -> Unit
    ) {
        row.removeAllViews()
        val dp = resources.displayMetrics.density
        items.forEach { (label, value) ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                setPadding(
                    (12 * dp).toInt(), (6 * dp).toInt(),
                    (12 * dp).toInt(), (6 * dp).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (7 * dp).toInt() }
                setOnClickListener { view ->
                    onPick(value)
                    for (i in 0 until row.childCount) {
                        val child = row.getChildAt(i) as TextView
                        styleChip(child, isSelected(child.tag as T))
                    }
                    // Selection pop
                    view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(70)
                        .withEndAction {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                        }.start()
                }
                tag = value
            }
            styleChip(chip, isSelected(value))
            pressScale(chip)
            row.addView(chip)
        }
    }


    /** Populates the lens chips (one per back camera) above the shutter. */
    private fun buildLensBar() {
        val bar = fragmentCameraBinding.lensBar
        bar.removeAllViews()
        val dp = resources.displayMetrics.density
        enumerateLenses().forEach { lens ->
            val chip = TextView(requireContext()).apply {
                text = lens.label
                textSize = 12f
                setPadding(
                    (12 * dp).toInt(), (6 * dp).toInt(),
                    (12 * dp).toInt(), (6 * dp).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (7 * dp).toInt() }
                setOnClickListener { switchCamera(lens.id) }
            }
            styleChip(chip, lens.id == activeCameraId)
            pressScale(chip)
            bar.addView(chip)
        }
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(200).start()
    }
    /** Lists back-facing cameras as lenses, labeled by 35mm-equivalent ratio to the main lens. */
    @SuppressLint("MissingPermission")
    private fun enumerateLenses(): List<LensInfo> {
        val back = cameraManager.cameraIdList.filter { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                ) == true
        }
        data class Cand(val id: String, val eq: Float, val focal: Float, val raw: Boolean)
        val cands = back.mapNotNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: return@mapNotNull null
            // 35mm-equivalent focal length from the sensor's physical diagonal
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val eq = if (size != null) {
                val diag = kotlin.math.sqrt(size.width * size.width + size.height * size.height)
                focal * 43.27f / diag
            } else {
                focal
            }
            Cand(id, eq, focal, c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true)
        }
        if (cands.isEmpty()) return emptyList()
        // Main lens = 35mm-equivalent closest to the classic ~26mm phone primary
        val mainEq = cands.minByOrNull { kotlin.math.abs(it.eq - 26f) }!!.eq
        fun label(eq: Float): String {
            val r = eq / mainEq
            return if (kotlin.math.abs(r - r.toInt()) < 0.05f) "${r.toInt()}X"
            else String.format(Locale.US, "%.1fX", r)
        }
        // Dedupe identical labels (some devices expose duplicate physicals); prefer RAW-capable
        return cands
            .sortedWith(compareByDescending<Cand> { it.raw }.thenBy { it.eq })
            .distinctBy { label(it.eq) }
            .sortedBy { it.eq }
            .map { LensInfo(it.id, label(it.eq)) }
    }

    /** Switches to another lens in place, reusing the same preview surface. */
    private fun switchCamera(cameraId: String) {
        if (switching || cameraId == activeCameraId) return
        switching = true
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                try { session.close() } catch (_: Exception) {}
                try { camera.close() } catch (_: Exception) {}
                activeCameraId = cameraId
                characteristics = cameraManager.getCameraCharacteristics(cameraId)
                zoomRatio = 1f
                fragmentCameraBinding.zoomChip.text = "1.0X"
                initializeCamera().join()
                buildLensBar()
            } catch (e: Exception) {
                Log.e(TAG, "Lens switch failed", e)
                toast("Lens switch failed")
            } finally {
                switching = false
            }
        }
    }

    /** Transient error feedback that doesn't clutter the viewfinder. */
    private fun toast(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, activeCameraId, cameraHandler)

        // Initialize an image reader which will be used to capture still photos
        Log.d(TAG, "Initializing image reader")
        Log.d(TAG, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP.toString())
        val size = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE
        )

        // HAL JPEG reader for the in-app RAW/JPEG toggle (skips the RAW->bitmap
        // conversion path entirely; HAL JPEG is faster and better quality)
        if (args.pixelFormat != ImageFormat.JPEG) {
            val jpegSize = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )!!
                .getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width }!!
            jpegReader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, IMAGE_BUFFER_SIZE
            )
        }

        // Creates list of Surfaces where the camera will output frames
        val targets = listOfNotNull(
            fragmentCameraBinding.viewFinder.holder.surface,
            imageReader.surface,
            if (args.pixelFormat != ImageFormat.JPEG) jpegReader.surface else null
        )

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            applyState(this)
        }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        wireControls()

        // Listen to the capture button — non-blocking: saves happen in the IO
        // scope while the camera keeps shooting
        fragmentCameraBinding.captureButton.setOnClickListener {
            if (capturing) return@setOnClickListener
            capturing = true
            // Shutter pulse
            fragmentCameraBinding.captureButton.animate().scaleX(0.92f).scaleY(0.92f)
                .setDuration(80).withEndAction {
                    fragmentCameraBinding.captureButton.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).start()
                }.start()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val reader = if (formatJpeg && args.pixelFormat != ImageFormat.JPEG) {
                        jpegReader
                    } else {
                        imageReader
                    }
                    takePhoto(reader).use { result ->
                        val output = saveResult(result)
                        Log.d(TAG, "Image saved: ${output.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    toast("Capture failed: ${e.message}")
                } finally {
                    capturing = false
                }
            }
        }
    }

    /** Applies every manual control to any request builder — preview or still. */
    private fun applyState(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
        if (proMode) {
            val isoRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                if (isoRange != null) iso.coerceIn(isoRange.lower, isoRange.upper) else iso
            )
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                if (expRange != null) exposureNs.coerceIn(expRange.lower, expRange.upper)
                else exposureNs
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        if (focusDiopter > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
        focusRegion?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        if (wbKelvin > 0) {
            // Manual WB: AWB off + channel gains only. The HAL's normal color-correction
            // matrix stays active (FAST) — swapping it for an identity transform discards
            // the sensor's calibration and produces a green cast.
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_FAST
            )
            val (rg, bg) = kelvinToGains(wbKelvin)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(rg, 1f, 1f, bg)
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_FAST
            )
        }
    }

    /** Approximate temperature -> R/B gains, normalized so 5200K is neutral. */
    private fun kelvinToGains(kelvin: Int): Pair<Float, Float> {
        fun raw(k: Int): Pair<Double, Double> {
            val t = k / 100.0
            val r = if (t <= 66) 255.0 else 329.7 * Math.pow(t - 60, -0.1332)
            val b = when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> 138.52 * kotlin.math.ln(t - 10) - 305.04
            }
            return r to b
        }
        val (rT, _) = raw(kelvin)
        val (rN, bN) = raw(5200)
        val bT = raw(kelvin).second
        val rg = (255.0 / kotlin.math.max(rT, 1.0) / (255.0 / kotlin.math.max(rN, 1.0)))
            .toFloat().coerceIn(0.25f, 4f)
        val bg = (255.0 / kotlin.math.max(bT, 1.0) / (255.0 / kotlin.math.max(bN, 1.0)))
            .toFloat().coerceIn(0.25f, 4f)
        return rg to bg
    }

    /** Rebuilds and re-issues the preview repeating request with current state.
     *  No AF triggers here: continuous AF is steered by metering regions only —
     *  a START followed by a quick IDLE cancels the sweep mid-travel and wedges the lens. */
    private fun refreshPreview() {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            applyState(this)
        }
        try {
            session.setRepeatingRequest(b.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "refreshPreview failed", e)
        }
    }

    /** Tap-to-focus: maps view coords onto the active array and fires AF. */
    private fun tapToFocus(x: Float, y: Float) {
        val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val vf = fragmentCameraBinding.viewFinder
        val nx = (x / vf.width).coerceIn(0.05f, 0.95f)
        val ny = (y / vf.height).coerceIn(0.05f, 0.95f)
        val cx = rect.left + (nx * rect.width()).toInt()
        val cy = rect.top + (ny * rect.height()).toInt()
        val half = (kotlin.math.min(rect.width(), rect.height()) * 0.06f).toInt().coerceAtLeast(24)
        focusRegion = MeteringRectangle(
            (cx - half).coerceAtLeast(rect.left),
            (cy - half).coerceAtLeast(rect.top),
            half * 2, half * 2, 1000
        )
        if (focusDiopter > 0f) {
            // Tapping the scene means "refocus here automatically"
            focusDiopter = 0f
            fragmentCameraBinding.focusSlider.progress = 0
            fragmentCameraBinding.focusSliderVal.text = "AF"
        }
        refreshPreview()
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto(reader: ImageReader):
            CombinedCaptureResult = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (reader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply {
            addTarget(reader.surface)
            applyState(this)
            // HAL rotates pixels + writes EXIF itself; avoids post-save
            // ExifInterface rewrites that OxygenOS rejects on MediaStore
            if (reader.imageFormat == ImageFormat.JPEG) {
                set(CaptureRequest.JPEG_ORIENTATION, relativeOrientation.value ?: 0)
            }
        }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                fragmentCameraBinding.viewFinder.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)
                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {

                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()
                        // TODO(owahltinez): b/142011420
                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        reader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }

                        // Compute EXIF orientation metadata
                        val rotation = relativeOrientation.value ?: 0
                        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_FRONT
                        val exifOrientation = computeExifOrientation(rotation, mirrored)

                        // Build the result and resume progress
                        cont.resume(
                            CombinedCaptureResult(
                                image, result, exifOrientation, reader.imageFormat
                            )
                        )

                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // HAL JPEG: ISP bytes, EXIF stamped with the app name before saving.
            // Done in-memory so OxygenOS doesn't reject a post-insert rewrite.
            ImageFormat.JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val filename = "IMG_${
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                }.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                }
                val resolver = requireContext().contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                ) ?: throw IOException("Failed to create MediaStore entry")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                // Stamp the app name into EXIF; keep original bytes if the rewrite fails
                try {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        ExifInterface(pfd.fileDescriptor).apply {
                            setAttribute(ExifInterface.TAG_SOFTWARE, "unprocess")
                            saveAttributes()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "EXIF stamp skipped", e)
                }
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                cont.resume(File(File(dcim, "Camera"), filename))
            }

            // Only expecting RAW sensor data
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
                // Metadata watermark: ImageDescription tag carries the app name
                dngCreator.setDescription("shot through unprocess")
                try {
                    if (args.convertToJpeg) {
                        // Get RAW image data
                        val rawImage = result.image
                        val rawBuffer = rawImage.planes[0].buffer
                        val rawBytes = ByteArray(rawBuffer.remaining())
                        rawBuffer.get(rawBytes)

                        // Create a temporary DNG file
                        val tempDngFile = File(requireContext().cacheDir, "temp.dng")
                        FileOutputStream(tempDngFile).use { outputStream ->
                            dngCreator.writeImage(outputStream, rawImage)
                        }

                        // TODO: Right now, using android's basic bitmap conversion,
                        //  may want to use RenderScript or other RAW processing library
                        val bitmap = BitmapFactory.decodeFile(tempDngFile.absolutePath)
                        tempDngFile.delete() // Clean up temp file

                        // Save as JPEG
                        val filename = "IMG_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.jpg"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            resolver.openOutputStream(uri)?.use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                            }

                            // Add EXIF orientation data using the URI
                            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                                ExifInterface(pfd.fileDescriptor).apply {
                                    setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                    saveAttributes()
                                }
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)
                        } else {
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) mkdirs()
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { stream ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                            }

                            // Add EXIF orientation data
                            ExifInterface(file.absolutePath).apply {
                                setAttribute(ExifInterface.TAG_ORIENTATION, result.orientation.toString())
                                saveAttributes()
                            }

                            cont.resume(file)
                        }

                        bitmap.recycle()
                    } else {
                        dngCreator.setOrientation(result.orientation)
                        val filename = "RAW_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(Date())
                        }.dng"

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // Android 10 and above: Use MediaStore
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "${Environment.DIRECTORY_DCIM}/Camera"
                                )
                            }

                            val resolver = requireContext().contentResolver
                            val uri = resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            ) ?: throw IOException("Failed to create MediaStore entry")

                            val outputStream = resolver.openOutputStream(uri)
                                ?: throw IOException("Failed to open output stream")

                            outputStream.use { stream ->
                                dngCreator.writeImage(stream, result.image)
                            }

                            // Create a reference file in the DCIM directory
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera")
                            val savedFile = File(appFolder, filename)
                            cont.resume(savedFile)

                        } else {
                            // Below Android 10: Use direct file access
                            val dcim = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM
                            )
                            val appFolder = File(dcim, "Camera").apply {
                                if (!exists()) {
                                    mkdirs()
                                }
                            }
                            val file = File(appFolder, filename)

                            FileOutputStream(file).use { outputStream ->
                                dngCreator.writeImage(outputStream, result.image)
                            }

                            // BUGFIX (fork): the legacy path never resumed the
                            // coroutine, leaving captures hanging forever
                            cont.resume(file)
                        }
                    }

                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to external storage", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val orientation: Int,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, extension: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "IMG_${sdf.format(Date())}.$extension")
        }
    }
}
