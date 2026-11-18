# Jenkins for bank-core. The Docker CLI and buildx talk to the host's Docker through the mounted socket:
# Testcontainers starts PostgreSQL that way, and the pipeline builds the application image.
FROM jenkins/jenkins:lts-jdk21

USER root
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker/buildx-bin /buildx /usr/libexec/docker/cli-plugins/docker-buildx
# The repository is mounted read-only from your home directory and belongs to your user, not to jenkins.
RUN git config --system --add safe.directory '*'
USER jenkins
