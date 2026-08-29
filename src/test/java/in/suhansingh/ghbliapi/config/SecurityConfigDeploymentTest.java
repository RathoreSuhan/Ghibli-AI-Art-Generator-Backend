package in.suhansingh.ghbliapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the two pieces of {@link SecurityConfig} that exist only for the deployed setup —
 * Vercel frontend, Render backend — and that therefore cannot be exercised by running the app
 * locally.
 *
 * <p>Both fail in the same expensive way: silently, and only once deployed. A CORS list that does
 * not actually match the Vercel origin looks like a working backend whose frontend is broken, and
 * a health check that answers 401 makes Render mark every deploy as failed and roll it back, with
 * a healthy application in the logs.
 *
 * <p>{@code AuthenticationIntegrationTest}'s CORS tests cover the localhost default. These cover
 * what happens once {@code CORS_ALLOWED_ORIGINS} is set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigDeploymentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    /**
     * Asks the bean directly rather than going through MockMvc, because the interesting behaviour
     * is the origin list itself and a preflight would only report the same answer through two more
     * layers. {@code checkOrigin} returns the echoed origin when allowed and null when not.
     */

    // --- CORS: the deployed origin list -------------------------------------

    /** The default is what a clean clone and the test suite see; the localhost pair, nothing else. */
    @Test
    void theDefaultOriginListIsLocalhostOnly() {
        CorsConfiguration configuration = configurationFor(SecurityConfig.DEFAULT_ALLOWED_ORIGINS);

        assertThat(configuration.getAllowedOrigins())
                .containsExactly("http://localhost:3000", "http://127.0.0.1:3000");
        assertThat(configuration.getAllowedOriginPatterns())
                .as("no wildcard in the default, so patterns must stay unset rather than empty")
                .isNull();
    }

    /**
     * The production shape: one fixed origin plus a pattern for Vercel's per-deployment preview
     * URLs. The split is the point — a '*' left in {@code allowedOrigins} is compared literally and
     * would match nothing at all, which is the failure this test exists for.
     */
    @Test
    void anEntryWithAWildcardBecomesAPatternAndTheRestStayExact() {
        CorsConfiguration configuration =
                configurationFor("https://ghbli-ai.vercel.app,https://ghbli-ai-*.vercel.app");

        assertThat(configuration.getAllowedOrigins()).containsExactly("https://ghbli-ai.vercel.app");
        assertThat(configuration.getAllowedOriginPatterns()).containsExactly("https://ghbli-ai-*.vercel.app");
    }

    @Test
    void bothTheFixedOriginAndAPreviewUrlAreAccepted() {
        CorsConfiguration configuration =
                configurationFor("https://ghbli-ai.vercel.app,https://ghbli-ai-*.vercel.app");

        assertThat(configuration.checkOrigin("https://ghbli-ai.vercel.app"))
                .isEqualTo("https://ghbli-ai.vercel.app");
        assertThat(configuration.checkOrigin("https://ghbli-ai-git-main-suhan.vercel.app"))
                .as("Vercel gives every deployment its own hostname; without the pattern each one "
                        + "would need a backend redeploy")
                .isEqualTo("https://ghbli-ai-git-main-suhan.vercel.app");
    }

    /**
     * Setting the variable must not turn into "allow everything". Note the http variant: the same
     * host over a different scheme is a different origin, and the browser treats it as such.
     */
    @Test
    void anythingNotListedIsStillRejected() {
        CorsConfiguration configuration =
                configurationFor("https://ghbli-ai.vercel.app,https://ghbli-ai-*.vercel.app");

        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
        assertThat(configuration.checkOrigin("http://ghbli-ai.vercel.app")).isNull();
        assertThat(configuration.checkOrigin("https://ghbli-ai.vercel.app.evil.example")).isNull();
    }

    /**
     * A trailing comma or a value pasted with spaces is an ordinary dashboard typo. It must not
     * contribute an empty origin — harmless in effect, but it reads like a configured value when
     * the list is dumped while debugging.
     */
    @Test
    void whitespaceAndEmptyEntriesAreDiscarded() {
        CorsConfiguration configuration = configurationFor(" https://ghbli-ai.vercel.app , ,");

        assertThat(configuration.getAllowedOrigins()).containsExactly("https://ghbli-ai.vercel.app");
    }

    /** Builds the bean by hand: the property varies per test, so injection cannot express it. */
    private static CorsConfiguration configurationFor(String allowedOrigins) {
        CorsConfigurationSource source =
                new SecurityConfig().corsConfigurationSource(allowedOrigins.split(","));

        // The source registers one configuration for "/**", so any path returns it. Asking for a
        // real endpoint keeps the test honest about that being true for the paths that matter.
        return ((org.springframework.web.cors.UrlBasedCorsConfigurationSource) source)
                .getCorsConfigurations()
                .get("/**");
    }

    // --- Health check: what Render polls -----------------------------------

    /**
     * Anonymous 200, or Render fails the deploy. The injected {@link #corsConfigurationSource} and
     * this endpoint share a context on purpose: this is the real chain, so a mistyped path in
     * {@code HEALTH_PATHS} shows up here as a 401 rather than at deploy time.
     */
    @Test
    void healthIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * The other half of the permitAll: only health is open. {@code /actuator/env} would publish the
     * whole Environment — including the resolved Mongo URI and JWT secret — so it must not be
     * reachable. It is 401 rather than 404 because {@code anyRequest().authenticated()} is
     * evaluated before the dispatcher discovers there is no such handler; either would be
     * acceptable, and the assertion allows both so this does not break on a Boot upgrade.
     */
    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        int status = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();

        assertThat(status)
                .as("/actuator/env must not be readable; management.endpoints.web.exposure.include "
                        + "keeps it unregistered and the filter chain keeps it closed")
                .isIn(401, 404);
    }
}
