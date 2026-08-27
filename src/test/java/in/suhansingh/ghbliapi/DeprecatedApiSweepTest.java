package in.suhansingh.ghbliapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regression guard for the deprecated and removed APIs this codebase was migrated off.
 *
 * <p>Every entry below is a form that <em>compiles</em>. Some produce a deprecation warning that
 * an ordinary build prints and nobody reads; others — the Spring Security no-argument DSL — were
 * removed outright in 6.1, so the risk is a copy-paste from a tutorial written against an older
 * version failing at a point far from the paste. Either way the compiler will not stop it, so the
 * only durable protection is a test.
 *
 * <p><strong>Comments are stripped before scanning.</strong> Several classes here document the
 * deprecated form in prose precisely to explain what was avoided and why — {@code
 * StabilityHttpClientConfig} names {@code setConnectTimeout}, {@code JwtServiceTest} names the
 * root-package {@code SignatureException}. Scanning raw text would make writing that documentation
 * break the build, which is the wrong incentive. Stripping is deliberately simple and can truncate
 * a line at a {@code //} inside a string literal, so it may miss a violation sitting after a URL
 * on the same line; it can never invent one.
 */
class DeprecatedApiSweepTest {

    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("src/test/java"));

    /** Excluded from its own sweep — see {@link #sourceFiles()}. */
    private static final Path OWN_FILE_NAME = Path.of("DeprecatedApiSweepTest.java");

    /**
     * Forbidden token to the reason it is forbidden. Order is only for readable output.
     *
     * <p>Each key is chosen so that the modern replacement does not contain it as a substring:
     * {@code @MockitoBean} does not contain {@code @MockBean}, and
     * {@code io.jsonwebtoken.security.SignatureException} does not contain
     * {@code io.jsonwebtoken.SignatureException}.
     */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();

    static {
        // --- Spring Boot test annotations (deprecated in 3.4, replaced by @MockitoBean) ---
        FORBIDDEN.put("@MockBean", "deprecated since Boot 3.4 — use @MockitoBean");
        FORBIDDEN.put("@SpyBean", "deprecated since Boot 3.4 — use @MockitoSpyBean");

        // --- jjwt 0.11.x API, replaced in 0.12/0.13 ---
        FORBIDDEN.put(".setSigningKey(", "jjwt 0.11 parser API — use Jwts.parser().verifyWith(key)");
        FORBIDDEN.put(".parseClaimsJws(", "jjwt 0.11 parser API — use parseSignedClaims()");
        FORBIDDEN.put("SignatureAlgorithm.", "jjwt 0.11 — the algorithm is inferred from the key");
        FORBIDDEN.put(".setSubject(", "jjwt 0.11 builder setter — use subject()");
        FORBIDDEN.put(".setIssuedAt(", "jjwt 0.11 builder setter — use issuedAt()");
        FORBIDDEN.put(".setExpiration(", "jjwt 0.11 builder setter — use expiration()");
        FORBIDDEN.put(".setClaims(", "jjwt 0.11 builder setter — use claims()");
        FORBIDDEN.put("io.jsonwebtoken.SignatureException",
                "moved to io.jsonwebtoken.security.SignatureException; the root-package type is a "
                        + "different, deprecated class and catching it silently stops catching "
                        + "tampered signatures");

        // --- Spring Security: the no-argument DSL, REMOVED (not merely deprecated) in 6.1 ---
        FORBIDDEN.put(".and()", "removed Spring Security DSL chaining — use the lambda form");
        FORBIDDEN.put(".csrf()", "removed no-arg DSL — use csrf(AbstractHttpConfigurer::disable)");
        FORBIDDEN.put(".cors()", "removed no-arg DSL — use cors(Customizer.withDefaults())");
        FORBIDDEN.put(".httpBasic()", "removed no-arg DSL — use httpBasic(...) with a Customizer");
        FORBIDDEN.put(".formLogin()", "removed no-arg DSL — use formLogin(...) with a Customizer");
        FORBIDDEN.put(".sessionManagement()", "removed no-arg DSL — use sessionManagement(...)");
        FORBIDDEN.put(".authorizeHttpRequests()", "removed no-arg DSL — use authorizeHttpRequests(...)");
        FORBIDDEN.put(".exceptionHandling()", "removed no-arg DSL — use exceptionHandling(...)");
        FORBIDDEN.put(".authorizeRequests(", "removed in 6.0 — use authorizeHttpRequests(...)");
        FORBIDDEN.put("WebSecurityConfigurerAdapter", "removed in Spring Security 6 — publish a "
                + "SecurityFilterChain bean instead");

        // --- Spring Boot 3.4 RestTemplateBuilder ---
        FORBIDDEN.put(".setConnectTimeout(", "deprecated RestTemplateBuilder setter — use connectTimeout()");
        FORBIDDEN.put(".setReadTimeout(", "deprecated RestTemplateBuilder setter — use readTimeout()");
    }

    /**
     * Removes {@code /* ... *&#47;} blocks and {@code //} line remainders, so what is scanned is
     * only code. See the class javadoc for the known limitation.
     */
    static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean inBlock = false;

        for (String line : source.split("\\R", -1)) {
            int index = 0;
            while (index < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", index);
                    if (end < 0) {
                        index = line.length();
                    } else {
                        inBlock = false;
                        index = end + 2;
                    }
                    continue;
                }

                int block = line.indexOf("/*", index);
                int lineComment = line.indexOf("//", index);

                if (lineComment >= 0 && (block < 0 || lineComment < block)) {
                    code.append(line, index, lineComment);
                    index = line.length();
                } else if (block >= 0) {
                    code.append(line, index, block);
                    inBlock = true;
                    index = block + 2;
                } else {
                    code.append(line, index, line.length());
                    index = line.length();
                }
            }
            code.append('\n');
        }

        return code.toString();
    }

    private static List<Path> sourceFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            assertThat(root)
                    .as("expected %s relative to the module directory (%s)",
                            root, Path.of("").toAbsolutePath())
                    .exists();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        // This file necessarily spells out every forbidden token, in the map above
                        // and in the stripper's own fixture. Scanning it would report itself.
                        .filter(path -> !path.endsWith(OWN_FILE_NAME))
                        .forEach(files::add);
            }
        }
        return files;
    }

    /**
     * The sweep. Collects every violation before failing, so one run shows the whole list rather
     * than making the migration a game of whack-a-mole.
     */
    @Test
    void noSourceFileUsesADeprecatedOrRemovedApi() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String[] lines = stripComments(Files.readString(file, StandardCharsets.UTF_8)).split("\\R", -1);

            for (int i = 0; i < lines.length; i++) {
                for (Map.Entry<String, String> forbidden : FORBIDDEN.entrySet()) {
                    if (lines[i].contains(forbidden.getKey())) {
                        violations.add("%s:%d uses %s (%s)"
                                .formatted(file, i + 1, forbidden.getKey(), forbidden.getValue()));
                    }
                }
            }
        }

        assertThat(violations)
                .as("deprecated or removed APIs found in source (comments excluded)")
                .isEmpty();
    }

    /**
     * Guards against the sweep passing because it read nothing. A test that walks the filesystem
     * is only as good as its assumption about the working directory, and a silently empty file
     * list would make the assertion above green forever.
     */
    @Test
    void theSweepActuallyReadsTheSourceTree() throws IOException {
        List<Path> files = sourceFiles();

        assertThat(files)
                .as("far fewer source files than this module has — the walk is probably rooted wrong")
                .hasSizeGreaterThan(30);

        assertThat(files)
                .anySatisfy(path -> assertThat(path.toString()).endsWith("JwtService.java"))
                .anySatisfy(path -> assertThat(path.toString()).endsWith("SecurityConfig.java"));
    }

    /**
     * Pins the stripper itself, since the sweep's usefulness rests on it. If stripping ever became
     * over-eager the sweep would go quietly blind, which is the failure mode worth catching.
     */
    @Test
    void commentStrippingRemovesCommentsAndKeepsCode() {
        String source = """
                // @MockBean in a line comment
                int keep = 1;
                /* @SpyBean in a
                   multi-line block */
                int alsoKeep = 2; // .and() trailing
                int inline = /* .csrf() */ 3;
                """;

        String stripped = stripComments(source);

        assertThat(stripped)
                .doesNotContain("@MockBean")
                .doesNotContain("@SpyBean")
                .doesNotContain(".and()")
                .doesNotContain(".csrf()");
        assertThat(stripped)
                .contains("int keep = 1;")
                .contains("int alsoKeep = 2;")
                .contains("int inline =")
                .contains("3;");
    }
}
