package com.jvuln.collector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static com.jvuln.util.ValueUtils.errorMessage;

@Component
public class SecureEvidenceFetcher implements EvidencePageFetcher {

    private static final int DEFAULT_MAX_BYTES = 512 * 1024;
    private static final int DEFAULT_MAX_CHARS = 4000;
    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 8000;
    private final PublicUrlPolicy urlPolicy;
    private final int maxBytes;
    private final int maxChars;
    private final int maxRedirects;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SecureEvidenceFetcher() {
        this(new PublicUrlPolicy(), DEFAULT_MAX_BYTES, DEFAULT_MAX_CHARS,
                DEFAULT_MAX_REDIRECTS, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    SecureEvidenceFetcher(PublicUrlPolicy urlPolicy, int maxBytes, int maxChars,
                          int maxRedirects, int connectTimeoutMs, int readTimeoutMs) {
        this.urlPolicy = urlPolicy;
        this.maxBytes = maxBytes;
        this.maxChars = maxChars;
        this.maxRedirects = maxRedirects;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public FetchOutcome fetch(String url) {
        try {
            URI current = URI.create(url);
            for (int redirect = 0; redirect <= maxRedirects; redirect++) {
                current = urlPolicy.requirePublic(current.toString());
                HttpURLConnection connection = open(current.toURL());
                try {
                    int status = connection.getResponseCode();
                    if (isRedirect(status)) {
                        if (redirect == maxRedirects) {
                            return FetchOutcome.failed("Evidence redirect limit exceeded");
                        }
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.trim().isEmpty()) {
                            return FetchOutcome.failed("Evidence redirect has no location");
                        }
                        current = current.resolve(location.trim());
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        return FetchOutcome.failed("Evidence server returned HTTP " + status);
                    }
                    String body = readBounded(connection);
                    String excerpt = extract(body, connection.getContentType());
                    if (excerpt.isEmpty()) {
                        return FetchOutcome.failed("Evidence response contained no text");
                    }
                    return FetchOutcome.success(excerpt);
                } finally {
                    connection.disconnect();
                }
            }
            return FetchOutcome.failed("Evidence redirect limit exceeded");
        } catch (SecurityException e) {
            return FetchOutcome.rejected(errorMessage(e, 300));
        } catch (SocketTimeoutException e) {
            return FetchOutcome.timedOut("Evidence fetch timed out");
        } catch (Exception e) {
            return FetchOutcome.failed(errorMessage(e, 300));
        }
    }

    private HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "JVuln-Platform/1.0 Evidence Collector");
        connection.setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml");
        return connection;
    }

    private String readBounded(HttpURLConnection connection) throws IOException {
        long contentLength = connection.getContentLengthLong();
        if (contentLength > maxBytes) {
            throw new IOException("Evidence response size exceeds limit");
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Evidence response size exceeds limit");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String extract(String body, String contentType) {
        String text;
        if ((contentType != null && contentType.toLowerCase().contains("html"))
                || body.trim().startsWith("<")) {
            Document document = Jsoup.parse(body);
            document.select("script, style, nav, footer, header, aside, iframe, "
                    + "video, audio, canvas, svg, noscript, form, button, input, "
                    + ".comments, .comment, .ad, .advertisement, .sidebar, .menu").remove();
            text = document.body() == null ? "" : document.body().text();
        } else {
            text = body;
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

}
