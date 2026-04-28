# Stage 1: Extract Spring Boot layers for efficient Docker caching
FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 2: Build final image
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S feddi-gateway && adduser -S feddi-gateway -G feddi-gateway

WORKDIR /app

# Copy layers in order of change frequency (most stable first)
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

# Copy platform extension
COPY libs/ libs/

EXPOSE 8080
USER feddi-gateway

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget --spider -q http://localhost:8080/actuator/health || exit 1

# ZGC for low-latency, MaxRAMPercentage auto-sizes to container memory limit
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseStringDeduplication", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:-OmitStackTraceInFastThrow", \
  "-Dloader.path=libs/", \
  "org.springframework.boot.loader.launch.JarLauncher"]
