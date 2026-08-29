package in.suhansingh.ghbliapi.client;

import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import in.suhansingh.ghbliapi.exception.StabilityApiException;
import in.suhansingh.ghbliapi.exception.StabilityErrorTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Turns a non-2xx response from {@link StabilityAIClient} into a
 * {@link StabilityApiException} at the boundary, instead of letting a bare
 * {@code FeignException} travel up to the advice.
 *
 * <p>Why the boundary and not the handler: this is the only place the response <em>body</em> is
 * still readable. Feign's default decoder does capture the body into
 * {@code FeignException.contentUTF8()}, but by the time the advice sees it the classification
 * would have to be repeated there — and the image-to-image path, which never touches Feign,
 * would need its own copy of the same rules. Both paths call
 * {@link StabilityErrorTranslator} instead.
 *
 * <p>Registered as a bean in {@code FeignConfig}, which is already the {@code configuration}
 * attribute on the client, so it applies to that client only.
 */
public class StabilityErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(StabilityErrorDecoder.class);

    @Override
    public Exception decode(String methodKey, Response response) {
        String body = readBody(response);
        StabilityApiException translated = StabilityErrorTranslator.translate(
                response.status(), body, firstHeader(response, "Retry-After"));

        // The upstream body is logged once, here, and never returned to the client: it can quote
        // the prompt back and names internal engine ids.
        log.warn(
                "Stability call {} failed: upstream status {} classified as {} — body: {}",
                methodKey,
                response.status(),
                translated.getFailure(),
                abbreviate(body));

        return translated;
    }

    /** Best effort — a missing or unreadable body only costs the body-based classification. */
    private String readBody(Response response) {
        if (response.body() == null) {
            return null;
        }

        try {
            return Util.toString(response.body().asReader(Util.UTF_8));
        } catch (IOException ex) {
            log.debug("Could not read Stability error body", ex);
            return null;
        }
    }

    /**
     * Case-insensitive header lookup. Feign's header map is a plain {@code Map<String,
     * Collection<String>>} whose key casing follows whatever the server sent, so an exact
     * {@code get("Retry-After")} would silently miss a lowercase {@code retry-after}.
     */
    private String firstHeader(Response response, String name) {
        for (Map.Entry<String, Collection<String>> entry : response.headers().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                Collection<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.iterator().next();
            }
        }
        return null;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "<none>";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
