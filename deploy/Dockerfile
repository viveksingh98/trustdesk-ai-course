# syntax=docker/dockerfile:1.7
# TrustDesk production image: build once, extract the layered jar, run as
# an unprivileged user on a read-only filesystem with secrets mounted as
# files. Nothing here reaches the network at runtime except through the
# egress proxy declared in compose.yaml.

FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /workspace/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination layers

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S trustdesk && adduser -S -G trustdesk -H -s /sbin/nologin trustdesk
WORKDIR /app
COPY --from=extract --chown=trustdesk:trustdesk /extract/layers/dependencies/ ./
COPY --from=extract --chown=trustdesk:trustdesk /extract/layers/spring-boot-loader/ ./
COPY --from=extract --chown=trustdesk:trustdesk /extract/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=trustdesk:trustdesk /extract/layers/application/ ./
USER trustdesk
ENV SPRING_PROFILES_ACTIVE=prod \
    TRUSTDESK_SECRETS_DIRECTORY=/run/secrets \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
