package com.roadvision.roadsense.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.roadvision.roadsense.MainActivity

/**
 * Foreground service that keeps the CameraX analysis bound to a RESUMED lifecycle while the
 * Activity is stopped or the screen is off. It deliberately exposes no binder: the ViewModel owns
 * the scheduler/pipeline and hands the controller over through [CaptureBridge].
 */
class CaptureService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    0
                },
            )
        }.onFailure {
            stopSelf()
            return
        }
        val controller = CaptureBridge.controller
        if (controller == null) {
            stopSelf()
            return
        }
        registry.currentState = Lifecycle.State.RESUMED
        controller.start(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            CaptureBridge.controller?.let { controller ->
                controller.stop()
                controller.start(this, this)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CaptureBridge.controller?.stop()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "道路采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "RoadVision 前台采集状态"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("RoadVision 正在采集")
        .setContentText("本地处理，不上传；点击返回应用")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        const val ACTION_RESTART = "com.roadvision.roadsense.action.RESTART_CAPTURE"
        private const val CHANNEL_ID = "roadvision-capture"
        private const val NOTIFICATION_ID = 4101
    }
}
