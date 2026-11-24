package app.daftari;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** غلاف أندرويد لتطبيق «دفتري»: يعرض ملفات الويب المضمّنة ويضيف قدرات الجوال. */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST + "/";
    static final int REQ_FILE = 1, REQ_SAVE = 2, REQ_AUTH = 3, REQ_CONTACTS = 10, REQ_SMS = 11;

    WebView web;
    ValueCallback<Uri[]> fileCb;
    String pendingSave;
    long smsSince;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0F766E);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " DaftariAndroid/1.0");
        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    return new WebResourceResponse(mime(path), "utf-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Uri u = Uri.parse(url);
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* لا يوجد تطبيق */ }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر ملف"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("حسناً", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); } })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { r.confirm(); } })
                    .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("نعم", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); } })
                    .setNegativeButton("لا", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.cancel(); } })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { r.cancel(); } })
                    .show();
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(BASE + "index.html");
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    void js(final String code) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(code, null); } });
    }

    boolean granted(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    // ---------- جهات الاتصال ----------
    void sendContacts() {
        new Thread(new Runnable() { public void run() {
            JSONArray out = new JSONArray();
            Map<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
            Cursor c = null;
            try {
                c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] { ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                                   ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                   ContactsContract.CommonDataKinds.Phone.NUMBER },
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                while (c != null && c.moveToNext()) {
                    String id = c.getString(0);
                    JSONObject o = byId.get(id);
                    if (o == null) {
                        o = new JSONObject();
                        o.put("name", c.getString(1) == null ? "" : c.getString(1));
                        o.put("phones", new JSONArray());
                        byId.put(id, o);
                        out.put(o);
                    }
                    if (c.getString(2) != null) o.getJSONArray("phones").put(c.getString(2));
                }
            } catch (Exception e) { /* نعيد ما جُمع */ }
            finally { if (c != null) c.close(); }
            js("window.onNativeContacts && onNativeContacts(" + JSONObject.quote(out.toString()) + ")");
        } }).start();
    }

    // ---------- رسائل SMS ----------
    void sendSms() {
        final long since = smsSince;
        new Thread(new Runnable() { public void run() {
            JSONArray out = new JSONArray();
            Cursor c = null;
            try {
                c = getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[] { "address", "body", "date" }, "date > ?",
                    new String[] { String.valueOf(since) }, "date ASC");
                while (c != null && c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("a", c.getString(0) == null ? "" : c.getString(0));
                    o.put("b", c.getString(1) == null ? "" : c.getString(1));
                    o.put("d", c.getLong(2));
                    out.put(o);
                }
            } catch (Exception e) { /* نعيد ما جُمع */ }
            finally { if (c != null) c.close(); }
            js("window.onNativeSms && onNativeSms(" + JSONObject.quote(out.toString()) + ")");
        } }).start();
    }

    // ---------- مشاركة التطبيق نفسه ----------
    void shareApk(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(ApkProvider.MIME);
        i.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://app.daftari.apk/Daftari.apk"));
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "مشاركة دفتري"));
    }

    class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }

        @JavascriptInterface public void pickContacts() {
            runOnUiThread(new Runnable() { public void run() {
                if (granted(Manifest.permission.READ_CONTACTS)) sendContacts();
                else requestPermissions(new String[] { Manifest.permission.READ_CONTACTS }, REQ_CONTACTS);
            } });
        }

        @JavascriptInterface public boolean hasSmsPermission() { return granted(Manifest.permission.READ_SMS); }

        @JavascriptInterface public void readSms(String sinceMs) {
            try { smsSince = Long.parseLong(sinceMs); } catch (Exception e) { smsSince = 0; }
            runOnUiThread(new Runnable() { public void run() {
                if (granted(Manifest.permission.READ_SMS)) sendSms();
                else requestPermissions(new String[] { Manifest.permission.READ_SMS }, REQ_SMS);
            } });
        }

        @JavascriptInterface public void saveFile(final String name, String content) {
            pendingSave = content;
            runOnUiThread(new Runnable() { public void run() {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(i, REQ_SAVE);
            } });
        }

        @JavascriptInterface public void shareApp(final String text) {
            runOnUiThread(new Runnable() { public void run() { shareApk(text); } });
        }

        @JavascriptInterface public String clipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData c = cm.getPrimaryClip();
                if (c != null && c.getItemCount() > 0) {
                    CharSequence t = c.getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                }
            } catch (Exception e) { /* ignore */ }
            return "";
        }

        @JavascriptInterface public boolean canDeviceAuth() {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardSecure();
        }

        @JavascriptInterface public void deviceAuth() {
            runOnUiThread(new Runnable() { public void run() {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent("دفتري", "افتح التطبيق بالبصمة أو قفل الجوال");
                if (i == null) js("onDeviceAuth(false)");
                else startActivityForResult(i, REQ_AUTH);
            } });
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_CONTACTS) { if (ok) sendContacts(); else js("onNativeDenied('contacts')"); }
        if (req == REQ_SMS) { if (ok) sendSms(); else js("onNativeDenied('sms')"); }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
        } else if (req == REQ_SAVE) {
            if (result == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(pendingSave.getBytes("UTF-8"));
                    os.close();
                    js("toast('✅ تم حفظ النسخة الاحتياطية')");
                } catch (Exception e) { js("alert('تعذّر حفظ الملف')"); }
            }
            pendingSave = null;
        } else if (req == REQ_AUTH) {
            js("onDeviceAuth(" + (result == RESULT_OK) + ")");
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.handleBack ? handleBack() : false", new ValueCallback<String>() {
            public void onReceiveValue(String v) { if (!"true".equals(v)) finish(); }
        });
    }
}
