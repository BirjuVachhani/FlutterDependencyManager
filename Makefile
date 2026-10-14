# make            clean, test, build and verify the plugin ZIP
# make run        launch a sandboxed IntelliJ IDEA (2026.2) with the plugin preinstalled (ARGS=... go to Gradle)
# make install    build and install into local IntelliJ IDEA and Android Studio (IDE=idea or IDE=studio for one)
# make release    build and publish the version in gradle.properties to GitHub Releases, replacing an
#                 existing release of that version (change the version with ./set-version.sh)
#
# GitHub Releases doubles as the custom plugin repository: each release carries the ZIP plus an
# updatePlugins.xml pointing at it, and IDEs add .../releases/latest/download/updatePlugins.xml.
SHELL := /bin/bash

NAME := FlutterDependencyManager
REPO := BirjuVachhani/FlutterDependencyManager
PLUGIN_XML := src/main/resources/META-INF/plugin.xml
VERSION := $(shell sed -nE 's/^version[[:space:]]*=[[:space:]]*//p' gradle.properties)
TAG := v$(VERSION)
ZIP := build/distributions/$(NAME)-$(VERSION).zip
XML := build/distributions/updatePlugins.xml
PLUGIN_ID = $(shell sed -nE 's|.*<id>(.*)</id>.*|\1|p' $(PLUGIN_XML))
SINCE_BUILD = $(shell sed -nE 's/.*sinceBuild = "([^"]+)".*/\1/p' build.gradle.kts)
IDE ?= all

.PHONY: bundle run install release

# The Plugin Verifier checks binary compatibility against locally installed IDEs only,
# which avoids its slow IDE downloads; it is skipped when none is installed.
bundle:
	@set -euo pipefail; \
	ides=(); \
	for dir in "$$HOME/Applications" /Applications; do \
	  for name in "IntelliJ IDEA" "IntelliJ IDEA Ultimate" "IntelliJ IDEA CE" "Android Studio"; do \
	    if [[ -d "$$dir/$$name.app" ]]; then ides+=("$$dir/$$name.app"); fi; \
	  done; \
	done; \
	tasks=(clean test buildPlugin verifyPluginStructure); \
	if (($${#ides[@]})); then \
	  tasks+=(verifyPlugin "-PverifyIdes=$$(IFS=,; echo "$${ides[*]}")"); \
	  printf 'Verifying against: %s\n' "$${ides[@]}"; \
	else \
	  echo "No local IntelliJ IDEA or Android Studio found: skipping the Plugin Verifier."; \
	fi; \
	./gradlew -q "$${tasks[@]}"
	@echo; echo "Release bundle: $(ZIP)"
	@echo "Version $(VERSION), $$(du -h $(ZIP) | cut -f1 | tr -d ' '), sha256 $$(shasum -a 256 $(ZIP) | cut -d' ' -f1)"

# The sandbox keeps its own config and plugins under build/idea-sandbox, so the real IDE setup is untouched.
run:
	./gradlew runIde $(ARGS)

# Unzips into the newest plugins dir of each IDE; restart the IDE afterwards to load it.
install:
	./gradlew -q buildPlugin
	@set -euo pipefail; \
	install_into() { \
	  local label=$$1 dir; shift; \
	  dir=$$(ls -d "$$@" 2>/dev/null | sort -V | tail -1 || true); \
	  if [[ -z "$$dir" ]]; then echo "skip: $$label not found"; return; fi; \
	  rm -rf "$$dir/$(NAME)"; \
	  unzip -qo $(ZIP) -d "$$dir"; \
	  echo "Installed into $$dir (restart $$label)"; \
	}; \
	if [[ $(IDE) != studio ]]; then install_into "IntelliJ IDEA" "$$HOME/Library/Application Support/JetBrains/"{IntelliJIdea,IdeaIC}*/plugins; fi; \
	if [[ $(IDE) != idea ]]; then install_into "Android Studio" "$$HOME/Library/Application Support/Google/"AndroidStudio*/plugins; fi

# Name, vendor and description are copied verbatim from plugin.xml so the IDE's plugin page matches it.
# The tag is created at local HEAD, which must already be pushed.
release: bundle
	@{ \
	  echo '<plugins>'; \
	  echo '  <plugin id="$(PLUGIN_ID)" version="$(VERSION)"'; \
	  echo '          url="https://github.com/$(REPO)/releases/download/$(TAG)/$(notdir $(ZIP))">'; \
	  echo '    <idea-version since-build="$(SINCE_BUILD)"/>'; \
	  sed -nE '/<(name|vendor)>/p; /<description>/,/<\/description>/p' $(PLUGIN_XML); \
	  echo '  </plugin>'; \
	  echo '</plugins>'; \
	} > $(XML)
	@if gh release view $(TAG) -R $(REPO) >/dev/null 2>&1; then \
	  echo "Replacing existing release $(TAG)"; \
	  gh release delete $(TAG) -R $(REPO) --yes --cleanup-tag; \
	fi
	gh release create $(TAG) -R $(REPO) --target $$(git rev-parse HEAD) --title $(TAG) --generate-notes $(ZIP) $(XML)
