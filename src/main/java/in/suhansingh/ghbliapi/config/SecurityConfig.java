package in.suhansingh.ghbliapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.suhansingh.ghbliapi.security.JwtAuthenticationFilter;
import in.suhansingh.ghbliapi.security.JwtService;
import in.suhansingh.ghbliapi.security.ProblemDetailAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The single filter chain. Adding {@code spring-boot-starter-security} to the pom was not
 * an additive change: it switched on a default chain that secures <em>every</em> endpoint
 * with HTTP Basic and a generated password. This class replaces that wholesale.
 *
 * <p>Written entirely in the lambda DSL. {@code WebSecurityConfigurerAdapter} is gone in
 * Spring Security 6, {@code antMatchers}/{@code mvcMatchers} are gone in favour of
 * {@code requestMatchers}, and the no-argument chained forms ({@code .csrf().disable()},
 * {@code .and()}) are deprecated for removal — so none of them appear here.
 *
 * <p>Deliberately holds no repository or {@code UserDetailsService} dependency. That keeps
 * it importable into a {@code @WebMvcTest}, where {@code @MockitoBean JwtService} is then
 * enough to stand the whole chain up; the DAO wiring that does need Mongo lives in
 * {@link AuthenticationConfig}. Without {@code @Import(SecurityConfig.class)} a slice test
 * silently gets Boot's <em>default</em> chain instead of this one and quietly asserts
 * against rules that do not exist in production.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The frontend is Create React App ({@code react-scripts start}), which serves on 3000.
     * Not 5173 — that is Vite's port, and this project is not a Vite project.
     */
    static final List<String> ALLOWED_ORIGINS = List.of(
            "http://localhost:3000",
            "http://127.0.0.1:3000");

    static final String AUTH_PATHS = "/api/v1/auth/**";

    static final String[] GENERATE_PATHS = {"/api/v1/generate", "/api/v1/generate-from-text"};

    /**
     * The history endpoints: list, image bytes, delete. Covered by {@code anyRequest()} already,
     * but written out explicitly so that "these are user-scoped and authenticated" is visible in
     * the chain rather than inferred from a default.
     */
    static final String GENERATION_PATHS = "/api/v1/generations/**";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            AuthenticationEntryPoint authenticationEntryPoint,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        return http
                // Safe to disable only because the session policy below is STATELESS and the
                // token travels in a header, never a cookie. CSRF needs the browser to attach
                // credentials automatically; nothing here does. Reintroducing cookie auth in a
                // later phase would make this line a vulnerability.
                .csrf(AbstractHttpConfigurer::disable)

                // Passed explicitly rather than relying on lookup. CorsConfigurer resolves an
                // unconfigured source by bean NAME "corsConfigurationSource", so renaming the
                // method below would disable CORS silently — no error, just failing browsers.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Off because the starter turns them on by default and each is a second way in:
                // a Basic-auth prompt and a form-login page on an API that authenticates only
                // by bearer token are attack surface with no caller.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // Without this the default is Http403ForbiddenEntryPoint, so a missing token
                // answers 403 and the frontend cannot tell "log in" from "not allowed".
                .exceptionHandling(handling -> handling.authenticationEntryPoint(authenticationEntryPoint))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AUTH_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, GENERATE_PATHS).authenticated()
                        // No method qualifier: GET, DELETE and anything added under this prefix
                        // later all require a token. Ownership is a separate question, decided in
                        // GenerationHistoryService from the SecurityContext — the chain only
                        // establishes *that* there is a user, never *which* rows are theirs.
                        .requestMatchers(GENERATION_PATHS).authenticated()
                        // Deny by default. Any endpoint a later phase adds is closed until
                        // someone opens it, which is the failure direction to prefer.
                        .anyRequest().authenticated())

                // Before UsernamePasswordAuthenticationFilter so the SecurityContext is already
                // populated by the time AuthorizationFilter evaluates the rules above.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * Replaces the {@code @CrossOrigin} annotation that used to sit on
     * {@code GenerationController}; that annotation was deleted in the same change, because
     * two sources of CORS truth means edits land in one and not the other.
     *
     * <p>There is intentionally no {@code permitAll} rule for {@code OPTIONS}. {@code CorsFilter}
     * runs at order 88, ahead of {@code AuthorizationFilter}, and returns from a preflight
     * without continuing the chain — so preflight never reaches an authorization check. A
     * blanket {@code OPTIONS permitAll} would be redundant and would also open OPTIONS on
     * every future endpoint.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(ALLOWED_ORIGINS);
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                // DELETE arrived with the history endpoints. Omitting it would not have failed a
                // test or logged anything — the browser would simply refuse the preflight for
                // DELETE /api/v1/generations/{id} and the frontend's delete button would appear
                // broken with a green server log.
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));

        // Authorization is the one that matters: it is not a CORS-safelisted header, so
        // without it here the browser refuses the preflight and every authenticated call
        // from the frontend fails before it is sent. Content-Type is needed for both
        // application/json and multipart/form-data bodies.
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE));

        // False on purpose. Credentials mean cookies, and this API carries its token in a
        // header; allowing them would also forbid using "*" anywhere and invite a future
        // wildcard-origin change that the browser would then reject.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * BCrypt at the library default cost (strength 10). Note the algorithm's 72-byte input
     * limit — anything longer is silently truncated, which is why {@code SignupRequest} caps
     * the password there rather than letting a long passphrase be quietly shortened.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper);
    }
}
