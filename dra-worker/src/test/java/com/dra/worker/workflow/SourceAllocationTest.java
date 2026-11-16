package com.dra.worker.workflow;

import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SubQuestion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceAllocationTest {

    /**
     * A question's later half must not be starved by the earlier half: with 3 sub-questions
     * and 5 fetch slots, every sub-question gets its top hit before any gets a second one.
     */
    @Test
    void everySubQuestionGetsAFetchSlotBeforeAnyGetsTwo() {
        List<SubQuestion> subQuestions = List.of(sub("s1"), sub("s2"), sub("s3"));
        Map<String, List<SearchHit>> hits = new LinkedHashMap<>();
        hits.put("s1", List.of(hit("https://a/1"), hit("https://a/2"), hit("https://a/3")));
        hits.put("s2", List.of(hit("https://b/1"), hit("https://b/2")));
        hits.put("s3", List.of(hit("https://c/1")));

        List<SearchHit> picked = ResearchWorkflowImpl.pick(hits, subQuestions, 5);

        assertEquals(5, picked.size());
        assertTrue(picked.stream().anyMatch(h -> h.url().startsWith("https://c/")),
                "the last sub-question must be represented");
        assertEquals(List.of("https://a/1", "https://b/1", "https://c/1",
                        "https://a/2", "https://b/2"),
                picked.stream().map(SearchHit::url).toList());
    }

    @Test
    void subQuestionWithoutHitsDoesNotWasteSlots() {
        List<SubQuestion> subQuestions = List.of(sub("s1"), sub("s2"));
        Map<String, List<SearchHit>> hits = new LinkedHashMap<>();
        hits.put("s1", List.of(hit("https://a/1"), hit("https://a/2"), hit("https://a/3")));
        hits.put("s2", List.of());

        List<SearchHit> picked = ResearchWorkflowImpl.pick(hits, subQuestions, 3);

        assertEquals(3, picked.size(), "an empty sub-question must not consume a slot");
    }

    @Test
    void duplicateUrlsAcrossSubQuestionsAreFetchedOnce() {
        List<SubQuestion> subQuestions = List.of(sub("s1"), sub("s2"));
        Map<String, List<SearchHit>> hits = new LinkedHashMap<>();
        hits.put("s1", List.of(hit("https://shared/x"), hit("https://a/2")));
        hits.put("s2", List.of(hit("https://shared/x"), hit("https://b/2")));

        List<SearchHit> picked = ResearchWorkflowImpl.pick(hits, subQuestions, 4);

        assertEquals(3, picked.size());
        assertEquals(1, picked.stream().filter(h -> h.url().equals("https://shared/x")).count());
    }

    private static SubQuestion sub(String id) {
        return new SubQuestion(id, Integer.parseInt(id.substring(1)), "t-" + id,
                "question " + id, List.of("query " + id), "OPEN");
    }

    private static SearchHit hit(String url) {
        return new SearchHit(url, "title", "snippet", 1, "mock", "2026-11-16");
    }
}
