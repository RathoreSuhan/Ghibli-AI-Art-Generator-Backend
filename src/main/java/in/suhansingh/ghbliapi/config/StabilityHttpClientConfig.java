package in.suhansingh.ghbliapi.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Single Spring-managed HTTP client for the direct (non-Feign) Stability calls.
 *
 * <p>Replaces the per-request {@code new RestTemplate()} that used to sit in
 * {@code GhibliArtService}. That default constructor produces a client with
 * connect and read timeouts of -1 (infinite), so a hung Stability connection
 * would pin a Tomcat worker thread indefinitely. It also rebuilt the whole
 * message-converter set and opened a fresh connection on every request.
 */
@Configuration
public class StabilityHttpClientConfig {

    /** Fail fast if the TCP handshake stalls. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** SDXL image-to-image routinely takes tens of seconds, so keep this generous. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Uses the non-deprecated Boot 3.4+ builder methods. {@code setConnectTimeout} /
     * {@code setReadTimeout} still exist but are deprecated.
     */
    @Bean
    public RestTemplate stabilityRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
    }
}
