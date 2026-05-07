# justfile for talos

set shell := ["bash", "-c"]

default:
    @just --list

# =============================================================================
# 🟢 INITIALIZATION
# =============================================================================

# Initialize the repo (install deps, compile protos)
init:
    ./gradlew --quiet
    just proto-all

# Clean everything
clean:
    ./gradlew clean
    find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
    find . -type d -name "node_modules" -exec rm -rf {} + 2>/dev/null || true

# =============================================================================
# 🔵 GRADLE BUILD
# =============================================================================

# Build a Kotlin module (JAR)
# Usage: just build-kt modules/api
build-kt module:
    ./gradlew :{{module}}:build --no-build-cache

build-all:
    ./gradlew :modules:api:build :modules:plugin-api:build :modules:observability:build :modules:config:build :modules:cli-app:build :modules:detekt-rules:build --no-build-cache

# Run Kotlin module tests
# Usage: just test-kt modules/api
test-kt module:
    ./gradlew :{{module}}:test

# Run all tests
test:
    ./gradlew test

# =============================================================================
# 🟡 PROTOCOL BUFFERS
# =============================================================================

# Regenerate protos for all languages
proto-all:
    # Shared proto build (placeholder - shared-proto module TBD)
    # ./gradlew :shared:libs:kotlin:shared-proto:assemble
    # ./gradlew :shared:libs:python:shared-proto:preparePythonPackage
    # ./gradlew :shared:libs:js:shared-proto:prepareJsPackage
    echo "Proto generation not yet configured (shared-proto module pending)"

# =============================================================================
# 🧹 LINTING
# =============================================================================

# Lint all Kotlin modules (ktlint + detekt)
lint:
    ./gradlew ktlintFormat
    ./gradlew detekt

# Lint a specific module
lint-kt module:
    ./gradlew :{{module}}:ktlintFormat
    ./gradlew :{{module}}:detekt

# Check linting (read-only / CI)
lint-check:
    ./gradlew ktlintCheck detekt

# =============================================================================
# 🐍 PYTHON SERVICES
# =============================================================================

# Sync Python dependencies for all modules with pyproject.toml
sync-py:
    ./gradlew :modules:config:syncPyDeps 2>/dev/null || true

# =============================================================================
# 🚀 DEPLOYMENT
# =============================================================================

# Deploy Kotlin service to Local K3s (using Jib → docker load)
# Usage: just deploy-kt modules/cli-app
deploy-kt module:
    ./gradlew :{{module}}:jibDockerBuild --no-configuration-cache \
      -Djib.dockerClient.executable=$(which docker) \
      -Djib.dockerClient.environment.DOCKER_HOST="unix://$HOME/.rd/docker.sock"
    echo "🚀 {{module}} loaded into K3s!"

# =============================================================================
# 🔧 DEBUGGING
# =============================================================================

# Port-forward K3s services (DB, Wiremock, etc.)
debug-tunnel:
    kubectl port-forward -n default svc/postgres 5432:5432 &
    kubectl port-forward -n default svc/wiremock 8089:8080 &
    kubectl port-forward -n default svc/tempo 3200:3200 &
    kubectl port-forward -n default svc/grafana 3000:3000 &
    kubectl port-forward -n default svc/prometheus 9090:9090 &
    wait

# =============================================================================
# 📦 INFO
# =============================================================================

# List all Gradle modules
modules:
    @./gradlew projects --quiet | grep ":modules:" | sed 's|.*:modules:||' | grep -v "^$"

# Show module dependencies
deps module:
    ./gradlew :{{module}}:dependencies --no-configuration-cache