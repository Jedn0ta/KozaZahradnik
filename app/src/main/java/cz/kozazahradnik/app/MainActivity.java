package cz.kozazahradnik.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Koza Zahradník — aplikace běží jako jedna HTML stránka v WebView.
 *
 * Tři věci, kvůli kterým tu je nativní kód:
 *
 * 1) Assety servírujeme přes WebViewAssetLoader na https origin, ne přes
 *    file://. Jedině tak localStorage spolehlivě přežije restart aplikace,
 *    a na něm stojí celá uložená zahrada.
 *
 * 2) Dotazy na katastr jdou přes KozaNative.get(). Služba ČÚZK neposílá
 *    hlavičky CORS, takže fetch() z WebView by na ni nedosáhl. Nativní
 *    HttpURLConnection žádné CORS neřeší.
 *
 * 3) Aktualizace. Aplikace si umí stáhnout novou verzi sama sebe z veřejného
 *    repozitáře na GitHubu. Stažený soubor uložíme do interního úložiště
 *    a servírujeme ho z /update/ — tedy ze stejného originu jako assety,
 *    takže uložená zahrada v localStorage zůstane nedotčená. Při startu
 *    pustíme novější z obou verzí. Když se stažená verze třikrát po sobě
 *    neohlásí, že se rozjela, zahodíme ji a vrátíme se k té z APK.
 */
public class MainActivity extends Activity {

    /** Nominatim i ČÚZK chtějí, aby se klient představil. */
    private static final String UA =
            "KozaZahradnik/1.0 (Android; kontakt doplň svůj e-mail)";

    /** Verze HTML zabudovaneho v tomhle APK. Musi odpovidat CISLO_VERZE v index.html. */
    private static final int ZABUDOVANA_VERZE = 42;

    private static final String PREFS = "koza";
    private static final String P_POKUSY = "aktualizace-pokusy";
    private static final String P_META = "aktualizace-meta";
    private static final String ZAKLAD = "https://appassets.androidplatform.net";

    private WebView web;
    private ValueCallback<Uri[]> vyberSouboru;
    private static final int REQ_SOUBOR = 1001;

    private File adresarAktualizace() {
        return new File(getFilesDir(), "update");
    }

