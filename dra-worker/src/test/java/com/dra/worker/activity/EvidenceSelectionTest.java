package com.dra.worker.activity;

import com.dra.domain.model.EvidenceView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSelectionTest {

    /**
     * Evidence arrives grouped by sub-question. Cutting the list at N in insertion order
     * drops whole sub-questions, so their facts never reach claim synthesis.
     */
    @Test
    void truncationKeepsEverySubQuestionRepresented() {
        List<EvidenceView> evidence = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            evidence.add(view("ev-a" + i, "s1"));
        }
        for (int i = 0; i < 10; i++) {
            evidence.add(view("ev-b" + i, "s2"));
        }
        evidence.add(view("ev-c0", "s3"));

        List<EvidenceView> selected = ClaimActivitiesImpl.selectEvenly(evidence, 6);

        assertEquals(6, selected.size());
        assertTrue(selected.stream().anyMatch(e -> e.subQuestionId().equals("s3")),
                "the last sub-question must keep at least one evidence row");
        // Round-robin: 1 per sub-question, then the rest fills by depth (s3 has only one row).
        assertEquals(3, selected.stream().filter(e -> e.subQuestionId().equals("s1")).count());
        assertEquals(2, selected.stream().filter(e -> e.subQuestionId().equals("s2")).count());
    }

    @Test
    void smallEvidenceSetsAreReturnedUnchanged() {
        List<EvidenceView> evidence = List.of(view("ev-1", "s1"), view("ev-2", "s2"));

        assertEquals(evidence, ClaimActivitiesImpl.selectEvenly(evidence, 60));
    }

    @Test
    void roundRobinStopsExactlyAtTheLimit() {
        List<EvidenceView> evidence = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            evidence.add(view("ev-" + i, "s" + (i % 3)));
        }

        assertEquals(7, ClaimActivitiesImpl.selectEvenly(evidence, 7).size());
    }

    private static EvidenceView view(String id, String subQuestionId) {
        return new EvidenceView(id, "task-1", "src-1", subQuestionId, 1,
                "quote " + id, "LLM", "https://example.org/" + id, "title");
    }
}
