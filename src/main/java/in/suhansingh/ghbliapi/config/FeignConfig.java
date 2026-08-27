package in.suhansingh.ghbliapi.config;

import feign.codec.Encoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Encoder for {@link in.suhansingh.ghbliapi.client.StabilityAIClient}.
 *
 * <p>This bean is <em>equivalent</em> to what Spring Cloud would supply on its own. Verified
 * against the 4.3.0 bytecode: {@code FeignClientsConfiguration#feignEncoder} builds the same
 * {@code new SpringEncoder(messageConverters)}, and the one-argument constructor internally
 * creates its own {@code new SpringFormEncoder()} plus a default {@code FeignEncoderProperties}.
 * So registering it changes nothing about how bodies are encoded, and in particular it is
 * <strong>not</strong> the reason multipart cannot go through Feign here — that is feign-form's
 * handling of {@code Resource} parts, measured in {@code FeignMultipartEncodingTest}.
 *
 * <p>Kept because it makes the encoder explicit at the call site rather than inherited from
 * autoconfiguration, and removing it would be a behaviour-neutral change to a working client.
 * Two things it does forgo, neither of which this application uses: {@code
 * spring.cloud.openfeign.encoder.*} properties, and any {@code HttpMessageConverterCustomizer}
 * beans. If either is ever needed, delete this class and let the framework default apply.
 */
@Configuration
public class FeignConfig {

    @Bean
    public Encoder feignEncoder(ObjectFactory<HttpMessageConverters> messageConverters) {
        return new SpringEncoder(messageConverters);
    }
}
