# syntax=docker/dockerfile:1
# US-014 AC3: multi-stage build. The build stage has the JDK and the Maven Wrapper; the runtime stage has only a JRE
# and the application jar, and runs as a non-root user.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml lombok.config ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/url-shortener-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app
EXPOSE 8080
# GET, not HEAD: probes use the public GET rule. wget ships with the Alpine base image.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
