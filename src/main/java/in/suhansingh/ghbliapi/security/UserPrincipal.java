package in.suhansingh.ghbliapi.security;

import in.suhansingh.ghbliapi.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The authenticated principal.
 *
 * <p>Exists instead of Spring's own {@code org.springframework.security.core.userdetails.User}
 * for one reason: that class carries only a username, and Phase 3 has to scope every
 * history query to a Mongo {@code _id}. Without the id on the principal the only way to
 * get it would be to re-query by e-mail on each request, or — far worse — to accept it
 * from the client, which is a textbook IDOR.
 *
 * <p>{@link #getUsername()} returns the e-mail because that is the login identifier
 * {@code DaoAuthenticationProvider} matches on. {@link #getId()} is the stable one.
 */
public class UserPrincipal implements UserDetails {

    private final String id;
    private final String email;

    /**
     * BCrypt hash on the login path; {@code null} on the token path, where no password is
     * ever checked. Only {@code DaoAuthenticationProvider} reads this, and only during
     * login, so the null case is unreachable rather than merely unlikely.
     */
    private final String password;

    private final String name;
    private final Collection<? extends GrantedAuthority> authorities;

    private UserPrincipal(String id, String email, String password, String name,
                          Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.name = name;
        this.authorities = authorities;
    }

    /** Login path: everything comes from the stored document. */
    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                user.getName(),
                toAuthorities(user.getRoles()));
    }

    /**
     * Token path: everything comes from claims that were just signature-verified, with no
     * database round trip. See {@link JwtAuthenticationFilter} for the revocation
     * tradeoff that buys.
     */
    public static UserPrincipal fromToken(String id, String email, Set<String> roles) {
        return new UserPrincipal(id, email, null, null, toAuthorities(roles));
    }

    /**
     * Roles are stored already carrying the {@code ROLE_} prefix (see
     * {@link User#ROLE_USER}), so they map straight across. Adding a second prefix here
     * would produce {@code ROLE_ROLE_USER} and break {@code hasRole("USER")} in a way that
     * only shows up once an authorization rule is actually written.
     */
    private static List<SimpleGrantedAuthority> toAuthorities(Set<String> roles) {
        return roles == null ? List.of() : roles.stream().map(SimpleGrantedAuthority::new).toList();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    /**
     * Role names as stored, i.e. still carrying the {@code ROLE_} prefix. Kept in insertion
     * order so the {@code roles} array in a token and in an auth response is stable rather
     * than hash-ordered — otherwise the same account serialises differently run to run and
     * assertions on it are flaky.
     */
    public Set<String> getRoles() {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    // Account lifecycle flags are hardcoded true: the User document models no expiry,
    // lock or credential-rotation state, so returning anything else would be inventing a
    // rule the data cannot support. Boot 3.5's UserDetails defaults these to true too —
    // they are spelled out here so the omission reads as a decision, not an oversight.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
