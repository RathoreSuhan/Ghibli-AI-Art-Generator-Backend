package in.suhansingh.ghbliapi.controller;

import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.dto.LoginRequest;
import in.suhansingh.ghbliapi.dto.SignupRequest;
import in.suhansingh.ghbliapi.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public authentication endpoints. Both are {@code permitAll} in {@code SecurityConfig} —
 * they have to be, since their entire job is to hand out the credential the rest of the API
 * requires.
 *
 * <p>No {@code @CrossOrigin} here. CORS is configured centrally by the
 * {@code corsConfigurationSource} bean, which covers every path.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * @return 201 with a usable token. Created is the honest status — a document now exists
     *         that did not before. No {@code Location} header: there is no endpoint that
     *         serves a user resource, and pointing at one that 404s is worse than omitting it.
     */
    @PostMapping(value = "/signup",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    /** @return 200 with a token. Nothing is created, so not 201. */
    @PostMapping(value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
