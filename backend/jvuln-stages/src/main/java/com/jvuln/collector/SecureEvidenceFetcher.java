package com.jvuln.collector;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.jvuln.util.ValueUtils.errorMessage;
import com.jvuln.util.RequestLogContext;

@Component
public class SecureEvidenceFetcher implements EvidencePageFetcher {

    private static final int DEFAULT_MAX_BYTES = 512 * 1024;
    private static final int DEFAULT_MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_MAX_CHARS = 4000;
    private static final int DEFAULT_MAX_REDIRECTS = 3;
    private static final int MAX_IMAGES_PER_PAGE = 10;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 8000;
    private final PublicUrlPolicy urlPolicy;
    private final int maxBytes;
    private final int maxImageBytes;
    private final int maxChars;
    private final int maxRedirects;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public SecureEvidenceFetcher() {
        this(new PublicUrlPolicy(), DEFAULT_MAX_BYTES, DEFAULT_MAX_IMAGE_BYTES, DEFAULT_MAX_CHARS,
                DEFAULT_MAX_REDIRECTS, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    SecureEvidenceFetcher(PublicUrlPolicy urlPolicy, int maxBytes, int maxChars,
                          int maxRedirects, int connectTimeoutMs, int readTimeoutMs) {
        this(urlPolicy, maxBytes, DEFAULT_MAX_IMAGE_BYTES, maxChars,
                maxRedirects, connectTimeoutMs, readTimeoutMs);
    }

    SecureEvidenceFetcher(PublicUrlPolicy urlPolicy, int maxBytes, int maxImageBytes, int maxChars,
                          int maxRedirects, int connectTimeoutMs, int readTimeoutMs) {
        this.urlPolicy = urlPolicy;
        this.maxBytes = maxBytes;
        this.maxImageBytes = maxImageBytes;
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
                    RequestLogContext.logWebRequest("GET", current.toString());
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
                    String contentType = connection.getContentType();
                    List<String> imageUrls = isHtml(body, contentType)
                            ? extractImageUrls(body, current.toString())
                            : java.util.Collections.emptyList();
                    String excerpt = extract(body, contentType);
                    if (excerpt.isEmpty() && imageUrls.isEmpty()) {
                        return FetchOutcome.failed("Evidence response contained no text");
                    }
                    return FetchOutcome.success(excerpt, imageUrls);
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

    @Override
    public ImageOutcome fetchImage(String url) {
        try {
            URI current = URI.create(url);
            for (int redirect = 0; redirect <= maxRedirects; redirect++) {
                current = urlPolicy.requirePublic(current.toString());
                HttpURLConnection connection = openImage(current.toURL());
                try {
                    RequestLogContext.logWebRequest("GET", current.toString());
                    int status = connection.getResponseCode();
                    if (isRedirect(status)) {
                        if (redirect == maxRedirects) {
                            return ImageOutcome.failed("Image redirect limit exceeded");
                        }
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.trim().isEmpty()) {
                            return ImageOutcome.failed("Image redirect has no location");
                        }
                        current = current.resolve(location.trim());
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        return ImageOutcome.failed("Image server returned HTTP " + status);
                    }
                    String contentType = connection.getContentType();
                    String mediaType = normalizeImageMediaType(contentType);
                    if (mediaType.isEmpty()) {
                        return ImageOutcome.failed("Unsupported image content type: " + contentType);
                    }
                    byte[] data = readBoundedBytes(connection, maxImageBytes);
                    return ImageOutcome.success(data, mediaType);
                } finally {
                    connection.disconnect();
                }
            }
            return ImageOutcome.failed("Image redirect limit exceeded");
        } catch (SecurityException e) {
            return ImageOutcome.failed(errorMessage(e, 300));
        } catch (SocketTimeoutException e) {
            return ImageOutcome.failed("Image fetch timed out");
        } catch (Exception e) {
            return ImageOutcome.failed(errorMessage(e, 300));
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
        if (isHtml(body, contentType)) {
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

    private boolean isHtml(String body, String contentType) {
        return (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html"))
                || body.trim().startsWith("<");
    }

    /** Extracts content-image URLs (screenshots), skipping avatars, icons, and site chrome. */
    private List<String> extractImageUrls(String body, String baseUri) {
        List<String> urls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Document document = Jsoup.parse(body, baseUri);
        for (Element img : document.select("img[src]")) {
            String src = img.absUrl("src");
            if (src == null || src.trim().isEmpty() || !isContentImage(src)) {
                continue;
            }
            if (seen.add(src) && urls.size() < MAX_IMAGES_PER_PAGE) {
                urls.add(src);
            }
        }
        return urls;
    }

    private boolean isContentImage(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("avatars.githubusercontent.com")
                || lower.contains("githubassets.com")
                || lower.contains("gravatar.com")
                || lower.contains("/emoji/")
                || lower.contains("badge") || lower.contains("shields.io")
                || lower.contains("icon") || lower.contains("logo")
                || lower.contains("spinner")) {
            return false;
        }
        return lower.contains("user-images.githubusercontent.com")
                || lower.contains("/user-attachments/")
                || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }

    private HttpURLConnection openImage(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "JVuln-Platform/1.0 Evidence Collector");
        connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp,image/*");
        return connection;
    }

    private byte[] readBoundedBytes(HttpURLConnection connection, int limit) throws IOException {
        long contentLength = connection.getContentLengthLong();
        if (contentLength > limit) {
            throw new IOException("Image response size exceeds limit");
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("Image response size exceeds limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String normalizeImageMediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String value = contentType.toLowerCase(Locale.ROOT).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        switch (value) {
            case "image/png":
            case "image/jpeg":
            case "image/gif":
            case "image/webp":
                return value;
            default:
                return "";
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

}
