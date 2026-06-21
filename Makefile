HOME := $(shell echo $$HOME)
HERE := $(shell echo $$PWD)
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

.PHONY: init
init: install-kondo-configs install-zprint-config     ## Bootstrap tooling

.clj-kondo:
	mkdir .clj-kondo

.PHONY: install-kondo-configs
install-kondo-configs: .clj-kondo
	clj-kondo --lint "$$(clojure -A:dev:test:cider:build -Spath)" --copy-configs --skip-lint

.PHONY: check-zprint-config
check-zprint-config:
	@echo "Checking (HOME)/.zprint.edn..."
	@if [ ! -f "$(HOME)/.zprint.edn" ]; then \
		echo "Error: ~/.zprint.edn not found"; \
		echo "Please create ~/.zprint.edn with the content: {:search-config? true}"; \
		exit 1; \
	fi
	@if ! grep -q "search-config?" "$(HOME)/.zprint.edn"; then \
		echo "Warning: ~/.zprint.edn might not contain required {:search-config? true} setting"; \
		echo "Please ensure this setting is present for proper functionality"; \
		exit 1; \
	fi

.zprint.edn:
	@echo "Creating .zprint.edn..."
	@echo '{:fn-map {"with-context" "with-meta"}, :map {:indent 0}}' > $@

.dir-locals.el:
	@echo "Creating .dir-locals.el..."
	@echo ';;; Directory Local Variables         -*- no-byte-compile: t; -*-' > $@
	@echo ';;; For more information see (info "(emacs) Directory Variables")' >> $@
	@echo '((clojure-dart-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-jank-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojure-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurec-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurec-ts-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurescript-mode . ((apheleia-formatter . (zprint))))' >> $@
	@echo ' (clojurescript-ts-mode . ((apheleia-formatter . (zprint)))))' >> $@

.PHONY: install-zprint-config
install-zprint-config: check-zprint-config .zprint.edn .dir-locals.el
	@echo "zprint configuration files created successfully."

# The Clojure CLI aliases that will be selected for main options for `repl`.
# Feel free to upgrade this, or to override it with an env var named DEPS_MAIN_OPTS.
# Expected format: "-M:alias1:alias2"
DEPS_MAIN_OPTS ?= "-M:dev:test:logs-dev:cider"

.PHONY: repl
repl:    ## Launch a REPL using the Clojure CLI
	clojure $(DEPS_MAIN_OPTS);

.PHONY: check-tagref
check-tagref:
	tagref

.PHONY: check-cljkondo
check-cljkondo:
	clj-kondo --lint $(CLOJURE_SOURCES)

.PHONY: check-zprint
check-zprint:
	zprint -c $(CLOJURE_SOURCES)

# Loading the CLI entry namespace transitively compiles every brick.
# Scoped to denote_mono files: dependencies (litellm-clj) warn too, but
# they are not ours to fix here. Reflection that the JVM tolerates kills
# the GraalVM native image at runtime, so warnings fail the build.
.PHONY: check-reflection
check-reflection:
	@warnings=$$(clojure -M:dev -e "(set! *warn-on-reflection* true) (require 'denote-mono.cli.core)" 2>&1 >/dev/null | grep "Reflection warning, denote_mono" || true); \
	if [ -n "$$warnings" ]; then echo "$$warnings"; exit 1; fi

.PHONY: check
check: check-tagref check-cljkondo check-zprint check-reflection ## Check lint/static analysis/formatting
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

.PHONY: test-coverage
test-coverage:   # Run coverage reports for the code using Clofidence
	clojure -X:dev:test:clofidence

.PHONY: build
build: check ## Build denote-cli uberjar
	clojure -T:build uberjar :project denote-cli

# Path to GraalVM's native-image: PATH first, then a user-local install
# under ~/.local/share/graalvm (macOS bundle layout).
NATIVE_IMAGE ?= $(or $(shell command -v native-image 2>/dev/null),$(lastword $(wildcard $(HOME)/.local/share/graalvm/*/Contents/Home/bin/native-image)))

.PHONY: native
native: build ## Build a native binary with GraalVM native-image
	@test -n "$(NATIVE_IMAGE)" || { echo "native-image not found; install GraalVM or set NATIVE_IMAGE"; exit 1; }
	$(NATIVE_IMAGE) -jar $$(ls -t projects/denote-cli/target/denote-cli-*-standalone.jar | head -1) \
	  -o projects/denote-cli/target/denote \
	  --features=clj_easy.graal_build_time.InitClojureClasses \
	  --initialize-at-build-time=com.fasterxml.jackson \
	  --initialize-at-build-time=org.slf4j \
	  --enable-url-protocols=http,https \
	  --no-fallback
	@echo "Native binary: projects/denote-cli/target/denote"

.PHONY: clean
clean: ## Delete generated artifacts
	rm -rf target/ .cpcache/ projects/*/target

# The released version lives in the denote-cli project's :uberjar alias
# (:major-version / :minor-version); the patch component is the git
# revision count computed at build time (see build.clj). The release
# targets bump the stored pair and commit the bump as its own jj change,
# stepping off any in-progress working copy first.
VERSION_DEPS := projects/denote-cli/deps.edn

define commit_version_bump
	if [ -n "$$(jj diff --summary)" ] || [ -n "$$(jj log -r @ --no-graph -T description)" ]; then jj new; fi; \
	perl -pi -e "s/:major-version \d+/:major-version $(1)/; s/:minor-version \d+/:minor-version $(2)/" $(VERSION_DEPS); \
	jj desc -m "chore(release): bump version to $(1).$(2)"; \
	jj new; \
	echo "Version is now $(1).$(2)"
endef

.PHONY: release-minor
release-minor: ## Bump the minor version and commit the bump
	@major=$$(perl -ne 'print $$1 if /:major-version (\d+)/' $(VERSION_DEPS)); \
	minor=$$(perl -ne 'print $$1 if /:minor-version (\d+)/' $(VERSION_DEPS)); \
	$(call commit_version_bump,$$major,$$((minor + 1)))

.PHONY: release-major
release-major: ## Bump the major version (minor resets to 0) and commit the bump
	@major=$$(perl -ne 'print $$1 if /:major-version (\d+)/' $(VERSION_DEPS)); \
	$(call commit_version_bump,$$((major + 1)),0)

.PHONY: current-version
current-version: ## Bump the major version (minor resets to 0) and commit the bump
	@major=$$(perl -ne 'print $$1 if /:major-version (\d+)/' $(VERSION_DEPS)); \
	minor=$$(perl -ne 'print $$1 if /:minor-version (\d+)/' $(VERSION_DEPS)); \
	echo "$$major.$$minor.$$(git rev-list --count HEAD 2>/dev/null || echo '?')"

.PHONY: install-antq
install-antq:
	@if [ -f .antqtool.lastupdated ] && find .antqtool.lastupdated -mtime +15 -print | grep -q .; then \
		echo "Updating antq tool to the latest version..."; \
		clojure -Ttools install-latest :lib com.github.liquidz/antq :as antq; \
		touch .antqtool.lastupdated; \
	else \
		echo "Skipping antq tool update..."; \
	fi

.antqtool.lastupdated:
	touch .antqtool.lastupdated

.PHONY: upgrade-deps
upgrade-deps: .antqtool.lastupdated install-antq    ## Upgrade all dependencies to their latest versions
	clojure -Tantq outdated :check-clojure-tools true :upgrade true
