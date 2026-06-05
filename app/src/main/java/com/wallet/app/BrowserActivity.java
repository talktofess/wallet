package com.wallet.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
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
 * The in-app browser. As a page loads, {@link WebViewClient#shouldInterceptRequest}
 * sees every network request and {@link android.webkit.DownloadListener} catches
 * direct downloads; both feed a {@link MediaDetector}, so streamed video the page
 * never offers as a "download" still surfaces. Tapping a hit runs a
 * {@link DownloadJob} and stores the result encrypted in the vault.
 */
public final class BrowserActivity extends Activity {

    private WebView web;
    private Button downloadsButton;
    private final MediaDetector detector = new MediaDetector();
    private VaultStore vault;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = ((App) getApplication()).vault();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        final EditText address = new EditText(this);
        address.setHint(R.string.address_hint);
        address.setSingleLine(true);
        address.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        downloadsButton = new Button(this);
        downloadsButton.setText("0");
        downloadsButton.setEnabled(false);
        downloadsButton.setOnClickListener(v -> showDownloads());

        bar.addView(address);
        bar.addView(downloadsButton);

        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(bar);
        root.addView(web);
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (detector.offer(url, null)) {        // content-type unknown here; sniff by URL
                    runOnUiThread(BrowserActivity.this::updateBadge);
                }
                return null;                            // observe only, don't replace the response
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
            }
        });

        // Direct downloads (Content-Disposition: attachment) give us the MIME type.
        web.setDownloadListener((url, ua, contentDisposition, mime, length) -> {
            detector.offer(url, mime);
            updateBadge();
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

    /** Android 13+ gates the download progress notification behind a runtime grant. */
    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void go(String url) {
        detector.clear();
        updateBadge();
        web.loadUrl(url);
    }

    private static String normalize(String url) {
        return url.startsWith("http") ? url : "https://" + url;
    }

    private void updateBadge() {
        int n = detector.count();
        downloadsButton.setText(String.valueOf(n));
        downloadsButton.setEnabled(n > 0);
    }

    private void showDownloads() {
        final List<MediaDetector.Hit> hits = detector.all();
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
        toast("Queued: " + display);
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
