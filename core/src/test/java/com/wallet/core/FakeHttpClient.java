package com.wallet.core;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wallet.core.net.HttpClient;

/**
 * In-memory {@link HttpClient} for tests: serves canned responses (or computes
 * them from the request via a handler, e.g. to react to a Range header) and
 * records every request so tests can assert what headers were sent.
 */
public final class FakeHttpClient implements HttpClient {

    public static final class Stub {
        public int status = 200;
        public byte[] body = new byte[0];
        public final Map<String, String> headers = new HashMap<>();
    }

    public interface Handler {
        Stub handle(String url, Map<String, String> requestHeaders);
    }

    public final List<String> requestedUrls = new ArrayList<>();
    public final List<Map<String, String>> requestHeaders = new ArrayList<>();

    private final Map<String, Stub> stubs = new HashMap<>();
    private Handler handler;

    public FakeHttpClient stub(String url, int status, byte[] body, String... headerKV) {
        Stub s = new Stub();
        s.status = status;
        s.body = body;
        for (int i = 0; i + 1 < headerKV.length; i += 2) s.headers.put(headerKV[i], headerKV[i + 1]);
        stubs.put(url, s);
        return this;
    }

    public FakeHttpClient handler(Handler h) {
        this.handler = h;
        return this;
    }

    public Map<String, String> lastHeaders() {
        return requestHeaders.get(requestHeaders.size() - 1);
    }

    @Override
    public Response get(String url, Map<String, String> headers) {
        requestedUrls.add(url);
        requestHeaders.add(headers == null ? new HashMap<>() : new HashMap<>(headers));

        Map<String, String> h = headers == null ? new HashMap<>() : headers;
        Stub s = handler != null ? handler.handle(url, h) : stubs.get(url);
        if (s == null) {
            s = new Stub();
            s.status = 404;
        }
        final Stub fs = s;

        return new Response() {
            @Override public int status() { return fs.status; }
            @Override public String header(String name) {
                for (Map.Entry<String, String> e : fs.headers.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
                }
                return null;
            }
            @Override public long contentLength() {
                String c = header("Content-Length");
                return c != null ? Long.parseLong(c) : fs.body.length;
            }
            @Override public InputStream body() { return new ByteArrayInputStream(fs.body); }
            @Override public String finalUrl() { return url; }
            @Override public void close() { }
        };
    }
}
