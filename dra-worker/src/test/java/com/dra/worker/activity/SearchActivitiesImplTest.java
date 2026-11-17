package com.dra.worker.activity;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.Usage;
import com.dra.domain.model.SearchQueryRow;
import com.dra.domain.port.SearchPort;
import com.dra.domain.port.SearchQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchActivitiesImplTest {

    private final List<SearchQueryRow> recorded = new ArrayList<>();

    private final SearchQueryRepository repository = recorded::add;

    @Test
    void recordsEachQueryWithResultCount() {
        SearchActivitiesImpl activity = new SearchActivitiesImpl(
                (query, topK) -> new SearchResult(
                        List.of(new SearchHit("https://example.org/1", "t", "s", 1, "tavily", null),
                                new SearchHit("https://example.org/2", "t", "s", 2, "tavily", null)),
                        "tavily", Usage.ZERO, 5),
                repository);

        activity.search(input(List.of("query one", "query two")));

        assertEquals(2, recorded.size());
        assertEquals("SUCCESS", recorded.get(0).status());
        assertEquals(2, recorded.get(0).resultCount());
        assertEquals("query one", recorded.get(0).queryText());
        assertEquals(64, recorded.get(0).queryHash().length());
        assertEquals("q1", recorded.get(0).subQuestionId());
        assertEquals(2, recorded.get(1).round());
    }

    @Test
    void recordsFailedQueryBeforeRethrowing() {
        SearchActivitiesImpl activity = new SearchActivitiesImpl(
                (query, topK) -> {
                    throw new DraException(ErrorCode.SEARCH_UNAVAILABLE, "quota", false);
                },
                repository);

        assertThrows(DraException.class,
                () -> activity.search(input(List.of("query one", "query two"))));

        assertEquals(1, recorded.size());
        assertEquals("FAILED", recorded.get(0).status());
        assertEquals(0, recorded.get(0).resultCount());
    }

    @Test
    void repositoryFailureDoesNotBreakSearch() {
        SearchActivitiesImpl activity = new SearchActivitiesImpl(
                (query, topK) -> new SearchResult(List.of(), "tavily", Usage.ZERO, 1),
                row -> {
                    throw new IllegalStateException("db down");
                });

        SearchResult result = activity.search(input(List.of("query one")));

        assertTrue(result.hits().isEmpty());
    }

    private static SearchInput input(List<String> queries) {
        return new SearchInput("task-1", 2,
                new SubQuestion("q1", 1, "Title", "Question?", queries, "OPEN"), 5);
    }
}
