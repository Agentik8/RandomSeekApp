package app.randomseek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

// Служба нужна только затем, чтобы Android не усыплял приложение, пока играет звук.
public class PlayerService extends Service {
    private static final String CHANNEL = "playback";
    public static final String ACTION_EXIT = "app.randomseek.EXIT";
    private PowerManager.WakeLock lock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
            if (lock != null && lock.isHeld()) lock.release();
            lock = null;
            stopForeground(true);
            stopSelf();
            if (MainActivity.instance != null) {
                MainActivity.instance.exitApp();
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            return 2; // START_NOT_STICKY
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Воспроизведение", 2));
        String title = intent != null ? intent.getStringExtra("title") : null;
        String artist = intent != null ? intent.getStringExtra("artist") : null;
        if (title == null || title.length() == 0) title = "Играет";
        if (artist == null || artist.length() == 0) artist = "Случайная перемотка";
        Intent exitIntent = new Intent(this, PlayerService.class).setAction(ACTION_EXIT);
        PendingIntent exit = PendingIntent.getService(this, 1, exitIntent, 0x04000000);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(0x20000000),
                0x04000000);
        // значок рисуется в коде, отдельные файлы ресурсов не нужны
        Bitmap bm = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(0xFFFFFFFF);
        new Canvas(bm).drawCircle(24f, 24f, 18f, p);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(artist)
                .setShowWhen(false)
                .setSmallIcon(Icon.createWithBitmap(bm))
                .setContentIntent(open)
                .setOngoing(false)
                .setDeleteIntent(exit)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, 2); // 2 = mediaPlayback
        } else {
            startForeground(1, n);
        }
        if (lock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "randomseek:play");
            lock.acquire(6 * 60 * 60 * 1000L);
        }
        return 2; // START_NOT_STICKY
    }

    // Приложение смахнули из списка недавних: закрываем всё полностью.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        stopForeground(true);
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
