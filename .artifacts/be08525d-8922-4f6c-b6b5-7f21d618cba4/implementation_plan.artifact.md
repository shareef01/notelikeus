# Implementation Plan: Windows Desktop App via Compose Multiplatform

This plan outlines the migration of the "Notelikeus" Android app to a multiplatform project (KMP) supporting Windows (via JVM/Skia) using Compose Multiplatform.

## User Review Required

> [!IMPORTANT]
> This migration involves structural changes to the project. The single `:app` module will be refactored into a Kotlin Multiplatform structure.
>
> - **DI Change:** Hilt is Android-only. We will migrate shared business logic to **Koin**, which is KMP-native.
> - **Navigation:** We will continue to use `androidx.navigation` as it now supports Multiplatform, or migrate to a more KMP-friendly library if needed.
> - **Room:** We will migrate to **Room KMP**, which supports Android and Desktop.

## Proposed Changes

### 1. Build Configuration & Dependencies
Update the Root `build.gradle.kts` and `libs.versions.toml` to include KMP and Compose Multiplatform plugins.

#### [MODIFY] [libs.versions.toml](file:///C:/Users/shareef01/AndroidStudioProjects/notelikeus/gradle/libs.versions.toml)
- Add Compose Multiplatform plugin version.
- Add Koin dependencies.
- Add Room KMP dependencies.

#### [MODIFY] [build.gradle.kts](file:///C:/Users/shareef01/AndroidStudioProjects/notelikeus/build.gradle.kts)
- Add `org.jetbrains.compose` and `org.jetbrains.kotlin.multiplatform` plugins.

---

### 2. Multiplatform Module Structure
We will refactor the project into a structure that shares as much code as possible.

#### [NEW] `composeApp` Module
This will be the main multiplatform module containing:
- `commonMain`: Shared UI (Compose) and ViewModels.
- `androidMain`: Android-specific entry point and implementations.
- `desktopMain`: Windows/Desktop-specific entry point and implementations.

#### [DELETE] [app](file:///C:/Users/shareef01/AndroidStudioProjects/notelikeus/app)
The existing `:app` module logic will be merged into `composeApp`.

---

### 3. Shared Business Logic & Data
Move models, repositories, and database definitions to `commonMain`.

#### [MODIFY] [Note.kt](file:///C:/Users/shareef01/AndroidStudioProjects/notelikeus/app/src/main/java/com/aus/notelikeus/domain/model/Note.kt) -> `composeApp/src/commonMain/kotlin/.../Note.kt`
#### [MODIFY] [NoteRepository.kt](file:///C:/Users/shareef01/AndroidStudioProjects/notelikeus/app/src/main/java/com/aus/notelikeus/domain/repository/NoteRepository.kt) -> `composeApp/src/commonMain/kotlin/.../NoteRepository.kt`

---

### 4. Windows-Specific Implementations
Add the desktop entry point and handle Windows-specific features.

#### [NEW] `desktopMain/kotlin/.../main.kt`
The `main` function for the Windows application.

#### [NEW] Windows Hello / Biometrics Implementation
Since `androidx.biometric` is Android-only, we will define an `expect/actual` interface for authentication and use a Windows-compatible library or native API for Windows Hello.

---

### 5. Dependency Injection (Koin)
Set up Koin to handle dependency injection across platforms.

#### [NEW] `commonMain/kotlin/.../di/Koin.kt`
Define shared modules for Repositories, ViewModels, and Database.

## Verification Plan

### Automated Tests
- Run `./gradlew desktopRun` to launch the Windows app.
- Run `./gradlew connectedAndroidTest` to ensure Android functionality is preserved.
- Run common unit tests in `commonTest`.

### Manual Verification
- Verify note creation, editing, and archiving on Windows.
- Verify Cloud Sync (Firestore) works correctly on the desktop version.
- Check Windows-specific UI elements (title bar, window sizing).
