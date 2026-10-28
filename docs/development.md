# Development

## Build
```sh
./gradlew :app:compileDebugKotlin
```

## Tests
```sh
./gradlew test
```

## End-to-end tests (emulator or device)
UI Automator tests in `e2e/` drive the dev flavor as the real home app. They clear
`com.ni.launcher.dev` data and set it as home, so use an emulator.
```sh
./gradlew :e2e:connectedDebugAndroidTest
```
Manual steps and emulator setup: `docs/testing/manual-emulator-test-plan.md`.
