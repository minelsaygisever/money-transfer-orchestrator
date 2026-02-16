# Makefile - Money Transfer Orchestrator

CHART_NAME := banking-app
CHART_DIR := ./banking-chart

BOLD=$(shell tput bold)
RED=$(shell tput setaf 1)
GREEN=$(shell tput setaf 2)
RESET=$(shell tput sgr0)

help:
	@echo "$(BOLD)Available Commands:$(RESET)"
	@echo "  $(GREEN)make build$(RESET)       - Compiles Java code & creates Docker images"
	@echo "  $(GREEN)make deploy$(RESET)      - Installs/Upgrades the system on Kubernetes"
	@echo "  $(GREEN)make clean$(RESET)       - Removes the Helm release"
	@echo "  $(GREEN)make tunnel$(RESET)      - Opens tunnels for Gateway & Keycloak"
	@echo "  $(GREEN)make restart$(RESET)     - Wipes and reinstalls everything (Clean + Build + Deploy)"

# --- 1. JAVA BUILD & DOCKER BUILD ---
build:
	@echo "$(BOLD)1. Compiling Java Projects (Skipping Tests for Speed)...$(RESET)"
	./mvnw clean package -DskipTests

	@echo "$(BOLD)2. Building Docker Images...$(RESET)"
	docker build -t money-transfer-orchestrator-account-service:latest -f account-service/Dockerfile .
	docker build -t money-transfer-orchestrator-transfer-service:latest -f transfer-service/Dockerfile .
	docker build -t money-transfer-orchestrator-gateway-service:latest -f gateway-service/Dockerfile .
	@echo "$(GREEN)All images are ready!$(RESET)"

# --- 2. DEPLOY (HELM) ---
deploy:
	@echo "$(BOLD)Deploying to Kubernetes...$(RESET)"
	helm upgrade --install $(CHART_NAME) $(CHART_DIR)
	@echo "$(GREEN)Deployment command sent!$(RESET)"
	@echo "To watch status: kubectl get pods -w"

# --- 3. CLEAN ---
clean:
	@echo "$(BOLD)Cleaning up...$(RESET)"
	-helm uninstall $(CHART_NAME)
	@echo "$(GREEN)System removed.$(RESET)"

# --- 4. TUNNEL ---
tunnel:
	@echo "$(BOLD)1. Cleaning up old tunnels...$(RESET)"
	@-pkill -f "kubectl port-forward" || true
	@sleep 2

	@echo "$(BOLD)2. Waiting for pods to be ready...$(RESET)"
	@kubectl wait --for=condition=available --timeout=60s deployment/gateway-service
	@kubectl wait --for=condition=available --timeout=60s deployment/keycloak

	@echo "$(BOLD)3. Opening Tunnels... (Silence mode)$(RESET)"	@kubectl port-forward deployment/gateway-service 30082:8082 > /dev/null 2>&1 &
	@kubectl port-forward deployment/keycloak 8180:8080 > /dev/null 2>&1 &

	@echo "$(GREEN)✔ Tunnels Active!$(RESET)"
	@echo ""
	@echo "$(BOLD)👉 Swagger UI :$(RESET) http://localhost:30082/webjars/swagger-ui/index.html"
	@echo "$(BOLD)👉 Keycloak   :$(RESET) http://localhost:8180"
	@echo ""
	@echo "$(RED)Press ENTER to stop tunnels...$(RESET)"
	@read dummy; \
	echo "Stopping tunnels..."; \
	pkill -f "kubectl port-forward"

# --- 5. RESTART ---
restart: clean build deploy