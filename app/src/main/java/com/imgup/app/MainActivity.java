package com.imgup.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final int PICK_IMAGES = 1;
    private static final long MAX_DOWNLOAD = 64L * 1024 * 1024;
    private static final String UA = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") imgup";

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray pending = new JSONArray();

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private File sharedDir;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keeps cookies between the requests made through the HTTP bridge (used for deleting)
        if (!(CookieHandler.getDefault() instanceof CookieManager)) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }

        sharedDir = new File(getCacheDir(), "shared");
        sharedDir.mkdirs();
        cleanOldShares();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0E0F);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new HttpBridge(), "NativeBridge");
        web.addJavascriptInterface(new ShareBridge(), "NativeShareBridge");
        web.addJavascriptInterface(new AppBridge(), "ImgupAndroid");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.loadUrl(HOME);

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        pool.shutdown();
        super.onDestroy();
    }

    /* ---------------- shared images and image links ---------------- */

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                receiveUri(stream);
            } else {
                String text = intent.getStringExtra(Intent.EXTRA_TEXT);
                String link = firstLink(text);
                if (link != null) receiveLink(link);
                else deliverError("Share an image or a link to an image");
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) for (Uri u : streams) if (u != null) receiveUri(u);
        }
        // Don't handle the same share twice
        setIntent(new Intent(Intent.ACTION_MAIN));
    }

    private void receiveUri(Uri uri) {
        pool.execute(() -> {
            String id = UUID.randomUUID().toString();
            File out = new File(sharedDir, id);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("no stream");
                copy(in, new FileOutputStream(out), MAX_DOWNLOAD);
                String mime = getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) mime = sniff(out);
                if (mime == null) {
                    out.delete();
                    deliverError("That file isn't an image");
                    return;
                }
                deliverFile(id, displayName(uri), mime);
            } catch (Exception e) {
                out.delete();
                deliverError("Couldn't read the shared image");
            }
        });
    }

    private void receiveLink(String link) {
        pool.execute(() -> {
            try {
                download(link, 0);
            } catch (Exception e) {
                deliverError("Couldn't download the image from that link");
            }
        });
    }

    private void download(String link, int depth) throws Exception {
        HttpURLConnection c = open(link);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code);
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            String finalUrl = c.getURL().toString();

            if (type.startsWith("text/html") && depth == 0) {
                // A web page: use the image it advertises (og:image / twitter:image)
                String html = readText(c.getInputStream(), 2 * 1024 * 1024);
                String img = metaImage(html);
                if (img == null) {
                    deliverError("That link isn't an image");
                    return;
                }
                download(new URL(new URL(finalUrl), img).toString(), depth + 1);
                return;
            }

            String id = UUID.randomUUID().toString();
            File out = new File(sharedDir, id);
            try (InputStream in = c.getInputStream()) {
                copy(in, new FileOutputStream(out), MAX_DOWNLOAD);
            }
            String mime = type.startsWith("image/") ? type.split(";")[0].trim() : sniff(out);
            if (mime == null) {
                out.delete();
                deliverError("That link isn't an image");
                return;
            }
            deliverFile(id, nameFromUrl(finalUrl), mime);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String link) throws IOException {
        String current = link;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "image/*,text/html;q=0.8,*/*;q=0.5");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("bad redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private void deliverFile(String id, String name, String mime) {
        try {
            writeString(new File(sharedDir, id + ".type"), mime);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name == null ? "image" : name);
            o.put("mime", mime);
            o.put("src", "/shared/" + id);
            deliver(o);
        } catch (Exception ignored) {
        }
    }

    private void deliverError(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", UUID.randomUUID().toString());
            o.put("error", message);
            deliver(o);
        } catch (Exception ignored) {
        }
    }

    private void deliver(JSONObject item) {
        main.post(() -> {
            if (pageReady) {
                JSONArray one = new JSONArray().put(item);
                web.evaluateJavascript("window.imgupReceive && window.imgupReceive("
                        + JSONObject.quote(one.toString()) + ")", null);
            } else {
                synchronized (pending) {
                    pending.put(item);
                }
            }
        });
    }

    private void flushPending() {
        String json;
        synchronized (pending) {
            if (pending.length() == 0) return;
            json = pending.toString();
            while (pending.length() > 0) pending.remove(0);
        }
        web.evaluateJavascript("window.imgupReceive && window.imgupReceive(" + JSONObject.quote(json) + ")", null);
    }

    /* ---------------- JavaScript bridges ---------------- */

    /** HTTP requests without browser CORS limits. Same contract imgup's page already uses. */
    private class HttpBridge {
        @JavascriptInterface
        public void httpRequestAsync(String json, String id) {
            pool.execute(() -> {
                String result = request(json);
                main.post(() -> web.evaluateJavascript("window.__wtaNativeHttpResponse && window.__wtaNativeHttpResponse("
                        + JSONObject.quote(id) + "," + JSONObject.quote(result) + ")", null));
            });
        }

        @JavascriptInterface
        public void share(String title, String text, String url) {
            shareLink(title, text, url);
        }
    }

    private class ShareBridge {
        @JavascriptInterface
        public void shareText(String title, String text, String url) {
            shareLink(title, text, url);
        }
    }

    private class AppBridge {
        @JavascriptInterface
        public String pending() {
            synchronized (pending) {
                String json = pending.toString();
                while (pending.length() > 0) pending.remove(0);
                return json;
            }
        }

        @JavascriptInterface
        public void copy(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("imgup link", text));
            });
        }
    }

    private void shareLink(String title, String text, String url) {
        String value = url != null && !url.isEmpty() ? url : text;
        if (value == null || value.isEmpty()) return;
        main.post(() -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, value);
            startActivity(Intent.createChooser(send, null));
        });
    }

    private String request(String json) {
        JSONObject out = new JSONObject();
        HttpURLConnection c = null;
        try {
            JSONObject req = new JSONObject(json);
            String url = req.getString("url");
            if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("Only web links are allowed");
            String method = req.optString("method", "GET").toUpperCase(Locale.ROOT);
            byte[] body = Base64.decode(req.optString("bodyBase64", ""), Base64.DEFAULT);

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            JSONObject headers = req.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    // Cookies are handled by the cookie store
                    if (k.equalsIgnoreCase("cookie") || k.equalsIgnoreCase("content-length")) continue;
                    c.setRequestProperty(k, headers.optString(k));
                }
            }
            if (body.length > 0 && !method.equals("GET")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in, MAX_DOWNLOAD);

            JSONObject h = new JSONObject();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                h.put(e.getKey(), String.join(", ", e.getValue()));
            }
            out.put("ok", true);
            out.put("status", code);
            out.put("statusText", c.getResponseMessage() == null ? "" : c.getResponseMessage());
            out.put("url", c.getURL().toString());
            out.put("headers", h);
            out.put("bodyBase64", Base64.encodeToString(data, Base64.NO_WRAP));
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
            }
        } finally {
            if (c != null) c.disconnect();
        }
        return out.toString();
    }

    /* ---------------- WebView plumbing ---------------- */

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if (path.equals("/") || path.equals("/index.html")) {
                    return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
                }
                if (path.startsWith("/shared/")) {
                    String id = u.getLastPathSegment();
                    if (id != null && id.matches("[A-Za-z0-9-]+")) {
                        File f = new File(sharedDir, id);
                        if (f.isFile()) {
                            String mime = readString(new File(sharedDir, id + ".type"));
                            return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null, new FileInputStream(f));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            WebResourceResponse missing = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            missing.setStatusCodeAndReasonPhrase(404, "Not Found");
            return missing;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (HOST.equals(u.getHost())) return false;
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("intent")) {
                openIntentLink(u.toString());
            } else if (scheme.equals("http") || scheme.equals("https")) {
                openInBrowser(u);
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            flushPending();
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            boolean multiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            Intent pick;
            if (Build.VERSION.SDK_INT >= 33) {
                pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
                if (multiple) pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
            } else {
                pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            }
            try {
                startActivityForResult(pick, PICK_IMAGES);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void openIntentLink(String link) {
        try {
            Intent i = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                String fallback = i.getStringExtra("browser_fallback_url");
                if (fallback != null) openInBrowser(Uri.parse(fallback));
            }
        } catch (Exception ignored) {
        }
    }

    private void openInBrowser(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /* ---------------- helpers ---------------- */

    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_IMG = Pattern.compile(
            "<meta[^>]+(?:property|name)\\s*=\\s*[\"'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT = Pattern.compile("content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static String firstLink(String text) {
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        if (!m.find()) return null;
        return m.group().replaceAll("[).,;:!?\\]}]+$", "");
    }

    private static String metaImage(String html) {
        Matcher m = META_IMG.matcher(html);
        while (m.find()) {
            Matcher c = CONTENT.matcher(m.group());
            if (c.find()) return c.group(1).replace("&amp;", "&");
        }
        return null;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "image" : last;
    }

    private static String nameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isEmpty() ? "image" : Uri.decode(last);
        } catch (Exception e) {
            return "image";
        }
    }

    /** Detects common image formats from the first bytes. */
    private static String sniff(File f) {
        byte[] b = new byte[12];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(b);
        } catch (IOException e) {
            return null;
        }
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (n >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (n >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, StandardCharsets.US_ASCII);
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.equals("mif1")) return "image/heic";
            if (brand.startsWith("avi")) return "image/avif";
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream os, long max) throws IOException {
        try (OutputStream out = os) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > max) throw new IOException("too large");
                out.write(buf, 0, r);
            }
        }
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream src = in) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int r;
            while ((r = src.read(buf)) != -1) {
                total += r;
                if (total > max) throw new IOException("too large");
                bos.write(buf, 0, r);
            }
        }
        return bos.toByteArray();
    }

    private static String readText(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream src = in) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = src.read(buf)) != -1 && bos.size() < max) bos.write(buf, 0, r);
        }
        return bos.toString("UTF-8");
    }

    private static void writeString(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readString(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in, 1024), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void cleanOldShares() {
        File[] files = sharedDir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File f : files) if (f.lastModified() < cutoff) f.delete();
    }
}
