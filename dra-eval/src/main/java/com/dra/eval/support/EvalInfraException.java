package com.dra.eval.support;

/** Infrastructure failure (API/DB/model unreachable, bad dataset path). Maps to exit code 3. */
public class EvalInfraException extends RuntimeException {

    public EvalInfraException(String message) {
        super(message);
    }

    public EvalInfraException(String message, Throwable cause) {
        super(message, cause);
    }
}
