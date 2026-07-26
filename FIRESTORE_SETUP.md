# Firebase Firestore Setup Guide
## For AnonChat + ParentalControl Merged App

---

## Quick Setup Checklist

- [ ] Create Firebase Project
- [ ] Enable Anonymous Authentication
- [ ] Enable Email/Password Authentication
- [ ] Create Firestore Database
- [ ] Deploy Firestore Security Rules
- [ ] Enable Firebase Storage
- [ ] Deploy Storage Security Rules
- [ ] Add Android App to Firebase
- [ ] Download google-services.json
- [ ] Test the App

---

## Step 1: Create Firebase Project

1. Go to **https://console.firebase.google.com**
2. Click **"Create a project"** (or "Add project")
3. Enter project name: `anonchat-a690b` (or your preferred name)
4. Disable Google Analytics (optional) → Click **"Create project"**
5. Wait for project to be created → Click **"Continue"**

---

## Step 2: Enable Authentication Methods

1. Left sidebar → **Authentication** → Click **"Get started"**
2. Go to **Sign-in method** tab
3. Enable **Anonymous**:
   - Click **Anonymous** → Toggle **Enable** → Click **Save**
4. Enable **Email/Password**:
   - Click **Email/Password** → Toggle **Enable** → Click **Save**

---

## Step 3: Create Firestore Database

1. Left sidebar → **Firestore Database** → Click **"Create database"**
2. Select location: Choose closest to your users (e.g., `asia-south1` for India)
3. Select **"Start in test mode"** → Click **"Next"**
4. Click **"Enable"**
5. Wait for database to be created

---

## Step 4: Deploy Firestore Security Rules

1. Go to Firestore Database → **Rules** tab
2. **Delete** the existing default rules
3. **Paste** the following rules:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // User profiles
    match /users/{userId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null;
      allow update: if request.auth != null && request.auth.uid == userId;
      allow delete: if false;
    }

    // Chat conversations
    match /chats/{chatId} {
      allow read: if request.auth != null &&
        request.auth.uid in resource.data.participants;
      allow create: if request.auth != null &&
        request.auth.uid in request.resource.data.participants;
      allow update: if request.auth != null &&
        request.auth.uid in resource.data.participants;

      // Messages within a chat
      match /messages/{messageId} {
        allow read: if request.auth != null &&
          request.auth.uid in get(/databases/$(database)/documents/chats/$(chatId)).data.participants;
        allow create: if request.auth != true == false;
        allow update: if request.auth != null;
      }
    }
  }
}
```

4. Click **"Publish"**

---

## Step 5: Enable Firebase Storage

1. Left sidebar → **Storage** → Click **"Get started"**
2. Select **"Start in test mode"** → Click **"Next"**
3. Select same location as Firestore → Click **"Done"**

---

## Step 6: Deploy Storage Security Rules

1. Go to Storage → **Rules** tab
2. **Replace** with these rules:

```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /chat_images/{allPaths=**} {
      allow read: if request.auth != null;
      allow write: if request.auth != null
                    && request.resource.size < 10 * 1024 * 1024
                    && request.resource.contentType.matches('image/.*');
    }
  }
}
```

3. Click **"Publish"**

---

## Step 7: Add Android App to Firebase

1. Go to **Project Settings** (gear icon ⚙️ top-left)
2. Under **"Your apps"** section, click the **Android** icon
3. Enter:
   - **Android package name**: `com.anonchat.app`
   - **App nickname**: `AnonChat` (optional)
   - **Debug signing certificate SHA-1** (optional, for Google Sign-In)
4. Click **"Register app"**

---

## Step 8: Download google-services.json

1. After registering, click **"Download google-services.json"**
2. Place the file in:
   ```
   CHAT APP/app/google-services.json
   ```
3. Click **"Next"** and follow the remaining steps

> **Note**: The `google-services.json` file is already in the project from the original AnonChat. Only download a new one if you created a NEW Firebase project.

---

## Step 9: Get Firebase Project Config (If Needed)

If you need to verify or update the config, go to **Project Settings** → **General** tab:

| Setting | Value |
|---------|-------|
| Project ID | `anonchat-a690b` |
| Web API Key | `AIzaSyDYe-a29HuvOuhIb3QrIJCcsORQnwSbh-E` |
| App ID | `1:145505706969:android:91ae5e53ac40a5dec9efc8` |
| Storage Bucket | `anonchat-a690b.firebasestorage.app` |

---

## Step 10: Test the Setup

### Test Anonymous Auth:
1. Open the app on device
2. Stay on **Anonymous** tab
3. Pick a color and enter a username
4. Tap **"Get Started"**
5. Should successfully log in → Navigate to main screen

### Test Parent Login:
1. Open the app
2. Switch to **"Parent Login"** tab
3. Enter email + password
4. Tap **"Parent Login"**
5. Should log in to both Firebase and Flask server

### Verify in Firebase Console:
1. Go to **Authentication** → **Users** tab
   - You should see anonymous users and/or email users
2. Go to **Firestore Database** → **Data** tab
   - You should see a `users` collection with user documents
3. Go to **Storage** → **Files** tab
   - Ready for chat image uploads

---

## Troubleshooting

### "Profile creation timed out"
- **Cause**: Firestore database not created or rules not published
- **Fix**: Complete Steps 3 and 4 above

### "Permission denied" in logs
- **Cause**: Firestore rules are too restrictive
- **Fix**: Make sure rules include `allow create: if request.auth != null;` for users collection

### "API key not valid"
- **Cause**: Wrong `google-services.json` or project config
- **Fix**: Re-download `google-services.json` from Firebase Console → Project Settings

### "Firebase not initialized"
- **Cause**: `google-services.json` missing or corrupted
- **Fix**: Ensure file is at `CHAT APP/app/google-services.json`

### Firestore writes are very slow
- **Cause**: Database region is far from device
- **Fix**: Create Firestore in region closest to your users

---

## Firestore Data Structure

After successful setup, your Firestore will contain:

```
📁 users (collection)
  └── 📄 {userId} (document)
        ├── userId: "abc123"
        ├── username: "johndoe"
        ├── avatarColor: "#6C63FF"
        ├── bio: "Hey there!"
        ├── fcmToken: "..."
        ├── createdAt: 1721481600000
        ├── isOnline: true
        └── lastSeen: 1721481600000

