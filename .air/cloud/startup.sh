#!/usr/bin/env bash
# Air cloud environment startup for kotlintesting (Kotlin Multiplatform / Kotlin Native XCTest).
#
# NOTE: the real targets (macOS/iOS + XCTest) need macOS with Xcode. On this Linux
# environment we provision JDK 21 + Gradle, build the convention plugins, configure the
# :kotlin-test-native-xctest module and prime the Gradle / Kotlin Native (~/.konan) caches.
# The :frameworkTesting module cannot be configured on Linux (it resolves Xcode SDK paths at
# configuration time), so use `gradle --configure-on-demand :kotlin-test-native-xctest:<task>`.
set -euo pipefail

if [ "${AIR_STARTUP_MODE:-}" = warmup ]; then WARMUP=1; else WARMUP=; fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPT_DIR="$HOME/.local/opt"
BIN_DIR="$HOME/.local/bin"
JDK_DIR="$OPT_DIR/jdk-21"
GRADLE_VERSION=8.14.3
GRADLE_DIR="$OPT_DIR/gradle-$GRADLE_VERSION"
ENV_FILE="$HOME/.kotlintesting-env.sh"

log() { echo "[startup] $*"; }

curl_dl() {
  # Route through the egress proxy when one is configured.
  if [ -n "${HTTPS_PROXY:-}" ]; then curl -fsSL --retry 3 -x "$HTTPS_PROXY" "$@"; else curl -fsSL --retry 3 "$@"; fi
}

mkdir -p "$OPT_DIR" "$BIN_DIR"

# --- JDK 21 (Gradle 8.14 Kotlin DSL does not run on newer JDKs; convention plugins use jvmToolchain(21))
if [ ! -x "$JDK_DIR/bin/java" ]; then
  log "Installing Temurin JDK 21..."
  tmp="$(mktemp -d)"
  curl_dl -o "$tmp/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$JDK_DIR"
  tar -xzf "$tmp/jdk.tgz" -C "$JDK_DIR" --strip-components=1
  rm -rf "$tmp"
fi
log "JDK: $("$JDK_DIR/bin/java" -version 2>&1 | grep -m1 -i 'runtime' || true)"

# --- Gradle (repository ships gradlew but no gradle/wrapper files)
if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  log "Installing Gradle $GRADLE_VERSION..."
  tmp="$(mktemp -d)"
  curl_dl -o "$tmp/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$tmp/gradle.zip" "$OPT_DIR"
  chmod +x "$GRADLE_DIR/bin/gradle"
  rm -rf "$tmp"
fi
ln -sfn "$GRADLE_DIR/bin/gradle" "$BIN_DIR/gradle"

# --- Login-shell environment
cat > "$ENV_FILE" <<EOF
export JAVA_HOME="$JDK_DIR"
export GRADLE_HOME="$GRADLE_DIR"
case ":\$PATH:" in *":$BIN_DIR:"*) ;; *) export PATH="$BIN_DIR:\$PATH" ;; esac
case ":\$PATH:" in *":$JDK_DIR/bin:"*) ;; *) export PATH="$JDK_DIR/bin:\$PATH" ;; esac
EOF
MARKER="# kotlintesting-env"
SOURCE_LINE="[ -f \"$ENV_FILE\" ] && . \"$ENV_FILE\" $MARKER"
profile=""
for f in "$HOME/.bash_profile" "$HOME/.bash_login" "$HOME/.profile"; do
  if [ -f "$f" ]; then profile="$f"; break; fi
done
[ -n "$profile" ] || { profile="$HOME/.profile"; touch "$profile"; }
for f in "$profile" "$HOME/.bashrc"; do
  touch "$f"
  grep -qF "$MARKER" "$f" || echo "$SOURCE_LINE" >> "$f"
done
# shellcheck disable=SC1090
. "$ENV_FILE"

# --- JVM proxy: the egress proxy is only exposed as HTTPS_PROXY/HTTP_PROXY, which the Gradle JVM
# ignores. Mirror it into ~/.gradle/gradle.properties (managed block, rewritten on every boot).
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
mkdir -p "$HOME/.gradle"
touch "$GRADLE_PROPS"
sed -i '/^# >>> kotlintesting-proxy/,/^# <<< kotlintesting-proxy/d' "$GRADLE_PROPS"
proxy_url="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$proxy_url" ]; then
  hostport="${proxy_url#*://}"; hostport="${hostport##*@}"; hostport="${hostport%%/*}"
  proxy_host="${hostport%%:*}"; proxy_port="${hostport##*:}"
  [ "$proxy_port" != "$hostport" ] || proxy_port=80
  non_proxy="$(echo "${NO_PROXY:-${no_proxy:-localhost,127.0.0.1}}" | tr ',' '|')"
  log "Configuring Gradle JVM proxy $proxy_host:$proxy_port"
  cat >> "$GRADLE_PROPS" <<EOF
# >>> kotlintesting-proxy (managed by .air/cloud/startup.sh)
systemProp.http.proxyHost=$proxy_host
systemProp.http.proxyPort=$proxy_port
systemProp.https.proxyHost=$proxy_host
systemProp.https.proxyPort=$proxy_port
systemProp.http.nonProxyHosts=$non_proxy
# <<< kotlintesting-proxy
EOF
fi

gradle_run() {
  (cd "$REPO_DIR" && gradle --console=plain --configure-on-demand "$@")
}

healthcheck() {
  log "healthcheck: java/gradle versions"
  java -version 2>&1 | head -1
  gradle --version | grep -E '^Gradle|^Kotlin|^Launcher JVM'
  log "healthcheck: build convention plugins and configure :kotlin-test-native-xctest, fetch Kotlin Native toolchain"
  gradle_run :kotlin-test-native-xctest:tasks --all :kotlin-test-native-xctest:commonizeNativeDistribution >/tmp/kotlintesting-healthcheck.log 2>&1 || {
    tail -50 /tmp/kotlintesting-healthcheck.log; log "healthcheck FAILED"; return 1; }
  grep -q 'iosSimulatorArm64Test' /tmp/kotlintesting-healthcheck.log || {
    tail -50 /tmp/kotlintesting-healthcheck.log; log "healthcheck FAILED: KMP targets not configured"; return 1; }
  ls -d "$HOME"/.konan/kotlin-native-prebuilt-* >/dev/null 2>&1 || {
    log "healthcheck FAILED: Kotlin Native toolchain not downloaded"; return 1; }
  log "healthcheck OK"
}

if [ -n "$WARMUP" ]; then
  # Primes Gradle distribution, plugin/dependency caches and the Kotlin Native toolchain for the snapshot.
  healthcheck
else
  # Warm the Gradle daemon in the background so the task starts promptly.
  log "Starting Gradle configuration in the background (log: /tmp/kotlintesting-gradle-warm.log)"
  nohup bash -c "cd '$REPO_DIR' && gradle --console=plain --configure-on-demand :kotlin-test-native-xctest:help" \
    >/tmp/kotlintesting-gradle-warm.log 2>&1 &
fi
log "Startup complete"
