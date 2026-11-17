package com.dra.worker.activity;

import com.dra.common.hash.Hashing;
import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.Usage;
import com.dra.domain.model.SearchQueryRow;
import com.dra.domain.port.SearchPort;
import com.dra.domain.port.SearchQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "dra.search", name = "provider", havingValue = "tavily")
public class SearchActivitiesImpl implements ResearchActivities.Search {

    private static final Logger log = LoggerFactory.getLogger(SearchActivitiesImpl.class);
    private static final String PROVIDER = "tavily";

    private final SearchPort searchPort;
    private final SearchQueryRepository searchQueryRepository;

    public SearchActivitiesImpl(SearchPort searchPort, SearchQueryRepository searchQueryRepository) {
        this.searchPort = searchPort;
        this.searchQueryRepository = searchQueryRepository;
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
            long queryStartedAt = System.nanoTime();
            try {
                SearchResult result = HeartbeatGuard.await(
                        () -> searchPort.search(query, input.topK()));
                recordQuery(input, query, "SUCCESS", result.hits().size(),
                        (System.nanoTime() - queryStartedAt) / 1_000_000L, result.usage().costMicros());
                hits.addAll(result.hits());
                usage = usage.plus(result.usage());
                provider = result.provider();
            } catch (RuntimeException e) {
                recordQuery(input, query, "FAILED", 0,
                        (System.nanoTime() - queryStartedAt) / 1_000_000L, 0);
                throw e;
            }
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

    private void recordQuery(SearchInput input, String query, String status,
                             int resultCount, long latencyMs, long costMicros) {
        String queryHash = Hashing.sha256Hex(query);
        try {
            searchQueryRepository.record(new SearchQueryRow(
                    Ids.stableId("search_query", input.taskId(),
                            String.valueOf(input.round()), queryHash),
                    input.taskId(), input.subQuestion().id(), input.round(), PROVIDER,
                    query, queryHash, status, resultCount, latencyMs, costMicros));
        } catch (RuntimeException e) {
            // Query tracing must never fail the research task.
            log.warn("search_query record dropped: {}", e.getMessage());
        }
    }
}
