package in.suhansingh.ghbliapi.security;

import io.jsonwebtoken.Claims;import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mints and verifies the HS256 access tokens.
 *
 * <p><strong>The subject is the Mongo user id, not the e-mail.</strong> {@code sub} is
 * meant to be a stable identifier: an address can be changed, and if it were the subject
 * then every token issued before the change would silently point at nothing. It also means
 * Phase 3 can scope a query to its owner with no lookup and no chance of trusting a
 * client-supplied id, by reading
 * {@code ((UserPrincipal) authentication.getPrincipal()).getId()}. Note that
 * {@code Authentication#getName()} is <em>not</em> that id — it delegates to
 * {@link UserPrincipal#getUsername()}, which is the e-mail.
 *
 * <p>Uses the jjwt 0.12+/0.13 API throughout. The 0.11-era forms that still fill blog
 * posts — {@code parserBuilder()}, {@code setSigningKey}, {@code parseClaimsJws},
 * {@code getBody()}, {@code setSubject}, {@code signWith(SignatureAlgorithm, String)} and
 * the {@code SignatureAlgorithm} enum — are deprecated on a removal path and are not used
 * here. {@code signWith(SecretKey)} picks HS256/384/512 from the key length itself, which
 * is why no algorithm is named anywhere in this class.
 */
@Service
public class JwtService {

    /** Granted authorities, stored with the {@code ROLE_} prefix already applied. */
    static final String CLAIM_ROLES = "roles";

    /** Not used for authorization — carried so a decoded token is readable while debugging. */
    static final String CLAIM_EMAIL = "email";

    /**
     * HS256 requires a key at least as long as its output: 256 bits / 32 bytes.
     * {@code Keys.hmacShaKeyFor} enforces this rather than silently padding.
     */
    private static final int MIN_KEY_BYTES = 32;

    private static final String KEY_ADVICE =
            "jwt.secret must be a Base64-encoded value of at least " + MIN_KEY_BYTES
                    + " bytes (256 bits). Generate one with: openssl rand -base64 32";

    private final SecretKey signingKey;
    private final Duration expiration;

    /**
     * @param secret           Base64-encoded key material. {@code @Value} with no default is
     *                         deliberate: a missing secret must break context startup loudly.
     *                         A fallback would be worse than a crash — every deployment that
     *                         forgot to set it would share one publicly known signing key.
     * @param expirationMillis token lifetime. This one <em>does</em> get a default, because
     *                         there is a safe value for it and no security consequence to
     *                         guessing.
     */
    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration:86400000}") long expirationMillis) {

        this.signingKey = buildSigningKey(secret);
        this.expiration = Duration.ofMillis(expirationMillis);
    }

    /**
     * Both failure modes here are worth translating, because the raw exceptions point at
     * the wrong thing. A human passphrase such as {@code changeit} is valid Base64, so it
     * decodes cleanly to 6 bytes and then fails as a {@link WeakKeyException} about key
     * length — which reads as "my key is too short" rather than "I pasted a password
     * where Base64 was expected".
     */
    private static SecretKey buildSigningKey(String secret) {
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (DecodingException ex) {
            throw new IllegalStateException("jwt.secret is not valid Base64. " + KEY_ADVICE, ex);
        }

        try {
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (WeakKeyException ex) {
            throw new IllegalStateException(
                    "jwt.secret decodes to " + keyBytes.length + " bytes, which is too short. " + KEY_ADVICE, ex);
        }
    }

    public String generateToken(UserPrincipal principal) {
        Instant issuedAt = Instant.now();

        return Jwts.builder()
                .subject(principal.getId())
                .claim(CLAIM_ROLES, List.copyOf(principal.getRoles()))
                .claim(CLAIM_EMAIL, principal.getEmail())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return the verified payload
     * @throws JwtException if the signature does not match, the token has expired, or the
     *                      value is not a well-formed JWS. Callers inside the filter chain
     *                      must not let this escape — see {@link JwtAuthenticationFilter}.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Reads the roles claim back out. Jackson deserialises a JSON array into a
     * {@code List<?>}, never into a {@code Set<String>}, so asking jjwt for the typed form
     * via {@code claims.get(CLAIM_ROLES, Set.class)} throws — hence the manual widening.
     */
    public Set<String> rolesFrom(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }

        Set<String> roles = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) {
                roles.add(value.toString());
            }
        }
        return roles;
    }

    /**
     * Seconds, following the {@code expires_in} convention of RFC 6749 — while the
     * {@code jwt.expiration} property is milliseconds, matching Spring's default duration
     * unit. Two units in one flow is worth naming once rather than discovering later.
     */
    public long getExpiresInSeconds() {
        return expiration.toSeconds();
    }
}
