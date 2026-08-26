package in.suhansingh.ghbliapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.suhansingh.ghbliapi.controller.GenerationController;
import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.model.User;
import in.suhansingh.ghbliapi.repository.UserRepository;
import in.suhansingh.ghbliapi.service.GhibliArtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end over the real filter chain, the real {@code DaoAuthenticationProvider}, real
 * BCrypt and the real unique index on embedded MongoDB. The slice tests prove the HTTP
 * contract; this proves the pieces are actually wired to each other.
 *
 * <p>Only {@link GhibliArtService} is mocked, and only so that a protected endpoint can be
 * called without reaching Stability AI. Everything on the security path is genuine.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String GENERATE = "/api/v1/generate";
    private static final String GENERATE_FROM_TEXT = "/api/v1/generate-from-text";

    private static final String EMAIL = "suhan@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    /**
     * Read straight from the test properties so the expired-token test can mint one with the
     * <em>correct</em> signature — otherwise it would prove only that a foreign key is
     * rejected, which is a different test.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    @MockitoBean
    private GhibliArtService ghibliArtService;

    @BeforeEach
    void clearUsers() {
        // Flapdoodle keeps one mongod for the whole run, so state leaks between test classes
        // unless each one starts from empty. Without this, the duplicate-email tests pass or
        // fail depending on execution order.
        userRepository.deleteAll();
    }

    // --- helpers ------------------------------------------------------------

    private AuthResponse signup(String name, String email, String password) throws Exception {
        String body = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload(name, email, password))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(body, AuthResponse.class);
    }

    private AuthResponse signupDefaultUser() throws Exception {
        return signup("Suhan Singh", EMAIL, PASSWORD);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Serialised by Jackson rather than hand-written JSON so a DTO rename breaks compilation. */
    private record SignupPayload(String name, String email, String password) {}

    private record LoginPayload(String email, String password) {}

    // --- signup -------------------------------------------------------------

    @Test
    void signupPersistsTheUserAndReturnsATokenForTheirMongoId() throws Exception {
        AuthResponse response = signupDefaultUser();

        User stored = userRepository.findByEmail(EMAIL).orElseThrow();

        assertThat(response.userId()).isEqualTo(stored.getId());
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.name()).isEqualTo("Suhan Singh");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.roles()).containsExactly(User.ROLE_USER);
        // Seconds, from the 3600000 ms in the test properties.
        assertThat(response.expiresIn()).isEqualTo(3600L);

        // The claim Phase 3 will scope history queries by.
        assertThat(jwtService.parseClaims(response.token()).getSubject()).isEqualTo(stored.getId());
    }

    /**
     * A trailing space in the e-mail field must not be a validation error. {@code @Email} does
     * not trim, so before {@code SignupRequest#setEmail} normalised the value this returned a
     * 400 telling the caller their address was malformed because of whitespace they could not
     * see.
     */
    @Test
    void aPaddedEmailIsAcceptedAndStoredTrimmed() throws Exception {
        AuthResponse response = signup("Suhan Singh", "  Suhan@Example.COM  ", PASSWORD);

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(userRepository.findByEmail(EMAIL)).isPresent();
    }

    @Test
    void signupStoresABcryptHashRatherThanThePassword() throws Exception {
        signupDefaultUser();

        String stored = userRepository.findByEmail(EMAIL).orElseThrow().getPassword();

        assertThat(stored).isNotEqualTo(PASSWORD);
        // $2a$ is BCryptPasswordEncoder's own prefix. A DelegatingPasswordEncoder would have
        // written {bcrypt}$2a$..., and login would then fail against this provider.
        assertThat(stored).startsWith("$2a$");
    }

    @Test
    void theSignupResponseBodyContainsNoTraceOfThePassword() throws Exception {
        String body = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("Suhan Singh", EMAIL, PASSWORD))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("$2a$");
    }

    @Test
    void signingUpTwiceWithTheSameEmailIs409() throws Exception {
        signupDefaultUser();

        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("Someone Else", EMAIL, "another-password"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Email already registered"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    /**
     * The case that would slip through if either normalisation or the index were missing:
     * Mongo string equality is case-sensitive, so {@code SUHAN@Example.COM} would otherwise
     * insert a second account for the same person and both could log in.
     *
     * <p>The padding is not incidental. This assertion originally failed with a 400, not a
     * 409, because {@code @Email} does not trim — a copy-pasted address with a trailing space
     * was reported as malformed. Normalising in {@code SignupRequest#setEmail} (before
     * validation, since Jackson calls the setter first) is what fixed it.
     */
    @Test
    void aDifferentlyCasedEmailIsTheSameAccount() throws Exception {
        signupDefaultUser();

        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupPayload("Suhan Again", "  SUHAN@Example.COM  ", PASSWORD))))
                .andExpect(status().isConflict());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    // --- login --------------------------------------------------------------

    @Test
    void loginWithTheRightPasswordReturnsAUsableToken() throws Exception {
        signupDefaultUser();

        String body = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthResponse response = objectMapper.readValue(body, AuthResponse.class);

        // "Usable" means it actually opens a protected endpoint, not merely that it parses.
        when(ghibliArtService.createGhibliArtFromText(anyString(), anyString())).thenReturn(FAKE_PNG);

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(response.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\",\"style\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void loginIsCaseInsensitiveOnTheEmail() throws Exception {
        signupDefaultUser();

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("  SUHAN@Example.COM  ", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithTheWrongPasswordIs401() throws Exception {
        signupDefaultUser();

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(EMAIL, "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Incorrect email or password."));
    }

    /**
     * The anti-enumeration property: an unknown address and a wrong password must be
     * indistinguishable, or the login endpoint becomes a way to test whether someone has an
     * account here. This holds because {@code DaoAuthenticationProvider} hides
     * {@code UsernameNotFoundException} behind {@code BadCredentialsException} by default.
     */
    @Test
    void anUnknownEmailIsIndistinguishableFromAWrongPassword() throws Exception {
        signupDefaultUser();

        String wrongPassword = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(EMAIL, "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload("nobody@example.com", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    // --- protecting /api/v1/generate ----------------------------------------

    @Test
    void generateWithoutATokenIs401NotA403() throws Exception {
        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\"}"))
                // 403 is Spring Security's default without an AuthenticationEntryPoint, and it
                // would tell the frontend "never allowed" instead of "log in".
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.instance").value(GENERATE_FROM_TEXT));
    }

    @Test
    void anExpiredTokenIs401AndNotA500() throws Exception {
        signupDefaultUser();
        UserPrincipal principal = UserPrincipal.fromUser(userRepository.findByEmail(EMAIL).orElseThrow());

        // Correctly signed with the real key, but already past its expiry — so this isolates
        // expiry from signature. A 500 here would mean the filter let the exception escape to
        // the container instead of deferring to the entry point.
        String expired = new JwtService(jwtSecret, -60_000L).generateToken(principal);

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(expired))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void aTokenWithARewrittenSubjectIs401() throws Exception {
        AuthResponse response = signupDefaultUser();
        String[] parts = response.token().split("\\.");

        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace(response.userId(), "000000000000000000000000");
        assertThat(forgedPayload).isNotEqualTo(payload);

        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(forged))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageInTheAuthorizationHeaderIs401NotA500() throws Exception {
        mockMvc.perform(post(GENERATE_FROM_TEXT)
                        .header(HttpHeaders.AUTHORIZATION, bearer("this.is.nonsense"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a quiet hillside\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Requirement 11: the pre-existing multipart endpoint still works with Security enabled.
     * The risk being checked is that a security filter reads the request body first — a
     * consumed input stream leaves the multipart resolver with nothing and the file arrives
     * empty or not at all. {@code JwtAuthenticationFilter} only ever touches a header.
     */
    @Test
    void multipartGenerateStillWorksWhenAuthenticated() throws Exception {
        AuthResponse response = signupDefaultUser();
        when(ghibliArtService.createGhibliArt(any(), anyString())).thenReturn(FAKE_PNG);

        byte[] body = mockMvc.perform(multipart(GENERATE)
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG))
                        .param("prompt", "make it dreamy")
                        .header(HttpHeaders.AUTHORIZATION, bearer(response.token())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(FAKE_PNG);

        // The uploaded bytes survived the filter chain rather than arriving empty.
        org.mockito.ArgumentCaptor<org.springframework.web.multipart.MultipartFile> file =
                org.mockito.ArgumentCaptor.forClass(org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.verify(ghibliArtService).createGhibliArt(file.capture(), anyString());
        assertThat(file.getValue().getBytes()).isEqualTo(FAKE_PNG);
    }

    @Test
    void multipartGenerateIs401WithoutAToken() throws Exception {
        mockMvc.perform(multipart(GENERATE)
                        .file(new MockMultipartFile("image", "cat.png", MediaType.IMAGE_PNG_VALUE, FAKE_PNG))
                        .param("prompt", "make it dreamy"))
                .andExpect(status().isUnauthorized());
    }

    // --- CORS ---------------------------------------------------------------

    /**
     * Passes without any {@code OPTIONS permitAll} rule because {@code CorsFilter} sits ahead
     * of {@code AuthorizationFilter} and answers a preflight without continuing the chain. If
     * that ordering ever changed, this test would start returning 401.
     */
    @Test
    void corsPreflightOnAProtectedEndpointSucceedsAndAllowsTheAuthorizationHeader() throws Exception {
        mockMvc.perform(options(GENERATE)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                // Authorization is not a CORS-safelisted request header: without it echoed
                // here the browser blocks every authenticated call before it is sent.
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("Authorization")));
    }

    @Test
    void corsPreflightAlsoWorksForTheLoopbackIpAndTheAuthEndpoints() throws Exception {
        mockMvc.perform(options(LOGIN)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:3000"));
    }

    @Test
    void aPreflightFromAnUnlistedOriginIsRejected() throws Exception {
        mockMvc.perform(options(GENERATE)
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /**
     * Port 3000 is Create React App's. 5173 is Vite's, and allowing it would be configuration
     * carried over from a build system this project does not use.
     */
    @Test
    void vitesPortIsNotAllowed() throws Exception {
        mockMvc.perform(options(GENERATE)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    /**
     * Guards the second half of requirement 10 — the annotation had to be <em>deleted</em>, not
     * just superseded. Two live CORS declarations is the failure mode where someone updates the
     * allowed origins in one place and the other silently keeps winning for its own paths.
     */
    @Test
    void theCrossOriginAnnotationIsGoneFromGenerationController() {
        assertThat(GenerationController.class.getAnnotation(CrossOrigin.class))
                .as("CORS must come only from the corsConfigurationSource bean")
                .isNull();

        for (Method method : GenerationController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(CrossOrigin.class))
                    .as("@CrossOrigin on %s", method.getName())
                    .isNull();
        }
    }
}
