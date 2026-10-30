package app.randomseek;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_NOTIF = 2;
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    public static MainActivity instance;

    public class Bridge {
        // Имя последнего открытого трека; пустая строка, если его ещё не было.
        @JavascriptInterface
        public String lastName() {
            File f = new File(getFilesDir(), "last_track");
            if (!f.exists()) return "";
            return getSharedPreferences("app", MODE_PRIVATE).getString("last_name", "");
        }

        @JavascriptInterface
        public void setPlaying(final boolean playing, final String title, final String artist) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    web.setKeepScreenOn(playing);
                    Intent i = new Intent(MainActivity.this, PlayerService.class);
                    if (playing) {
                        i.putExtra("title", title == null ? "" : title);
                        i.putExtra("artist", artist == null ? "" : artist);
                        startForegroundService(i);
                    } else {
                        stopService(i);
                    }
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        requestWindowFeature(1); // без заголовка
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });
        // Страница и последний трек отдаются с условного адреса: так у страницы постоянный
        // источник (настройки сохраняются), и она может прочитать сохранённый файл.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!"appassets.local".equals(u.getHost())) return null;
                String path = u.getPath();
                try {
                    if ("/last".equals(path)) {
                        File f = new File(getFilesDir(), "last_track");
                        if (f.exists()) return new WebResourceResponse("application/octet-stream", null, new FileInputStream(f));
                    } else if ("/".equals(path) || "/index.html".equals(path)) {
                        return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
                    }
                } catch (Exception e) { /* отдадим 404 ниже */ }
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                        new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
            }
        });
        web.loadUrl("https://appassets.local/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && chooser != null) {
            Uri[] uris = WebChromeClient.FileChooserParams.parseResult(res, data);
            chooser.onReceiveValue(uris);
            chooser = null;
            if (uris != null && uris.length > 0) saveLast(uris[0]);
        }
    }

    // Назад сворачивает приложение, а не закрывает: звук продолжает играть.
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    // Копируем выбранный трек во внутреннюю память, чтобы открыть его при следующем запуске.
    private void saveLast(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String name = null;
                    Cursor c = getContentResolver().query(uri, null, null, null, null);
                    if (c != null) {
                        try {
                            int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (i >= 0 && c.moveToFirst()) name = c.getString(i);
                        } finally { c.close(); }
                    }
                    if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
                    File tmp = new File(getFilesDir(), "last_track.tmp");
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(tmp);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    out.close();
                    in.close();
                    File dst = new File(getFilesDir(), "last_track");
                    if (dst.exists()) dst.delete();
                    if (tmp.renameTo(dst)) {
                        getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_name", name).apply();
                    }
                } catch (Exception e) { /* не удалось сохранить: в следующий раз трек выберут заново */ }
            }
        }).start();
    }

    // Полный выход: закрыть окно, остановить службу и завершить процесс.
    public void exitApp() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                stopService(new Intent(MainActivity.this, PlayerService.class));
                finishAndRemoveTask();
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
                }, 300);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        stopService(new Intent(this, PlayerService.class));
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
