package com.dra.common.error;

/** Application-level exception carrying a stable code; mapped to HTTP by the API layer. */
public class DraException extends RuntimeException {

    private final ErrorCode code;
    private final boolean retryable;

    public DraException(ErrorCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public DraException(ErrorCode code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
