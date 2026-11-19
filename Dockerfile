# Build the jar first: ./mvnw package (Jenkins does both).
FROM eclipse-temurin:21-jre

RUN groupadd --system bank && useradd --system --gid bank --home-dir /app bank

WORKDIR /app
COPY target/bank-core-*.jar /app/bank-core.jar
USER bank

EXPOSE 8080
# Heap sized from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/bank-core.jar"]
