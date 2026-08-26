package in.suhansingh.ghbliapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.model.Generation;
import in.suhansingh.ghbliapi.model.GenerationImage;
import in.suhansingh.ghbliapi.repository.GenerationImageRepository;
import in.suhansingh.ghbliapi.repository.GenerationRepository;
import in.suhansingh.ghbliapi.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The asymmetry requirement, isolated: <strong>a persistence failure must not cost the user their
 * image.</strong>
 *
 * <p>Its own class rather than a case in {@code GenerationHistoryIntegrationTest} because it needs
 * a broken repository for the whole context, and a {@code @MockitoBean} is per-class. That is also
 * why it is worth having at all — the failure it guards is invisible in normal operation. Mongo
 * being unreachable, an Atlas failover mid-request, or a document over the 16 MB BSON limit would
 * all turn a paid, seconds-long Stability call the user has already waited for into a 500 with
 * nothing to show, if the recording call were allowed to throw.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationPersistenceFailureTest {

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String GENERATE_FROM_TEXT = "/api/v1/generate-from-text";

    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private GhibliArtService ghibliArtService;

    /**
     * Both repositories are mocked so each half of the two-document write can be broken
     * independently. Mocking them also means the real collections are never touched by this
     * class, so it cannot leak state into the integration test.
     */
    @MockitoBean
    private GenerationImageRepository generationImageRepository;

    @MockitoBean
    private GenerationRepository generationRepository;

    @BeforeEach
    void reset() {
        userRepository.deleteAll();
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString())).thenReturn(FAKE_PNG);
    }

    private String signUp() throws Exception {
        String body = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("Test User", "owner@example.com", "correct-horse-battery"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(body, AuthResponse.class).token();
    }

    private byte[] generate(String token) throws Exception {
        return mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
    }

    /**
     * The image write fails — the shape of "Mongo is gone". Without the catch in
     * {@code recordQuietly} this would be a 502 or 500 from the catch-all handler and the PNG
     * would be lost.
     */
    @Test
    void aFailingImageSaveStillReturnsThePngAndWritesNoMetadata() throws Exception {
        String token = signUp();
        when(generationImageRepository.save(any(GenerationImage.class)))
                .thenThrow(new DataAccessResourceFailureException("mongod is not reachable"));

        assertThat(generate(token)).isEqualTo(FAKE_PNG);

        // No half-written row: metadata is only attempted once the image is stored.
        verify(generationRepository, never()).save(any(Generation.class));
    }

    /**
     * The metadata write fails after the image is already stored. The user keeps the image, and
     * the now-unreferenced bytes are cleaned up rather than left behind — nothing can reach them
     * otherwise, since the only route to an image id is through a metadata document.
     */
    @Test
    void aFailingMetadataSaveStillReturnsThePngAndCleansUpTheOrphanImage() throws Exception {
        String token = signUp();

        GenerationImage stored = new GenerationImage("someone", FAKE_PNG, MediaType.IMAGE_PNG_VALUE);
        stored.setId("image-doc-id");
        when(generationImageRepository.save(any(GenerationImage.class))).thenReturn(stored);
        when(generationRepository.save(any(Generation.class)))
                .thenThrow(new DataAccessResourceFailureException("write concern not satisfied"));

        assertThat(generate(token)).isEqualTo(FAKE_PNG);

        verify(generationImageRepository).deleteById("image-doc-id");
    }

    /** Even the compensating delete failing must not reach the client. */
    @Test
    void aFailingCleanupAfterAFailingMetadataSaveStillReturnsThePng() throws Exception {
        String token = signUp();

        GenerationImage stored = new GenerationImage("someone", FAKE_PNG, MediaType.IMAGE_PNG_VALUE);
        stored.setId("image-doc-id");
        when(generationImageRepository.save(any(GenerationImage.class))).thenReturn(stored);
        when(generationRepository.save(any(Generation.class)))
                .thenThrow(new DataAccessResourceFailureException("write concern not satisfied"));
        doThrow(new DataAccessResourceFailureException("still not reachable"))
                .when(generationImageRepository).deleteById(anyString());

        assertThat(generate(token)).isEqualTo(FAKE_PNG);
    }

    /**
     * An {@code Error} is deliberately <em>not</em> swallowed — {@code recordQuietly} catches
     * {@link Exception}, not {@link Throwable}. An {@code OutOfMemoryError} while copying a
     * megabyte into a document is not a bookkeeping problem to log past; the JVM is in trouble and
     * pretending otherwise hides it. So this is a 5xx rather than a quiet success.
     */
    @Test
    void anErrorIsNotSwallowed() throws Exception {
        String token = signUp();
        when(generationImageRepository.save(any(GenerationImage.class)))
                .thenThrow(new StackOverflowError("not an Exception"));

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"general\"}"))
                .andExpect(status().is5xxServerError());
    }

    /**
     * The read endpoints are the opposite contract. A broken repository there must not be
     * flattened into an empty page — a caller told "you have no history" by a database outage
     * would reasonably conclude their work was deleted.
     */
    @Test
    void theHistoryEndpointDoesNotHideAFailureAsAnEmptyPage() throws Exception {
        String token = signUp();
        when(generationRepository.findByUserIdOrderByCreatedAtDesc(anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("mongod is not reachable"));

        mockMvc.perform(get("/api/v1/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /** A metadata row whose image document is gone is a 404, not a 500 and not an empty 200. */
    @Test
    void aMetadataRowPointingAtAMissingImageIsA404() throws Exception {
        String token = signUp();
        String userId = userRepository.findByEmail("owner@example.com").orElseThrow().getId();

        Generation orphaned = new Generation();
        orphaned.setId("generation-id");
        orphaned.setUserId(userId);
        orphaned.setImageId("image-doc-id");
        when(generationRepository.findByIdAndUserId("generation-id", userId))
                .thenReturn(Optional.of(orphaned));
        when(generationImageRepository.findByIdAndUserId("image-doc-id", userId))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/generations/generation-id/image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private record SignupPayload(String name, String email, String password) {}
}
