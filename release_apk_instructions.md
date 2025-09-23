# Instructions for Creating a Release APK for Castafiore

This document provides step-by-step instructions for creating a signed release APK for the Castafiore Android application.

## Step 1: Create a Keystore File

If you don't already have a keystore file, you need to create one. Open a terminal/command prompt and run the following command:

```
keytool -genkey -v -keystore castafiore.keystore -alias castafiore -keyalg RSA -keysize 2048 -validity 10000
```

You will be prompted to:
- Enter a password for the keystore
- Enter your name, organization, and location information
- Enter a password for the key (you can use the same password as the keystore)

This will create a file named `castafiore.keystore` in the current directory. Move this file to the root of your project directory.

## Step 2: Update the Signing Configuration

Open the `app/build.gradle.kts` file and update the signing configuration with your actual keystore information:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../castafiore.keystore") // Make sure the path is correct
        storePassword = "your_keystore_password" // Replace with your actual password
        keyAlias = "castafiore" // Replace if you used a different alias
        keyPassword = "your_key_password" // Replace with your actual password
    }
}
```

## Step 3: Build the Release APK

### Option 1: Using Android Studio

1. Open the project in Android Studio
2. Select `Build` > `Generate Signed Bundle / APK...` from the menu
3. Select `APK` and click `Next`
4. Fill in the keystore information (or use the existing keystore)
5. Select `release` as the build type and click `Finish`

The signed APK will be generated in the `app/build/outputs/apk/release/` directory.

### Option 2: Using Gradle Command Line

Run the following command from the project root directory:

```
./gradlew assembleRelease
```

On Windows, use:

```
gradlew.bat assembleRelease
```

The signed APK will be generated in the `app/build/outputs/apk/release/` directory.

## Step 4: Verify the APK

You can verify that the APK is signed correctly by running:

```
jarsigner -verify -verbose -certs app/build/outputs/apk/release/app-release.apk
```

## Security Note

Keep your keystore file and passwords secure. If you lose them, you won't be able to publish updates to your app under the same app identity.

## Additional Options

### Storing Keystore Information Securely

For better security, consider storing your keystore information in a separate properties file that is not committed to version control:

1. Create a file named `keystore.properties` in the project root with the following content:
```
storeFile=../castafiore.keystore
storePassword=your_keystore_password
keyAlias=castafiore
keyPassword=your_key_password
```

2. Update your `app/build.gradle.kts` to read from this file:
```kotlin
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = java.util.Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(java.io.FileInputStream(keystorePropertiesFile.absolutePath))
}

android {
    // ...
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
    // ...
}
```

3. Add `keystore.properties` to your `.gitignore` file to prevent it from being committed to version control.
