package com.wallet.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;

import com.wallet.core.download.ContentDisposition;

/**
 * The in-app browser — where you find the video. You watch a page normally; the
 * moment a video plays, the big <b>Download</b> bar at the bottom lights up and a
 * tap saves it (encrypted) to your vault. It surfaces media three ways, so even a
 * "you can't download this" site is covered:
 *
 * <ul>
 *   <li>{@link WebViewClient#shouldInterceptRequest} sees every network request,
 *       catching {@code .m3u8 / .mpd / .mp4} streams as they load;</li>
 *   <li>an injected JS bridge reports the source of the {@code <video>} that is
 *       actually playing — this catches tokenized/extensionless URLs the request
 *       sniff can't recognise ("the video I'm watching");</li>
 *   <li>{@link android.webkit.DownloadListener} catches plain file downloads.</li>
 * </ul>
 */
public final class BrowserActivity extends Activity {

    private WebView web;
    private EditText address;
    private Button downloadBar;
    private final MediaDetector detector = new MediaDetector();
    private VaultStore vault;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = ((App) getApplication()).vault();
        if (!vault.isUnlocked()) { finish(); return; }     // never browse from a locked vault

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        address = new EditText(this);
        address.setHint(R.string.address_hint);
        address.setSingleLine(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);

        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        downloadBar = new Button(this);
        downloadBar.setAllCaps(false);
        downloadBar.setBackgroundColor(Color.parseColor("#2E7D32"));
        downloadBar.setTextColor(Color.WHITE);
        downloadBar.setVisibility(View.GONE);
        downloadBar.setOnClickListener(v -> downloadDetected());

        root.addView(address);
        root.addView(web);
        root.addView(downloadBar);
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new MediaBridge(), "AndroidMedia");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (detector.offer(url, null)) {        // content-type unknown here; sniff by URL
                    runOnUiThread(BrowserActivity.this::refreshBar);
                }
                return null;                            // observe only, don't replace the response
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
                view.evaluateJavascript(VIDEO_PROBE, null);
            }
        });

        // Direct downloads (Content-Disposition: attachment) give us the MIME type.
        web.setDownloadListener((url, ua, contentDisposition, mime, length) -> {
            detector.offer(url, mime);
            refreshBar();
            promptDownload(url, mime, URLUtil.guessFileName(url, contentDisposition, mime));
        });

        address.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                go(normalize(address.getText().toString()));
                return true;
            }
            return false;
        });

        maybeRequestNotifications();
    }

    /**
     * Injected into every page once loaded: find the {@code <video>} elements (and
     * their {@code <source>} children) and report their real URLs back through the
     * {@code AndroidMedia} bridge — now and whenever one starts playing, since many
     * sites create the player after the page settles.
     */
    private static final String VIDEO_PROBE =
            "(function(){"
          + "  if(window.__wlt)return; window.__wlt=true;"
          + "  function rep(){try{"
          + "    document.querySelectorAll('video').forEach(function(v){"
          + "      if(v.currentSrc)AndroidMedia.found(v.currentSrc);"
          + "      if(v.src)AndroidMedia.found(v.src);"
          + "      v.querySelectorAll('source').forEach(function(s){if(s.src)AndroidMedia.found(s.src);});"
          + "    });"
          + "  }catch(e){}}"
          + "  rep();"
          + "  document.addEventListener('play',rep,true);"
          + "  document.addEventListener('loadeddata',rep,true);"
          + "  setInterval(rep,1500);"
          + "})();";

    /** Bridge target for {@link #VIDEO_PROBE}. {@code found} is called on a binder thread. */
    private final class MediaBridge {
        @JavascriptInterface
        public void found(String url) {
            if (detector.offerVideo(url)) runOnUiThread(BrowserActivity.this::refreshBar);
        }
    }

    /** Android 13+ gates the download progress notification behind a runtime grant. */
    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void go(String url) {
        detector.clear();
        refreshBar();
        web.loadUrl(url);
    }

    private static String normalize(String url) {
        return url.startsWith("http") ? url : "https://" + url;
    }

    /** Show/label the download bar to match what's been detected on this page. */
    private void refreshBar() {
        int n = detector.count();
        if (n == 0) {
            downloadBar.setVisibility(View.GONE);
        } else {
            downloadBar.setVisibility(View.VISIBLE);
            downloadBar.setText(n == 1
                    ? getString(R.string.download_this_video)
                    : getString(R.string.download_pick, n));
        }
    }

    /** The download bar was tapped: save the one video, or let the user pick. */
    private void downloadDetected() {
        List<MediaDetector.Hit> hits = detector.all();
        if (hits.isEmpty()) {
            toast(getString(R.string.no_video_yet));
            return;
        }
        if (hits.size() == 1) {
            MediaDetector.Hit h = hits.get(0);
            promptDownload(h.url, h.contentType, null);
            return;
        }
        String[] labels = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            labels[i] = hits.get(i).kind + "   " + hits.get(i).url;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.media_found)
                .setItems(labels, (d, which) -> {
                    MediaDetector.Hit h = hits.get(which);
                    promptDownload(h.url, h.contentType, null);
                })
                .show();
    }

    private void promptDownload(String url, String mime, String name) {
        if (!vault.isUnlocked()) {
            toast("Unlock the vault first");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_to_vault)
                .setMessage(url)
                .setPositiveButton(R.string.download, (d, w) -> startDownload(url, mime, name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownload(String url, String mime, String name) {
        // Enqueue and let the foreground DownloadService work it; watch it on the
        // Downloads screen (menu). HLS is remuxed to .mp4 by the job.
        String display = (name != null && !name.isEmpty()) ? name : ContentDisposition.fromUrl(url);
        ((App) getApplication()).queue().add(url, display, mime, System.currentTimeMillis());
        Intent i = new Intent(this, DownloadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        toast("Saving to vault: " + display);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Downloads");
        menu.add(0, 2, 1, "Lock vault");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, DownloadsActivity.class));
            return true;
        }
        if (item.getItemId() == 2) {
            ((App) getApplication()).vault().lock();
            startActivity(new Intent(this, ChessActivity.class));
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
