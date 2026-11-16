package com.dra.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class EvalApplicationTest {

    @Test
    void rewritesShortFlagsOntoConfigurationProperties() {
        String[] normalized = EvalApplication.normalize(new String[]{
                "--mode=real",
                "--split=regression",
                "--judge.model=deepseek-reasoner",
                "--url-check.enabled=true",
                "--foo=bar"
        });

        assertArrayEquals(new String[]{
                "--dra.eval.mode=real",
                "--dra.eval.split=regression",
                "--dra.eval.judge.model=deepseek-reasoner",
                "--dra.eval.url-check.enabled=true",
                "--foo=bar"
        }, normalized);
    }

    @Test
    void leavesPlainArgumentsAlone() {
        String[] normalized = EvalApplication.normalize(new String[]{"--debug", "value"});

        assertArrayEquals(new String[]{"--debug", "value"}, normalized);
    }
}
