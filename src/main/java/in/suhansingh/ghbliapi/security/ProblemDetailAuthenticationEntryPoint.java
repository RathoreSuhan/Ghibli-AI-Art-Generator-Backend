package in.suhansingh.ghbliapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.net.URI;

/**
 * Answers unauthenticated requests to protected endpoints with <strong>401</strong>.
 *
 * <p>Without an entry point Spring Security falls back to {@code Http403ForbiddenEntryPoint}
 * and answers 403 — which tells a client "you are authenticated but not allowed", so a
 * frontend cannot distinguish "log in again" from "you may never do this" and will not know
 * to redirect to the login screen. Setting this explicitly is what makes the distinction
 * correct.
 *
 * <p>Chosen over the one-liner {@code new HttpStatusEntryPoint(UNAUTHORIZED)} because that
 * returns a bodiless 401. Every other error in this API is an RFC 9457 {@code ProblemDetail}
 * (see {@code GlobalExceptionHandler}), and the exception handler cannot cover this one —
 * the rejection happens in the filter chain, before any {@code @ControllerAdvice} is
 * reachable. Emitting the same shape here keeps one error contract instead of two.
 *
 * <p>The detail message is deliberately constant. It never says whether a token was absent,
 * expired, or forged: that distinction is useful to an attacker probing for valid tokens and
 * of no use to a legitimate client, which must do the same thing either way. The specific
 * reason is logged at debug by {@link JwtAuthenticationFilter} instead.
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String TITLE = "Unauthorized";
    static final String DETAIL = "Authentication required. Send a valid Authorization: Bearer <token> header.";

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETAIL);
        problemDetail.setTitle(TITLE);
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Not set by ProblemDetail itself: it is a body type, so the response encoding is
        // ours to declare. Omitting it leaves the charset to the container and mangles any
        // non-ASCII detail text.
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
