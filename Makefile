.PHONY: check build lint test clean sdk test-ircd emu emu-install emu-windowed play install devices

ANDROID_HOME ?= $(error ANDROID_HOME is not set; run inside `nix develop`)

ADB := $(ANDROID_HOME)/platform-tools/adb

AAPT2_OVERRIDE := $(wildcard $(ANDROID_HOME)/build-tools/35.0.0/aapt2)
ifneq ($(AAPT2_OVERRIDE),)
GRADLE_FLAGS += -Pandroid.aapt2FromMavenOverride=$(AAPT2_OVERRIDE)
endif

build:
	./gradlew $(GRADLE_FLAGS) :app:assembleDebug

test:
	./gradlew $(GRADLE_FLAGS) test

lint:
	./gradlew $(GRADLE_FLAGS) :app:lintDebug

check: build lint test

test-ircd:
	bash scripts/ensure-ergo.sh
	./gradlew $(GRADLE_FLAGS) :core:client:test --tests "*ErgoRoundTripTest*" --rerun-tasks

emu:
	bash scripts/emu.sh --headless

emu-windowed:
	bash scripts/emu.sh

play: build emu-windowed
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk
	$(ADB) shell am start -n dev.brentdevs.yardhal/.MainActivity
	@echo "Yardhal is running in the emulator window."

emu-install: build emu
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk
	$(ADB) shell am start -n dev.brentdevs.yardhal/.MainActivity

clean:
	./gradlew clean

devices:
	@$(ADB) devices -l

install: build
	@$(ADB) get-state >/dev/null 2>&1 || { echo "No device connected. Plug in USB (enable USB debugging) or run 'adb pair' for wireless."; exit 1; }
	$(ADB) install -r app/build/outputs/apk/debug/app-debug.apk
	$(ADB) shell am start -n dev.brentdevs.yardhal/.MainActivity
	@echo "Yardhal installed and launched on: "$$($(ADB) shell getprop ro.product.model | tr -d '\r')"

sdk:
	bash scripts/ensure-sdk.sh && echo "ANDROID_HOME=$$ANDROID_HOME"
