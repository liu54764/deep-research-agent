package com.dra.worker.activity;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.Usage;
import com.dra.domain.port.SearchPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "dra.search", name = "provider", havingValue = "tavily")
public class SearchActivitiesImpl implements ResearchActivities.Search {

    private final SearchPort searchPort;

    public SearchActivitiesImpl(SearchPort searchPort) {
        this.searchPort = searchPort;
    }

    @Override
    public SearchResult search(SearchInput input) {
        long startedAt = System.nanoTime();
        List<SearchHit> hits = new ArrayList<>();
        Usage usage = Usage.ZERO;
        String provider = "unknown";
        // Two queries per sub-question keeps Tavily credit usage bounded.
        List<String> queries = input.subQuestion().queries().stream().limit(2).toList();
        for (String query : queries) {
            SearchResult result = searchPort.search(query, input.topK());
            hits.addAll(result.hits());
            usage = usage.plus(result.usage());
            provider = result.provider();
        }
        Map<String, SearchHit> unique = new LinkedHashMap<>();
        for (SearchHit hit : hits) {
            if (hit != null && hit.url() != null) {
                unique.putIfAbsent(hit.url(), hit);
            }
        }
        List<SearchHit> picked = unique.values().stream()
                .limit(Math.max(input.topK() * 2, 10))
                .toList();
        return new SearchResult(picked, provider, usage,
                (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
