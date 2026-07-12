# Notelikeus

A sophisticated, cross-platform notes application for **Android** and **Web (PWA)**, inspired by Google Keep but enhanced with advanced privacy and synchronization capabilities. Notelikeus is architected for speed, security, and a seamless multi-device experience.

## 🚀 Key Features

- **Advanced Note Management**: Support for titles, rich text (Markdown-based), dynamic checklists, and hierarchical labels.
- **Smart Editor**: WYSIWYG editing with automatic bulleting, list continuation, and real-time formatting.
- **Privacy First**: 
    - **Local Encryption**: Android data is secured using a **SQLCipher-encrypted Room database**.
    - **Biometric Security**: Per-note biometric lock and optional app-wide authentication gate.
- **Cloud Synchronization**: 
    - Optional real-time sync with **Firebase Firestore**.
    - Identity management via **Google Sign-In**.
    - Offline-first architecture: Changes are queued and synced automatically when a connection is restored.
- **Native Android Integration**:
    - **Glance Widgets**: Interactive home screen widgets for pinned and recent notes.
    - **System Reminders**: Precise date/time notifications integrated with the Android Alarm Manager.
- **Rich Organization**: 
    - Pinned notes, archiving, and multi-stage trash recovery.
    - Advanced search with history and real-time filtering by color and label.
    - Date-grouped layouts (Today, Yesterday, Last Week).
- **Interoperability**: Robust JSON-based backup and import/export system.

---

## 🛠 Technical Stack

### Android (Native)
| Component | Technology |
|-----------|------------|
| **UI Framework** | Jetpack Compose (Material 3) |
| **Architecture** | Clean Architecture with MVVM |
| **Dependency Injection** | Hilt |
| **Local Persistence** | Room Persistence Library + SQLCipher |
| **Background Tasks** | WorkManager (Sync) |
| **Networking/Cloud** | Firebase Auth (Google Sign-In) + Firestore |
| **App Widgets** | Jetpack Glance |
| **Preferences** | Jetpack DataStore |
| **Testing** | JUnit 4, Turbine, MockK, Robolectric, Compose UI Test |

### Web (PWA)
| Component | Technology |
|-----------|------------|
| **Framework** | React + TypeScript |
| **Build Tool** | Vite |
| **Styling** | Tailwind CSS |
| **State Management** | Zustand |
| **Persistence** | LocalStorage / IndexedDB |
| **PWA Features** | Service Workers (Offline support, Push Notifications) |
| **Deployment** | Firebase Hosting |

---

## 🏗 Project Architecture

### Android Module Structure
The Android application follows a modular approach based on Clean Architecture principles:

- **`data/`**: Implementation of repositories, DAO interfaces, database migrations, and external service adapters (Firebase, Backup, Reminders).
- **`domain/`**: Pure Kotlin layer containing business logic models and repository interfaces.
- **`di/`**: Hilt modules for dependency management.
- **`ui/`**: 
    - `main/`: Grid/List visualization, filtering logic, and settings.
    - `editor/`: Advanced rich-text editing engine and reminder scheduling.
    - `navigation/`: Type-safe navigation graph using Compose Navigation.
    - `theme/`: Material 3 design system implementation.
    - `widget/`: Glance-based widget implementation.

### Web Architecture
The PWA located in [`web/`](web/) is a modern React application optimized for performance and offline reliability:

- **Service Workers**: Handles resource caching and background sync notifications.
- **Real-time Sync**: Uses Firestore's `onSnapshot` for instantaneous cross-device updates.
- **Responsive Design**: Mobile-first UI that adapts seamlessly to desktop environments.

---

## 🛠 Development & Build

### Requirements
- Android Studio Ladybug or newer.
- JDK 17+.
- Node.js & npm (for web/scripts).

### Build Commands
```bash
# Build Android Debug APK
./gradlew :app:assembleDebug

# Run Android Unit Tests
./gradlew :app:testDebugUnitTest

# Web Development (PWA)
cd web
npm install
npm run dev

# Deploy Web & Firestore Rules
firebase deploy --only hosting,firestore:rules
```

---

## 🔒 Security & Data
Notelikeus prioritizes user data sovereignty. In **Guest Mode**, all data remains strictly on-device. When **Cloud Sync** is enabled, data is transmitted over encrypted channels directly to the user's private Firebase instance. No third-party tracking or analytics SDKs are integrated.

## 📝 License
Private project — All rights reserved.
