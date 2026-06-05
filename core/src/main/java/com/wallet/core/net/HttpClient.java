package com.wallet.core.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * The seam between download logic and the network. The real implementation wraps
 * {@code HttpURLConnection} (works on the JVM and Android); tests inject a fake
 * that serves canned responses, so the whole download engine is verified with no
 * sockets.
 */
public interface HttpClient {

    Response get(String url, Map<String, String> headers) throws IOException;

    interface Response extends AutoCloseable {
        int status();
        /** Header value (case-insensitive), or null. */
        String header(String name);
        /** Body length in bytes, or -1 if unknown. */
        long contentLength();
        InputStream body();
        /** The URL after following redirects. */
        String finalUrl();

        @Override
        void close();
    }
}
