# Castafiore

Aplicación Android (Kotlin) para interactuar con un servidor Navidrome/Subsonic.

## Características (resumen)
- Reproducción de música desde servidor Navidrome
- Búsqueda de artistas, álbumes y canciones
- Marcado (star) y elementos favoritos
- Recuperación de canciones aleatorias y similares

## Estructura del proyecto
- `app/` Módulo principal Android
- `lrcview/` Módulo adicional (por ejemplo para letras / vistas personalizadas)
- `build.gradle.kts`, `settings.gradle.kts` Configuración de Gradle Kotlin DSL

## Requisitos
- Android Studio (Giraffe+ recomendado)
- JDK 17 (si el wrapper de Gradle lo requiere)
- Servidor Navidrome operativo para pruebas

## Comandos Gradle principales
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```
En Windows CMD:
```cmd
gradlew.bat assembleDebug
```

## Publicación (resumen rápido)
1. Ajustar `gradle.properties` y versión en `app/build.gradle.kts`.
2. Ejecutar script `build_release_apk.bat` o `gradlew.bat assembleRelease`.
3. El APK quedará en `app/build/outputs/apk/release/`.

## Seguridad
- El archivo `keystore.jks` está ignorado en `.gitignore` para no exponerse.
- No subir `local.properties` ni credenciales.

## Licencia
(Indica aquí la licencia que desees usar, por ejemplo MIT, Apache 2.0, etc.)

