package in.suhansingh.ghbliapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The password-checking half of authentication, kept apart from {@link SecurityConfig} for
 * one concrete reason: this is the only security wiring that needs a
 * {@code UserDetailsService}, and therefore Mongo. A {@code @WebMvcTest} can import
 * {@code SecurityConfig} and get the real filter chain without dragging a repository into
 * the slice; it never loads this class, because {@code @WebMvcTest} does not scan
 * {@code @Configuration}.
 *
 * <p>Only the login endpoint reaches any of this. Once a token exists,
 * {@code JwtAuthenticationFilter} authenticates from verified claims and no provider runs.
 */
@Configuration
public class AuthenticationConfig {

    /**
     * Constructed with the non-deprecated {@code DaoAuthenticationProvider(UserDetailsService)}
     * constructor. The no-arg form plus {@code setUserDetailsService(..)} — still what most
     * tutorials show — is deprecated in Spring Security 6.5.
     *
     * <p>{@code setPasswordEncoder} stays a setter because there is no constructor overload
     * for it. Leaving it unset would fall back to {@code DelegatingPasswordEncoder}, which
     * expects an {@code {id}} prefix on the stored hash and rejects the bare
     * {@code $2a$...} that {@code BCryptPasswordEncoder} writes — so every login would fail
     * on a correct password.
     */
    @Bean
    DaoAuthenticationProvider daoAuthenticationProvider(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Exposed so {@code AuthService} can inject it. Built explicitly rather than pulled from
     * {@code AuthenticationConfiguration#getAuthenticationManager()}: with a single known
     * provider, one line of {@code ProviderManager} is clearer than asking the framework to
     * discover what we just defined.
     *
     * <p>{@code ProviderManager} erases credentials on the returned {@code Authentication}
     * after a successful authenticate, so the submitted password does not linger in the
     * object handed back to the service.
     */
    @Bean
    AuthenticationManager authenticationManager(DaoAuthenticationProvider daoAuthenticationProvider) {
        return new ProviderManager(daoAuthenticationProvider);
    }
}
