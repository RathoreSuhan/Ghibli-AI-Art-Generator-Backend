package in.suhansingh.ghbliapi.enums;

import org.springframework.http.HttpStatus;

/**
 * The distinct ways a call to Stability AI can fail, and what each one means to a caller.
 *
 * <p>Before this enum every upstream problem — an exhausted balance, a rate limit, a rotated
 * API key, a refused prompt, a total outage — arrived at the frontend as the same
 * <em>502 "Generation failed"</em>, so the UI could only ever print one sentence and the user
 * could not tell "wait ten seconds" from "top up the account".
 *
 * <p>Each constant carries the whole decision for its case in one row: the status the API
 * answers with, the machine-readable {@code code} the frontend switches on, the human title,
 * the default detail sentence, and whether retrying the same request could plausibly succeed.
 * Keeping them together is the point — status and retryability must never disagree, and a new
 * failure mode is one line here rather than an edit in three files.
 */
public enum StabilityFailure {

    /** Stability answered 402: the account balance is empty. Retrying cannot help. */
    CREDITS_EXHAUSTED(
            HttpStatus.PAYMENT_REQUIRED,
            "stability_credits_exhausted",
            "Out of generation credits",
            "The Stability AI account has no credits left, so no new artwork can be generated. "
                    + "Top up the balance and try again.",
            false),

    /** Stability answered 429: too many requests too quickly. Worth retrying after a pause. */
    RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "stability_rate_limited",
            "Too many requests",
            "Stability AI is rate limiting this account right now. Wait a few seconds and try again.",
            true),

    /**
     * Stability rejected our credentials (401, or a 403 that is not about content). Surfaced as
     * 502 rather than 401 on purpose: the <em>caller</em> is authenticated fine — it is this
     * server's upstream key that is wrong, and a 401 would push the frontend into a pointless
     * re-login.
     */
    AUTH_FAILED(
            HttpStatus.BAD_GATEWAY,
            "stability_auth_failed",
            "Image service key rejected",
            "Stability AI rejected this server's API key. The key is missing, expired or revoked — "
                    + "retrying will not help until it is replaced.",
            false),

    /** The prompt or the uploaded photo tripped Stability's content filter. */
    REQUEST_REJECTED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "stability_request_rejected",
            "Prompt was refused",
            "Stability AI refused this request — its content filter rejected the prompt or the "
                    + "uploaded photo. Try rewording the description or using a different image.",
            false),

    /** Upstream 5xx or a connection that never opened. The service itself is down. */
    UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "stability_unavailable",
            "Image service unavailable",
            "Stability AI is not responding at the moment. This is an outage on their side, "
                    + "so please try again shortly.",
            true),

    /** The connection opened but the response never arrived inside the configured read timeout. */
    TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "stability_timeout",
            "Image service timed out",
            "Stability AI took too long to answer and the request was abandoned. "
                    + "Busy periods usually clear quickly — try again.",
            true),

    /** A response we have no specific rule for. Deliberately conservative: not retryable. */
    UNKNOWN(
            HttpStatus.BAD_GATEWAY,
            "stability_error",
            "Generation failed upstream",
            "Stability AI returned an unexpected error, so the artwork could not be generated.",
            false);

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final String detail;
    private final boolean retryable;

    StabilityFailure(HttpStatus status, String code, String title, String detail, boolean retryable) {
        this.status = status;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.retryable = retryable;
    }

    /** Status this API answers with — not the status Stability sent us. */
    public HttpStatus getStatus() {
        return status;
    }

    /** Stable identifier the frontend keys its wording off; never localise or reword it. */
    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    /** True when re-sending the identical request could succeed, which is what gates "Try again". */
    public boolean isRetryable() {
        return retryable;
    }
}
