package cz.kozazahradnik.app;

import android.app.Activity;
import android.content.Intent;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Koza Zahradník — aplikace běží jako jedna HTML stránka v WebView.
 *
 * Dvě věci, kvůli kterým tu je nativní kód:
 *
 * 1) Assety servírujeme přes WebViewAssetLoader na https origin, ne přes
 *    file://. Jedině tak localStorage spolehlivě přežije restart aplikace,
 *    a na něm stojí celá uložená zahrada.
 *
 * 2) Dotazy na katastr jdou přes KozaNative.get(). Služba ČÚZK neposílá
 *    hlavičky CORS, takže fetch() z WebView by na ni nedosáhl. Nativní
 *    HttpURLConnection žádné CORS neřeší.
 */
public class MainActivity extends Activity {

    /** Nominatim i ČÚZK chtějí, aby se klient představil. */
    private static final String UA =
            "KozaZahradnik/1.0 (Android; kontakt doplň svůj e-mail)";

    private WebView web;
    private ValueCallback<Uri[]> vyberSouboru;
    private static final int REQ_SOUBOR = 1001;

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

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
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

        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
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
    }
}