    private File souborAktualizace() {
        return new File(adresarAktualizace(), "index.html");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** Cislo stazene verze, nebo 0 kdyz zadna neni. */
    private int cisloStazene() {
        String m = prefs().getString(P_META, "");
        java.util.regex.Matcher mm =
                java.util.regex.Pattern.compile("\"cislo\"\\s*:\\s*(\\d+)").matcher(m);
        return mm.find() ? Integer.parseInt(mm.group(1)) : 0;
    }

    private void zahodAktualizaci() {
        File f = souborAktualizace();
        if (f.exists()) f.delete();
        prefs().edit().remove(P_META).putInt(P_POKUSY, 0).apply();
    }

    @Override
    protected void onCreate(Bundle stav) {
        super.onCreate(stav);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage = uložená zahrada
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.addJavascriptInterface(new Most(), "KozaNative");

        adresarAktualizace().mkdirs();

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/update/", new WebViewAssetLoader.InternalStoragePathHandler(
                        this, adresarAktualizace()))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                // odkazy na obchody s osivem otevřeme v prohlížeči, ne uvnitř aplikace
                if (u.getHost() != null && !u.getHost().equals("appassets.androidplatform.net")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                        return true;
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }
        });

        // Výběr fotky záhonu: foťák i galerie
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (vyberSouboru != null) vyberSouboru.onReceiveValue(null);
                vyberSouboru = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_SOUBOR);
                } catch (Exception e) {
                    vyberSouboru = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(ZAKLAD + vyberStranku());
    }

    /**
     * Ktere HTML pustit: stazenou aktualizaci, nebo verzi z APK.
     * Stazena vyhrava jen kdyz je novejsi a kdyz se predtim rozjela.
     */
    private String vyberStranku() {
        final String vApk = "/assets/www/index.html";
        if (!souborAktualizace().exists()) return vApk;

        if (cisloStazene() <= ZABUDOVANA_VERZE) {   // APK uz je stejne nove nebo novejsi
            zahodAktualizaci();
            return vApk;
        }

        int pokusy = prefs().getInt(P_POKUSY, 0);
        if (pokusy >= 3) {                           // stazena verze se nerozjizdi
            zahodAktualizaci();
            return vApk;
        }
        prefs().edit().putInt(P_POKUSY, pokusy + 1).apply();
        return "/update/index.html";
    }

    @Override
    protected void onActivityResult(int req, int vysledek, Intent data) {
        if (req == REQ_SOUBOR) {
            if (vyberSouboru == null) return;
            vyberSouboru.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(vysledek, data));
            vyberSouboru = null;
            return;
        }
        super.onActivityResult(req, vysledek, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /**
     * Most pro stahování z katastru a meteoslužby.
     *
     * Metody označené @JavascriptInterface běží na vlastním vlákně WebView,
     * ne na hlavním, takže blokující požadavek je tu v pořádku.
     */
    public class Most {

        @JavascriptInterface
        public String get(String adresa, String accept) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(adresa);
                String schema = u.getProtocol();
                if (!"https".equals(schema) && !"http".equals(schema)) {
                    return "KOZA_ERR:nepodporovaný protokol";
                }
                c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept-Encoding", "gzip");
                if (accept != null && accept.length() > 0) {
                    c.setRequestProperty("Accept", accept);
                }

                int kod = c.getResponseCode();
                InputStream is = (kod >= 200 && kod < 400) ? c.getInputStream() : c.getErrorStream();
                if (is == null) return "KOZA_ERR:HTTP " + kod;
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                    is = new GZIPInputStream(is);
                }

                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                r.close();

                if (kod < 200 || kod >= 300) {
                    return "KOZA_ERR:HTTP " + kod;
                }
                return sb.toString();
            } catch (Exception e) {
                return "KOZA_ERR:" + e.getClass().getSimpleName() + " " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** Ulozi stazenou verzi. meta je JSON s cislem, nazvem verze a popisem. */
        @JavascriptInterface
        public String ulozAktualizaci(String meta, String html) {
            if (html == null || html.length() < 20000) return "KOZA_ERR:soubor je podezrele maly";
            if (html.indexOf("KOZA_APP") < 0) return "KOZA_ERR:tohle neni Koza Zahradnik";
            try {
                adresarAktualizace().mkdirs();
                File tmp = new File(adresarAktualizace(), "index.html.tmp");
                OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(tmp), StandardCharsets.UTF_8);
                w.write(html);
                w.close();
                File cil = souborAktualizace();
                if (cil.exists() && !cil.delete()) return "KOZA_ERR:nelze prepsat starou verzi";
                if (!tmp.renameTo(cil)) return "KOZA_ERR:nelze ulozit";
                prefs().edit().putString(P_META, meta == null ? "" : meta)
                        .putInt(P_POKUSY, 0).apply();
                return "OK";
            } catch (Exception e) {
                return "KOZA_ERR:" + e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }

        /** Co je stazene (JSON), nebo prazdny retezec. */
        @JavascriptInterface
        public String aktualizaceInfo() {
            return souborAktualizace().exists() ? prefs().getString(P_META, "") : "";
        }

        @JavascriptInterface
        public String smazAktualizaci() {
            zahodAktualizaci();
            return "OK";
        }

        /** Stranka se rozjela - pojistka se vynuluje. */
        @JavascriptInterface
        public void aktualizaceOk() {
            prefs().edit().putInt(P_POKUSY, 0).apply();
        }

        /** Verze zabudovana v APK. */
        @JavascriptInterface
        public int zabudovanaVerze() {
            return ZABUDOVANA_VERZE;
        }

        /** Restart stranky se spravnou verzi. */
        @JavascriptInterface
        public void restart() {
            web.post(new Runnable() {
                public void run() {
                    web.clearCache(true);
                    web.loadUrl(ZAKLAD + vyberStranku());
                }
            });
        }
    }
}
