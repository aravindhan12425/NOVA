package com.assistant.nova.services

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.assistant.nova.MainActivity
import com.assistant.nova.R
import com.assistant.nova.core.privacy.OnlookerDetector
import com.assistant.nova.core.privacy.SeenFace
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Privacy guard: while the screen is on and unlocked, watches the front camera for someone
 * else looking at the screen (see [OnlookerDetector]) and warns the owner with a vibration,
 * a message and a dark cover over the screen.
 *
 * Everything runs on the phone (ML Kit's offline face detector); no picture is stored or sent.
 * Android only lets a camera service start while Nova is open, so it's started from Nova's
 * screen (and again when Nova is opened after a reboot).
 */
class PrivacyGuardService : LifecycleService() {

    companion object {
        private const val TAG = "NovaPrivacy"
        private const val CHANNEL = "nova_privacy"
        private const val CHANNEL_ALERT = "nova_privacy_alert"
        private const val ID = 4201
        private const val ID_ALERT = 4202
        /** Faces are checked this often; the camera itself also runs at its lowest frame rate. */
        private const val EVERY_MS = 350L

        @Volatile var isRunning = false
            private set

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, PrivacyGuardService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the privacy guard", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrivacyGuardService::class.java))
        }
    }

    private lateinit var analyzerThread: ExecutorService
    private lateinit var faces: FaceDetector
    private val onlookers = OnlookerDetector()
    private var provider: ProcessCameraProvider? = null
    private var cameraOn = false
    @Volatile private var lastChecked = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopCamera()
                    NovaAccessibilityService.instance?.hidePrivacyCurtain()
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> if (screenInUse()) startCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannels()
        analyzerThread = Executors.newSingleThreadExecutor()
        faces = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eyes open?
                .setMinFaceSize(0.05f)
                .enableTracking() // same id for the same person across frames
                .build(),
        )
        ContextCompat.registerReceiver(
            this, screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            ServiceCompat.startForeground(
                this, ID, ongoing(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (e: Exception) {
            // Android refuses a camera service started from the background: wait for Nova to be opened
            Log.w(TAG, "Not allowed to use the camera right now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (screenInUse()) startCamera()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screen) }
        stopCamera()
        NovaAccessibilityService.instance?.hidePrivacyCurtain()
        faces.close()
        analyzerThread.shutdown()
        super.onDestroy()
    }

    private fun screenInUse(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // ── Camera ──────────────────────────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        if (cameraOn) return
        cameraOn = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            if (!cameraOn) return@addListener
            val builder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        )
                        .build(),
                )
            // The camera's lowest frame rate: far less battery than the usual 30 frames a second
            lowestFrameRate()?.let { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val analysis = builder.build().also { it.setAnalyzer(analyzerThread, ::analyze) }
            try {
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                onlookers.reset()
                Log.i(TAG, "Watching (front camera)")
            } catch (e: Exception) {
                // Another app (video call, camera) has the camera: try again next time the screen turns on
                Log.w(TAG, "Front camera not available", e)
                cameraOn = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        if (!cameraOn) return
        cameraOn = false
        runCatching { provider?.unbindAll() }
        Log.i(TAG, "Camera off (screen off or locked)")
    }

    private fun lowestFrameRate(): Range<Int>? = runCatching {
        val cm = getSystemService(CameraManager::class.java)
        val front = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return null
        cm.getCameraCharacteristics(front).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper >= 5 }
            ?.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }.getOrNull()

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (now - lastChecked < EVERY_MS || media == null) {
            proxy.close(); return
        }
        lastChecked = now
        val rotation = proxy.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        faces.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { found ->
                val seen = found.map { f ->
                    SeenFace(
                        id = f.trackingId,
                        left = f.boundingBox.left.toFloat(), top = f.boundingBox.top.toFloat(),
                        right = f.boundingBox.right.toFloat(), bottom = f.boundingBox.bottom.toFloat(),
                        yaw = f.headEulerAngleY, pitch = f.headEulerAngleX,
                        leftEyeOpen = f.leftEyeOpenProbability, rightEyeOpen = f.rightEyeOpenProbability,
                    )
                }
                if (onlookers.update(seen, width, SystemClock.elapsedRealtime())) warn(seen.size)
            }
            .addOnCompleteListener { proxy.close() }
    }

    // ── Warning ─────────────────────────────────────────────────────────────

    private fun warn(faceCount: Int) {
        Log.i(TAG, "Someone else is looking at the screen ($faceCount faces in view)")
        runCatching {
            val v = getSystemService(VibratorManager::class.java).defaultVibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 120, 90), -1))
        }
        NovaAccessibilityService.instance?.showPrivacyCurtain()
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Someone may be looking at your screen")
            .setContentText("Nova noticed another person facing your phone.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Privacy guard", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Nova watches for people looking at your screen"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Someone looking at the screen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When another person seems to be reading your screen"
                enableVibration(false) // Nova vibrates itself, once
                setSound(null, null)
            },
        )
    }

    private fun ongoing(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Privacy guard is on")
            .setContentText("Watching for people looking at your screen. Nothing is recorded.")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
