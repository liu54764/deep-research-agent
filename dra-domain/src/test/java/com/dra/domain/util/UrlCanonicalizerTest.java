package com.dra.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlCanonicalizerTest {

    @Test
    void stripsTrackingFragmentAndTrailingSlash() {
        String canonical = UrlCanonicalizer.canonicalize(
                "HTTPS://WWW.Example.com/a/b/?utm_source=x&utm_medium=y&q=hello#section");
        assertEquals("https://example.com/a/b?q=hello", canonical);
    }

    @Test
    void sortsQueryParameters() {
        assertEquals("https://example.com/p?a=1&b=2",
                UrlCanonicalizer.canonicalize("https://example.com/p?b=2&a=1"));
    }

    @Test
    void keepsNonDefaultPort() {
        assertEquals("https://example.com:8443/x",
                UrlCanonicalizer.canonicalize("https://example.com:8443/x/"));
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlCanonicalizer.canonicalize("ftp://example.com/file"));
    }
}
