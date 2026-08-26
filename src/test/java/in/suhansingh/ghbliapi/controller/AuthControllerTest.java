package in.suhansingh.ghbliapi.controller;

import in.suhansingh.ghbliapi.config.SecurityConfig;
import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.exception.EmailAlreadyExistsException;
import in.suhansingh.ghbliapi.model.User;
import in.suhansingh.ghbliapi.security.JwtService;
import in.suhansingh.ghbliapi.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the two auth endpoints: status codes, validation, and error shape,
 * with {@link AuthService} mocked out. What actually happens in Mongo is
 * {@code AuthenticationIntegrationTest}'s job.
 *
 * <p>{@code @Import(SecurityConfig.class)} is what makes this test meaningful rather than
 * merely green. Without it {@code @WebMvcTest} silently substitutes Boot's default filter
 * chain, which secures everything — so "signup is publicly reachable" would be proved
 * against a chain that is not the application's. The {@code @MockitoBean JwtService} is
 * needed only to satisfy {@code SecurityConfig}; no request here carries a token.
 *
 * <p>Note the absence of {@code @WithMockUser}: these endpoints must work for a caller with
 * no credentials at all, which is the point of the {@code permitAll} rule.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String LOGIN = "/api/v1/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtService jwtService;

    private static AuthResponse sampleResponse() {
        return new AuthResponse(
                "header.payload.signature", AuthResponse.BEARER, 3600L,
                "64b7f0c2e1a2b3c4d5e6f7a8", "Suhan Singh", "suhan@example.com", Set.of(User.ROLE_USER));
    }

    // --- happy paths --------------------------------------------------------

    @Test
    void signupReturns201WithATokenAndNoPasswordAnywhere() throws Exception {
        when(authService.signup(any())).thenReturn(sampleResponse());

        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Suhan Singh","email":"suhan@example.com","password":"correct-horse"}"""))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value("header.payload.signature"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.userId").value("64b7f0c2e1a2b3c4d5e6f7a8"))
                .andExpect(jsonPath("$.email").value("suhan@example.com"))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"))
                // AuthResponse is a record with no password component, so this cannot regress
                // by accident — but it is the assertion someone will look for.
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /** 200, not 201: logging in creates nothing. */
    @Test
    void loginReturns200WithAToken() throws Exception {
        when(authService.login(any())).thenReturn(sampleResponse());

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"suhan@example.com","password":"correct-horse"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("header.payload.signature"));
    }

    // --- conflict -----------------------------------------------------------

    @Test
    void duplicateEmailIs409NotA500() throws Exception {
        when(authService.signup(any()))
                .thenThrow(new EmailAlreadyExistsException("An account with email suhan@example.com already exists"));

        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Suhan Singh","email":"suhan@example.com","password":"correct-horse"}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Email already registered"))
                .andExpect(jsonPath("$.instance").value(SIGNUP));
    }

    /**
     * The signup race: the pre-check passed, then the unique index rejected the insert. Without
     * an explicit handler the catch-all would make this a 500, so a caller who simply picked a
     * taken address would be told the server broke.
     */
    @Test
    void aLostSignupRaceLooksIdenticalToAnOrdinaryDuplicate() throws Exception {
        when(authService.signup(any())).thenThrow(new DuplicateKeyException("E11000 duplicate key error"));

        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Suhan Singh","email":"suhan@example.com","password":"correct-horse"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already registered"))
                // Mongo's own message quotes the offending document; it must not be echoed.
                .andExpect(jsonPath("$.detail").value("An account with that email already exists"));
    }

    // --- bad credentials ----------------------------------------------------

    @Test
    void wrongPasswordIs401NotA500AndSaysNothingSpecific() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"suhan@example.com","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Invalid credentials"))
                .andExpect(jsonPath("$.detail").value("Incorrect email or password."));
    }

    // --- validation ---------------------------------------------------------

    @Test
    void malformedEmailIsRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Suhan Singh","email":"not-an-email","password":"correct-horse"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.email").value("must be a well-formed email address"));
    }

    @Test
    void shortPasswordIsRejected() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Suhan Singh","email":"suhan@example.com","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("must be between 8 and 72 characters"));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","email":"suhan@example.com","password":"correct-horse"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("must not be blank"));
    }

    /**
     * Login validation is deliberately thinner than signup's — only {@code @NotBlank}. A short
     * password here is a 401 from the provider, not a 400, because applying the signup rules
     * would both leak the password policy and lock out any account created before it changed.
     */
    @Test
    void loginAcceptsAShortPasswordAtTheDtoLayerAndLetsAuthenticationDecide() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"suhan@example.com","password":"x"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankLoginPasswordIsA400() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"suhan@example.com","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("must not be blank"));
    }
}
