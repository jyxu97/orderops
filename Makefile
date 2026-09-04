.PHONY: local-up local-down local-init app-up app-down build test \
        web-install web-dev web-build web-test web-lint web-typecheck \
        load-test throughput-test ws-latency-test ws-latency-suite \
        reliability-test failure-test

# Lombok 1.18.32 is incompatible with Java 23+; use Corretto 21
JAVA_HOME ?= /Users/ggq/Library/Java/JavaVirtualMachines/corretto-21.0.11/Contents/Home
export JAVA_HOME

local-up:
	docker compose up -d

local-down:
	docker compose down

local-init:
	bash infra/dynamodb/create-tables.sh
	bash infra/localstack/init-sqs.sh

# Whole stack in containers, including the React app on http://localhost:3000
app-up:
	docker compose --profile app up -d --build

app-down:
	docker compose --profile app down

build:
	mvn -f apps/orderops/pom.xml clean package -DskipTests

test:
	mvn -f apps/orderops/pom.xml test

# ── Frontend ────────────────────────────────────────────────────────────────

web-install:
	npm --prefix apps/web ci

# Vite dev server on :5173, proxying /api and /ws to the API on :8080
web-dev:
	npm --prefix apps/web run dev

web-build:
	npm --prefix apps/web run build

web-test:
	npm --prefix apps/web run test

web-lint:
	npm --prefix apps/web run lint

web-typecheck:
	npm --prefix apps/web run typecheck

load-test:
	@echo "Seeding inventory: 100 units of load-test-item..."
	bash load-tests/scripts/seed.sh http://localhost:8080 load-test-item 100
	@echo "Running k6 concurrency test (1000 VUs, 100-unit stock)..."
	k6 run -e BASE_URL=http://localhost:8080 -e ITEM_ID=load-test-item load-tests/k6/concurrent-checkout.js

# Sustained order-creation latency at a stated concurrency (200 VUs).
throughput-test:
	@echo "Seeding inventory: 50000 units of throughput-item..."
	bash load-tests/scripts/seed.sh http://localhost:8080 throughput-item 50000 19.99
	k6 run --summary-export load-tests/results/throughput-200vu.json \
	  -e BASE_URL=http://localhost:8080 -e ITEM_ID=throughput-item load-tests/k6/throughput.js

# WebSocket delivery latency. Override CLIENTS to change the connection count.
CLIENTS ?= 500
ws-latency-test:
	npm --prefix load-tests/ws install --silent
	node load-tests/ws/latency-benchmark.mjs --clients $(CLIENTS) --orders 20 --warmup 5 \
	  --out ../results --label $(CLIENTS)-clients

# The full 100/250/500/1000 ladder, writing one result file per level.
ws-latency-suite:
	npm --prefix load-tests/ws install --silent
	@for n in 100 250 500 1000; do \
	  node load-tests/ws/latency-benchmark.mjs --clients $$n --orders 20 --warmup 5 \
	    --out ../results --label $$n-clients || exit 1; \
	done

# Duplicate checkout, DLQ isolation, and dead-letter recovery.
# Needs a clean queue and order table: each script purges what it can, but a shared local stack
# carrying state from a throughput run will make the counts unattributable.
reliability-test:
	bash load-tests/scripts/idempotency-test.sh
	bash load-tests/scripts/dlq-isolation-test.sh
	bash load-tests/scripts/redrive-recovery-test.sh

failure-test:
	bash load-tests/scripts/failure-test.sh
