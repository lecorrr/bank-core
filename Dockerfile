# Build the jar first: ./mvnw package (Jenkins does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user: Kubernetes can then check runAsNonRoot against the image.
RUN groupadd --gid 10001 bank && useradd --uid 10001 --gid bank --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin bank

WORKDIR /app
COPY target/bank-core-*.jar /app/bank-core.jar
USER 10001:10001

EXPOSE 8080
# Heap from the container's memory limit; 60 % leaves room for metaspace and threads in a 640 MiB pod.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/bank-core.jar"]
