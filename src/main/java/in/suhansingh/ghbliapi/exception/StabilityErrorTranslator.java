package in.suhansingh.ghbliapi.exception;

import in.suhansingh.ghbliapi.enums.StabilityFailure;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The single place that decides <em>what an upstream failure means</em>.
 *
 * <p>There are two independent seams that talk to Stability — the Feign client for
 * text-to-image and an injected {@code RestTemplate} for the multipart image-to-image call
 * (see {@code StabilityAIClient} for why they differ) — and each reports errors in its own
 * vocabulary: {@code Response}/{@code FeignException} on one side, {@code HttpStatusCodeException}/
 * {@code ResourceAccessException} on the other. Both funnel into these static methods so the
 * two paths cannot drift into classifying the same 402 differently.
 *
 * <p>Static and stateless on purpose: it makes decisions from arguments only, so it is unit
 * testable without a Spring context — see {@code StabilityErrorTranslatorTest}.
 */
public final class StabilityErrorTranslator {

    /**
     * Substrings that mean "the content filter refused this", not "the request was malformed".
     * Stability signals moderation with a JSON {@code name} field rather than a distinct status,
     * so the body is the only way to tell a refused prompt from a genuine 400/403.
     */
    private static final String[] MODERATION_MARKERS = {
            "content_moderation", "invalid_prompts", "moderation", "content policy", "content_policy", "nsfw",
    };

    /** Some accounts report an empty balance in the body of a 400 instead of as a 402. */
    private static final String[] CREDIT_MARKERS = {
            "insufficient_balance", "insufficient credits", "not enough credits", "payment_required",
    };

    /** Guards against a self-referential cause chain turning the walk below into a hang. */
    private static final int MAX_CAUSE_DEPTH = 8;

    private StabilityErrorTranslator() {
        // Static helper; never instantiated.
    }

    /**
     * Classifies a failure that <em>did</em> come back as an HTTP response.
     *
     * @param status upstream status code
     * @param body   upstream response body, may be null — Stability puts the machine-readable
     *               reason in here, which is what separates a moderation refusal from a bad request
     */
    public static StabilityFailure classify(int status, String body) {
        String haystack = body == null ? "" : body.toLowerCase(Locale.ROOT);

        // Checked before the status switch: an empty balance is the one cause that shows up under
        // more than one status, and it is also the one the operator most needs named correctly.
        if (containsAny(haystack, CREDIT_MARKERS) && status != 401 && status != 403) {
            return StabilityFailure.CREDITS_EXHAUSTED;
        }

        return switch (status) {
            case 402 -> StabilityFailure.CREDITS_EXHAUSTED;
            case 429 -> StabilityFailure.RATE_LIMITED;
            case 401 -> StabilityFailure.AUTH_FAILED;
            // 403 is overloaded by Stability: a forbidden key and a filtered prompt share it.
            case 403 -> containsAny(haystack, MODERATION_MARKERS)
                    ? StabilityFailure.REQUEST_REJECTED
                    : StabilityFailure.AUTH_FAILED;
            // A plain 400 is usually our own bug (bad dimensions, malformed part), so it only
            // becomes a user-facing refusal when the body says the filter rejected the content.
            case 400 -> containsAny(haystack, MODERATION_MARKERS)
                    ? StabilityFailure.REQUEST_REJECTED
                    : StabilityFailure.UNKNOWN;
            default -> status >= 500 ? StabilityFailure.UNAVAILABLE : StabilityFailure.UNKNOWN;
        };
    }

    /** Builds the exception for an HTTP-level failure, including any {@code Retry-After} hint. */
    public static StabilityApiException translate(int status, String body, String retryAfterHeader) {
        return translate(status, body, retryAfterHeader, null);
    }

    /** Same, keeping the original exception as the cause so the stack trace survives in the log. */
    public static StabilityApiException translate(
            int status, String body, String retryAfterHeader, Throwable cause) {
        return new StabilityApiException(
                classify(status, body), status, parseRetryAfterSeconds(retryAfterHeader), cause);
    }

    /**
     * Classifies a failure with <em>no</em> response at all — the socket timed out, the host did
     * not resolve, the connection was refused. Walks the cause chain because both Feign and
     * RestTemplate wrap the real {@code IOException} at least one level deep.
     */
    public static StabilityFailure classify(Throwable throwable) {
        Throwable current = throwable;

        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            // InterruptedIOException covers SocketTimeoutException and Apache HttpClient's own
            // ConnectTimeoutException, so both read as "we gave up waiting" rather than "down".
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) {
                return StabilityFailure.TIMEOUT;
            }

            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException) {
                return StabilityFailure.UNAVAILABLE;
            }

            // Last resort for wrappers that only carry the reason as text (e.g. "Read timed out").
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timed out")) {
                return StabilityFailure.TIMEOUT;
            }

            current = current.getCause() == current ? null : current.getCause();
        }

        // Nothing came back and it was not a timeout: treat the service as down, which is
        // retryable — the honest answer when the request never reached anyone.
        return StabilityFailure.UNAVAILABLE;
    }

    /** Builds the exception for a transport-level failure. Upstream status is unknown, so null. */
    public static StabilityApiException translate(Throwable cause) {
        return new StabilityApiException(classify(cause), null, null, cause);
    }

    /**
     * Reads the numeric (delta-seconds) form of {@code Retry-After}. The HTTP-date form is ignored
     * rather than parsed: Stability sends seconds, and a half-parsed date would produce a nonsense
     * countdown in the UI, which is worse than no countdown at all.
     *
     * @return the seconds to wait, or null when the header is absent, malformed or absurd
     */
    public static Integer parseRetryAfterSeconds(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        try {
            int seconds = Integer.parseInt(header.trim());
            // Reject negatives, and cap at an hour so a stray huge value cannot freeze the button.
            return seconds > 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
