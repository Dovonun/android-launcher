# Architecture

## Stack
- Kotlin
- Jetpack Compose
- Material 3
- Room
- Gradle Kotlin DSL with version catalog

## Shape
The app is intentionally small. Keep behavior close to the call site unless duplication becomes real noise.

Destructive database migrations are acceptable while the project is alpha.

## Performance bias
Avoid layout shift and unnecessary UI work. Do not add reactive plumbing, abstraction layers, or caching systems before the need is visible.
