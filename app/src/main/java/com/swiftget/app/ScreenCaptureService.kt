package com.swiftget.app

import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Screenshot and screen recording through Android's own screen-capture permission.
 * Android asks you to approve every capture session. Apps that mark their window as secure,
 * and DRM-protected video, are blanked by the system: that is Android's rule and is not bypassed here.
 * Audio is the microphone only (it will pick up what your speaker plays, but also your surroundings).
 */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        const val EXTRA_AUDIO = "audio"
        const val MODE_SHOT = "shot"
        const val MODE_RECORD = "record"
        const val ACTION_STOP = "swiftget.stop_capture"
    }

    private val thread = HandlerThread("swiftget-capture").also { it.start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var reader: ImageReader? = null
    private var pfd: ParcelFileDescriptor? = null
    private var videoUri: Uri? = null
    private var discard = false
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            finish(); return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECORD
        val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        if (data == null) {
            stopSelf(); return START_NOT_STICKY
        }

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (audio && mode == MODE_RECORD) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, 1003, notification(mode), type)

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            projection = p
            // Required before creating a virtual display on Android 14+.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    finish()
                }
            }, handler)
            if (mode == MODE_SHOT) {
                handler.postDelayed({ shoot() }, 1200) // let the Clipz window slide away first
            } else {
                handler.post { record(audio) }
            }
        } catch (e: Exception) {
            toast("Couldn't start capture: ${e.message}")
            finish()
        }
        return START_NOT_STICKY
    }

    private fun notification(mode: String) = run {
        Notif.ensureChannels(this)
        val stop = PendingIntent.getService(
            this, 4, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        NotificationCompat.Builder(this, "progress")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (mode == MODE_SHOT) "Clipz: taking a screenshot" else "Clipz is recording your screen")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun size(): Triple<Int, Int, Int> {
        val dm = resources.displayMetrics
        return Triple(dm.widthPixels and 1.inv(), dm.heightPixels and 1.inv(), dm.densityDpi)
    }

    // ------------------------------------------------------------ recording

    private fun record(audio: Boolean) {
        val p = projection ?: return
        try {
            val (w, h, dpi) = size()
            val name = "Clipz_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Clipz")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create the video file")
            videoUri = uri
            val fd = contentResolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("could not open the video file")
            pfd = fd

            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(fd.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
            }
            r.setVideoSize(w, h)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(8_000_000)
            r.prepare()
            recorder = r
            display = p.createVirtualDisplay(
                "ClipzRecord", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
            )
            r.start()
            toast("Recording. Pull down the notification and tap Stop when done.")
        } catch (e: Exception) {
            discard = true
            toast("Couldn't start recording: ${e.message}")
            finish()
        }
    }

    // ------------------------------------------------------------ screenshot

    private fun shoot() {
        val p = projection ?: return
        try {
            val (w, h, dpi) = size()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            var saved = false
            display = p.createVirtualDisplay(
                "ClipzShot", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
            )
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (saved) {
                    img.close(); return@setOnImageAvailableListener
                }
                saved = true
                try {
                    val plane = img.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    saveBitmap(Bitmap.createBitmap(padded, 0, 0, w, h))
                } catch (e: Exception) {
                    toast("Couldn't save the screenshot: ${e.message}")
                } finally {
                    img.close()
                }
                finish()
            }, handler)
        } catch (e: Exception) {
            toast("Couldn't take the screenshot: ${e.message}")
            finish()
        }
    }

    private fun saveBitmap(bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Clipz_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Clipz")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create the image file")
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        toast("Screenshot saved to Pictures/Clipz")
    }

    // ------------------------------------------------------------ teardown

    @Synchronized
    private fun finish() {
        if (finishing) return
        finishing = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            discard = true // stopped before any frame was written
        }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { pfd?.close() }
        pfd = null
        videoUri?.let { uri ->
            if (discard) {
                runCatching { contentResolver.delete(uri, null, null) }
            } else {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                runCatching { contentResolver.update(uri, v, null, null) }
                toast("Recording saved to Movies/Clipz")
            }
        }
        videoUri = null
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
