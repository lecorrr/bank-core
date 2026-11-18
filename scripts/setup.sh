#!/usr/bin/env bash
# setup.sh: checks an Arch Linux or Ubuntu machine for bank-core, creates the Maven wrapper, runs the build.
#
#   bash scripts/setup.sh --check   report what is missing, change nothing
#   bash scripts/setup.sh           create ./mvnw if missing, then run ./mvnw verify
#   bash scripts/setup.sh --force   recreate ./mvnw even if it exists, then build
#
# It never runs sudo or a package manager: anything that changes the system is printed for you to run.
# A JDK 21 the script does not find under /usr/lib/jvm can be given with BANK_JDK21_HOME=/path/to/jdk.
set -euo pipefail

readonly SCRIPT_NAME="setup.sh"
readonly MAVEN_VERSION="3.9.11"
# Only the tests of this script point these three elsewhere.
readonly MODULES_DIR="${BANK_MODULES_DIR:-/usr/lib/modules}"
readonly OS_RELEASE="${BANK_OS_RELEASE:-/etc/os-release}"
readonly JVM_DIR="${BANK_JVM_DIR:-/usr/lib/jvm}"
readonly MIN_FREE_GB_DOCKER=5
readonly MIN_FREE_GB_HOME=2

echo "== ${SCRIPT_NAME}: bank-core setup and build =="

mode="build"
force=0
for arg in "$@"; do
  case "$arg" in
    --check) mode="check" ;;
    --force) force=1 ;;
    -h | --help)
      sed -n '2,9p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option '$arg'. Use --check, --force or --help." >&2
      exit 2
      ;;
  esac
done

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ ! -f pom.xml || ! -f src/main/resources/static/openapi/bank-core.yaml ]]; then
  echo "FAIL  ${project_dir} is not the unpacked bank-core project: pom.xml or the API contract is missing" >&2
  exit 1
fi

results=()
failures=0
ok() { results+=("OK    $1"); }
warn() { results+=("WARN  $1"); }
fail() {
  results+=("FAIL  $1")
  failures=$((failures + 1))
}
fix() { results+=("      run: $1"); }
note() { results+=("      $1"); }

print_summary() {
  echo
  echo "== summary: ${SCRIPT_NAME} (${mode}) =="
  printf '%s\n' "${results[@]}"
}

finish() {
  print_summary
  if ((failures > 0)); then
    echo "result: ${failures} problem(s); fix them and run the script again"
    exit 1
  fi
  echo "result: all good"
  exit 0
}

# Free space in whole GB on the file system holding $1 (0 when df cannot tell).
free_gb() {
  local kb
  kb="$(df -Pk "$1" 2> /dev/null | awk 'NR == 2 { print $4 }' || true)"
  echo $((${kb:-0} / 1048576))
}

# The value of KEY in os-release, without quotes; empty when the file or the key is missing.
os_value() {
  sed -n "s/^$1=//p" "$OS_RELEASE" 2> /dev/null | head -n 1 | tr -d '"' || true
}

# The version line of `java -version` for the JDK in $1. The JVM prints "Picked up JAVA_TOOL_OPTIONS: ..."
# (or _JAVA_OPTIONS, JDK_JAVA_OPTIONS) before it when those variables are set, so skip such lines.
jdk_version_of() {
  "$1/bin/java" -version 2>&1 | grep -v -E '^(NOTE: )?Picked up ' | head -n 1 || true
}

