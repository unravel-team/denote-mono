CLOJURE_SOURCES := $(shell find . -name '*.clj' -not -path './.clj-kondo/*' -not -path './target/*' -not -path './projects/*/target/*')

SHELL = /bin/bash -Eeu
.DEFAULT_GOAL := help

.PHONY: help
help: ## A brief explanation of everything you can do
	@awk '/^[a-zA-Z0-9_-]+:.*##/ { \
		printf "%-25s # %s\n", \
		substr($$1, 1, length($$1)-1), \
		substr($$0, index($$0,"##")+3) \
	}' $(MAKEFILE_LIST)

.PHONY: doctor
doctor: ## Verify toolchain prerequisites
	@for cmd in clojure clj-kondo zprint tagref; do \
		command -v $$cmd >/dev/null 2>&1 || { echo "$$cmd is required"; exit 1; }; \
	done
	@echo "denote-mono Clojure/Polylith scaffold ready."

.PHONY: repl
repl: ## Launch a REPL using the Clojure CLI
	clojure -M:dev:test

.PHONY: check-tagref
check-tagref:
	tagref

.PHONY: check-cljkondo
check-cljkondo:
	clj-kondo --lint $(CLOJURE_SOURCES)

.PHONY: check-zprint
check-zprint:
	zprint -c $(CLOJURE_SOURCES)

.PHONY: check
check: check-tagref check-cljkondo check-zprint ## Check lint/static analysis/formatting
	@echo "All checks passed!"

.PHONY: format
format: ## Format Clojure code with zprint
	@if [ -n "$(CLOJURE_SOURCES)" ]; then zprint -lfw $(CLOJURE_SOURCES); fi

.PHONY: test-unit
test-unit: ## Run unit tests through cognitect test-runner
	clojure -M:dev:test

.PHONY: test-poly
test-poly: ## Run Polylith tests
	clojure -M:poly test

.PHONY: test
test: test-unit ## Run default test suite

.PHONY: build
build: check ## Build denote-cli uberjar
	clojure -T:build uberjar :project denote-cli

.PHONY: clean
clean: ## Delete generated artifacts
	rm -rf target/ .cpcache/ projects/*/target
