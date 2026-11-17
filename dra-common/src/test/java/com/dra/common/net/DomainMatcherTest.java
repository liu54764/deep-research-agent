package com.dra.common.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainMatcherTest {

    @Test
    void extractsBareHost() {
        assertEquals("linkedin.com", DomainMatcher.hostOf("https://www.linkedin.com/in/dan-kafka"));
        assertEquals("example.org", DomainMatcher.hostOf("http://EXAMPLE.org/path"));
        assertNull(DomainMatcher.hostOf("not a url"));
        assertNull(DomainMatcher.hostOf(null));
    }

    @Test
    void matchesExactAndSubdomains() {
        assertTrue(DomainMatcher.matches("linkedin.com", "linkedin.com"));
        assertTrue(DomainMatcher.matches("jobs.linkedin.com", "linkedin.com"));
        assertFalse(DomainMatcher.matches("example.com", "linkedin.com"));
        assertFalse(DomainMatcher.matches("notlinkedin.com", "linkedin.com"));
    }

    @Test
    void normalizesSchemePortAndWww() {
        assertTrue(DomainMatcher.matches("linkedin.com", "https://www.linkedin.com/in/x"));
        assertTrue(DomainMatcher.matches("linkedin.com", "linkedin.com:443"));
        assertTrue(DomainMatcher.anyMatch("a.example.org", List.of("www.example.org")));
    }

    @Test
    void emptyDomainListNeverMatches() {
        assertFalse(DomainMatcher.anyMatch("linkedin.com", List.of()));
        assertFalse(DomainMatcher.anyMatch(null, List.of("linkedin.com")));
    }
}
