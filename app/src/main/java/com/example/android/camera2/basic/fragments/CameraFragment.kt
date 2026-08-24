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
import androidx.core.graphics.drawable.toDrawable
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
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
import androidx.core.content.ContextCompat
import java.io.OutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.params.RggbChannelVector

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

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
    private var focusDiopter = 0f      // 0 = continuous AF
    private var wbKelvin = 0           // 0 = auto
    private var evIndex = 0
    private var proMode = false
    private var iso = 100
    private var exposureNs = 8_000_000L
    private var formatJpeg = false     // default: RAW (unprocess's spirit)
    private var capturing = false
    private var focusRegion: MeteringRectangle? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentCameraBinding.captureButton.setOnApplyWindowInsetsListener { v, insets ->
            v.translationX = (-insets.systemWindowInsetRight).toFloat()
            v.translationY = (-insets.systemWindowInsetBottom).toFloat()
            insets.consumeSystemWindowInsets()
        }

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
                Log.d(
                    TAG,
                    "View finder size: ${fragmentCameraBinding.viewFinder.width} x ${fragmentCameraBinding.viewFinder.height}"
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

        fragmentCameraBinding.viewFinder.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                tapToFocus(e.x, e.y)
                true
            } else {
                false
            }
        }
    }

    /** Wires the manual-control UI (format toggle, sliders, pro panel). */
    private fun wireControls() {
        fragmentCameraBinding.fmtToggle.setOnClickListener {
            formatJpeg = !formatJpeg
            fragmentCameraBinding.fmtToggle.text = if (formatJpeg) "JPEG" else "RAW"
            setStatus(if (formatJpeg) "output: HAL JPEG" else "output: RAW DNG")
        }

        val panel = fragmentCameraBinding.controlsScroll
        fragmentCameraBinding.controlsBtn.setOnClickListener {
            val show = panel.visibility == android.view.View.GONE
            panel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        }

        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        } else {
            Range(1f, 1f)
        }
        fragmentCameraBinding.zoomBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            val t = bar.progress / bar.max.toFloat()
            zoomRatio = zoomRange.lower + (zoomRange.upper - zoomRange.lower) * t
            fragmentCameraBinding.zoomVal.text =
                String.format(java.util.Locale.US, "%.1fx", zoomRatio)
            refreshPreview()
        })

        val maxFocus = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f
        if (maxFocus <= 0f) fragmentCameraBinding.focusBar.isEnabled = false
        fragmentCameraBinding.focusBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            focusDiopter = maxFocus * bar.progress / bar.max.toFloat()
            fragmentCameraBinding.focusVal.text =
                if (focusDiopter <= 0.01f) "af"
                else String.format(java.util.Locale.US, "%.2fd", focusDiopter)
            refreshPreview()
        })

        listOf(
            fragmentCameraBinding.wbAuto to 0,
            fragmentCameraBinding.wb3200 to 3200,
            fragmentCameraBinding.wb4000 to 4000,
            fragmentCameraBinding.wb5200 to 5200,
            fragmentCameraBinding.wb6000 to 6000
        ).forEach { (view, k) ->
            view.setOnClickListener {
                wbKelvin = k
                fragmentCameraBinding.wbVal.text = if (k == 0) "auto" else "${k}K"
                fragmentCameraBinding.kelvinBar.progress = (k - 2500).coerceIn(0, fragmentCameraBinding.kelvinBar.max)
                refreshPreview()
            }
        }
        fragmentCameraBinding.kelvinBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            wbKelvin = 2500 + bar.progress
            fragmentCameraBinding.wbVal.text = "${wbKelvin}K"
            refreshPreview()
        })

        val evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(0, 0)
        fragmentCameraBinding.evBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            evIndex = (bar.progress - bar.max / 2).coerceIn(evRange.lower, evRange.upper)
            fragmentCameraBinding.evVal.text = (if (evIndex >= 0) "+" else "") + evIndex.toString()
            refreshPreview()
        })

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (isoRange == null || expRange == null) fragmentCameraBinding.proSwitch.isEnabled = false
        fragmentCameraBinding.proSwitch.setOnCheckedChangeListener { _, checked ->
            proMode = checked
            fragmentCameraBinding.isoRow.visibility =
                if (checked) android.view.View.VISIBLE else android.view.View.GONE
            fragmentCameraBinding.isoBar.visibility = fragmentCameraBinding.isoRow.visibility
            fragmentCameraBinding.expRow.visibility = fragmentCameraBinding.isoRow.visibility
            fragmentCameraBinding.expBar.visibility = fragmentCameraBinding.isoRow.visibility
            refreshPreview()
        }
        fragmentCameraBinding.isoBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            val r = isoRange ?: return@simpleSeek
            val t = bar.progress / bar.max.toFloat()
            iso = (r.lower * Math.pow(r.upper.toDouble() / r.lower, t.toDouble())).toInt()
                .coerceIn(r.lower, r.upper)
            fragmentCameraBinding.isoVal.text = iso.toString()
            if (proMode) refreshPreview()
        })
        fragmentCameraBinding.expBar.setOnSeekBarChangeListener(simpleSeek { bar ->
            val r = expRange ?: return@simpleSeek
            val lo = r.lower.coerceAtLeast(10_000L)
            val hi = r.upper.coerceAtMost(4_000_000_000L)
            val t = bar.progress / bar.max.toFloat()
            exposureNs = (lo * Math.pow(hi.toDouble() / lo, t.toDouble())).toLong()
                .coerceIn(lo, hi)
            fragmentCameraBinding.expVal.text =
                (1e9 / exposureNs.toDouble()).toInt().coerceAtLeast(1).toString()
            if (proMode) refreshPreview()
        })
    }

    private inline fun simpleSeek(crossinline block: (android.widget.SeekBar) -> Unit):
            android.widget.SeekBar.OnSeekBarChangeListener {
        return object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                block(seekBar)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
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
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

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
            if (capturing) {
                setStatus("busy…")
                return@setOnClickListener
            }
            capturing = true
            setStatus("capturing…")
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
                        setStatus("saved ${output.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture failed", e)
                    setStatus("error: ${e.message}")
                } finally {
                    capturing = false
                }
            }
        }
    }

    /** Posts a status line to the camera UI. */
    private fun setStatus(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            _fragmentCameraBinding?.statusText?.text = msg
        }
    }

    /** Applies every manual control to any request builder — preview or still. */
    private fun applyState(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
        if (proMode) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }
        if (focusDiopter > 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        focusRegion?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
        if (wbKelvin > 0) {
            val (rg, bg) = kelvinToGains(wbKelvin)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(rg, 1f, 1f, bg)
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

    /** Rebuilds and re-issues the preview repeating request with current state. */
    private fun refreshPreview(triggerAf: Boolean = false) {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            applyState(this)
            set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                if (triggerAf) CameraMetadata.CONTROL_AF_TRIGGER_START
                else CameraMetadata.CONTROL_AF_TRIGGER_IDLE
            )
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
        if (focusDiopter <= 0f) refreshPreview(triggerAf = true)
        cameraHandler.postDelayed({ refreshPreview() }, 150)
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
            // HAL JPEG: raw bytes straight from the ISP, saved as-is
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
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                cont.resume(File(File(dcim, "Camera"), filename))
            }

            // Only expecting RAW sensor data
            ImageFormat.RAW_SENSOR -> {
                val dngCreator = DngCreator(characteristics, result.metadata)
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
