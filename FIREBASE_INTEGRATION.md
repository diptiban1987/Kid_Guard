# Firebase Integration Guide for AnonChat

This document provides detailed step-by-step instructions for setting up Firebase for the AnonChat Android application.  

---

## Prerequisites

- A Google account (Gmail) E-Mail - diptibannayak1987@gmail.com
- The `google-services.json` file downloaded from Firebase Console
- Android device connected for debugging

---

## Step 1: Create a Firebase Project

1. Open [Firebase Console](https://console.firebase.google.com/) in your web browser.
2. Log in with your Google account.
3. Click on **"Create a project"**.
4. Enter your **Project name** (e.g., `AnonChat`).
5. Click **Continue**.
6. On the Google Analytics page, **disable** the toggle for Google Analytics.
7. Click **Create project** and wait for the project to be created.

---

## Step 2: Register Your Android App

1. Once your project is ready, click the **Android icon** (or go to Project Settings > General > Add app > Android).
2. Enter the following **Android package name**:
   ```
   com.anonchat.app
   ```
3. Enter an **App nickname** (e.g., `AnonChat`).
4. The **Debug signing certificate SHA-1** is optional for now. You can skip it.
5. Click **Register app**.

---

## Step 3: Download the google-services.json File

1. After registering your app, you will see a button to **"Download google-services.json"**.
2. Click that button and save the file to a known location on your computer.
3. Copy this file into your Android project at:
   ```
   D:\CHAT APP\app\google-services.json
   ```
   Overwrite the existing placeholder file.

---

## Step 4: Enable Firebase Authentication (Anonymous Sign-In)

1. In the Firebase Console sidebar, go to **Build > Authentication**.
2. Click **"Get started"**.
3. Navigate to the **Sign-in method** tab.
4. Find **Anonymous** in the list and toggle it to **Enable**.
5. Click **Save**.

> This allows users to sign in without needing an email or password.

---

## Step 5: Set Up Cloud Firestore Database

1. In the Firebase Console sidebar, go to **Build > Firestore Database**.
2. Click **"Create database"**.
3. Choose **"Start in test mode"** (or production rules if you prefer).
4. Click **Next**.
5. Select a **Cloud Firestore location** close to your region (e.g., `us-central`, `europe-west`).
6. Click **Enable**.

> **Note:** Test mode allows all reads and writes for 30 days. Remember to update your security rules before production.

---

## Step 6: Set Up Firebase Storage (Optional, for images)

1. In the Firebase Console sidebar, go to **Build > Storage**.
2. Click **"Get started"**.
3. Choose **"Start in test mode"**.
4. Click **Next** and then **Done**.

> Storage is used in AnonChat for sharing images in chats.

---

## Step 7: Rebuild and Install the App

Open a terminal in the project root and run:

```bash
# Set environment variables (Windows PowerShell example)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "$PWD\android-sdk"

# Build the debug APK
.\gradlew.bat assembleDebug

# Install on the connected device
adb install -r -d "app\build\outputs\apk\debug\app-debug.apk"
```

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| **"API key not available"** | Ensure you replaced `app/google-services.json` with the real file from Firebase Console. |
| **Build fails with license error** | Run `sdkmanager.bat --licenses` and accept all licenses. |
| **App crashes on launch** | Check that Firebase Authentication and Firestore are enabled in the Firebase Console. |
| **Cannot connect to Firebase** | Verify your internet connection and that the `google-services.json` values match your Firebase project. |

---

## Firebase Services Used in AnonChat

| Service | Purpose |
|---------|---------|
| **Firebase Authentication** | Anonymous sign-in for users |
| **Cloud Firestore** | Storing messages, users, and chat data |
| **Firebase Storage** | Uploading and sharing images |
| **Firebase Cloud Messaging (FC blower)** | Push notifications for new messages |

---

## Important Notes for Production

- **Security Rules**: Before publishing to the Play Store, update your Firestore and Storage security rules. The default test mode allows anyone to read/write.
- **SHA-1 Certificate**: For production, add your release SHA-1 certificate in Firebase project settings.
- **API Key Restrictions**: Consider restricting your API key in Google Cloud Console to prevent unauthorized usage.

---

## Next Steps

1. Follow the steps above to create and configure your Firebase project.
2. Download the `google-services.json` file.
3. Replace the existing file in the project.
4. Rebuild and reinstall the app using the commands in **Step 7**.

Once completed, the AnonChat app will be fully functional with real-time chat, user profiles, and image sharing!
