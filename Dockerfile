# Build stage
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle/
COPY build.gradle settings.gradle ./
COPY src src/
RUN ./gradlew bootJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
VOLUME ["/mirror"]
ENTRYPOINT ["java", "-jar", "app.jar"]
