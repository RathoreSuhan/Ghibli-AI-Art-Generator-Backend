package in.suhansingh.ghbliapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Switches on Spring Data auditing, which is what makes {@code @CreatedDate} on
 * {@code Generation#createdAt} do anything.
 *
 * <p>This is the third member of a family of Mongo settings that fail <em>silently</em> when
 * absent, alongside {@code spring.data.mongodb.auto-index-creation} (Phase 1) and
 * {@code spring-boot-starter-validation} (Phase 0). Without this annotation the field simply
 * stays null: no exception, no warning. The visible symptom would be a history list in
 * arbitrary order, because {@code findByUserIdOrderByCreatedAtDesc} would be sorting a column
 * of nulls — and it would look like a bug in the sort rather than in configuration.
 * {@code GenerationHistoryIntegrationTest} asserts a saved document comes back with a
 * timestamp, so the wiring is checked rather than assumed.
 *
 * <p>Deliberately <strong>no</strong> {@code AuditorAware} bean and no {@code @CreatedBy}.
 * {@code @CreatedBy} without an {@code AuditorAware} is the same silent-null trap one level
 * deeper, and the field it would populate here is {@code Generation#userId} — the value every
 * ownership check depends on. That one is written explicitly from the {@code SecurityContext}
 * in {@code GenerationHistoryService}, where it is visible and testable, rather than injected
 * by a callback.
 *
 * <p>Lives in its own class instead of on {@code GhbliapiApplication} so that a
 * {@code @WebMvcTest} slice, which does not scan {@code @Configuration}, does not drag Mongo
 * auditing infrastructure into a context that has no Mongo.
 */
@Configuration
@EnableMongoAuditing
public class MongoAuditingConfig {
}
