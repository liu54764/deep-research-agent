package com.dra.worker.activity.stub;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.Usage;
import com.dra.domain.port.SearchPort;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "dra.search", name = "provider", havingValue = "mock", matchIfMissing = true)
public class StubSearchActivities implements ResearchActivities.Search {

    private final SearchPort searchPort;
    private final WorkerProperties properties;

    public StubSearchActivities(SearchPort searchPort, WorkerProperties properties) {
        this.searchPort = searchPort;
        this.properties = properties;
    }

    @Override
    public SearchResult search(SearchInput input) {
        StubSupport.sleep(properties.getSearchSleepMs());
        List<SearchHit> hits = new ArrayList<>();
        for (String query : input.subQuestion().queries()) {
            hits.addAll(searchPort.search(query, input.topK()).hits());
        }
        return new SearchResult(hits, "mock", Usage.ZERO, properties.getSearchSleepMs());
    }
}
