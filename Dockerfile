# The application image. Build the jar first (./mvnw package); Jenkins does both.
FROM eclipse-temurin:21-jre

# Never run as root inside the container.
RUN groupadd --system bank && useradd --system --gid bank --home-dir /app bank

WORKDIR /app
COPY target/bank-core-*.jar /app/bank-core.jar
USER bank

EXPOSE 8080
# Size the heap from the container's memory limit instead of the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/bank-core.jar"]
