FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

# Copied by Gradle (copySpringSubgraphJar task)
COPY app.jar app.jar

EXPOSE 4003

ENTRYPOINT ["java", "-jar", "app.jar"]
