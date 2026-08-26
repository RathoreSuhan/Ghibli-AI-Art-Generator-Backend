package in.suhansingh.ghbliapi.config;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canary. A real {@code mongod} is listening on 127.0.0.1:27017 on this machine, so a
 * misconfigured test suite connects to the live database and passes green while embedded
 * MongoDB never starts at all. Nothing in the log says so.
 *
 * <p>Three ways that happens, all of which these assertions catch:
 * <ul>
 *   <li>{@code spring.autoconfigure.exclude} used to "gate" flapdoodle — it silently
 *       leaves the default localhost:27017 connection in place.</li>
 *   <li>{@code spring.data.mongodb.uri} pinned in the test properties, overriding the
 *       host and port flapdoodle publishes.</li>
 *   <li>Switching these tests to {@code @DataMongoTest}. Slice tests only load a curated
 *       auto-configuration list (see
 *       {@code AutoConfigureDataMongo.imports}), and flapdoodle's
 *       {@code EmbeddedMongoAutoConfiguration} is not on it — so embedded Mongo is
 *       skipped and the driver falls back to localhost:27017. Full {@code @SpringBootTest}
 *       honours {@code AutoConfiguration.imports} and is required here.</li>
 * </ul>
 */
@SpringBootTest
class EmbeddedMongoCanaryTest {

    /** The live server on this machine. Nothing in the test suite may touch it. */
    private static final int LOCAL_MONGOD_PORT = 27017;

    @Autowired
    private Environment environment;

    @Autowired
    private MongoClient mongoClient;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void forceAConnection() {
        // The driver is lazy: the cluster description stays UNKNOWN with no server
        // entries until something actually talks to the server.
        mongoTemplate.getDb().runCommand(new Document("ping", 1));
    }

    @Test
    void flapdoodleVersionPropertyIsSet() {
        // Without this the flapdoodle auto-configuration throws during context startup
        // and every @SpringBootTest in the project fails.
        assertThat(environment.getProperty("de.flapdoodle.mongodb.embedded.version"))
                .as("de.flapdoodle.mongodb.embedded.version must be set in src/test/resources/application.properties")
                .isNotBlank();
    }

    @Test
    void resolvedPortIsPresentAndIsNotTheLiveLocalServer() {
        assertThat(embeddedPort())
                .as("connected to the real mongod on %d — embedded Mongo is not in use", LOCAL_MONGOD_PORT)
                .isNotEqualTo(LOCAL_MONGOD_PORT);
    }

    @Test
    void theDriverIsActuallyConnectedToThatPortAndNotTo27017() {
        int published = embeddedPort();

        // Asserting on the property alone would still pass if something later overrode
        // the uri, so check the socket the driver really opened.
        Set<Integer> connectedPorts = mongoClient.getClusterDescription().getServerDescriptions().stream()
                .map(server -> server.getAddress().getPort())
                .collect(Collectors.toSet());

        assertThat(connectedPorts)
                .as("the driver must be talking to the embedded mongod on the published port")
                .containsExactly(published)
                .doesNotContain(LOCAL_MONGOD_PORT);
    }

    @Test
    void testPropertiesDoNotPinTheMongoUri() {
        String host = environment.getProperty("spring.data.mongodb.host");
        String uri = environment.getProperty("spring.data.mongodb.uri");
        int published = embeddedPort();

        assertThat(host)
                .as("flapdoodle publishes the host it bound alongside the port")
                .isIn("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

        // Flapdoodle publishes host and port, NOT a uri — MongoProperties derives the
        // connection from those two. So the correct state here is absent. A value would
        // mean the test properties pinned one, which either overrides the embedded server
        // or, if it referenced an unset ${MONGODB_URI}, breaks context startup with a
        // "connection string is invalid" that points at Mongo instead of at the property.
        if (uri != null) {
            assertThat(uri)
                    .as("a uri is set in test properties, so it must at least target the embedded server")
                    .doesNotContain("${")
                    .contains(":" + published)
                    .doesNotContain(":" + LOCAL_MONGOD_PORT);
        }
    }

    /**
     * Flapdoodle publishes the port it bound into the Environment. Its absence is the
     * single clearest signal that embedded Mongo never started — so it gets an explicit
     * message here rather than surfacing as {@code NumberFormatException: Cannot parse
     * null string} from whichever assertion happened to parse it first.
     */
    private int embeddedPort() {
        String port = environment.getProperty("spring.data.mongodb.port");

        assertThat(port)
                .as("spring.data.mongodb.port is unset: embedded Mongo never started, so the "
                        + "driver fell back to the default localhost:%d — the live server on this machine",
                        LOCAL_MONGOD_PORT)
                .isNotBlank();

        return Integer.parseInt(port);
    }
}