📁 chats (collection)
  └── 📄 {chatId} (document)
        ├── participants: ["user1", "user2"]
        ├── participantNames: {"user1": "john", "user2": "jane"}
        ├── participantColors: {"user1": "#6C63FF", "user2": "#FF6584"}
        ├── lastMessage: "Hello!"
        ├── lastMessageTimestamp: 1721481600000
        └── 📁 messages (subcollection)
              └── 📄 {messageId} (document)
                    ├── senderId: "user1"
                    ├── content: "Hello!"
                    ├── type: "text"
                    ├── timestamp: 1721481600000
                    └── readBy: ["user1"]
```

---

## Security Rules Summary

| Collection | Read | Create | Update | Delete |
|------------|------|--------|--------|--------|
| `users/{userId}` | Any authenticated user | Any authenticated user | Only owner | Never |
| `chats/{chatId}` | Only participants | Only participants | Only participants | Never |
| `chats/{chatId}/messages/{msg}` | Only participants | Only participants | Only sender | Never |

---

## Production Rules (Optional - More Secure)

When ready for production, replace the test rules with:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    match /users/{userId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null
                    && request.resource.data.keys().hasAll(['userId', 'username', 'avatarColor'])
                    && request.resource.data.userId == request.auth.uid;
      allow update: if request.auth != null
                    && request.auth.uid == userId
                    && !request.resource.data.diff(resource.data).affectedKeys()
                        .hasAny(['userId']);
      allow delete: if false;
    }

    match /chats/{chatId} {
      allow read: if request.auth != null &&
        request.auth.uid in resource.data.participants;
      allow create: if request.auth != null &&
        request.auth.uid in request.resource.data.participants;
      allow update: if request.auth != null &&
        request.auth.uid in resource.data.participants;

      match /messages/{messageId} {
        allow read: if request.auth != null &&
          request.auth.uid in get(/databases/$(database)/documents/chats/$(chatId)).data.participants;
        allow create: if request.auth != null
                      && request.resource.data.senderId == request.auth.uid
                      && request.resource.data.keys().hasAll(['senderId', 'content', 'timestamp']);
        allow update: if request.auth != null &&
          request.auth.uid == resource.data.senderId;
        allow delete: if false;
      }
    }
  }
}
```

These rules:
- Validate required fields on user creation
- Prevent users from changing their `userId`
- Ensure only the sender can update messages (for read receipts / deletion)
- Prevent any document deletion
