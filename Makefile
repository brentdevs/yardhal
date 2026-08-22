.PHONY: check build test clean sdk

ANDROID_HOME ?= $(error ANDROID_HOME is not set; run inside `nix develop`)

AAPT2_OVERRIDE := $(wildcard $(ANDROID_HOME)/build-tools/35.0.0/aapt2)
ifneq ($(AAPT2_OVERRIDE),)
GRADLE_FLAGS += -Pandroid.aapt2FromMavenOverride=$(AAPT2_OVERRIDE)
endif

build:
	./gradlew $(GRADLE_FLAGS) :app:assembleDebug

test:
	./gradlew $(GRADLE_FLAGS) test

check: build test

clean:
	./gradlew clean

sdk:
	bash scripts/ensure-sdk.sh && echo "ANDROID_HOME=$$ANDROID_HOME"
