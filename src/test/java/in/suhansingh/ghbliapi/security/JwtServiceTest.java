package in.suhansingh.ghbliapi.security;

import in.suhansingh.ghbliapi.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain unit tests — no Spring context. {@link JwtService} takes its two settings as
 * constructor arguments precisely so a test can build one with a deliberately broken
 * configuration (a negative lifetime, a short key) without standing up an application and
 * without waiting real time for a token to expire.
 */
class JwtServiceTest {

    /** Same 32-byte test key as src/test/resources/application.properties. Signs nothing real. */
    private static final String SECRET = "Z2hibGktdGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcyE=";

    /** A different, equally valid 32-byte key — used to prove a foreign signature is rejected. */
    private static final String OTHER_SECRET = "b3RoZXIta2V5LXRoYXQtaXMtMzItYnl0ZXMtbG9uZyE=";

    private static final long ONE_HOUR_MS = 3_600_000L;

    private final JwtService jwtService = new JwtService(SECRET, ONE_HOUR_MS);

    private static UserPrincipal principal() {
        User user = new User("Suhan Singh", "Suhan@Example.COM", "irrelevant-hash");
        user.setId("64b7f0c2e1a2b3c4d5e6f7a8");
        return UserPrincipal.fromUser(user);
    }

    // --- round trip ---------------------------------------------------------

    @Test
    void tokenCarriesTheUserIdAsSubjectNotTheEmail() {
        Claims claims = jwtService.parseClaims(jwtService.generateToken(principal()));

        // The whole reason Phase 3 can scope a query without trusting client input.
        assertThat(claims.getSubject()).isEqualTo("64b7f0c2e1a2b3c4d5e6f7a8");
        assertThat(claims.getSubject()).isNotEqualTo("suhan@example.com");
    }

    @Test
    void tokenCarriesRolesAndTheNormalisedEmail() {
        Claims claims = jwtService.parseClaims(jwtService.generateToken(principal()));

        assertThat(jwtService.rolesFrom(claims)).containsExactly(User.ROLE_USER);
        // Normalised on the way into User, so the token never carries the typed-in casing.
        assertThat(claims.get(JwtService.CLAIM_EMAIL, String.class)).isEqualTo("suhan@example.com");
    }

    @Test
    void issuedAtAndExpirationAreSetAndOrderedByTheConfiguredLifetime() {
        Claims claims = jwtService.parseClaims(jwtService.generateToken(principal()));

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(ONE_HOUR_MS);
    }

    @Test
    void rolesFromReturnsEmptyRatherThanNullWhenTheClaimIsAbsent() {
        // Defends the filter: a token minted before the roles claim existed would otherwise
        // NPE inside it — and the filter sits upstream of ExceptionTranslationFilter, so an
        // NPE there surfaces as a 500 rather than a 401. Built with the builder directly
        // because Claims is immutable in jjwt 0.13 and the claim cannot be removed after the
        // fact.
        String withoutRoles = Jwts.builder()
                .subject("64b7f0c2e1a2b3c4d5e6f7a8")
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();

        assertThat(jwtService.rolesFrom(jwtService.parseClaims(withoutRoles))).isEmpty();
    }

    /** Milliseconds in, seconds out — the one unit change in the whole flow. */
    @Test
    void expiresInIsReportedInSeconds() {
        assertThat(jwtService.getExpiresInSeconds()).isEqualTo(3600L);
    }

    // --- rejection ----------------------------------------------------------

    @Test
    void expiredTokenIsRejected() {
        // Negative lifetime: the token is born already expired, so no test has to sleep.
        JwtService expiredIssuer = new JwtService(SECRET, -60_000L);
        String token = expiredIssuer.generateToken(principal());

        assertThatThrownBy(() -> jwtService.parseClaims(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        String foreign = new JwtService(OTHER_SECRET, ONE_HOUR_MS).generateToken(principal());

        assertThatThrownBy(() -> jwtService.parseClaims(foreign))
                // io.jsonwebtoken.security.SignatureException. The deprecated root-package
                // io.jsonwebtoken.SignatureException still exists in 0.13.0 and an IDE will
                // happily auto-import it, so this assertion is also pinning the import.
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void tamperingWithThePayloadInvalidatesTheSignature() {
        String token = jwtService.generateToken(principal());
        String[] parts = token.split("\\.");

        // Rewrite the subject to another user's id and re-attach the original signature —
        // the exact privilege escalation the signature exists to stop. Re-encoding keeps the
        // payload valid JSON, so a failure here can only be the signature check.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace("64b7f0c2e1a2b3c4d5e6f7a8", "000000000000000000000000");
        assertThat(forgedPayload).isNotEqualTo(payload);

        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parseClaims(forged))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void garbageIsRejectedAsMalformedRatherThanAccepted() {
        assertThatThrownBy(() -> jwtService.parseClaims("not-a-jwt"))
                .isInstanceOf(MalformedJwtException.class);
    }

    // --- secret validation at construction -----------------------------------

    @Test
    void secretShorterThan32BytesFailsFastWithActionableAdvice() {
        // "short" -> 5 bytes. Valid Base64, far too little key material for HS256.
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new JwtService(tooShort, ONE_HOUR_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short")
                // The message has to name the fix, because the raw WeakKeyException does not.
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void exactly32BytesIsAccepted() {
        String thirtyTwo = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> new JwtService(thirtyTwo, ONE_HOUR_MS)).doesNotThrowAnyException();
    }

    @Test
    void aPassphraseThatIsNotBase64IsReportedAsSuch() {
        // "!" is outside the Base64 alphabet, so this fails at decode rather than on length —
        // and the message has to say so, or the user goes looking for a key-length problem.
        assertThatThrownBy(() -> new JwtService("not base64!", ONE_HOUR_MS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
    }
}
