package in.suhansingh.ghbliapi.exception;

/**
 * Thrown when a generation request reaches Stability but cannot be completed.
 * Mapped to a single RFC 9457 {@code ProblemDetail} response by
 * {@link GlobalExceptionHandler}, so both generation endpoints report failures
 * the same way.
 */
public class GenerationFailedException extends RuntimeException {

    public GenerationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
