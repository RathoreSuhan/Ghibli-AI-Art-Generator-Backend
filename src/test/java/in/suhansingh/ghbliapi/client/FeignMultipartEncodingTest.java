package in.suhansingh.ghbliapi.client;

import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringEncoder;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why photo-to-art does <strong>not</strong> go through OpenFeign — measured, not assumed.
 *
 * <p>The comment this test replaces claimed only that "multipart Feign encoding was causing
 * generation failures", with no record of what the failure was. That left a plausible but
 * unproven story (Stability rejecting Feign's wire format) standing as the reason. These tests
 * encode the exact request {@code GhibliArtService#createGhibliArt} builds and look at the bytes
 * Feign would have put on the wire, so the reason is now a measurement.
 *
 * <p><strong>What it shows.</strong> An {@code init_image} part typed as a Spring
 * {@link Resource} is dropped entirely, and no exception is raised. The other parts encode
 * correctly, so the request Stability receives is a well-formed multipart body that is simply
 * missing the image — which its API answers with a 400 about a missing {@code init_image}, a
 * message that points at the caller rather than at the encoder.
 *
 * <p><strong>Why.</strong> Traced through {@code feign-form 13.6}, which
 * {@code spring-cloud-starter-openfeign 4.3.0} pulls in as a non-optional compile dependency:
 *
 * <ol>
 *   <li>{@code SpringEncoder.encode} sees a form-related {@code Content-Type} and hands the whole
 *       body to {@code SpringFormEncoder}. This happens with the encoder this application
 *       registers <em>and</em> with the framework default — the one-argument
 *       {@code SpringEncoder(ObjectFactory)} constructor builds its own
 *       {@code new SpringFormEncoder()} internally, so the choice of encoder bean is not what
 *       decides this.</li>
 *   <li>{@code MultipartFormContentProcessor} picks the first {@code Writer} whose
 *       {@code isApplicable} accepts the value. For a {@code ByteArrayResource} every specific
 *       writer declines: it is not {@code byte[]} ({@code ByteArrayWriter}), not
 *       {@code java.io.File} ({@code SingleFileWriter}), not a {@code MultipartFile}
 *       ({@code SpringSingleMultipartFileWriter}) and not a {@code Number}/{@code CharSequence}/
 *       {@code Boolean} ({@code SingleParameterWriter}).</li>
 *   <li>The catch-all {@code PojoWriter} therefore wins, because {@code PojoUtil.isUserPojo}
 *       returns true for anything whose package does not start with {@code java.} — and
 *       {@code org.springframework.core.io} does not. It then calls {@code PojoUtil.toMap}, which
 *       reflects over {@code getClass().getDeclaredFields()} and <em>skips every final field</em>.
 *       {@code ByteArrayResource} declares exactly one field, {@code private final byte[]
 *       byteArray}. The map comes back empty, the write loop runs zero times, and the part is
 *       gone without a word.</li>
 * </ol>
 *
 * <p><strong>The decision.</strong> Keep the injected {@code RestTemplate} for image-to-image.
 * Feign could be made to work — {@link #aMultipartFileIsEncodedCorrectlyByTheSameEncoder()} shows
 * the same encoder handles a {@link MultipartFile} fine, so an adapter wrapping the resized bytes
 * would do it — but that trades a working, verified call for a type-laundering shim whose only
 * purpose is to satisfy an encoder, on the one path that cannot be tested without spending money
 * at Stability. The resize step is what forces the issue: it produces {@code byte[]}, not the
 * original {@code MultipartFile}, so the value being sent is never naturally of a type feign-form
 * handles.
 *
 * <p>Two of these tests would fail if a future feign-form release fixed the drop — which is the
 * point. They document a third-party behaviour that the design decision rests on, so if it
 * changes, the decision gets re-examined instead of quietly outliving its reason.
 */
class FeignMultipartEncodingTest {

    private static final byte[] RESIZED_PNG = "pretend-this-is-a-1024x1024-png".getBytes(StandardCharsets.UTF_8);

    private static final String INIT_IMAGE = "init_image";
    private static final String PROMPT_PART = "text_prompts[0][text]";
    private static final String STYLE_PART = "style_preset";

    private static final String PROMPT = "a quiet hillside, in the beautiful, detailed anime style of studio ghibli.";

    /**
     * The image-to-image call as it would look if it were declared on {@link StabilityAIClient}:
     * the same three parts, the same names, the same order as the {@code LinkedMultiValueMap} in
     * {@code GhibliArtService#createGhibliArt}.
     */
    interface ImageToImageProbe {

        @PostMapping(value = "/v1/generation/{engine}/image-to-image",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        byte[] withResource(@RequestHeader("Authorization") String authorization,
                            @RequestPart(INIT_IMAGE) Resource initImage,
                            @RequestPart(PROMPT_PART) String prompt,
                            @RequestPart(STYLE_PART) String stylePreset);

        @PostMapping(value = "/v1/generation/{engine}/image-to-image-mpf",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        byte[] withMultipartFile(@RequestHeader("Authorization") String authorization,
                                 @RequestPart(INIT_IMAGE) MultipartFile initImage,
                                 @RequestPart(PROMPT_PART) String prompt,
                                 @RequestPart(STYLE_PART) String stylePreset);
    }

    /**
     * A capturing {@link Client} instead of a real socket. The question is what Feign encodes,
     * not whether a server is reachable, and an in-process client makes the answer deterministic:
     * no ports, no timeouts, nothing to leak between tests.
     */
    private static final class CapturingClient implements Client {

        private final AtomicReference<Request> captured = new AtomicReference<>();

        @Override
        public Response execute(Request request, Request.Options options) {
            captured.set(request);
            return Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .headers(Collections.emptyMap())
                    .body(new byte[]{(byte) 0x89, 'P', 'N', 'G'})
                    .build();
        }

        Request request() {
            return captured.get();
        }
    }

    /**
     * Built with the encoder this application actually registers —
     * {@code new SpringEncoder(messageConverters)}, the same expression as
     * {@code FeignConfig#feignEncoder} — so what is measured here is this project's
     * configuration and not a hypothetical one.
     */
    private static ImageToImageProbe probe(CapturingClient client) {
        ObjectFactory<HttpMessageConverters> converters = HttpMessageConverters::new;

        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new SpringEncoder(converters))
                .client(client)
                .target(ImageToImageProbe.class, "http://stability.invalid");
    }

    private static String bodyOf(CapturingClient client) {
        Request request = client.request();
        assertThat(request).as("the encoder never produced a request").isNotNull();
        assertThat(request.body()).as("multipart body was null").isNotNull();
        return new String(request.body(), StandardCharsets.UTF_8);
    }

    // --- the finding ---------------------------------------------------------

    /**
     * The whole reason for this file. A {@code ByteArrayResource} part is silently discarded:
     * no exception, no log, no part in the body.
     */
    @Test
    void aResourcePartIsDroppedFromTheEncodedBodyWithoutAnError() {
        CapturingClient client = new CapturingClient();

        probe(client).withResource("Bearer sk-not-a-real-key",
                new ByteArrayResource(RESIZED_PNG), PROMPT, "anime");

        String body = bodyOf(client);

        assertThat(body)
                .as("feign-form's PojoWriter claimed the Resource and wrote none of it — "
                        + "encoded body was:%n%s", body)
                .doesNotContain(INIT_IMAGE);
        assertThat(body)
                .as("the image bytes are absent too, so this is a dropped part and not a rename")
                .doesNotContain(new String(RESIZED_PNG, StandardCharsets.UTF_8));
    }

    /**
     * The half that makes the failure expensive to diagnose: everything else encodes perfectly.
     * Stability receives a valid multipart request with two good parts and no image, so its error
     * names {@code init_image} and reads like a caller mistake rather than an encoder fault.
     */
    @Test
    void theOtherPartsSurviveSoTheRequestLooksWellFormed() {
        CapturingClient client = new CapturingClient();

        probe(client).withResource("Bearer sk-not-a-real-key",
                new ByteArrayResource(RESIZED_PNG), PROMPT, "anime");

        String body = bodyOf(client);

        assertThat(body).contains(PROMPT_PART).contains(PROMPT);
        assertThat(body).contains(STYLE_PART).contains("anime");

        // Proof that the multipart path was taken at all, rather than the body being JSON:
        // SpringEncoder only delegates to SpringFormEncoder for a form-related Content-Type.
        assertThat(client.request().headers().get("Content-Type"))
                .as("Content-Type header: %s", client.request().headers())
                .anySatisfy(value -> assertThat(value).contains("multipart/form-data")
                        .contains("boundary="));
    }

    /**
     * Isolates the cause to the <em>type</em>, not to multipart-over-Feign in general. The same
     * encoder, the same three parts, the same interface — only {@link MultipartFile} instead of
     * {@link Resource} — and the image arrives.
     *
     * <p>This is also the shape option (a) would have taken: adapt the resized {@code byte[]} to
     * a {@code MultipartFile} and Feign would work. It is not adopted, for the reasons in the
     * class javadoc.
     */
    @Test
    void aMultipartFileIsEncodedCorrectlyByTheSameEncoder() {
        CapturingClient client = new CapturingClient();

        probe(client).withMultipartFile("Bearer sk-not-a-real-key",
                new MockMultipartFile(INIT_IMAGE, "upload.png", MediaType.IMAGE_PNG_VALUE, RESIZED_PNG),
                PROMPT, "anime");

        String body = bodyOf(client);

        assertThat(body)
                .as("MultipartFile has a dedicated writer, so the part is present — "
                        + "encoded body was:%n%s", body)
                .contains(INIT_IMAGE)
                .contains("filename=")
                .contains(new String(RESIZED_PNG, StandardCharsets.UTF_8));
    }

    /**
     * The property that made this worth measuring rather than reasoning about: nothing throws.
     * A dropped part is not an error condition anywhere in feign-form, so no amount of
     * exception handling around the call would have surfaced it.
     */
    @Test
    void encodingADroppedPartRaisesNothingAtAll() {
        CapturingClient client = new CapturingClient();

        byte[] response = probe(client).withResource("Bearer sk-not-a-real-key",
                new ByteArrayResource(RESIZED_PNG), PROMPT, "anime");

        // A 200 came back from the capturing client, meaning encode() completed normally.
        assertThat(response).isNotEmpty();
    }

    /**
     * The exact value {@code GhibliArtService} sends: an anonymous {@code ByteArrayResource}
     * subclass that overrides {@code getFilename()} so the multipart part carries a name.
     *
     * <p>Kept separate because the anonymous subclass is dropped for a second, independent
     * reason — {@code getDeclaredFields()} on it returns only the compiler's synthetic captured
     * variables, and none of {@code ByteArrayResource}'s own fields, since {@code toMap} does not
     * walk superclasses. So overriding {@code getFilename} cannot help: feign-form never asks.
     */
    @Test
    void theAnonymousSubclassWithAFilenameIsDroppedToo() {
        CapturingClient client = new CapturingClient();

        Resource named = new ByteArrayResource(RESIZED_PNG) {
            @Override
            public String getFilename() {
                return "upload.png";
            }
        };

        probe(client).withResource("Bearer sk-not-a-real-key", named, PROMPT, "anime");

        String body = bodyOf(client);

        assertThat(body).doesNotContain(INIT_IMAGE);
        assertThat(body)
                .as("getFilename() is never consulted, so the filename does not appear either")
                .doesNotContain("upload.png");
    }
}
