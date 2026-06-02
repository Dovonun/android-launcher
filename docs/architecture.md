# Architecture

## Stack
- Kotlin
- Jetpack Compose
- Material 3
- Room
- Gradle Kotlin DSL with version catalog

## Shape
The app is intentionally small. Keep state ownership obvious and behavior close to the call site unless duplication becomes real noise.

- ViewModels own app behavior and long-lived state.
- UI owns temporary interaction state.
- Room stores favorites, tags, shortcuts, and user organization.
- Destructive database migrations are acceptable while the project is alpha.

## Performance bias
Prefer simple data flows and cheap UI work. Avoid adding reactive plumbing, abstraction layers, or caching systems before the need is visible.

## Testing bias
- Pure logic should have JVM tests.
- Android/system API behavior can need manual device checks.
