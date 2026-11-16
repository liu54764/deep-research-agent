package com.dra.eval.judge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelSamplerTest {

    @Test
    void samplesPerKindAndIsDeterministic() {
        List<LabelItem> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candidates.add(new LabelItem("J1", "q" + i, "c" + i, "p", "SUPPORT", null));
        }
        for (int i = 0; i < 10; i++) {
            candidates.add(new LabelItem("J2", "q" + i, "kp" + i, "p", "COVERED", null));
        }

        List<LabelItem> first = LabelSampler.sample(candidates, 12, 4);
        List<LabelItem> second = LabelSampler.sample(candidates, 12, 4);

        assertEquals(12, first.stream().filter(i -> i.kind().equals("J1")).count());
        assertEquals(4, first.stream().filter(i -> i.kind().equals("J2")).count());
        assertEquals(first, second, "sampling must be stable across runs");
    }

    @Test
    void zeroLimitYieldsNoItemsOfThatKind() {
        List<LabelItem> candidates = List.of(
                new LabelItem("J1", "q1", "c1", "p", "SUPPORT", null));

        assertTrue(LabelSampler.sample(candidates, 0, 0).isEmpty());
    }
}
