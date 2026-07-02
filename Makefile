.PHONY: local-up local-down local-init build test load-test failure-test

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

build:
	mvn -f apps/orderops/pom.xml clean package -DskipTests

test:
	mvn -f apps/orderops/pom.xml test

load-test:
	k6 run load-tests/k6/concurrent-checkout.js

failure-test:
	bash load-tests/scripts/failure-test.sh
