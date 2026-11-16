package com.dra.domain.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Deterministic URL normalization used for dedupe and idempotency keys. */
public final class UrlCanonicalizer {

    private UrlCanonicalizer() {
    }

    public static String canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid url: " + rawUrl, e);
        }
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("unsupported scheme: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url has no host: " + rawUrl);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        int port = uri.getPort();
        boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String authority = defaultPort ? host : host + ":" + port;

        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String query = canonicalQuery(uri.getRawQuery());
        return scheme + "://" + authority + path + (query.isEmpty() ? "" : "?" + query);
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
                .filter(part -> !part.isBlank())
                .map(part -> part.split("=", 2))
                .filter(parts -> !isTrackingParameter(parts[0]))
                .map(parts -> parts.length == 2 ? parts[0] + "=" + parts[1] : parts[0])
                .sorted()
                .collect(Collectors.joining("&"));
    }

    private static boolean isTrackingParameter(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.startsWith("utm_") || normalized.equals("gclid")
                || normalized.equals("fbclid") || normalized.equals("ref");
    }
}
