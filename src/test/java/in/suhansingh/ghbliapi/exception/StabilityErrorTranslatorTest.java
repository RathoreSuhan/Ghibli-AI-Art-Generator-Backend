package in.suhansingh.ghbliapi.exception;

import in.suhansingh.ghbliapi.enums.StabilityFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The branching that decides <em>which</em> upstream failure happened lives entirely in
 * {@link StabilityErrorTranslator}, so it is tested here with no Spring context: the two callers
 * ({@code StabilityErrorDecoder} and {@code GhibliArtService}) only forward arguments to it.
 *
 * <p>What this actually protects is the frontend contract. Every {@code code} the UI switches on is
 * derived from these decisions, so a 402 silently reclassified as {@code UNKNOWN} would put the
 * generic "unexpected error" wording in front of an operator whose balance is empty.
 */
class StabilityErrorTranslatorTest {

    @ParameterizedTest
    @CsvSource({
            "402, CREDITS_EXHAUSTED",
            "429, RATE_LIMITED",
            "401, AUTH_FAILED",
            "500, UNAVAILABLE",
            "502, UNAVAILABLE",
            "503, UNAVAILABLE",
            "504, UNAVAILABLE",
            "418, UNKNOWN",
    })
    void statusAloneDecidesTheUnambiguousCases(int status, StabilityFailure expected) {
        assertThat(StabilityErrorTranslator.classify(status, null)).isEqualTo(expected);
    }

    /**
     * 403 is the overloaded one: Stability uses it both for a key it will not accept and for a
     * prompt its filter refused. Only the body tells them apart, and they need opposite wording —
     * one is the operator's problem, the other the user's.
     */
    @Test
    void forbiddenIsAuthFailureUnlessTheBodySaysModeration() {
        assertThat(StabilityErrorTranslator.classify(403, "{\"name\":\"unauthorized\"}"))
                .isEqualTo(StabilityFailure.AUTH_FAILED);

        assertThat(StabilityErrorTranslator.classify(403, "{\"name\":\"content_moderation\"}"))
                .isEqualTo(StabilityFailure.REQUEST_REJECTED);
    }

    /**
     * A bare 400 is our own bug (bad SDXL dimensions, a dropped multipart part), so it must NOT be
     * reported to the user as "your prompt was refused" — only a moderation body earns that.
     */
    @Test
    void badRequestIsOnlyARefusalWhenTheBodySaysSo() {
        assertThat(StabilityErrorTranslator.classify(400, "{\"name\":\"invalid_sdxl_v1_dimensions\"}"))
                .isEqualTo(StabilityFailure.UNKNOWN);

        assertThat(StabilityErrorTranslator.classify(400, "{\"name\":\"invalid_prompts\"}"))
                .isEqualTo(StabilityFailure.REQUEST_REJECTED);
    }

    /** Some accounts report an empty balance in a 400 body rather than as a 402. */
    @Test
    void emptyBalanceIsRecognisedFromTheBodyWhateverTheStatus() {
        assertThat(StabilityErrorTranslator.classify(400, "{\"name\":\"insufficient_balance\"}"))
                .isEqualTo(StabilityFailure.CREDITS_EXHAUSTED);
    }

    @Test
    void translateCarriesStatusCodeAndRetryAfterOntoTheException() {
        StabilityApiException ex = StabilityErrorTranslator.translate(429, "{\"name\":\"rate_limited\"}", "17");

        assertThat(ex.getFailure()).isEqualTo(StabilityFailure.RATE_LIMITED);
        assertThat(ex.getFailure().getCode()).isEqualTo("stability_rate_limited");
        assertThat(ex.getFailure().isRetryable()).isTrue();
        assertThat(ex.getUpstreamStatus()).isEqualTo(429);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(17);
        // The message is the enum's sentence, so the log and the response body cannot disagree.
        assertThat(ex.getMessage()).isEqualTo(StabilityFailure.RATE_LIMITED.getDetail());
    }

    // --- no response at all -------------------------------------------------

    /** A timeout is retryable; treating it as a hard failure would hide a transient blip. */
    @Test
    void readTimeoutIsATimeoutEvenWhenWrappedTwice() {
        Throwable wrapped = new RuntimeException("I/O error", new IOException(new SocketTimeoutException("Read timed out")));
        assertThat(StabilityErrorTranslator.classify(wrapped)).isEqualTo(StabilityFailure.TIMEOUT);
    }

    /** RestTemplate's ResourceAccessException only carries the reason as text in some cases. */
    @Test
    void timedOutInTheMessageIsEnoughWhenNoTypedCauseSurvives() {
        assertThat(StabilityErrorTranslator.classify(new RuntimeException("Read timed out")))
                .isEqualTo(StabilityFailure.TIMEOUT);
    }

    @Test
    void refusedOrUnresolvableHostReadsAsUnavailable() {
        assertThat(StabilityErrorTranslator.classify(new RuntimeException("wrapped", new ConnectException("Connection refused"))))
                .isEqualTo(StabilityFailure.UNAVAILABLE);
        assertThat(StabilityErrorTranslator.classify(new UnknownHostException("api.stability.invalid")))
                .isEqualTo(StabilityFailure.UNAVAILABLE);
    }

    /** A cause that points at itself used to be a plausible way to hang the walk. */
    @Test
    void selfReferentialCauseChainTerminates() {
        RuntimeException loop = new RuntimeException("nothing useful") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(StabilityErrorTranslator.classify(loop)).isEqualTo(StabilityFailure.UNAVAILABLE);
    }

    // --- Retry-After parsing ------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    // The HTTP-date form is deliberately not parsed: half-understanding it would render a
    // nonsense countdown, which is worse for the user than showing none.
    @ValueSource(strings = {"   ", "soon", "Wed, 21 Oct 2015 07:28:00 GMT", "-5", "0", "99999"})
    void unusableRetryAfterValuesBecomeNull(String header) {
        assertThat(StabilityErrorTranslator.parseRetryAfterSeconds(header)).isNull();
    }

    @Test
    void numericRetryAfterIsParsedAndTrimmed() {
        assertThat(StabilityErrorTranslator.parseRetryAfterSeconds(" 30 ")).isEqualTo(30);
    }
}
