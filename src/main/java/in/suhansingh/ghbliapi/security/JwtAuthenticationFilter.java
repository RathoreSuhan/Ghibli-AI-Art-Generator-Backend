package in.suhansingh.ghbliapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns a valid {@code Authorization: Bearer <jwt>} header into an authenticated
 * {@code SecurityContext}.
 *
 * <p><strong>This filter never throws on a bad token.</strong> That is the whole design
 * constraint. It sits upstream of {@code ExceptionTranslationFilter}, which is the
 * component that turns an authentication failure into a 401 — so an exception escaping
 * here is not translated at all. It propagates to the container and surfaces as a
 * <em>500</em>, telling the client "the server is broken" when the truth is "your token
 * expired". Every JWT failure is therefore swallowed here, the context is left
 * unauthenticated, and {@code AuthorizationFilter} downstream produces the 401 through the
 * configured {@link ProblemDetailAuthenticationEntryPoint}.
 *
 * <p>It also does not consult the database. The principal is rebuilt from claims that were
 * just signature-verified, so an authenticated request costs zero queries. The tradeoff is
 * the one inherent to stateless JWT: until a token expires it stays valid even if the
 * account is deleted or its roles change. Fixing that needs short-lived access tokens plus
 * a refresh token, or a revocation list — either of which reintroduces per-request state.
 * With {@code jwt.expiration} at 24h, the exposure window is 24h.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}. Boot auto-registers every
 * {@code Filter} bean with the servlet container, so a scanned filter ends up in the chain
 * twice — once inside {@code FilterChainProxy} and once beside it. {@code OncePerRequestFilter}
 * makes the duplicate harmless, but it is still a filter running on paths the security chain
 * does not own. {@code SecurityConfig} constructs this directly instead, which also makes
 * {@code @Import(SecurityConfig.class)} enough to get a working chain in a slice test.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = bearerToken(request);

        // No token is not an error. Public endpoints must still work, and a protected one
        // is rejected downstream by AuthorizationFilter, not here.
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parseClaims(token);

            UserPrincipal principal = UserPrincipal.fromToken(
                    claims.getSubject(),
                    claims.get(JwtService.CLAIM_EMAIL, String.class),
                    jwtService.rolesFrom(claims));

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException ex) {
            // By far the most common case in normal use, so it gets its own branch to keep
            // it out of the noisier "malformed" bucket when reading logs.
            rejectQuietly("token expired at " + ex.getClaims().getExpiration(), ex);
        } catch (SignatureException ex) {
            // io.jsonwebtoken.security.SignatureException — NOT the root-package
            // io.jsonwebtoken.SignatureException, which is the deprecated pre-0.12 type
            // that IDE auto-import still offers and which compiles fine while catching a
            // supertype-adjacent class by accident.
            rejectQuietly("token signature does not verify (tampered, or signed with another key)", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            // Malformed, unsupported, or an empty/whitespace token string.
            rejectQuietly("token could not be parsed", ex);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Deliberately does not write to the response — the entry point owns the response body
     * so there is exactly one place that shapes a 401. Logged at debug because an invalid
     * token is a client-side condition; logging it at warn would let anyone flood the log
     * by sending junk headers.
     */
    private void rejectQuietly(String reason, Exception cause) {
        SecurityContextHolder.clearContext();
        if (logger.isDebugEnabled()) {
            logger.debug("Rejected bearer token: " + reason, cause);
        }
    }

    /** @return the raw token, or {@code null} when the header is absent or not a bearer one */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
