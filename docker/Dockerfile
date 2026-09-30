# One image with both services; compose picks which jar to run.

# Build stage: same Maven version as ./mvnw (the Temurin JDK image has no curl for the wrapper).
FROM maven:3.9.14-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
# Cache ~/.m2 between builds so dependencies aren't downloaded every time.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# Runtime stage: JRE only, no build tools.
FROM eclipse-temurin:25-jre
COPY --from=build /src/service-a/target/service-a.jar /app/service-a.jar
COPY --from=build /src/service-b/target/service-b.jar /app/service-b.jar
