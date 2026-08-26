package in.suhansingh.ghbliapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Guards the fix for the confirmed bug: {@code GhibliArtService} used to call
 * {@code new RestTemplate()} on every request, which has connect and read timeouts
 * of -1 (infinite) and can pin a Tomcat worker thread forever.
 */
class StabilityHttpClientConfigTest {

    @Test
    void registersOneSharedRestTemplateBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
                .withUserConfiguration(StabilityHttpClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(org.springframework.web.client.RestTemplate.class);
                    // Singleton scope is the point — the same instance is reused per request.
                    assertThat(context.getBean("stabilityRestTemplate"))
                            .isSameAs(context.getBean("stabilityRestTemplate"));
                });
    }

    /**
     * Also pins the non-deprecated builder methods: {@code setConnectTimeout} /
     * {@code setReadTimeout} still compile in Boot 3.5 but are deprecated, and swapping
     * to them would fail this verification.
     */
    @Test
    void appliesExplicitConnectAndReadTimeouts() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class, RETURNS_SELF);

        new StabilityHttpClientConfig().stabilityRestTemplate(builder);

        verify(builder).connectTimeout(Duration.ofSeconds(10));
        verify(builder).readTimeout(Duration.ofSeconds(60));
    }
}
