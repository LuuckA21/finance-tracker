# ---- build ----
# The JDK of the runtime below (scripts/java-version.sh checks both lines match) and the Maven of
# the wrapper (.mvn/wrapper/maven-wrapper.properties). unzip: the wrapper checks the SHA-256 of the
# .zip distribution, and without unzip it would fetch the .tar.gz instead.
FROM eclipse-temurin:25.0.4.1_1-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src ./src
# Integration tests need Docker (Testcontainers): run them outside the image build with `./mvnw verify`
RUN ./mvnw -q -B package -DskipTests

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
