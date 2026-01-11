# ClipboardSync Android Client

<div align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-blue.svg" alt="Language">
  <img src="https://img.shields.io/badge/Min%20SDK-24-orange.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/Target%20SDK-35-red.svg" alt="Target SDK">
  <img src="https://img.shields.io/badge/Version-1.0.0-brightgreen.svg" alt="Version">
</div>

## 📋 Overview

**ClipboardSync** is a multi-platform cloud clipboard synchronization solution that enables real-time clipboard content sharing across MacOS, Windows, Android, and iOS devices. This repository contains the Android client implementation.

### ✨ Key Features

- 🔄 **Real-time Synchronization**: Instant clipboard sync across all your devices
- 📱 **Multi-platform Support**: Works seamlessly with MacOS, Windows, iOS, and Android
- 🔒 **Secure**: End-to-end encryption and JWT-based authentication
- 📁 **Multiple Content Types**: Support for text, images, and files
- 🚀 **Background Service**: Continuous monitoring without user intervention
- 🔧 **Customizable**: Configurable file size limits and sync preferences

## 🏗️ Architecture

### Technology Stack

- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose with Material3 Design
- **Architecture**: MVVM with Dependency Injection (Hilt)
- **Networking**: Retrofit2 + OkHttp3 + WebSocket
- **Database**: Room Database
- **Security**: Android Security Crypto
- **Background Processing**: Foreground Services + Coroutines

### Project Structure

```
app/src/main/java/com/siw/clipboardsync/
├── data/                    # Data layer (models, repositories, database)
├── di/                      # Dependency injection modules
├── receiver/                # Broadcast receivers (boot, etc.)
├── service/                 # Background services
├── ui/                      # UI layer (Compose screens, viewmodels)
└── utils/                   # Utility classes and helpers
```

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog | 2023.1.1 or later
- Android SDK 24 (Android 7.0) or higher
- Kotlin 2.0.21
- Java 11

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/clipboardsync-android.git
   cd clipboardsync-android
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an existing project"
   - Navigate to the cloned directory and select it

3. **Sync dependencies**
   ```bash
   ./gradlew build
   ```

4. **Configure backend endpoints** (Optional)
   - Development: `http://localhost:8080`
   - Production: `https://clip.imiss.me`
   - WebSocket: `wss://clip.imiss.me/ws/sync`

5. **Run the app**
   - Connect an Android device or start an emulator
   - Click "Run" in Android Studio or use:
   ```bash
   ./gradlew installDebug
   ```

## 📱 Features

### Core Functionality

- **Clipboard Monitoring**: Automatically detects clipboard changes
- **Real-time Sync**: WebSocket-based instant synchronization
- **Device Management**: Register and manage up to 5 devices per account
- **Content Types**: Support for text, images, and files (up to 10MB)
- **Background Operation**: Runs continuously in the background
- **Auto-start**: Automatically starts on device boot

### Security Features

- **JWT Authentication**: Secure token-based authentication
- **Device Registration**: Unique device identification and management
- **Encrypted Storage**: Secure local data storage using Android Security Crypto
- **Permission Management**: Proper handling of clipboard and notification permissions

### User Experience

- **Material3 Design**: Modern, intuitive user interface
- **System Integration**: Status bar notifications and system tray integration
- **Minimal Resource Usage**: Optimized for battery and performance
- **Customizable Settings**: User-configurable sync preferences

## 🔧 Configuration

### Backend Configuration

The app connects to the ClipboardSync backend API. Configure the endpoints in your build configuration:

- **Development**: `http://localhost:8080`
- **Production**: `https://clip.imiss.me`
- **WebSocket**: `wss://clip.imiss.me/ws/sync`

### Permissions

The app requires the following permissions:

