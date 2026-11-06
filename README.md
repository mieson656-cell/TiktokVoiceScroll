# Голосовой TikTok (VoiceScroll)
Kotlin, Vosk (офлайн, vosk-model-small-ru-0.22), AccessibilityService, minSdk 29, arm64.
Сборка: загрузить проект в GitHub -> Actions -> Build APK -> артефакт VoiceScroll-debug-apk.
Локально: скачать модель в app/src/main/assets/model, затем `gradle :app:assembleDebug` (JDK 17, Android SDK 35).
