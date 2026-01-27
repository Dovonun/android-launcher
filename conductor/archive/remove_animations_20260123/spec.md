# Specification: Remove view animations

## Goal
Remove the `AnimatedContent` transition wrapper from `MainActivity.kt` to make view switching instant.

## Requirements
- **Instant Transitions:** Switching between "Favorites" and "All Apps" should happen immediately without any slide or fade effects.
- **Maintain Logic:** The underlying view state management in `ViewVM` and `MainActivity` must remain functional.
- **Code Cleanliness:** Remove unused imports related to animations.
