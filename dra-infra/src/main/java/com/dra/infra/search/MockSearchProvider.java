package com.dra.infra.search;

import com.dra.common.hash.Hashing;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.Usage;
import com.dra.domain.port.SearchPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic fake search used until a real provider key is wired. */
@Component
@ConditionalOnProperty(name = "dra.search.provider", havingValue = "mock", matchIfMissing = true)
public class MockSearchProvider implements SearchPort {

    @Override
    public SearchResult search(String query, int topK) {
        int count = Math.max(1, Math.min(topK, 8));
        String hash = Hashing.sha256Hex(query).substring(0, 10).toLowerCase(Locale.ROOT);
        List<SearchHit> hits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            hits.add(new SearchHit(
                    "https://example.org/mock/" + hash + "/" + (i + 1),
                    "Mock result " + (i + 1) + " for: " + query,
                    "Mock snippet relevant to \"" + query + "\" (result " + (i + 1) + ").",
                    i + 1,
                    "mock",
                    "2026-01-01"));
        }
        return new SearchResult(hits, "mock", Usage.ZERO, 0);
    }
}