# Prints the first JDK 21 found in the usual places; fails when there is none.
find_jdk21() {
  local dir
  for dir in "${JVM_DIR}/java-21-openjdk" "${JVM_DIR}"/java-21-openjdk-* "${JVM_DIR}"/*21*; do
    if [[ -x "${dir}/bin/javac" && "$(jdk_version_of "$dir")" == *'"21'* ]]; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

# --- system -----------------------------------------------------------------------------------------

os_id="$(os_value ID)"
os_like="$(os_value ID_LIKE)"
os_name="$(os_value PRETTY_NAME)"
case " ${os_id} ${os_like} " in
  *" arch "*) os_family="arch" ;;
  *" debian "* | *" ubuntu "*) os_family="debian" ;;
  *) os_family="other" ;;
esac

if [[ "$os_family" == "debian" ]]; then
  pkg_jdk="openjdk-21-jdk-headless"
  pkg_docker="docker.io docker-compose-v2 docker-buildx"
  pkg_compose="docker-compose-v2"
else
  pkg_jdk="jdk21-openjdk"
  pkg_docker="docker docker-compose docker-buildx"
  pkg_compose="docker-compose"
fi

# Prints how to install the given packages on this distribution.
fix_install() {
  case "$os_family" in
    arch) fix "sudo pacman -Syu --needed $*" ;;
    debian) fix "sudo apt update && sudo apt install $*" ;;
    *) note "install with your package manager: $*" ;;
  esac
}

case "$os_family" in
  arch) ok "Arch Linux" ;;
  debian) ok "${os_name:-$os_id}, packages through apt" ;;
  *) warn "unknown distribution (${os_name:-no readable ${OS_RELEASE}}); package names below are Arch's" ;;
esac

kernel="$(uname -r)"
if [[ -d "${MODULES_DIR}/${kernel}" ]]; then
  ok "running kernel ${kernel} has its modules"
else
  fail "the kernel was upgraded since boot (no ${MODULES_DIR}/${kernel}); Docker networking will fail"
  fix "reboot"
fi

# A system Maven is only needed once, to create ./mvnw.
maven_ok=0
if [[ -x ./mvnw && $force -eq 0 ]] || command -v mvn > /dev/null 2>&1; then
  maven_ok=1
fi
jdk_packages="$pkg_jdk"
if ((maven_ok == 0)); then
  jdk_packages="${pkg_jdk} maven"
fi

jdk_ok=0
JDK21_HOME=""
if [[ -n "${BANK_JDK21_HOME:-}" ]]; then
  if [[ ! -x "${BANK_JDK21_HOME}/bin/javac" ]]; then
    fail "BANK_JDK21_HOME=${BANK_JDK21_HOME} is not a JDK: it has no bin/javac"
  elif [[ "$(jdk_version_of "$BANK_JDK21_HOME")" != *'"21'* ]]; then
    fail "BANK_JDK21_HOME=${BANK_JDK21_HOME} is not a JDK 21: $(jdk_version_of "$BANK_JDK21_HOME")"
  else
    JDK21_HOME="$BANK_JDK21_HOME"
  fi
  if [[ -z "$JDK21_HOME" ]]; then
    note "unset BANK_JDK21_HOME to let the script search ${JVM_DIR}, or point it at a JDK 21"
  fi
elif found="$(find_jdk21)"; then
  JDK21_HOME="$found"
else
  fail "JDK 21 is not installed (none found under ${JVM_DIR})"
  fix_install "$jdk_packages"
fi
if [[ -n "$JDK21_HOME" ]]; then
  ok "JDK 21 in ${JDK21_HOME} ($(jdk_version_of "$JDK21_HOME"))"
  jdk_ok=1
fi

if [[ -x ./mvnw && $force -eq 0 ]]; then
  ok "Maven wrapper ./mvnw is present, so a system Maven is not needed"
elif ((maven_ok == 1)); then
  ok "Maven for creating the wrapper: $(mvn -v 2> /dev/null | head -n 1 || true)"
else
  fail "Maven is not installed; it is needed once, to create ./mvnw"
  if ((jdk_ok == 1)); then
    fix_install maven
  else
    note "the install command above includes it"
  fi
fi

if command -v git > /dev/null 2>&1; then
  ok "git $(git --version | awk '{ print $3 }')"
else
  warn "git is not installed (needed to commit your work, not to build)"
  fix_install git
fi

# --- docker: Testcontainers needs it for the integration tests -------------------------------------

docker_ok=0
# Ask the daemon itself first: with socket activation (docker.socket enabled, docker.service not),
# the service stays inactive until the first client connects, and `docker info` is that client.
if ! command -v docker > /dev/null 2>&1; then
  fail "Docker is not installed"
  fix_install "$pkg_docker"
  fix "sudo systemctl enable --now docker.socket"
elif ! docker_error="$(docker info 2>&1 > /dev/null)"; then
  if [[ " $(id -nG) " != *" docker "* ]]; then
    fail "user ${USER:-$(id -un)} may not use Docker (not in the docker group)"
    fix "sudo usermod -aG docker ${USER:-$(id -un)}"
    note "then log out and back in, so the new group applies"
  elif command -v systemctl > /dev/null 2>&1 \
    && ! systemctl is-active --quiet docker.service && ! systemctl is-active --quiet docker.socket; then
    fail "Docker is not running: neither docker.service nor docker.socket is active"
    fix "sudo systemctl enable --now docker.socket"
  else
    fail "docker info failed: ${docker_error%%$'\n'*}"
  fi
else
  docker_ok=1
  ok "Docker $(docker version --format '{{.Server.Version}}' 2> /dev/null)"
  if docker compose version > /dev/null 2>&1; then
    ok "docker compose $(docker compose version --short 2> /dev/null)"
  else
    warn "the docker compose plugin is missing (compose.yaml needs it)"
    fix_install "$pkg_compose"
    if [[ "$os_family" == "debian" ]]; then
      note "if Docker came from Docker's own apt repository, the package is docker-compose-plugin instead"
    fi
  fi
  docker_root="$(docker info --format '{{.DockerRootDir}}' 2> /dev/null || true)"
  if [[ -n "$docker_root" && -d "$docker_root" ]]; then
    docker_free="$(free_gb "$docker_root")"
    if ((docker_free < MIN_FREE_GB_DOCKER)); then
      warn "only ${docker_free} GB free for Docker images in ${docker_root}"
      note "free space with 'docker image prune', or move Docker's data-root to a bigger disk"
    else
      ok "${docker_free} GB free for Docker images in ${docker_root}"
    fi
  fi
fi

home_free="$(free_gb "$HOME")"
if ((home_free < MIN_FREE_GB_HOME)); then
  warn "only ${home_free} GB free in ${HOME}; the Maven repository (~/.m2) needs about 1 GB"
else
  ok "${home_free} GB free in ${HOME} for the Maven repository"
fi

db_port="${BANK_DB_PORT:-5434}"
for port in "$db_port" 8080; do
  if command -v ss > /dev/null 2>&1 && [[ -n "$(ss -Hltn "sport = :${port}" 2> /dev/null)" ]]; then
    warn "port ${port} is already in use (compose.yaml publishes it); fine if it is bank-core itself"
    if [[ "$port" == "$db_port" ]]; then
      note "to use another port for bank-core's database: export BANK_DB_PORT=<free port>"
    fi
  fi
done

if [[ "$mode" == "check" ]]; then
  finish
fi

# --- build ------------------------------------------------------------------------------------------

if ((jdk_ok == 0 || maven_ok == 0)); then
  fail "build skipped: install the JDK and Maven first"
  finish
fi

mkdir -p logs
stamp="$(date +%Y%m%d-%H%M%S)"

if [[ ! -x ./mvnw || $force -eq 1 ]]; then
  wrapper_log="logs/wrapper-${stamp}.log"
  echo "Creating the Maven wrapper for Maven ${MAVEN_VERSION} (log: ${wrapper_log})"
  if JAVA_HOME="$JDK21_HOME" mvn -B -N wrapper:wrapper -Dmaven="$MAVEN_VERSION" > "$wrapper_log" 2>&1 \
    && [[ -x ./mvnw ]] && grep -q "apache-maven-${MAVEN_VERSION}" .mvn/wrapper/maven-wrapper.properties; then
    ok "created ./mvnw for Maven ${MAVEN_VERSION}"
  else
    fail "could not create the Maven wrapper; last lines of ${wrapper_log}:"
    while IFS= read -r line; do note "$line"; done < <(tail -n 8 "$wrapper_log")
    finish
  fi
fi

build_args=(-B -ntp verify)
if ((docker_ok == 0)); then
  build_args+=(-DskipITs)
  warn "Docker is not usable, so the integration tests are skipped in this build"
fi

build_log="logs/verify-${stamp}.log"
echo "Building and testing with ./mvnw ${build_args[*]} (log: ${build_log})"
echo "The first build downloads every dependency and takes several minutes."
started=$SECONDS
set +e
JAVA_HOME="$JDK21_HOME" ./mvnw "${build_args[@]}" > "$build_log" 2>&1
build_status=$?
set -e
elapsed=$((SECONDS - started))
duration="$((elapsed / 60))m$((elapsed % 60))s"

# Surefire and Failsafe each print one totals line ("Tests run: N, Failures: ..., Skipped: N").
mapfile -t totals < <(grep -E '^\[[A-Z]+\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' \
  "$build_log" | sed -E 's/^\[[A-Z]+\] //')

if ((build_status == 0)) && grep -q 'BUILD SUCCESS' "$build_log"; then
  ok "build: BUILD SUCCESS in ${duration}"
else
  fail "build: BUILD FAILURE after ${duration} (exit code ${build_status}); first errors from the log:"
  while IFS= read -r line; do note "$line"; done < <(grep -E '^\[ERROR\]' "$build_log" \
    | grep -v -E 'To see the full stack trace|Re-run Maven|For more information|^\[ERROR\] *$|-> \[Help' \
    | head -n 25)
fi

if ((${#totals[@]} >= 1)); then
  note "unit tests:        ${totals[0]}"
fi
if ((${#totals[@]} >= 2)); then
  note "integration tests: ${totals[1]}"
elif ((docker_ok == 1 && build_status == 0)); then
  warn "no integration test results in the log: did Failsafe run?"
fi
note "log: ${build_log}"

finish
