# Jenkins on your machine

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

## Deploy to the k3d cluster

bank-open-api's k3d cluster runs bank-core and bank-open-api on Kubernetes. With it, this Jenkins also pushes
each image to the cluster's registry (`localhost:5050`) and deploys it with Helm: bank-core from
`charts/bank-core`. Without the cluster the deploy stages are skipped.

Create the cluster and Jenkins' deploy account, in `~/projects/bank-open-api`:

```
bash scripts/cluster-up.sh
```

Then restart Jenkins with the cluster's settings: k3d's Docker network, the deploy account's kubeconfig and the
bank-open-api repository. Here in bank-core, with `DOCKER_GID` exported as above:

```
docker compose -f ci/jenkins/compose.yaml -f ci/jenkins/compose.k3d.yaml up -d --build
```

Build the `bank-core` job: after the tests and the image it runs **Deploy to k3d**. Then create a second job
the same way as the first, named `bank-open-api`, with the repository `file:///repos/bank-open-api`, and build
it. Its last stage calls the API through Traefik, as a provider would. From your machine:

```
kubectl -n bank get pods
```

```
curl -sS -w '\n' -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" localhost:8480/v2/accounts
```

The deploy account may change Deployments, Services and routes in the `bank` namespace and nothing else; the
cluster and PostgreSQL stay with `cluster-up.sh`.

## When something goes wrong

- `No such DSL method 'junit'`: install the **JUnit** plugin (Manage Jenkins, Plugins), then build again.
- `permission denied ... docker.sock`: `DOCKER_GID` was not exported when the container was created.
  Export it and run the `up -d --build` command again.
- Integration tests cannot connect to PostgreSQL: the container reaches the database through
  `host.docker.internal`; check that no firewall on your machine blocks traffic from Docker networks.
- After a kernel update Docker networking fails until you reboot.
- `network k3d-bank declared as external, but could not be found`: the cluster is not running. Run
  `bash scripts/cluster-up.sh` in bank-open-api, then the `up -d --build` command again.
- `bind source path does not exist` when Jenkins starts: the deploy account's kubeconfig or bank-open-api is
  missing. Run `bash scripts/cluster-up.sh` in bank-open-api, which must sit next to bank-core in `~/projects`.
- `helm: not found` in the deploy stage: the Jenkins image is from before bank-core 0.7.0; `up -d --build`
  builds it again.
- `Forbidden`, `Unauthorized` or no connection to the cluster after you deleted and created it again: the
  Jenkins container still has the old cluster's network. Run `bash scripts/cluster-up.sh` in bank-open-api,
  then the `up -d --build` command above with `--force-recreate` added.

## Stop it

Stop Jenkins and keep its jobs and history (the `bank-core-ci_jenkins-home` volume):

```
docker compose -f ci/jenkins/compose.yaml down
```

Remove everything, including that volume:

```
docker compose -f ci/jenkins/compose.yaml down -v
```

The socket mount gives Jenkins full control of your Docker, which is fine on your own machine. On a shared
server you would use separate build agents instead.
