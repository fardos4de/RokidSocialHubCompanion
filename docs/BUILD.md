# Build and APK

GitHub Actions builds the Android debug APK on every push to `main` using `.github/workflows/android-debug.yml`.

The build uses Java 17, Android SDK 35, Build Tools 35.0.0, Gradle 8.9, and Android Gradle Plugin 8.7.3.

After a successful run, download the `RokidSocialHubCompanion-debug` workflow artifact and extract `app-debug.apk`.

This APK is a development/debug build intended for phone testing of App 1 before the Rokid glasses and AIUI layers are added.
