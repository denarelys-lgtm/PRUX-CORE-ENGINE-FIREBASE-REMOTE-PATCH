package com.example.detectcamera;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

import com.example.detectcamera.core.PruxCore;

/**
 * Servicio persistente del servidor Android.
 * El servidor y el monitor ADB son independientes.
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";
    private static final String CHANNEL_ID = "ServerServiceChannel";
    private static final int NOTIFICATION_ID = 7001;

    private volatile boolean running;
    private volatile boolean serviceActive;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceActive = true;

        createNotificationChannel();
        reportCoreState("network", "STARTING", "SERVER_STARTING");
        registerNetworkRecovery();

        startForeground(NOTIFICATION_ID, buildNotification("Inicializando servidor..."));

        try {
            WebServerManager.start(this);
            running = true;
            reportCoreState("network", "RUNNING", "SERVER_STARTED");
            updateNotification();
            Log.i(TAG, "Servidor Android iniciado y escuchando en puerto "
                    + WebServerManager.getPuerto());

            // Solo publicamos el dispositivo y abrimos el túnel si el servidor local arrancó.
            DeviceRegistryManager.registrarDispositivoEnNube(getApplicationContext(), null);
            CloudflareTunnelManager.iniciarTunel(this);
        } catch (IOException e) {
            running = false;
            reportCoreState("network", "ERROR", "SERVER_START_FAILED");
            Log.e(TAG, "No se pudo iniciar WebServer; Cloudflare no será iniciado", e);
        }

        PruxAdbEngine.get(this).startPersistentMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        // Punto 10: puerto real desde WebServerManager.
        String ip = "Servidor local";
        if (WebServerManager.get() != null) {
            int puerto = WebServerManager.getPuerto();
            ip = "Servidor activo en :" + puerto;
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DetectCamera")
                .setContentText(text + " · " + ip)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification("Servidor activo"));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID,
                        "Servidor DetectCamera",
                        NotificationManager.IMPORTANCE_MIN));
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        serviceActive = false;
        reportCoreState("network", "STOPPED", "SERVER_DESTROYED");
        PruxCore core = PruxCore.get();
        if (core != null) core.recovery().unregister("network");

        WebServerManager.stop();
        CloudflareTunnelManager.detenerTunel();

        Log.w(TAG, "ServerService destruido");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent =
                new Intent(getApplicationContext(), CameraService.class);
        restartServiceIntent.setPackage(getPackageName());

        PendingIntent restartServicePI = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

        AlarmManager alarmService =
                (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000,
                    restartServicePI);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void registerNetworkRecovery() {
        PruxCore core = PruxCore.get();
        if (core == null) return;
        core.recovery().register("network", () -> {
            if (!serviceActive) return false;
            try {
                WebServerManager.stop();
                WebServerManager.start(getApplicationContext());
                running = true;
                updateNotification();
                CloudflareTunnelManager.iniciarTunel(getApplicationContext());
                Log.i(TAG, "Servidor recuperado por PRUX CORE");
                return true;
            } catch (IOException error) {
                Log.e(TAG, "Reintento de recuperación del servidor falló", error);
                return false;
            }
        });
    }

    private void reportCoreState(String key, String state, String event) {
        PruxCore core = PruxCore.get();
        if (core != null) core.reportComponent(key, state, event, null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
