package in.suhansingh.ghbliapi.service;

import in.suhansingh.ghbliapi.dto.AuthResponse;
import in.suhansingh.ghbliapi.dto.LoginRequest;
import in.suhansingh.ghbliapi.dto.SignupRequest;
import in.suhansingh.ghbliapi.exception.EmailAlreadyExistsException;
import in.suhansingh.ghbliapi.model.User;
import in.suhansingh.ghbliapi.repository.UserRepository;
import in.suhansingh.ghbliapi.security.JwtService;
import in.suhansingh.ghbliapi.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration and login. Both paths end at the same place: a signed token plus the handful
 * of user fields the frontend needs.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    /**
     * Registers a user and returns them already logged in, so the frontend does not have to
     * make a second call it would always make.
     *
     * <p>Uniqueness is enforced twice on purpose, and the two are not redundant. The
     * {@code existsByEmail} check produces a clear 409 for the ordinary case. The unique
     * index is the actual guarantee: between that check and the {@code save} another request
     * can insert the same address, and only the index can lose that race deterministically.
     * Catching {@code DuplicateKeyException} turns the loser into the same 409 instead of a
     * 500 — which also means the index must exist, i.e.
     * {@code spring.data.mongodb.auto-index-creation=true}, or the race silently creates two
     * accounts with one address.
     */
    public AuthResponse signup(SignupRequest request) {
        String email = User.normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("An account with email " + email + " already exists");
        }

        // The constructor's setEmail normalises again; passing the normalised value keeps the
        // check above and the insert below talking about the same string.
        User user = new User(request.getName(), email, passwordEncoder.encode(request.getPassword()));

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DuplicateKeyException ex) {
            throw new EmailAlreadyExistsException("An account with email " + email + " already exists");
        }

        return issueToken(UserPrincipal.fromUser(saved));
    }

    /**
     * Verifies the password through {@code AuthenticationManager} rather than calling
     * {@code passwordEncoder.matches} here. That is not ceremony: the provider is what applies
     * {@code hideUserNotFoundExceptions}, so an unknown address and a wrong password both come
     * back as {@code BadCredentialsException} and the endpoint cannot be used to enumerate
     * accounts. Doing the comparison by hand would need that behaviour reimplemented, and it
     * is the kind of detail that gets reimplemented slightly wrong.
     *
     * <p>The resulting {@code BadCredentialsException} is left to propagate;
     * {@code GlobalExceptionHandler} maps it to 401. Catching it here to return something
     * would only move the mapping away from where every other error lives.
     */
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        User.normalizeEmail(request.getEmail()),
                        request.getPassword()));

        // Safe: MongoUserDetailsService is the only UserDetailsService in the context, and it
        // returns nothing else.
        return issueToken((UserPrincipal) authentication.getPrincipal());
    }

    private AuthResponse issueToken(UserPrincipal principal) {
        return AuthResponse.of(
                jwtService.generateToken(principal),
                jwtService.getExpiresInSeconds(),
                principal);
    }
}
