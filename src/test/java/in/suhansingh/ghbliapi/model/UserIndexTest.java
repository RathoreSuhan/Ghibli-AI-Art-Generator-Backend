package in.suhansingh.ghbliapi.model;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the unique e-mail index physically exists in MongoDB.
 *
 * <p>This cannot be taken on trust from the annotation. Spring Data MongoDB set
 * {@code MongoMappingContext.autoIndexCreation = false} by default in 3.0, so
 * {@code @Indexed(unique = true)} creates nothing unless
 * {@code spring.data.mongodb.auto-index-creation=true} is set. There is no warning when
 * it is missing — the annotation is simply inert, duplicate e-mails insert cleanly, and
 * the bug only surfaces much later as two accounts sharing one address.
 */
@SpringBootTest
class UserIndexTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private Environment environment;

    @Test
    void autoIndexCreationIsEnabled() {
        assertThat(environment.getProperty("spring.data.mongodb.auto-index-creation", Boolean.class, false))
                .as("without this, every @Indexed in the codebase silently creates no index")
                .isTrue();
    }

    @Test
    void uniqueIndexOnEmailExistsInTheDatabase() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(User.class).getIndexInfo();

        assertThat(indexes)
                .as("indexes actually present on the users collection: %s", indexes)
                .anySatisfy(index -> {
                    assertThat(index.getIndexFields())
                            .extracting(IndexField::getKey)
                            .containsExactly("email");
                    assertThat(index.isUnique())
                            .as("index on email exists but is not unique — it would not reject duplicates")
                            .isTrue();
                });
    }

    /**
     * The index above is created from the test properties. Production reads
     * application.properties, which this build is not allowed to read — so the guard is
     * that the documented example carries the key, and the setup instructions point at it.
     */
    @Test
    void exampleConfigDocumentsTheAutoIndexCreationKey() throws IOException {
        String example = new ClassPathResource("application-example.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(example).contains("spring.data.mongodb.auto-index-creation=true");
        assertThat(example).contains("spring.data.mongodb.uri=");
    }
}
