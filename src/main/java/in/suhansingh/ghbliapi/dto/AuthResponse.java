package in.suhansingh.ghbliapi.dto;

import in.suhansingh.ghbliapi.model.User;
import in.suhansingh.ghbliapi.security.UserPrincipal;

import java.util.Set;

/**
 * What signup and login both return.
 *
 * <p>A record rather than a Lombok {@code @Data} class like the request DTOs: the field
 * list is the whole security contract here, and a record makes "there is no password
 * field, and no setter could ever add one" structurally true rather than a convention.
 * {@link User} is never serialised directly — {@code @JsonIgnore} on the hash would
 * cover it, but sending the entity would also expose whatever fields Phase 3 adds.
 *
 * @param token       the signed JWT, to be sent back as {@code Authorization: Bearer <token>}
 * @param tokenType   always {@code Bearer}; spelled out so the client never hardcodes it
 * @param expiresIn   lifetime in SECONDS from issue, not an absolute timestamp — the client
 *                    cannot trust its own clock to be in sync with the server's
 * @param userId      the Mongo id, which is also the JWT subject
 * @param name        display name for the header
 * @param email       normalised (lower-cased, trimmed) address as stored
 * @param roles       granted authorities, already carrying the {@code ROLE_} prefix
 */
public record AuthResponse(
        String token,
        String tokenType,
        long expiresIn,
        String userId,
        String name,
        String email,
        Set<String> roles) {

    public static final String BEARER = "Bearer";

    /**
     * Single construction point, so no caller can assemble one field-by-field and slip.
     *
     * <p>Built from the principal rather than from {@link User} because login already has
     * one — {@code MongoUserDetailsService} loaded the document to check the password — and
     * reading the entity again just to fill in a name would be a second query for data
     * already in hand.
     */
    public static AuthResponse of(String token, long expiresInSeconds, UserPrincipal principal) {
        return new AuthResponse(
                token,
                BEARER,
                expiresInSeconds,
                principal.getId(),
                principal.getName(),
                principal.getEmail(),
                principal.getRoles());
    }
}
