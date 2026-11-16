package com.dra.eval.support;

/** Exit codes are part of the contract (M9-eval.md §6.6) and are used by CI. */
public final class EvalExitCode {

    public static final int OK = 0;
    /** A metric fell below its threshold or regressed against the baseline. */
    public static final int THRESHOLD = 1;
    /** A fail-closed invariant was violated: the run is invalid, fix the bug first. */
    public static final int INVARIANT = 2;
    /** API / DB / model endpoint unavailable. */
    public static final int INFRA = 3;
    /** Cost guard tripped. */
    public static final int BUDGET = 4;

    private EvalExitCode() {
    }
}
