# Stage 1: Build
FROM maven:3.9.6-amazoncorretto-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM amazoncorretto:21-al2023
WORKDIR /app
COPY --from=build /build/target/orderops-*.jar app.jar

# APP_MODE=api  → HTTP server (default)
# APP_MODE=worker → SQS consumer, no HTTP port needed
ENV APP_MODE=api
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]