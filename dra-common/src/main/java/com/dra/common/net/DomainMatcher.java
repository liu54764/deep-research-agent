package com.dra.common.net;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Host-level matching shared by search noise filtering and the workflow's request domain rules.
 * Domains are matched exactly or as a parent domain ({@code linkedin.com} also matches
 * {@code www.linkedin.com} and {@code jobs.linkedin.com}).
 */
public final class DomainMatcher {

    private DomainMatcher() {
    }

    /** Lowercase bare host (no {@code www.}) of a URL, or null when the URL is unusable. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean matches(String host, String domain) {
        String normalized = normalize(domain);
        return host != null && !normalized.isEmpty()
                && (host.equals(normalized) || host.endsWith("." + normalized));
    }

    public static boolean anyMatch(String host, List<String> domains) {
        if (host == null || domains == null) {
            return false;
        }
        for (String domain : domains) {
            if (matches(host, domain)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String domain) {
        if (domain == null) {
            return "";
        }
        String value = domain.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        return value;
    }
}
