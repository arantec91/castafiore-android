@echo off
echo ===================================================
echo Building Release APK for Castafiore
echo ===================================================
echo.
echo This script will build a signed release APK for the Castafiore app.
echo Make sure you have created a keystore file and updated the signing
echo configuration in app/build.gradle.kts as described in the
echo release_apk_instructions.md file.
echo.
echo Press any key to continue or Ctrl+C to cancel...
pause > nul

echo.
echo Building release APK...
call gradlew.bat assembleRelease

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ===================================================
    echo Build failed! Please check the error messages above.
    echo Make sure your keystore configuration is correct.
    echo See release_apk_instructions.md for more details.
    echo ===================================================
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ===================================================
echo Build successful!
echo.
echo Your signed APK is located at:
echo app\build\outputs\apk\release\app-release.apk
echo ===================================================
echo.
echo You can install this APK on your device or distribute it.
echo.
pause