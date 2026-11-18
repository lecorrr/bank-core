# Jenkins on your laptop

`ci/jenkins/` runs Jenkins in Docker and builds bank-core from your own git repository with the
`Jenkinsfile`: compile, every test (Testcontainers starts PostgreSQL through the host's Docker), the test
and coverage reports, then the `bank-core` Docker image.

Jenkins builds what is committed, not your working tree. Commit before you press "Build Now".

## Start it

Jenkins needs the id of your `docker` group to use the Docker socket:

```
export DOCKER_GID=$(getent group docker | cut -d: -f3)
```

Build and start it (the first time downloads several hundred MB of images):

```
docker compose -f ci/jenkins/compose.yaml up -d --build
```

Print the one-time admin password:

```
docker compose -f ci/jenkins/compose.yaml exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Open <http://localhost:8081>, paste the password, choose **Install suggested plugins** and create your
admin user.

## Create the job

1. **New Item**, name `bank-core`, type **Pipeline**, OK.
2. Under **Pipeline**: Definition **Pipeline script from SCM**, SCM **Git**.
3. Repository URL `file:///repos/bank-core` (your project, mounted read-only). No credentials.
4. Branch Specifier `*/main`, Script Path `Jenkinsfile`. Save.
5. **Build Now**. The first build downloads Maven and every dependency, so it takes a few minutes.

Test results appear on the build page under **Tests**; the coverage report is under **Artifacts**
(`target/site/jacoco/index.html`). A successful build leaves `bank-core:latest` in your local Docker:

```
docker image ls bank-core
```

## When something goes wrong

- `No such DSL method 'junit'`: install the **JUnit** plugin (Manage Jenkins, Plugins), then build again.
- `permission denied ... docker.sock`: `DOCKER_GID` was not exported when the container was created.
  Export it and run the `up -d --build` command again.
- Integration tests cannot connect to PostgreSQL: the container reaches the database through
  `host.docker.internal`; check that no firewall on the laptop blocks traffic from Docker networks.
- After a kernel update Docker networking fails until you reboot.

## Stop it

Stop Jenkins and keep its jobs and history (the `bank-core-ci_jenkins-home` volume):

```
docker compose -f ci/jenkins/compose.yaml down
```

Remove everything, including that volume:

```
docker compose -f ci/jenkins/compose.yaml down -v
```

The socket mount gives Jenkins full control of your Docker, which is fine on your own laptop. On a shared
server you would use separate build agents instead.
