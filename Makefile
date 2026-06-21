.PHONY: doctor build test build-macos test-macos build-android test-android test-scripts clean

doctor:
	@./scripts/doctor

build:
	@./scripts/build all

test:
	@./scripts/test all

build-macos:
	@./scripts/build macos

test-macos:
	@./scripts/test macos

build-android:
	@./scripts/build android

test-android:
	@./scripts/test android

test-scripts:
	@./scripts/tests/doctor

clean:
	@./scripts/clean
