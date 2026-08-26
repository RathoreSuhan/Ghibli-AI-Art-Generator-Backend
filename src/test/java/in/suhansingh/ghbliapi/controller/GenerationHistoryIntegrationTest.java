package in.suhansingh.ghbliapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.exception.GenerationFailedException;
import in.suhansingh.ghbliapi.model.Generation;
import in.suhansingh.ghbliapi.model.GenerationImage;
import in.suhansingh.ghbliapi.enums.GenerationType;
import in.suhansingh.ghbliapi.repository.GenerationImageRepository;
import in.suhansingh.ghbliapi.repository.GenerationRepository;
import in.suhansingh.ghbliapi.repository.UserRepository;
import in.suhansingh.ghbliapi.service.GhibliArtService;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 end to end: real filter chain, real JWTs minted by signing up, real MongoDB. Only
 * {@link GhibliArtService} is mocked, and only so a generation can happen without calling
 * Stability — everything on the persistence and authorization path is genuine.
 *
 * <p>The questions this class exists to answer, none of which a unit test can:
 *
 * <ul>
 *   <li>does a successful generation actually leave exactly one metadata document and one image
 *       document behind, owned by the right user;</li>
 *   <li>can user A see, download or delete anything belonging to user B;</li>
 *   <li>does the list response contain image bytes — checked against the real serialised JSON,
 *       not against the DTO's declared fields.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationHistoryIntegrationTest {

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String GENERATE = "/api/v1/generate";
    private static final String GENERATE_FROM_TEXT = "/api/v1/generate-from-text";
    private static final String GENERATIONS = "/api/v1/generations";

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String ATTACKER_EMAIL = "attacker@example.com";

    /** Any syntactically valid ObjectId that no test ever inserts. */
    private static final String ABSENT_ID = "64b8f0000000000000000000";

    private static final int PNG_WIDTH = 200;
    private static final int PNG_HEIGHT = 150;

    /** The nine fields Phase 5 codes against. Anything else in the JSON is a contract change. */
    private static final List<String> SUMMARY_FIELDS = List.of(
            "id", "type", "prompt", "style", "engineId", "width", "height", "imageSizeBytes", "createdAt");

    /**
     * A real, decodable PNG rather than the four-byte {@code {0x89,'P','N','G'}} stub the older
     * tests use. Two reasons: the dimension read in {@code GenerationHistoryService} is
     * best-effort, so the stub would store nulls and this suite would pass while proving nothing
     * about width and height; and the deliberate pixel noise makes it incompressible enough
     * (tens of KB) that comparing it against the size of a history page is a real measurement
     * rather than a coincidence of two small numbers.
     */
    private byte[] png;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GenerationRepository generationRepository;

    @Autowired
    private GenerationImageRepository generationImageRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockitoBean
    private GhibliArtService ghibliArtService;

    @BeforeEach
    void reset() throws IOException {
        // Flapdoodle keeps one mongod for the whole run and the context is shared with the other
        // @SpringBootTest classes, so leftovers would break the "exactly one document" counts.
        userRepository.deleteAll();
        generationRepository.deleteAll();
        generationImageRepository.deleteAll();

        png = noisyPng(PNG_WIDTH, PNG_HEIGHT);
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString())).thenReturn(png);
        when(ghibliArtService.createGhibliArtFromText(anyString(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(png);
        when(ghibliArtService.createGhibliArt(any(), anyString())).thenReturn(png);
    }

    // --- helpers ------------------------------------------------------------

    /** Deterministic per-pixel noise, so the encoded size is stable across runs but not tiny. */
    private static byte[] noisyPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Coordinate mixing rather than a gradient or a solid fill: PNG's filters would
                // compress either of those down to a few hundred bytes, and then comparing an
                // image against a page of JSON would prove nothing.
                int mixed = (x * 374761393) + (y * 668265263) + 0x9E3779B9;
                mixed = (mixed ^ (mixed >>> 13)) * 1274126177;
                image.setRGB(x, y, mixed & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("Test User", email, "correct-horse-battery"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(body, AuthResponse.class).token();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record SignupPayload(String name, String email, String password) {}

    /**
     * One text-to-image generation for the holder of {@code token}.
     *
     * <p>The pause at the end is not padding. {@code @CreatedDate} stamps {@code Instant.now()}
     * and BSON dates are millisecond-precision, so two generations issued back to back can land
     * on the same stored timestamp and the newest-first order becomes a coin flip. Sleeping past
     * a millisecond boundary makes every ordering assertion below deterministic.
     */
    private void generateText(String token, String prompt, String style) throws Exception {
        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"" + prompt + "\",\"style\":\"" + style + "\"}"))
                .andExpect(status().isOk());

        Thread.sleep(2);
    }

    private String listBody(String token) throws Exception {
        return mockMvc.perform(get(GENERATIONS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode listAsJson(String token) throws Exception {
        return objectMapper.readTree(listBody(token));
    }

    /** The newest generation belonging to {@code token}'s user, read back out of Mongo. */
    private Generation newestGenerationOf(String token) throws Exception {
        String id = listAsJson(token).get("content").get(0).get("id").asText();
        return generationRepository.findById(id).orElseThrow();
    }

    // --- requirement 5: save on generate ------------------------------------

    @Test
    void aSuccessfulTextGenerationPersistsExactlyOneMetadataAndOneImageDocument() throws Exception {
        String token = tokenFor(OWNER_EMAIL);

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"fantasy_art\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        assertThat(generationRepository.count()).isEqualTo(1);
        assertThat(generationImageRepository.count()).isEqualTo(1);

        Generation stored = generationRepository.findAll().get(0);
        assertThat(stored.getUserId())
                .isEqualTo(userRepository.findByEmail(OWNER_EMAIL).orElseThrow().getId());
        assertThat(stored.getType()).isEqualTo(GenerationType.TEXT_TO_IMAGE);
        // The prompt as typed, without the Ghibli suffix GhibliArtService appends.
        assertThat(stored.getPrompt()).isEqualTo("a quiet hillside");
        assertThat(stored.getStyle()).isEqualTo("fantasy_art");
        assertThat(stored.getEngineId()).isEqualTo("stable-diffusion-xl-1024-v1-0");
        assertThat(stored.getCreatedAt())
                .as("@CreatedDate is silently null without @EnableMongoAuditing")
                .isNotNull();
        // Read out of the returned PNG's header, so they describe the actual output.
        assertThat(stored.getWidth()).isEqualTo(PNG_WIDTH);
        assertThat(stored.getHeight()).isEqualTo(PNG_HEIGHT);
        assertThat(stored.getImageSizeBytes()).isEqualTo(png.length);

        GenerationImage image = generationImageRepository.findById(stored.getImageId()).orElseThrow();
        assertThat(image.getData()).isEqualTo(png);
        assertThat(image.getUserId()).isEqualTo(stored.getUserId());
        assertThat(image.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
    }

    /**
     * Photo-to-art takes no style from the caller — the service applies a fixed {@code anime}
     * preset — so history records that preset rather than a null.
     */
    @Test
    void aSuccessfulPhotoGenerationIsRecordedAsImageToImageWithTheAppliedPreset() throws Exception {
        String token = tokenFor(OWNER_EMAIL);

        mockMvc.perform(multipart(GENERATE)
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, png))
                        .param("prompt", "make it dreamy")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        assertThat(generationRepository.count()).isEqualTo(1);
        assertThat(generationImageRepository.count()).isEqualTo(1);

        Generation stored = generationRepository.findAll().get(0);
        assertThat(stored.getType()).isEqualTo(GenerationType.IMAGE_TO_IMAGE);
        assertThat(stored.getPrompt()).isEqualTo("make it dreamy");
        assertThat(stored.getStyle()).isEqualTo("anime");
    }

    @Test
    void aFailedGenerationRecordsNothing() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString()))
                .thenThrow(new GenerationFailedException("Stability said no", new RuntimeException("boom")));

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"general\"}"))
                .andExpect(status().isBadGateway());

        // The recording call sits after the generate call, so an exception never reaches it.
        assertThat(generationRepository.count()).isZero();
        assertThat(generationImageRepository.count()).isZero();
    }

    // --- requirement 3: the list carries no bytes ---------------------------

    /**
     * Asserted against the real serialised JSON rather than the record's components, because the
     * risk is a field reaching the response that nobody declared — which reading the record
     * cannot rule out.
     */
    @Test
    void theListResponseContainsNoImageBytesAndNoImageId() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");

        String body = listBody(token);
        JsonNode row = objectMapper.readTree(body).get("content").get(0);

        List<String> fields = new ArrayList<>();
        row.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("the history row contract").containsExactlyInAnyOrderElementsOf(SUMMARY_FIELDS);

        // "iVBORw0KGgo" is how a base64'd PNG always begins, so this catches the bytes arriving
        // under any field name at any depth in the document.
        assertThat(body).doesNotContain("iVBORw0KGgo");
        assertThat(body).doesNotContain("imageId");
        assertThat(body).doesNotContain("\"data\"");
        // The whole page of metadata is smaller than the single image it describes.
        assertThat(body.length())
                .as("a history page must not be image-sized")
                .isLessThan(png.length);
    }

    /**
     * The storage split is real at the BSON level: no BinData in the metadata collection, and the
     * bytes present in the other one. If a later change inlined the image, the DTO would still
     * hide it from the list response and this is what would notice.
     */
    @Test
    void theMetadataCollectionHoldsNoBinaryAndTheImageCollectionDoes() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");

        Document metadata = mongoTemplate.getCollection("generations").find().first();
        assertThat(metadata).isNotNull();
        assertThat(metadata.values())
                .as("no BinData on the metadata document: %s", metadata.keySet())
                .noneMatch(value -> value instanceof Binary || value instanceof byte[]);

        Document imageDocument = mongoTemplate.getCollection("generation_images").find().first();
        assertThat(imageDocument).isNotNull();
        assertThat(imageDocument.get("data")).isInstanceOf(Binary.class);
    }

    /**
     * Pins the {@code createdAt} wire format, because Phase 5 has to parse it and the shape is not
     * obvious from the {@code Instant} field. Boot disables
     * {@code WRITE_DATES_AS_TIMESTAMPS}, so this is an ISO-8601 string rather than an epoch
     * number — a property of the auto-configuration, not of the DTO, which is exactly why it is
     * asserted here. The trailing {@code Z} matters: it makes the value directly parseable by
     * {@code new Date(...)} in the browser with no timezone guessing.
     */
    @Test
    void createdAtIsSerialisedAsAnIsoInstantInUtc() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");

        String createdAt = objectMapper.readTree(listBody(token))
                .get("content").get(0).get("createdAt").asText();

        assertThat(createdAt)
                .as("ISO-8601 UTC, optional fractional seconds")
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        // Round-trips, so it is a real instant and not a plausibly-shaped string.
        assertThat(Instant.parse(createdAt)).isBefore(Instant.now().plusSeconds(60));
    }

    // --- requirement 6/7: the list endpoint ---------------------------------

    @Test
    void historyReturnsOnlyTheCallersOwnGenerationsNewestFirst() throws Exception {
        String ownerToken = tokenFor(OWNER_EMAIL);
        String strangerToken = tokenFor(ATTACKER_EMAIL);

        generateText(ownerToken, "mine first", "general");
        generateText(strangerToken, "not yours", "general");
        generateText(ownerToken, "mine second", "general");

        assertThat(generationRepository.count()).isEqualTo(3);

        mockMvc.perform(get(GENERATIONS).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].prompt").value("mine second"))
                .andExpect(jsonPath("$.content[1].prompt").value("mine first"));

        mockMvc.perform(get(GENERATIONS).header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].prompt").value("not yours"));
    }

    @Test
    void theEmptyHistoryOfANewUserIsAnEmptyPageNotA404() throws Exception {
        String token = tokenFor(OWNER_EMAIL);

        mockMvc.perform(get(GENERATIONS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.numberOfElements").value(0));
    }

    @Test
    void thePaginationEnvelopeReportsTheRequestedPageAndSize() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        for (int i = 0; i < 3; i++) {
            generateText(token, "prompt " + i, "general");
        }

        mockMvc.perform(get(GENERATIONS)
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                // page 1 of size 2 over [prompt 2, prompt 1, prompt 0] newest-first.
                .andExpect(jsonPath("$.content[0].prompt").value("prompt 0"));
    }

    @Test
    void anOutOfRangePageOrSizeIsA400ProblemDetail() throws Exception {
        String token = tokenFor(OWNER_EMAIL);

        mockMvc.perform(get(GENERATIONS).param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get(GENERATIONS).param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());

        // Without the @Max, one request could ask Mongo for every document the caller owns.
        mockMvc.perform(get(GENERATIONS).param("size", "5000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest());
    }

    /**
     * There is no {@code sort} parameter, so passing one changes nothing. If a {@code Pageable}
     * argument were ever bound to this handler instead, this request would merge
     * {@code createdAt ASC} into the repository's {@code OrderBy…Desc} and win on that field,
     * reversing the documented order without any code change.
     */
    @Test
    void aSortQueryParameterCannotReverseTheOrdering() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "older", "general");
        generateText(token, "newer", "general");

        mockMvc.perform(get(GENERATIONS)
                        .param("sort", "createdAt,asc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].prompt").value("newer"))
                .andExpect(jsonPath("$.content[1].prompt").value("older"));
    }

    // --- requirement 6: the image endpoint ----------------------------------

    @Test
    void theImageEndpointReturnsTheStoredPngBytes() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");
        Generation stored = newestGenerationOf(token);

        byte[] body = mockMvc.perform(get(GENERATIONS + "/" + stored.getId() + "/image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, png.length))
                // private, not public: the response is user-specific and must not be cached by a
                // shared proxy.
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(png);
    }

    @Test
    void theImageEndpointIs404ForAnIdThatDoesNotExist() throws Exception {
        String token = tokenFor(OWNER_EMAIL);

        mockMvc.perform(get(GENERATIONS + "/" + ABSENT_ID + "/image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                // The mapping declares produces=image/png; the error must still come back as
                // problem+json rather than an image content type with a JSON body.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Generation not found"));
    }

    // --- requirement 7: cross-user access -----------------------------------

    /**
     * The IDOR test that matters most: a real, existing generation id belonging to somebody else.
     * 404 rather than 403 or 200 — 403 would confirm the id is real and turn the endpoint into an
     * enumeration oracle, and 200 would be the vulnerability itself.
     */
    @Test
    void aCrossUserImageFetchIs404AndReturnsNoBytes() throws Exception {
        String ownerToken = tokenFor(OWNER_EMAIL);
        String attackerToken = tokenFor(ATTACKER_EMAIL);

        generateText(ownerToken, "private artwork", "general");
        Generation victim = newestGenerationOf(ownerToken);

        byte[] body = mockMvc.perform(get(GENERATIONS + "/" + victim.getId() + "/image")
                        .header(HttpHeaders.AUTHORIZATION, bearer(attackerToken)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isNotEqualTo(png);
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("iVBORw0KGgo");
        // Still there, untouched.
        assertThat(generationImageRepository.findById(victim.getImageId())).isPresent();
    }

    @Test
    void aCrossUserDeleteIs404AndDeletesNothing() throws Exception {
        String ownerToken = tokenFor(OWNER_EMAIL);
        String attackerToken = tokenFor(ATTACKER_EMAIL);

        generateText(ownerToken, "private artwork", "general");
        Generation victim = newestGenerationOf(ownerToken);

        mockMvc.perform(delete(GENERATIONS + "/" + victim.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(attackerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Generation not found"));

        assertThat(generationRepository.findById(victim.getId())).isPresent();
        assertThat(generationImageRepository.findById(victim.getImageId())).isPresent();
        // And the owner still sees it.
        assertThat(listAsJson(ownerToken).get("totalElements").asLong()).isEqualTo(1);
    }

    /**
     * A user id supplied by the client must be inert. The owner comes from the SecurityContext
     * only, so these are ignored rather than honoured — a handler that bound either would let the
     * attacker's token read the owner's history.
     */
    @Test
    void aUserIdSuppliedByTheClientIsIgnored() throws Exception {
        String ownerToken = tokenFor(OWNER_EMAIL);
        String attackerToken = tokenFor(ATTACKER_EMAIL);
        generateText(ownerToken, "private artwork", "general");

        String ownerId = userRepository.findByEmail(OWNER_EMAIL).orElseThrow().getId();

        mockMvc.perform(get(GENERATIONS)
                        .param("userId", ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(attackerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get(GENERATIONS)
                        .header("X-User-Id", ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(attackerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- requirement 6: delete ----------------------------------------------

    @Test
    void deleteRemovesBothTheMetadataAndTheImageDocument() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");
        Generation stored = newestGenerationOf(token);
        String imageId = stored.getImageId();

        assertThat(generationRepository.count()).isEqualTo(1);
        assertThat(generationImageRepository.count()).isEqualTo(1);

        mockMvc.perform(delete(GENERATIONS + "/" + stored.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(generationRepository.findById(stored.getId())).isEmpty();
        assertThat(generationImageRepository.findById(imageId)).isEmpty();
        assertThat(generationRepository.count()).isZero();
        assertThat(generationImageRepository.count()).isZero();

        // Gone from the caller's history, not merely unreachable.
        assertThat(listAsJson(token).get("totalElements").asLong()).isZero();
    }

    @Test
    void deletingTheSameGenerationTwiceIs204ThenA404() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "a quiet hillside", "general");
        String id = newestGenerationOf(token).getId();

        mockMvc.perform(delete(GENERATIONS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete(GENERATIONS + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingOneGenerationLeavesTheOthersAlone() throws Exception {
        String token = tokenFor(OWNER_EMAIL);
        generateText(token, "keep me", "general");
        generateText(token, "delete me", "general");

        // content[0] is the newest, i.e. "delete me".
        String doomed = listAsJson(token).get("content").get(0).get("id").asText();

        mockMvc.perform(delete(GENERATIONS + "/" + doomed)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(GENERATIONS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].prompt").value("keep me"));

        assertThat(generationImageRepository.count()).isEqualTo(1);
    }

    // --- requirement 6: all three are authenticated -------------------------

    @Test
    void allThreeEndpointsAre401WithoutAToken() throws Exception {
        mockMvc.perform(get(GENERATIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        mockMvc.perform(get(GENERATIONS + "/" + ABSENT_ID + "/image"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete(GENERATIONS + "/" + ABSENT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbageTokenIs401OnTheHistoryEndpointsToo() throws Exception {
        mockMvc.perform(get(GENERATIONS).header(HttpHeaders.AUTHORIZATION, bearer("this.is.nonsense")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The browser-side half of the delete endpoint. Without {@code DELETE} in the CORS allowed
     * methods the preflight fails and the frontend's delete button breaks with a clean server
     * log — nothing on the server ever sees the request.
     */
    @Test
    void corsPreflightAllowsDeleteOnAGeneration() throws Exception {
        mockMvc.perform(options(GENERATIONS + "/" + ABSENT_ID)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("DELETE")));
    }
}
