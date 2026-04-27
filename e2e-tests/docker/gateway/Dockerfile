FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

# Copy the Spring Boot JAR (copied by Gradle copyGatewayJar task)
COPY app.jar app.jar

# Copy customizations JAR (if present)
COPY libs/ libs/

# Copy feddi-gateway configuration
COPY feddi-gateway.yml feddi-gateway.yml

EXPOSE 8080 9090 9091

# Use loader.path to include additional JARs on the classpath
ENTRYPOINT ["java", "-Dloader.path=libs/", "-jar", "app.jar"]
