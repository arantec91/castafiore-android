# Castafiore — Resumen del Repositorio

## Información General
- **Nombre del proyecto**: Castafiore
- **Módulos**: `:app` (Aplicación Android)
- **Namespace / ApplicationId**: `com.arantec.castafiore`
- **Kotlin**: 2.0.21
- **AGP (Android Gradle Plugin)**: 8.12.3
- **Java/Kotlin JVM target**: 11
- **Build features**: `viewBinding = true`
- **Plugins**:
  - **com.android.application** (via catálogo de versiones)
  - **org.jetbrains.kotlin.android** (via catálogo de versiones)
  - **kotlin-parcelize**
  - **androidx.navigation.safeargs.kotlin** (2.9.3)
  - **kotlin-kapt**

## Configuración Android (app/build.gradle.kts)
- **compileSdk**: 36
- **targetSdk**: 36
- **minSdk**: 30
- **versionCode**: 105
- **versionName**: 4.1.2
- **Firma (release)**: definida con `signingConfigs.release` (keystore en `app/../keystore.jks`).
  - Nota: evita exponer credenciales en repos públicos.
- **Optimización**: `isMinifyEnabled = false` (ProGuard configurado, no minificado en release)

## Dependencias Clave
- **AndroidX Core/AppCompat/Material**:
  - `androidx.core:core-ktx:${versions.coreKtx}`
  - `androidx.appcompat:appcompat:${versions.appcompat}`
  - `com.google.android.material:material:${versions.material}` (pin fijo por `resolutionStrategy` + `constraints`)
- **UI/UX**:
  - ConstraintLayout 2.1.4
  - RecyclerView 1.3.2
  - ViewPager2 1.0.0
  - Palette-ktx 1.0.0
  - Shimmer 0.5.0
- **Navigation**:
  - `androidx.navigation:navigation-fragment-ktx` 2.9.3
  - `androidx.navigation:navigation-ui-ktx` 2.9.3
  - Plugin Safe Args 2.9.3
- **Lifecycle**:
  - lifecycle-process, lifecycle-common, lifecycle-viewmodel-ktx, lifecycle-livedata-ktx, lifecycle-runtime-ktx (2.7.0)
- **Red/Networking**:
  - Retrofit 2.9.0 (con Gson converter)
  - OkHttp/Interceptor 4.12.0
- **Multimedia**:
  - ExoPlayer 2.19.1
  - `androidx.media:media:1.7.0` (MediaStyle)
- **Work**:
  - WorkManager `androidx.work:work-runtime-ktx:2.9.0`
- **Imágenes**:
  - Glide 4.16.0 (+ kapt compiler y okhttp3-integration)
- **Concurrencia**:
  - Kotlin Coroutines Android 1.8.1
- **Testing**:
  - JUnit 4.13.2
  - AndroidX JUnit 1.3.0
  - Espresso 3.7.0

## Manifest (app/src/main/AndroidManifest.xml)
- **Permisos**:
  - INTERNET
  - FOREGROUND_SERVICE_MEDIA_PLAYBACK
  - FOREGROUND_SERVICE_DATA_SYNC
  - WAKE_LOCK
  - POST_NOTIFICATIONS
- **Ajustes de app**:
  - `usesCleartextTraffic = true` (útil en dev; revisar antes de publicar)
  - `theme = @style/Theme.Castafiore`
- **Activities**:
  - `.ui.activities.SplashActivity` (launcher)
  - `.ui.activities.SetupActivity`
  - `.ui.activities.MainActivity`
  - `.ui.activities.PlayerActivity` (tema Player, parent MainActivity)
  - `.ui.activities.QueueActivity` (tema Queue)
  - `.ui.activities.LyricsActivity`
- **Servicios**:
  - `.service.MusicService` (mediaPlayback)
  - `androidx.work.impl.foreground.SystemForegroundService` (dataSync)
- **Receivers**:
  - `androidx.media.session.MediaButtonReceiver` (MEDIA_BUTTON)

## Catálogo de Versiones (gradle/libs.versions.toml)
- **Material**: 1.12.0 (pinned)
- **Navigation**: 2.9.3 (fragment/ui y plugin Safe Args)
- **Otros**: core-ktx 1.16.0, appcompat 1.7.1, espresso 3.7.0, junit 4.13.2

## Archivos/Recursos Relevantes
- Layout abierto: `app/src/main/res/layout/item_search_result.xml`
  - **Descripción**: tarjeta con `MaterialCardView`, imagen (`ShapeableImageView`), título/subtítulo, botones de favorito/más (ocultos por defecto), y fila de acciones opcional (`TopSongs` y `Radio`).
- Scripts/Docs útiles en raíz:
  - `build_release_apk.bat`
  - `build_log.txt`
  - `release_apk_instructions.md`, `release_apk_summary.md`
  - `shuffle_fix_summary.md`, `reproduce_shuffle_issue.md`, `shared_preferences_conflict_fix.md`

## Construcción y Ejecución
1. **Android Studio**:
   - Build > Make Project
   - Run en un dispositivo/emulador con API >= 30
   - Para release: Build > Generate Signed Bundle/APK (usa `signingConfigs.release` ya definido)
2. **Línea de comandos (Windows)**:
   - Preferible usar Android Studio o el script `build_release_apk.bat` incluido.
   - Si cuentas con wrapper (`gradlew.bat`), comandos típicos serían: `./gradlew assembleDebug` o `./gradlew assembleRelease` (verificar existencia del wrapper en el repo).

## Notas y Recomendaciones
- Mantener el pin de Material para evitar incompatibilidades con Navigation u otras deps que soliciten versiones no publicadas.
- Revisar `usesCleartextTraffic` antes de publicar en producción.
- Las credenciales de firma están definidas en el gradle; considera externalizarlas a `gradle.properties` locales o variables de entorno para mayor seguridad.
- `minSdk = 30`: asegúrate de probar en API 30+.

---
Este archivo es generado para facilitar el soporte contextual. Si necesitas que añada secciones (por ejemplo, estructura de paquetes Kotlin, flujos de navegación, o puntos de entrada de DI), indícalo y lo amplío.