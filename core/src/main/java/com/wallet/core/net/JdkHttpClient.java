package com.wallet.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;

/**
 * {@link HttpClient} backed by {@code HttpURLConnection}. Available identically on
 * the desktop JVM and Android, so the download engine needs no platform shim.
 */
public final class JdkHttpClient implements HttpClient {

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String userAgent;

    public JdkHttpClient() {
        this(15_000, 30_000,
             "Mozilla/5.0 (Linux; Android) Wallet/1.0");   // many CDNs reject blank UAs
    }

    public JdkHttpClient(int connectTimeoutMs, int readTimeoutMs, String userAgent) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.userAgent = userAgent;
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws IOException {
        final HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);   // note: HttpURLConnection won't cross http<->https
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", userAgent);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        final int status = conn.getResponseCode();
        final InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        return new Response() {
            @Override public int status() { return status; }
            @Override public String header(String name) { return conn.getHeaderField(name); }
            @Override public long contentLength() { return conn.getContentLengthLong(); }
            @Override public InputStream body() { return in; }
            @Override public String finalUrl() { return conn.getURL().toString(); }

            @Override public void close() {
                try { if (in != null) in.close(); } catch (IOException ignored) { /* closing */ }
                conn.disconnect();
            }
        };
    }
}
