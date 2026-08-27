package in.suhansingh.ghbliapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code application-example.properties} honest about what a clean clone needs.
 *
 * <p>The example file is the only committed answer to "what do I have to set to run this?", and
 * it is the kind of file that rots invisibly: adding a key to the live properties costs nothing
 * and breaks nobody's build, so the omission surfaces later as somebody else's failed startup.
 * This test makes that a build failure instead. It already caught one real drift — the multipart
 * limits had been raised to 20MB in the live file while the example still said 5MB/10MB.
 *
 * <p><strong>Why the files are read from disk rather than the classpath.</strong>
 * {@code src/test/resources/application.properties} shadows the main one during tests — Spring
 * resolves {@code classpath:/application.properties} to the first match, and
 * {@code target/test-classes} precedes {@code target/classes}. So a
 * {@code ClassPathResource("application.properties")} here would read the test copy and prove
 * nothing about production config. Reading {@code src/main/resources} directly is the only way to
 * see the real file. Paths are relative to the module directory, which is Surefire's working
 * directory.
 */
class ConfigurationCompletenessTest {

    private static final Path MAIN_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final Path EXAMPLE_PROPERTIES = Path.of("src/main/resources/application-example.properties");
    private static final Path TEST_PROPERTIES = Path.of("src/test/resources/application.properties");

    /** {@code ${VAR}} or {@code ${VAR:default}} — captures the variable name only. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)");

    private static String read(Path path) throws IOException {
        assertThat(path)
                .as("expected to find %s relative to the module directory (%s)",
                        path, Path.of("").toAbsolutePath())
                .exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Property keys only: skips blank lines and comments, and takes the text before the first '='. */
    private static Set<String> keysOf(String properties) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : properties.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                keys.add(trimmed.substring(0, separator).trim());
            }
        }
        return keys;
    }

    private static Set<String> placeholdersIn(String properties) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(properties);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * The main point: nothing the application reads at startup may be undocumented.
     *
     * <p>Compares keys, not whole lines, because the values legitimately differ — the live file
     * resolves secrets from the environment while the example carries placeholder text.
     */
    @Test
    void exampleConfigDocumentsEveryKeyTheLiveConfigSets() throws IOException {
        Set<String> live = keysOf(read(MAIN_PROPERTIES));
        Set<String> documented = keysOf(read(EXAMPLE_PROPERTIES));

        assertThat(live).as("application.properties parsed as having no keys at all").isNotEmpty();
        assertThat(documented)
                .as("keys present in application.properties but missing from "
                        + "application-example.properties — a clean clone would not know to set them")
                .containsAll(live);
    }

    /**
     * The reverse direction, which catches a subtler rot: a key removed from the live file but
     * left in the example sends the next person to configure something that is no longer read.
     */
    @Test
    void exampleConfigDocumentsNoKeyTheLiveConfigNoLongerReads() throws IOException {
        Set<String> live = keysOf(read(MAIN_PROPERTIES));
        Set<String> documented = keysOf(read(EXAMPLE_PROPERTIES));

        assertThat(live)
                .as("keys documented in application-example.properties that application.properties "
                        + "no longer sets — either stale, or the live file lost something")
                .containsAll(documented);
    }

    /**
     * Every environment variable the live file dereferences must be named in the example, so the
     * list of things to export is discoverable without reading the live file.
     */
    @Test
    void exampleConfigNamesEveryEnvironmentVariableTheLiveConfigResolves() throws IOException {
        String example = read(EXAMPLE_PROPERTIES);
        Set<String> variables = placeholdersIn(read(MAIN_PROPERTIES));

        assertThat(variables)
                .as("application.properties resolves no ${...} at all — secrets may have been inlined")
                .isNotEmpty();

        assertThat(variables).allSatisfy(variable ->
                assertThat(example)
                        .as("application.properties resolves ${%s} but the example file never "
                                + "mentions it, so a clean clone has no way to learn it exists", variable)
                        .contains(variable));
    }

    /**
     * The test classpath copy shadows rather than merges, so its required keys are invisible to
     * anyone reading only the main config. The example file carries them in a "tests only" footer;
     * this asserts the footer keeps up with what the test config actually needs.
     */
    @Test
    void exampleConfigDocumentsTheEmbeddedMongoVersionUsedByTests() throws IOException {
        String example = read(EXAMPLE_PROPERTIES);
        Set<String> testKeys = keysOf(read(TEST_PROPERTIES));

        assertThat(testKeys)
                .as("the flapdoodle version key is what makes @SpringBootTest start at all")
                .contains("de.flapdoodle.mongodb.embedded.version");

        assertThat(example)
                .as("application-example.properties must mention the embedded-Mongo version key, "
                        + "including that the test classpath copy shadows the main file")
                .contains("de.flapdoodle.mongodb.embedded.version");
    }

    /**
     * Both committed files are in git, so neither may carry a real credential. Checks the shapes
     * that a leak actually takes rather than trying to be a general secret scanner: a Stability
     * key literal, an Atlas SRV string with an inlined password, and a JWT secret that is a
     * literal rather than a placeholder.
     *
     * <p>Only <em>values</em> are examined, never comments. Both files describe where to obtain an
     * {@code mongodb+srv://} URI in prose, and an earlier version of this test failed on its own
     * documentation — the leak being guarded against is a secret the application would read, not
     * a word in a sentence.
     */
    @Test
    void neitherCommittedConfigFileContainsALiteralCredential() throws IOException {
        for (Path path : List.of(MAIN_PROPERTIES, EXAMPLE_PROPERTIES)) {
            for (String line : read(path).split("\\R")) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")
                        || separator <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();

                switch (key) {
                    case "stability.api.key" -> assertThat(value)
                            .as("%s sets a literal Stability API key; it must resolve from "
                                    + "${STABILITY_API_KEY}", path)
                            .doesNotStartWith("sk-")
                            .contains("${STABILITY_API_KEY");

                    case "spring.data.mongodb.uri" -> {
                        assertThat(value)
                                .as("%s inlines an Atlas connection string, which carries a "
                                        + "username and password", path)
                                .doesNotContain("mongodb+srv://");
                        assertThat(value)
                                .as("%s must resolve the URI from ${MONGODB_URI}", path)
                                .contains("${MONGODB_URI");
                    }

                    case "jwt.secret" -> assertThat(value)
                            .as("%s sets jwt.secret to a literal; it must resolve from "
                                    + "${JWT_SECRET} so no signing key is committed", path)
                            .contains("${JWT_SECRET");

                    default -> { /* no credential-shaped key */ }
                }
            }
        }
    }
}
