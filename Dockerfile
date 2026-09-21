# syntax=docker/dockerfile:1

# Build stage: resolve dependencies first so that layer survives code-only changes.
FROM eclipse-temurin:21-jdk-resolute AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/main/ src/main/
RUN ./mvnw -B -q package -DskipTests \
	&& cp target/stockahead-*.jar app.jar \
	&& java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Runtime stage: pinned to the Ubuntu 26.04 JRE; it ships curl for Coolify's healthcheck.
FROM eclipse-temurin:21-jre-resolute
RUN groupadd --system --gid 10001 app \
	&& useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# Baked into the image, not configured in Coolify: it applies wherever the image runs and survives UI edits.
HEALTHCHECK --interval=5s --timeout=5s --start-period=60s --retries=10 \
	CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