- `INTERNET` - Network communication
- `ACCESS_NETWORK_STATE` - Network status monitoring
- `FOREGROUND_SERVICE` - Background clipboard monitoring
- `FOREGROUND_SERVICE_DATA_SYNC` - Data synchronization service
- `POST_NOTIFICATIONS` - System notifications
- `RECEIVE_BOOT_COMPLETED` - Auto-start on device boot
- `WAKE_LOCK` - Keep service running

## 🛠️ Development

### Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
./gradlew connectedAndroidTest

# Code quality checks
./gradlew ktlintCheck
```

### Project Configuration

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 14)
- **Compile SDK**: 35
- **Java Version**: 11
- **Kotlin Version**: 2.0.21

### Dependencies

Key dependencies include:

- **Jetpack Compose**: Modern UI toolkit
- **Hilt**: Dependency injection
- **Retrofit2**: HTTP client
- **Room**: Local database
- **WebSocket**: Real-time communication
- **Security Crypto**: Encrypted storage

See `gradle/libs.versions.toml` for complete dependency list.

## 🧪 Testing

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest

# Test coverage
./gradlew jacocoTestReport
```

### Test Structure

- `app/src/test/` - Unit tests
- `app/src/androidTest/` - Instrumented tests
- Test frameworks: JUnit 4, Mockito, Espresso

## 📚 API Documentation

The Android client integrates with the ClipboardSync backend API. Key endpoints include:

- **Authentication**: `/api/v1/auth/login`, `/api/v1/auth/register`
- **Device Management**: `/api/v1/devices`
- **Clipboard Sync**: `/api/v1/clipboard`
- **WebSocket**: `/ws/sync` for real-time updates

For complete API documentation, see [API_TEST_RESULTS.md](doc/API_TEST_RESULTS.md).

## 🔒 Security

### Data Protection

- **Local Storage**: Encrypted using Android Security Crypto
- **Network Communication**: HTTPS/WSS encryption
- **Authentication**: JWT tokens with refresh mechanism
- **Device Verification**: Unique device ID and registration

### Privacy

- **Minimal Data Collection**: Only necessary clipboard content
- **User Control**: Configurable sync settings and content filtering
- **Secure Transmission**: End-to-end encryption for sensitive content

## 🤝 Contributing

We welcome contributions! Please follow these steps:

1. **Fork the repository**
2. **Create a feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes**
4. **Add tests** for new functionality
5. **Run quality checks**
   ```bash
   ./gradlew ktlintCheck test
   ```
6. **Commit your changes**
   ```bash
   git commit -m "Add: your feature description"
   ```
7. **Push to your fork**
   ```bash
   git push origin feature/your-feature-name
   ```
8. **Create a Pull Request**

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use ktlint for code formatting
- Write meaningful commit messages
- Add documentation for public APIs

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🌟 Related Projects

- **Backend API**: [ClipboardSync Server](https://github.com/yourusername/clipboardsync-server)
- **MacOS Client**: [ClipboardSync macOS](https://github.com/yourusername/clipboardsync-macos)
- **Windows Client**: [ClipboardSync Windows](https://github.com/yourusername/clipboardsync-windows)
- **iOS Client**: [ClipboardSync iOS](https://github.com/yourusername/clipboardsync-ios)

## 📞 Support

- **Documentation**: [Project Documentation](doc/)
- **Issues**: [GitHub Issues](https://github.com/yourusername/clipboardsync-android/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/clipboardsync-android/discussions)

## 🎯 Roadmap

### Current Status (v1.0.0)
- ✅ Basic project structure
- ✅ Jetpack Compose UI foundation
- ✅ Material3 theming
- ✅ Dependency injection setup
- ✅ Network layer foundation

### Upcoming Features
- 🔄 Clipboard monitoring service
- 🔄 Authentication implementation
- 🔄 Real-time WebSocket communication
- 🔄 Settings and preferences UI
- 🔄 File upload/download support
- 🔄 Performance optimizations

---

<div align="center">
  <p>Made with ❤️ for seamless cross-platform clipboard synchronization</p>
  <p>
    <a href="#clipboardsync-android-client">Back to top</a>
  </p>
</div>