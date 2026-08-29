package in.suhansingh.ghbliapi.exception;

import feign.FeignException;
import in.suhansingh.ghbliapi.enums.StabilityFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One error contract for the whole API: RFC 9457 {@link ProblemDetail}
 * ({@code application/problem+json}), which is native in Boot 3.5.
 *
 * <p>Before this advice, {@code /generate} returned the raw exception message as
 * {@code text/plain} bytes while {@code /generate-from-text} returned an empty 500 body,
 * so the text-to-art UI had no way to show a cause. Successful responses are untouched —
 * they stay raw {@code image/png} bytes.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the standard Spring MVC failures
 * (unreadable JSON, missing request parameter or part, upload too large, unsupported
 * method) already come back as ProblemDetail instead of Boot's default error map.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bean-validation failures on {@code @Valid @RequestBody}. The parent class already
     * returns a 400 ProblemDetail here; this adds the per-field breakdown so the client
     * can say *which* field was wrong.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "is invalid");
        }

        ProblemDetail problemDetail = ex.getBody();
        problemDetail.setTitle("Validation failed");
        problemDetail.setDetail(fieldErrors.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("; ")));
        problemDetail.setProperty("errors", fieldErrors);

        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    /** Validation on method parameters (activated by {@code @Validated}). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage(), request);
    }

    /** Client-side rejections raised by the service, e.g. the 5MB init-image guard. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid generation request", ex.getMessage(), request);
    }

    /** The photo-to-art path failed somewhere between here and Stability. */
    @ExceptionHandler(GenerationFailedException.class)
    public ProblemDetail handleGenerationFailed(GenerationFailedException ex, HttpServletRequest request) {
        log.error("Generation failed for {}", request.getRequestURI(), ex);
        return problem(HttpStatus.BAD_GATEWAY, "Generation failed", ex.getMessage(), request);
    }

    /**
     * A failure that came from Stability AI, already classified at the network seam by
     * {@link StabilityErrorTranslator}.
     *
     * <p>Declared <em>before</em> {@link #handleFeignException} for readability only — Spring
     * resolves by the most specific exception type, and {@link StabilityApiException} is not a
     * {@code FeignException}, so the two never compete.
     *
     * <p>Three properties beyond the standard ProblemDetail fields, and each one exists because the
     * frontend cannot work it out for itself:
     *
     * <ul>
     *   <li>{@code code} — a stable identifier such as {@code stability_credits_exhausted}. Status
     *       alone is not enough (402 and 429 are distinct, but 502 covers two different causes) and
     *       matching on {@code detail} text would break the moment the wording changes.</li>
     *   <li>{@code retryable} — whether pressing the button again could work. This is the one thing
     *       the UI must not guess: offering "Try again" for an empty balance is a lie.</li>
     *   <li>{@code upstreamStatus} / {@code retryAfterSeconds} — diagnostics and the countdown.</li>
     * </ul>
     *
     * <p>Returns {@code ResponseEntity} rather than a bare {@code ProblemDetail} purely so a real
     * {@code Retry-After} header can travel with the 429/503, per RFC 9110 §10.2.3.
     */
    @ExceptionHandler(StabilityApiException.class)
    public ResponseEntity<ProblemDetail> handleStabilityApiException(
            StabilityApiException ex, HttpServletRequest request) {

        StabilityFailure failure = ex.getFailure();

        // Retryable cases are noise (upstream hiccups); the rest need an operator's attention —
        // an empty balance or a rejected key will not fix itself.
        if (failure.isRetryable()) {
            log.warn(
                    "Stability call for {} failed: {} (upstream status {})",
                    request.getRequestURI(),
                    failure,
                    ex.getUpstreamStatus());
        } else {
            log.error(
                    "Stability call for {} failed: {} (upstream status {})",
                    request.getRequestURI(),
                    failure,
                    ex.getUpstreamStatus(),
                    ex);
        }

        ProblemDetail problemDetail = problem(failure.getStatus(), failure.getTitle(), ex.getMessage(), request);
        problemDetail.setProperty("code", failure.getCode());
        problemDetail.setProperty("retryable", failure.isRetryable());

        if (ex.getUpstreamStatus() != null) {
            problemDetail.setProperty("upstreamStatus", ex.getUpstreamStatus());
        }

        HttpHeaders headers = new HttpHeaders();
        if (ex.getRetryAfterSeconds() != null) {
            problemDetail.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }

        return new ResponseEntity<>(problemDetail, headers, failure.getStatus());
    }

    /**
     * The text-to-art path goes through Feign, which signals upstream errors this way.
     *
     * <p>Kept as a safety net rather than deleted: {@code StabilityErrorDecoder} now converts every
     * response from the Stability client into a {@link StabilityApiException} above, so this only
     * fires for a Feign client added later without its own decoder.
     */
    @ExceptionHandler(FeignException.class)
    public ProblemDetail handleFeignException(FeignException ex, HttpServletRequest request) {
        log.error("Stability call failed with status {}", ex.status(), ex);
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Generation failed",
                "Stability API rejected the request (upstream status " + ex.status() + ").",
                request);
    }

    /**
     * Signup against an address that is already taken.
     *
     * <p>{@link DuplicateKeyException} is handled by the same method rather than left to the
     * catch-all below, because it arrives from exactly one place: the unique index on
     * {@code users.email} rejecting the loser of a signup race. Mapping it here makes a race
     * indistinguishable from an ordinary duplicate, instead of a 500 that looks like a bug in
     * the server. The detail comes from the exception on the {@code EmailAlreadyExistsException}
     * path and is fixed text on the Mongo path, whose own message quotes the offending
     * document.
     */
    @ExceptionHandler({EmailAlreadyExistsException.class, DuplicateKeyException.class})
    public ProblemDetail handleEmailAlreadyExists(Exception ex, HttpServletRequest request) {
        String detail = ex instanceof EmailAlreadyExistsException
                ? ex.getMessage()
                : "An account with that email already exists";

        return problem(HttpStatus.CONFLICT, "Email already registered", detail, request);
    }

    /**
     * Login failures. Reached because {@code AuthService} lets the provider's exception
     * propagate — without this handler the catch-all below would turn a simply wrong password
     * into a 500.
     *
     * <p>The detail is one fixed string for every cause. {@code BadCredentialsException} is
     * already deliberately ambiguous between "no such user" and "wrong password" — see
     * {@code MongoUserDetailsService} — and echoing {@code ex.getMessage()} would undo that,
     * since {@code UsernameNotFoundException} and its siblings carry distinguishing text.
     *
     * <p>401 rather than 403: the caller has not proved who they are. This covers failures at
     * the login endpoint only; a missing or invalid token on a protected endpoint never gets
     * here, because it is rejected in the filter chain by
     * {@code ProblemDetailAuthenticationEntryPoint}.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.debug("Authentication failed for {}", request.getRequestURI(), ex);
        return problem(HttpStatus.UNAUTHORIZED, "Invalid credentials", "Incorrect email or password.", request);
    }

    /**
     * A history entry the caller does not own, or that does not exist.
     *
     * <p>Needed as its own handler for a mundane reason: without it the catch-all below turns
     * every cross-user or stale-id request into a 500, so the frontend cannot tell a deleted
     * generation from a broken server.
     *
     * <p>404 for both cases — see {@link GenerationNotFoundException} for why answering 403 to
     * "exists but not yours" would leak the existence of other users' generations. The detail
     * echoes the exception message, which names only the id the caller already supplied.
     */
    @ExceptionHandler(GenerationNotFoundException.class)
    public ProblemDetail handleGenerationNotFound(GenerationNotFoundException ex, HttpServletRequest request) {
        log.debug("Generation not found for {}", request.getRequestURI(), ex);
        return problem(HttpStatus.NOT_FOUND, "Generation not found", ex.getMessage(), request);
    }

    /**
     * Authenticated, but not allowed — including the {@code CurrentUser.requireId()} guard
     * firing when a history endpoint is somehow reached with no user id in the context.
     *
     * <p>Also declared to keep {@link AccessDeniedException} away from the catch-all. Note it
     * only fires for exceptions raised <em>inside</em> a controller or service: a denial by
     * {@code AuthorizationFilter} happens before dispatch and is handled by the filter chain's
     * own {@code AccessDeniedHandler}, not here.
     *
     * <p>403, not 401. The caller proved who they are; retrying with a fresh token changes
     * nothing, and answering 401 would send the frontend into a pointless re-login.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied for {}", request.getRequestURI(), ex);
        return problem(HttpStatus.FORBIDDEN, "Access denied", "You do not have access to this resource.", request);
    }

    /** Last resort — never leak a stack trace or an internal message to the client. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}", request.getRequestURI(), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Something went wrong while handling the request.",
                request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                status, detail != null ? detail : status.getReasonPhrase());
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        return problemDetail;
    }
}
