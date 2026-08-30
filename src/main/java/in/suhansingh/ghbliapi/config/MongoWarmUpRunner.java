package in.suhansingh.ghbliapi.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Opens one Mongo connection as soon as the application is up, so the first real request does not
 * have to.
 *
 * <p>The API runs on Render's free plan, which spins the instance down after 15 minutes without
 * traffic. Waking it already costs a container start plus a JVM start; on top of that, the very
 * first query pays SRV DNS resolution, a TLS handshake and SCRAM authentication against Atlas
 * before it does any work. That last part is what this class removes — and it is precisely the
 * part a login cannot avoid, because looking up the user is the first thing {@code AuthService}
 * does.
 *
 * <p>Why this exists as a class at all, rather than as a property: an uptime pinger hitting
 * {@code /actuator/health} never touches Mongo, because
 * {@code management.health.mongo.enabled=false}. That property is deliberate and is
 * <strong>not</strong> changed here — Render fails a deploy when the health check answers
 * non-200, so with the Mongo indicator on, a paused Atlas cluster would take the whole service
 * down instead of only failing the calls that actually need a database. This runner gets the
 * connection pool warm without putting Mongo back on the deploy gate.
 *
 * <p>Two deliberate choices about failure, both following {@code recordQuietly} in
 * {@code GenerationHistoryService}: bookkeeping must never break the real work.
 * <ul>
 *   <li><strong>Off the startup thread</strong>, on a daemon thread, so a slow or unreachable
 *       Atlas delays nothing — the app is already serving by the time this runs, and a daemon
 *       thread cannot hold the JVM open during a shutdown or redeploy.</li>
 *   <li><strong>Every exception caught and logged at warn.</strong> An unreachable or slow Atlas
 *       must cost one log line and nothing else — the warm-up is an optimisation, so its own
 *       failure may never be visible in a response.</li>
 * </ul>
 *
 * <p>Which is why the dependency is an {@link ObjectProvider} rather than a plain
 * {@code MongoTemplate}. Injecting the template directly makes Spring build it while the context
 * is still refreshing, and building it does real I/O — {@code auto-index-creation=true} means the
 * mapping context creates {@code User.email}'s unique index at that moment. With an unreachable
 * database that surfaced as {@code UnsatisfiedDependencyException: … mongoWarmUpRunner}, so a
 * class whose whole job is to be ignorable had become a reason the app would not start. Resolving
 * the bean inside {@link #ping()} moves that work to the daemon thread, where the {@code catch}
 * below turns it into one warn line.
 *
 * <p>To be clear about what that does and does not buy: this class adds <em>no</em> boot-time
 * database dependency of its own. It does not make the application bootable without a database —
 * {@code @EnableMongoRepositories} already resolves {@code mongoTemplate} into every repository's
 * {@code mongoOperations} during refresh, so {@code UserRepository} has always required a
 * reachable Mongo to start. That is unchanged, and out of this class's hands.
 */
@Component
public class MongoWarmUpRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoWarmUpRunner.class);

    private final ObjectProvider<MongoTemplate> mongoTemplateProvider;

    public MongoWarmUpRunner(ObjectProvider<MongoTemplate> mongoTemplateProvider) {
        this.mongoTemplateProvider = mongoTemplateProvider;
    }

    /**
     * {@code ApplicationReadyEvent} rather than {@code @PostConstruct}: the point is to warm the
     * pool <em>after</em> the app can serve traffic, not to add a step before it can.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpConnectionPool() {
        Thread thread = new Thread(this::ping, "mongo-warm-up");
        thread.setDaemon(true); // Must not delay a SIGTERM from Render on redeploy
        thread.start();
    }

    private void ping() {
        long startedAt = System.currentTimeMillis();
        try {
            // Building the template is itself part of the cost being moved off the startup path,
            // so it happens here rather than in the constructor. See the class javadoc.
            MongoTemplate mongoTemplate = mongoTemplateProvider.getObject();

            // The cheapest command Mongo has: it does the connect, TLS and auth work and reads
            // nothing. No collection is touched, so this stays correct if the schema changes.
            mongoTemplate.executeCommand(new Document("ping", 1));
            log.info("MongoDB connection pool warm after {} ms", System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            // Warn, not error: the app is fully functional for anything that does not need Mongo,
            // and every endpoint that does will report its own failure with real context.
            log.warn("MongoDB warm-up ping failed after {} ms; the first database call will pay the "
                    + "connection cost instead. Cause: {}", System.currentTimeMillis() - startedAt, e.getMessage());
        }
    }
}
