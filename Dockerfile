# ---------------------------------------------------------------------------
# Container image for the ghbliapi backend, built by Render.
#
# Docker is not a preference here, it is the only option: Render's native runtimes are
# Node, Python, Ruby, Go, Rust and Elixir — there is no Java runtime, so a Spring Boot
# service has to ship as an image.
#
# Build context is the ghbliapi/ directory (see dockerContext in ../render.yaml), so every
# path below is relative to that, not to the repository root.
# ---------------------------------------------------------------------------


# ---- Stage 1: build the executable jar ------------------------------------------------
# The Maven image rather than ./mvnw: the wrapper jar is git-ignored in this repo
# (.mvn/wrapper/maven-wrapper.jar), so a fresh clone has no wrapper to run.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# pom.xml on its own layer, ahead of the sources. Docker reuses this layer — and the ~200MB
# of downloaded dependencies with it — on every push that changed only Java files, which is
# almost all of them.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

COPY src ./src

# Tests are skipped in the image build on purpose. The suite starts a real mongod through
# flapdoodle, which downloads a ~100MB binary into a writable cache on first run: slow and
# network-dependent inside a deploy, for a signal that belongs in CI. Run them where they
# make sense instead:  ./mvnw test
RUN mvn -B -e clean package -DskipTests


# ---- Stage 2: runtime -----------------------------------------------------------------
# JRE, not JDK: nothing compiles at runtime, and the smaller image measurably shortens the
# cold start that a spun-down free instance pays on its first request.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Only the repackaged fat jar crosses the stage boundary — the JDK, the Maven cache and the
# sources stay behind. The glob does not also match target/*.jar.original, which is the
# pre-repackage artifact and would start with "no main manifest attribute".
COPY --from=build /build/target/*.jar app.jar

# A process that only reads its own jar has no use for root.
RUN useradd --system --uid 10001 --no-create-home spring
USER spring

# JAVA_TOOL_OPTIONS rather than baking flags into the ENTRYPOINT, so Render's dashboard can
# add to it without editing this file.
#
# MaxRAMPercentage matters on the free plan: the JVM's container default is 25% of the
# limit, so a 512MB instance would cap the heap near 128MB and start throwing OOM once a
# 20MB upload, its Ghibli-fied result and the Mongo document for both are all live at once.
# 75% leaves the rest for metaspace, code cache and thread stacks.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Documentation only. Render routes to whatever port the process actually listens on, and
# that comes from server.port=${PORT:8080} in application.properties — Render sets PORT.
EXPOSE 8080

# Exec form, so java is PID 1 and receives the SIGTERM Render sends on shutdown or redeploy.
# Wrapped in a shell it would be the shell that got the signal, and the JVM would be killed
# after the grace period instead of closing its Mongo connections.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
