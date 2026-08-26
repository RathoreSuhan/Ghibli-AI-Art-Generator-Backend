package in.suhansingh.ghbliapi.security;

import in.suhansingh.ghbliapi.model.User;
import in.suhansingh.ghbliapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users for {@code DaoAuthenticationProvider} during login. Nothing else calls it —
 * {@link JwtAuthenticationFilter} authenticates from verified claims instead of hitting
 * Mongo on every request.
 *
 * <p>The {@link User#normalizeEmail} call is load-bearing, not defensive. {@code User}
 * lower-cases and trims on the way in, and Mongo string equality is case-sensitive, so
 * without normalizing the lookup a login as {@code Suhan@Example.com} would miss the
 * stored {@code suhan@example.com} and report bad credentials for a correct password.
 */
@Service
@RequiredArgsConstructor
public class MongoUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = User.normalizeEmail(username);

        return userRepository.findByEmail(email)
                .map(UserPrincipal::fromUser)
                // The message never reaches the client: DaoAuthenticationProvider has
                // hideUserNotFoundExceptions = true by default and converts this into a
                // generic BadCredentialsException, so "no such account" and "wrong
                // password" are indistinguishable from outside. That is what stops the
                // login endpoint from being a user-enumeration oracle.
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
