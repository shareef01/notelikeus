# Notelikeus

A robust, full-stack notes ecosystem engineered for high performance, privacy, and seamless cross-platform synchronization. Built by **shareef01**, Notelikeus demonstrates advanced proficiency in modern Android development (Jetpack Compose) and scalable web architectures (React PWA), synchronized via a real-time Firebase backbone.

## 🌟 Technical Highlights

- **Native Android Engineering**: Leveraging the full Jetpack suite (Compose, Hilt, Room, WorkManager, Glance) to deliver a fluid, 60fps user experience.
- **Privacy-Centric Architecture**: 
    - Implemented **SQLCipher** for military-grade at-rest encryption of the local SQLite database.
    - Integrated **Biometric Auth** and custom secure-gate logic for sensitive data protection.
- **Distributed System Sync**: 
    - Designed an **offline-first** synchronization engine using Firestore's real-time listeners.
    - Conflict resolution strategy optimized for low-latency updates across mobile and web clients.
- **Enterprise-Grade UI/UX**: 
    - Design system built on **Material 3**, featuring dynamic color support and adaptive layouts for foldable and large-screen devices.
    - Custom **Rich Text Engine** supporting Markdown-lite syntax with a high-performance WYSIWYG editor.

---

## 🛠 Engineering Stack

### Android (Mobile)
- **Framework**: Jetpack Compose (Material 3)
- **Architecture**: Clean Architecture + MVVM + Repository Pattern
- **DI**: Hilt (Dagger)
- **Persistence**: Room (SQLCipher encrypted)
- **Async**: Kotlin Coroutines & Flow
- **Background**: WorkManager (Intelligent Sync Scheduling)
- **Widgets**: Jetpack Glance (Remote Views via Compose)

### Web (PWA)
- **Stack**: React 18 + TypeScript + Vite
- **Styling**: Tailwind CSS (Optimized for performance and rapid iteration)
- **State**: Zustand (Lightweight, atomic state management)
- **PWA**: Service Workers for full offline capability and background installation prompts.

### Cloud & DevOps
- **Backend**: Firebase (Auth, Firestore, Hosting)
- **CI/CD**: GitHub Actions (Automated unit testing, linting, and deployment)
- **Analytics**: Designed for Zero-Tracking (User privacy focus)

---

## 📐 Architecture & Design Decisions

### Clean Architecture Implementation
The project is strictly divided into three layers to ensure maintainability and testability:
1. **Data Layer**: Handles all external data sources (Room, Firestore, Preferences DataStore). Implements the Repository pattern to abstract data origin from the rest of the app.
2. **Domain Layer**: Contains the core business logic and models. It is a pure Kotlin module, facilitating unit testing without Android dependencies.
3. **UI Layer**: A reactive UI built with Compose, driven by ViewModels that expose state via `StateFlow` and handle events through a structured intent system.

### Optimized Synchronization
To keep the app free-tier friendly (Firebase Spark), the sync engine was optimized to minimize document reads/writes. It utilizes a version-tracking mechanism that only pushes deltas, significantly reducing bandwidth and cloud costs.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug+
- JDK 17
- Node.js (LTS)

### Build
```bash
# Android
./gradlew :app:assembleDebug

# Web
cd web && npm install && npm run dev
```

---

## 🔒 Security Posture
Notelikeus is built on the principle of data sovereignty. Every design decision—from choosing **SQLCipher** over standard Room to implementing **Google Sign-In** for direct user-to-cloud communication—was made to ensure that the user, and only the user, has access to their data.

---
Developed with ❤️ by [shareef01](https://github.com/shareef01)
