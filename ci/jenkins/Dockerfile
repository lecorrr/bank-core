# Jenkins for the bank's services. The Docker CLI and buildx talk to the host's Docker through the mounted socket:
# Testcontainers starts its databases that way, and the pipelines build the images. kubectl and helm deploy to
# the k3d cluster of bank-open-api.
#
# One Jenkins release, the LTS of November 2026: a rebuild, after a power cut or on another machine, gives the same
# Jenkins, not whatever lts-jdk21 means that day. Moving to a newer LTS is a change of this line, then
# docker compose ... up -d --build (docs/jenkins.md); Jenkins upgrades its home on its first start.
FROM jenkins/jenkins:2.580.3-lts-jdk21

USER root
COPY --from=docker:cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker/buildx-bin /buildx /usr/libexec/docker/cli-plugins/docker-buildx

# The versions of bank-open-api's scripts/install-tools.sh. The checksum files come from the same servers, so they
# catch a broken download rather than a forged one.
ARG KUBECTL_VERSION=v1.36.6
ARG HELM_VERSION=v4.3.1
RUN set -eu; \
    arch="$(dpkg --print-architecture)"; \
    curl -fsSLo /tmp/kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${arch}/kubectl"; \
    echo "$(curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${arch}/kubectl.sha256")  /tmp/kubectl" \
        | sha256sum -c -; \
    install -m 0755 /tmp/kubectl /usr/local/bin/kubectl; \
    helm_file="helm-${HELM_VERSION}-linux-${arch}.tar.gz"; \
    curl -fsSLo "/tmp/${helm_file}" "https://get.helm.sh/${helm_file}"; \
    echo "$(curl -fsSL "https://get.helm.sh/${helm_file}.sha256sum" | cut -d' ' -f1)  /tmp/${helm_file}" \
        | sha256sum -c -; \
    tar -xzf "/tmp/${helm_file}" -C /tmp "linux-${arch}/helm"; \
    install -m 0755 "/tmp/linux-${arch}/helm" /usr/local/bin/helm; \
    rm -rf /tmp/kubectl "/tmp/${helm_file}" "/tmp/linux-${arch}"

# The repositories are mounted read-only from your home directory and belong to your user, not to jenkins.
RUN git config --system --add safe.directory '*'
USER jenkins
