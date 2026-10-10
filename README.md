# VaultNote

**Private notes, checklists and Kanban boards for Android. Encrypted on the phone, with optional Google Drive sync that Google cannot read.**

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/UI-XML%20Views%20%2B%20Material%203-6750A4)

VaultNote keeps every note encrypted with a random vault key that only you can unlock. The app works fully offline. If you turn on sync, it stores only unreadable, encrypted files in a hidden folder of your own Google Drive, so neither Google nor the developer can read your notes.


<!-- Add screenshots here, for example:
<p align="center">
  <img src="docs/screenshots/list.png" width="22%"/>
  <img src="docs/screenshots/editor.png" width="22%"/>
  <img src="docs/screenshots/board.png" width="22%"/>
  <img src="docs/screenshots/settings.png" width="22%"/>
</p>
-->

## Features

| Area | What you get |
| --- | --- |
| **Notes** | Title and text, autosaved shortly after you stop typing |
| **Checklists** | Add, drag to reorder, tick items; progress shown on cards |
| **Kanban boards** | Custom boards and columns, drag cards between columns, column colors, notes and checklists as cards |
| **Images** | Up to 10 per note, compressed and encrypted, synced to Drive |
| **Tags and search** | Tag anything, filter by tag, full-text search |
| **Backlinks** | Type `[[Title]]` to link notes; tap to open; linked-from chips |
| **Reminders** | Date, time, repeat (none, daily, weekly); survive reboot and app update |
| **Today view** | Overdue, today and upcoming reminders in one screen |
| **Organize** | Colors, pinning, archive, trash (kept 30 days), multi-select with Select all, move to or out of a board |
| **Locked notes** | Require biometrics each time the note is opened |
| **Appearance** | Grid or list view, light, dark, system and dynamic colors |
| **Cloud sync** | Optional Google Drive sync, restore on a new phone, three sign-out choices |

## Security design

- **One vault key (DEK):** random 256-bit key that encrypts every note and image. It exists in memory only while the app is unlocked.
- **Two ways to unlock it:** a key derived from your passphrase (PBKDF2-SHA256, 600,000 iterations) and a recovery key shown once at setup. Both wrap the same vault key.
- **Encryption:** AES-256-GCM. Blob layout is `version (1) || IV (12) || ciphertext + tag`. Each note and image uses its own ID as associated data.
- **Database:** Room on SQLCipher; its key is wrapped by an Android Keystore key.
- **App protection:** biometric unlock, auto-lock (immediately, 30 s or 5 min), screenshot and recents blocking, clipboard auto-clear for locked notes, auto-backup disabled.
- **Cloud:** only the `drive.appdata` scope. Drive holds ciphertext and random file names, nothing else.

**Important:** if you lose both your passphrase and your recovery key, your notes cannot be recovered by anyone.

## How sync works

Each item becomes one small encrypted file in the Drive app-data folder.

| File | Content |
| --- | --- |
| `<noteId>.vn` | One note, checklist or board: JSON, gzip, then AES-256-GCM (associated data = note ID) |
| `<imageId>.att` | One compressed JPEG, AES-256-GCM (associated data = image ID) |
| `keyring.bin` | The wrapped vault keys and key-check value (no plaintext keys) |

A sync pass uploads the keyring if changed, pulls remote changes, fetches missing images, pushes local changes, then cleans up unused images. If the same note changes on two devices while apart, the newer version wins and the older one is kept as a "(conflict copy)" note.

## Architecture

- Single `MainActivity` with the Navigation component and Fragments
- ViewModels expose `StateFlow` and `Flow` to the UI
- `NoteRepository` is the single write path (save, search index, tags, links, reminders, sync scheduling)
- Hilt for dependency injection (KSP), WorkManager for sync, AlarmManager for reminders

```
vaultnote
├── data/        repository, board operations, attachment store
│   └── db/      Room entities, DAOs, database, migrations
├── di/          Hilt modules
├── crypto/      AES-GCM, PBKDF2, recovery key, keyring, DB key provider
├── security/    app lock state machine
├── sync/        Drive API client, auth, payload, sync manager, worker
├── reminder/    scheduler, receiver, notifications
├── ui/          list, editor, board, today, security, settings
└── util/        colors, theme, biometrics, clipboard, screen guard
```

Boards are a third item type that reuses the notes table: a board stores its columns as JSON, and a card is a normal note or checklist with a board ID, column ID and position.

## Tech stack

| Purpose | Library |
| --- | --- |
| Database | Room 2.7.2, SQLCipher for Android 4.9.0 |
| Dependency injection | Hilt 2.57.1 with KSP |
| UI | XML Views, Material 3, ViewBinding, Navigation 2.9.x |
| Async and lifecycle | Kotlin coroutines and Flow, Lifecycle 2.9.x |
| Serialization | kotlinx-serialization-json |
| Biometrics | androidx.biometric 1.1.0 |
| Google authorization | play-services-auth 21.6.0 |
| HTTP | OkHttp 4.12.0 |
| Background work | WorkManager 2.10.0 |

Requirements: Android 8.0 (API 26) or newer, Java 17.

## Getting started

### Build and run (no Google setup needed)

1. Clone the repository and open it in Android Studio.
2. Let Gradle sync, then run the `app` configuration on a device or emulator.

Everything except Google Drive sync works at this point.

### Enable Google Drive sync

Sync needs a Google Cloud project, because Google must know which app may ask for access.

1. Create a project in the [Google Cloud console](https://console.cloud.google.com/) and enable the **Google Drive API**.
2. Configure the **Google Auth Platform** (consent screen). Add yourself as a test user while it is in testing mode.
3. Add the scope `https://www.googleapis.com/auth/drive.appdata`.
4. Create an **Android OAuth client** with package name `com.vasanth.vaultnote` and the SHA-1 fingerprint of your signing key.
5. Get your debug SHA-1 with:

   ```bash
   ./gradlew signingReport
   ```

No client ID or secret is stored in the code. Google identifies the app by its package name and signing-certificate SHA-1. A release build uses a different key, so add its SHA-1 as another Android OAuth client.

## Using the app

1. **First run:** choose a passphrase, then save the recovery key shown once.
2. **Create:** tap + and choose note, checklist or board.
3. **Boards:** add your own columns, add cards, long-press and drag cards between columns.
4. **Select many:** long-press an item, tap others, then use the top bar or the ⋮ menu.
5. **Sync:** Settings, Cloud sync, tap the account row.
6. **New phone:** choose *Restore from Google Drive*, sign in, and unlock with your passphrase or recovery key.
7. **Sign out:** Settings has three choices: keep the cloud data, delete the cloud data, or erase everything here and in the cloud.

## Testing

The cryptography has JVM unit tests that run without a device:

```bash
./gradlew test
```

`CryptoTest` covers the encrypt and decrypt round trip, tamper detection, wrong key or associated data, a PBKDF2 test vector, and recovery key encoding. It also prints a cross-platform Base64 test vector (key bytes `00` to `1f`, IV bytes `64` to `6f`, associated data `note-1`) so another client, such as a future web app, can prove it uses identical crypto.

Sync, boards and images are verified by manual test runs on a phone and a second device.

## Privacy

VaultNote has no analytics, no developer server and no accounts of its own. Your notes leave the phone only if you turn on sync, and then only as encrypted files in your own Google Drive app folder.
