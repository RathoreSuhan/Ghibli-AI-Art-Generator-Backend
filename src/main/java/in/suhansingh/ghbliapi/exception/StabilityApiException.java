package in.suhansingh.ghbliapi.exception;

import in.suhansingh.ghbliapi.enums.StabilityFailure;

/**
 * Raised when the failure came from Stability AI rather than from this application.
 *
 * <p>The distinction matters because {@link GenerationFailedException} means "something broke on
 * our side of the call" — a bad resize, an unreadable upload, a bug — and deserves a flat 502,
 * whereas an upstream refusal has a specific cause the user can act on. This type carries that
 * cause already decided (by {@link StabilityErrorTranslator}, at the two seams that touch the
 * network) so {@code GlobalExceptionHandler} only has to render it and never re-inspect causes.
 *
 * <p>Deliberately not a subclass of {@code GenerationFailedException}: Spring picks the most
 * specific {@code @ExceptionHandler}, so subclassing would work, but the existing handler logs at
 * {@code error} and answers 502 for every case, and inheriting that contract is exactly what this
 * class exists to avoid.
 */
public class StabilityApiException extends RuntimeException {

    private final StabilityFailure failure;
    /** The status Stability sent, echoed for diagnostics. Null for a failure with no response. */
    private final Integer upstreamStatus;
    /** Parsed from the upstream {@code Retry-After} header when present, else null. */
    private final Integer retryAfterSeconds;

    public StabilityApiException(StabilityFailure failure, Integer upstreamStatus, Integer retryAfterSeconds) {
        // The enum's own sentence is the message, so logs and the response body agree.
        this(failure, upstreamStatus, retryAfterSeconds, failure.getDetail(), null);
    }

    public StabilityApiException(
            StabilityFailure failure, Integer upstreamStatus, Integer retryAfterSeconds, Throwable cause) {
        this(failure, upstreamStatus, retryAfterSeconds, failure.getDetail(), cause);
    }

    public StabilityApiException(
            StabilityFailure failure,
            Integer upstreamStatus,
            Integer retryAfterSeconds,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.upstreamStatus = upstreamStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public StabilityFailure getFailure() {
        return failure;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
