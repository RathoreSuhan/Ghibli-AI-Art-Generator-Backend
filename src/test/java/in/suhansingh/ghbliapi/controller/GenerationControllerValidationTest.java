package in.suhansingh.ghbliapi.controller;

import in.suhansingh.ghbliapi.config.SecurityConfig;
import in.suhansingh.ghbliapi.dto.TextGenerationRequestDTO;
import in.suhansingh.ghbliapi.exception.GenerationFailedException;
import in.suhansingh.ghbliapi.security.JwtService;
import in.suhansingh.ghbliapi.service.GenerationHistoryService;
import in.suhansingh.ghbliapi.service.GhibliArtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves two things that are otherwise invisible:
 *
 * <ol>
 *   <li>{@code @Valid}/{@code @NotBlank} actually reject bad input. Without
 *       spring-boot-starter-validation on the classpath there is no
 *       hibernate-validator, the annotations are inert, and every one of the
 *       "rejects" tests below would return 200 instead of 400 — with no warning
 *       anywhere in the log.</li>
 *   <li>Both endpoints report failures through the same RFC 9457 ProblemDetail
 *       contract, instead of text/plain bytes on one and an empty body on the other.</li>
 * </ol>
 *
 * <p>Phase 2 added three annotations that are all load-bearing:
 *
 * <ul>
 *   <li>{@code @Import(SecurityConfig.class)} — {@code @WebMvcTest} does not scan
 *       {@code @Configuration}, so without this the slice silently runs Boot's DEFAULT
 *       security chain instead of the real one. Nothing fails; the tests would just be
 *       asserting against rules the application does not have.</li>
 *   <li>{@code @MockitoBean JwtService} — {@code SecurityConfig} needs one to build the JWT
 *       filter, and a {@code @Service} is not in this slice either. Without it the context
 *       fails to start with {@code UnsatisfiedDependencyException}. It is never called: no
 *       test here sends a token.</li>
 *   <li>{@code @WithMockUser} — both endpoints now require authentication, so every request
 *       below would be a 401 instead of the status under test. Authentication is not what
 *       this class is about; it is asserted properly in {@code AuthenticationIntegrationTest}.</li>
 * </ul>
 */
@WebMvcTest(GenerationController.class)
@Import(SecurityConfig.class)
@WithMockUser
class GenerationControllerValidationTest {

    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private GhibliArtService ghibliArtService;

    @MockitoBean
    private JwtService jwtService;

    /**
     * Phase 3 gave {@code GenerationController} a second constructor argument, and a
     * {@code @Service} is not part of a {@code @WebMvcTest} slice — without this the context
     * fails to start with {@code UnsatisfiedDependencyException} and every test below breaks for
     * a reason unrelated to what they assert.
     *
     * <p>A mock also keeps this class honest about its scope: the recording call becomes a no-op,
     * so nothing here depends on Mongo and the persistence behaviour is proved where it belongs,
     * in {@code GenerationHistoryIntegrationTest} and {@code GenerationPersistenceFailureTest}.
     */
    @MockitoBean
    private GenerationHistoryService generationHistoryService;

    // --- validation is live -------------------------------------------------

    /**
     * The unambiguous check. {@code spring-boot-starter-web} pulls in no JSR-380 provider,
     * so without spring-boot-starter-validation this throws NoProviderFoundException and
     * Boot's ValidationAutoConfiguration registers no Validator bean at all — which is why
     * {@code @Valid} then does nothing rather than complaining.
     */
    @Test
    void hibernateValidatorIsTheWiredJsr380Provider() {
        assertThat(jakarta.validation.Validation.buildDefaultValidatorFactory().getClass().getName())
                .startsWith("org.hibernate.validator");

        TextGenerationRequestDTO blank = new TextGenerationRequestDTO();
        blank.setPrompt("   ");
        assertThat(applicationContext.getBean(jakarta.validation.Validator.class).validate(blank))
                .hasSize(1);
    }

    @Test
    void blankPromptIsRejectedWithProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"   \",\"style\":\"general\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                // Present only because hibernate-validator produced a field error.
                .andExpect(jsonPath("$.errors.prompt").value("must not be blank"));
    }

    @Test
    void missingPromptIsRejectedWithProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"style\":\"general\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.prompt").value("must not be blank"));
    }

    // --- the style default reaches the service unharmed ---------------------

    @Test
    void omittedStyleDefaultsToGeneralInsteadOfNull() throws Exception {
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString())).thenReturn(FAKE_PNG);

        mockMvc.perform(post("/api/v1/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        ArgumentCaptor<String> style = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ghibliArtService)
                .createGhibliArtFromText(eq("a quiet hillside"), style.capture());
        assertThat(style.getValue()).isEqualTo("general");
    }

    @Test
    void explicitNullStyleIsPassedThroughWithoutFailing() throws Exception {
        when(ghibliArtService.createGhibliArtFromText(anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(FAKE_PNG);

        // Jackson overwrites the DTO default with null here, which is exactly the
        // input that used to NPE inside GhibliArtService. The service-side guard
        // is covered by GhibliArtServiceStyleTest.
        mockMvc.perform(post("/api/v1/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":null}"))
                .andExpect(status().isOk());
    }

    // --- one error contract for both endpoints ------------------------------

    @Test
    void textEndpointFailureReturnsProblemDetailNotAnEmptyBody() throws Exception {
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString()))
                .thenThrow(new GenerationFailedException("Stability said no", new RuntimeException("boom")));

        mockMvc.perform(post("/api/v1/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"general\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Generation failed"))
                .andExpect(jsonPath("$.detail").value("Stability said no"))
                .andExpect(jsonPath("$.instance").value("/api/v1/generate-from-text"));
    }

    @Test
    void photoEndpointFailureReturnsProblemDetailNotTextPlain() throws Exception {
        when(ghibliArtService.createGhibliArt(any(), anyString()))
                .thenThrow(new GenerationFailedException("Photo to art generation failed: nope", new RuntimeException()));

        mockMvc.perform(multipart("/api/v1/generate")
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG))
                        .param("prompt", "make it dreamy"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Photo to art generation failed: nope"));
    }

    @Test
    void oversizedImageIsA400NotA502() throws Exception {
        when(ghibliArtService.createGhibliArt(any(), anyString()))
                .thenThrow(new IllegalArgumentException("Image is too large. Stability supports up to 5MB for photo-to-art."));

        mockMvc.perform(multipart("/api/v1/generate")
                        .file(new MockMultipartFile("image", "big.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG))
                        .param("prompt", "make it dreamy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid generation request"))
                .andExpect(jsonPath("$.detail")
                        .value("Image is too large. Stability supports up to 5MB for photo-to-art."));
    }

    @Test
    void missingPromptParamOnPhotoEndpointReturnsProblemDetail() throws Exception {
        mockMvc.perform(multipart("/api/v1/generate")
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void successfulGenerationStillReturnsRawPngBytes() throws Exception {
        when(ghibliArtService.createGhibliArt(any(), anyString())).thenReturn(FAKE_PNG);

        byte[] body = mockMvc.perform(multipart("/api/v1/generate")
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG))
                        .param("prompt", "make it dreamy"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        assertThat(body).isEqualTo(FAKE_PNG);
    }
}
