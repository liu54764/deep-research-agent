package com.dra.infra.fetch;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import com.dra.infra.http.OutboundHttpClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Fetches static HTML (no JS rendering, no PDF) with SSRF guards, redirect
 * re-validation, size caps and a readability-style text extraction.
 */
@Component
public class WebFetcher {

    private static final Logger log = LoggerFactory.getLogger(WebFetcher.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("text/html", "application/xhtml+xml", "text/plain");
    private static final Set<String> BLOCKED_HOSTS = Set.of("localhost", "metadata.google.internal");

    private final OutboundHttpClient http;
    private final FetchProperties properties;

    public WebFetcher(OutboundHttpClient http, FetchProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    public FetchedPage fetch(String rawUrl) {
        URI current;
        try {
            current = URI.create(rawUrl.trim());
        } catch (RuntimeException e) {
            return FetchedPage.failed(rawUrl, "FETCH_INVALID_URL", 0);
        }

        for (int hop = 0; hop <= properties.getMaxRedirects(); hop++) {
            try {
                validate(current);
            } catch (BlockedUrlException e) {
                log.warn("blocked outbound URL {}: {}", current, e.getMessage());
                return FetchedPage.failed(current.toString(), "FETCH_BLOCKED_URL", 0);
            }

            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("User-Agent", properties.getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response;
            try {
                response = http.client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DraException(ErrorCode.FETCH_UNAVAILABLE, "fetch interrupted", true, e);
            } catch (IOException e) {
                throw new DraException(ErrorCode.FETCH_UNAVAILABLE,
                        "fetch failed for " + current + ": " + e.getMessage(), true, e);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    return FetchedPage.failed(current.toString(), "FETCH_BAD_REDIRECT", status);
                }
                current = current.resolve(location);
                continue;
            }
            if (isRetryableStatus(status)) {
                throw new DraException(ErrorCode.FETCH_UNAVAILABLE,
                        "fetch HTTP " + status + " for " + current, true);
            }
            if (status < 200 || status >= 300) {
                return FetchedPage.failed(current.toString(), "FETCH_HTTP_" + status, status);
            }

            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("text/html").toLowerCase(Locale.ROOT);
            String mediaType = contentType.split(";")[0].trim();
            if (!ALLOWED_CONTENT_TYPES.contains(mediaType)) {
                return FetchedPage.failed(current.toString(), "FETCH_UNSUPPORTED_CONTENT", status);
            }

            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            String encoding = response.headers().firstValue("Content-Encoding")
                    .orElse("").toLowerCase(Locale.ROOT);
            if (encoding.contains("gzip")) {
                bytes = gunzip(bytes);
            }
            if (bytes.length > properties.getMaxBytes()) {
                return FetchedPage.failed(current.toString(), "FETCH_TOO_LARGE", status);
            }

            Document document;
            try {
                document = Jsoup.parse(new ByteArrayInputStream(bytes), null, current.toString());
            } catch (IOException e) {
                return FetchedPage.failed(current.toString(), "FETCH_PARSE_ERROR", status);
            }
            String title = document.title();
            if (title == null || title.isBlank()) {
                Element h1 = document.selectFirst("h1");
                title = h1 == null ? "" : h1.text();
            }
            String content = extractText(document);
            if (content.isBlank()) {
                return FetchedPage.failed(current.toString(), "FETCH_EMPTY_CONTENT", status);
            }
            return new FetchedPage(true, current.toString(), truncate(title.trim(), 512),
                    truncate(content, 50_000), null, status);
        }
        return FetchedPage.failed(current.toString(), "FETCH_TOO_MANY_REDIRECTS", 0);
    }

    private void validate(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedUrlException("unsupported scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedUrlException("missing host");
        }
        if (BLOCKED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BlockedUrlException("blocked host");
        }
        if (properties.isAllowPrivateForTests()) {
            return;
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new BlockedUrlException("unsupported port " + port);
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new BlockedUrlException("private/reserved address " + address.getHostAddress());
                }
            }
        } catch (IOException e) {
            throw new BlockedUrlException("DNS resolution failed: " + e.getMessage());
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true; // IPv6 unique local fc00::/7
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return true; // 100.64.0.0/10 CGNAT
            }
        }
        return false;
    }

    private static String extractText(Document document) {
        document.select("script,style,noscript,svg,iframe,form,nav,header,footer,aside").remove();
        Element main = document.selectFirst("article");
        if (main == null) {
            main = document.selectFirst("main");
        }
        if (main == null) {
            main = document.selectFirst("[role=main]");
        }
        if (main == null) {
            main = document.body();
        }
        if (main == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        Elements blocks = main.select("h1,h2,h3,h4,p,li,blockquote,pre");
        for (Element block : blocks) {
            if (hasTextBlockAncestor(block)) {
                continue;
            }
            String value = block.text().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(value);
            if (text.length() >= 45_000) {
                break;
            }
        }
        if (text.length() < 200) {
            String all = main.text().trim();
            if (all.length() > text.length()) {
                return all;
            }
        }
        return text.toString();
    }

    private static boolean hasTextBlockAncestor(Element element) {
        for (Element parent : element.parents()) {
            String tag = parent.tagName();
            if ("p".equals(tag) || "li".equals(tag) || "blockquote".equals(tag) || "pre".equals(tag)) {
                return true;
            }
            if ("body".equals(tag)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Only the standard 5xx range is treated as a transient server fault worth retrying.
     * Vendor-specific codes outside it — LinkedIn's 999, Cloudflare's 1020 — are access
     * denials: retrying them just burns attempts and wall-clock without ever succeeding.
     */
    static boolean isRetryableStatus(int status) {
        return status >= 500 && status <= 599;
    }

    private static byte[] gunzip(byte[] bytes) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new DraException(ErrorCode.FETCH_UNAVAILABLE, "cannot decode gzip response", false, e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class BlockedUrlException extends RuntimeException {
        private BlockedUrlException(String message) {
            super(message);
        }
    }
}
