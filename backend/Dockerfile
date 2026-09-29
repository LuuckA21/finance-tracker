# ---- build ----
# Exact Maven, same JDK major as the runtime: this stage only compiles and packages, no code of the
# application runs here. Updated by hand (see .github/dependabot.yml).
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Integration tests need Docker (Testcontainers): run them outside the image build with `mvn verify`
RUN mvn -q -B package -DskipTests

# ---- runtime ----
# The exact JDK of the backend: CI reads it from this line (scripts/java-version.sh), so the unit,
# integration and end-to-end tests run on the Java that runs production. Dependabot proposes
# updates within Java 25; the next LTS is chosen by hand, together with java.version in pom.xml.
FROM eclipse-temurin:25.0.4.1_1-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /src/target/finance-tracker-*.jar app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
