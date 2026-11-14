# NFC Tool (Android)

A self-contained NFC utility for the **Samsung Galaxy S8** (and any Android 7+ /
API 24+ phone with NFC). Read, write, erase, lock, and emulate NDEF tags.

## Features

| Tab | What it does |
|-----|--------------|
| **Read** | UID, tech list, tag type/chip, capacity, writability, and all NDEF records. |
| **Write** | Write a Text or URL/URI record. Formats blank tags automatically. |
| **Erase** | Overwrites the tag with an empty NDEF record. |
| **Lock** | Two modes — see below. |
| **Emulate** | Phone presents itself as a readable NDEF Type 4 tag (HCE). |

### Lock modes
- **Password lock (reversible)** — NTAG213/215/216 only. Sets a 32-bit password
  (PWD/PACK) and `AUTH0`/`ACCESS` so nobody can rewrite the tag. Undo it with the
  same password on the Lock tab (Arm unlock). Optionally require the password to
  read as well. The password is hashed (SHA-256) to derive PWD/PACK, so only you
  need to remember it — nothing is stored.
- **Permanent lock (irreversible)** — uses the tag's lock bits via
  `Ndef.makeReadOnly()`. Works on most tag types. **Cannot be undone** — the tag
  becomes read-only forever.

## Honest limits (read this)

- **Emulation is NDEF Type 4 only.** The phone answers as a readable NDEF tag.
  Android **cannot clone a tag's UID** and **cannot emulate a MIFARE Classic**
  card — the NFC controller owns the UID and the Crypto1 cipher isn't exposed to
  apps. iOS can't emulate tags at all. If you need "copy my building fob and
  replay it," that requires a **Proxmark3 / PN532 / Flipper Zero**, not a phone.
- **Password lock requires an NTAG21x** tag. Other chips report "unsupported."
- MIFARE Classic sector-key auth is **not** implemented (different scheme).

## Build & install

You need Android Studio (or the Android SDK + JDK 17). The Gradle wrapper is
included, so no separate Gradle install is needed.

### Option A — Android Studio (easiest)
1. `File → Open` this folder.
2. Let it sync (it downloads the Android SDK + dependencies).
3. Plug in the S8 with **USB debugging** on (Settings → Developer options).
4. Press **Run** ▶. It builds, installs, and launches.

### Option B — command line
```bash
# from this folder, with ANDROID_HOME pointing at your SDK
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enabling USB debugging on the S8
Settings → About phone → Software information → tap **Build number** 7×, then
Settings → Developer options → **USB debugging**.

## Using it
1. Turn on NFC (Settings → Connections → NFC). The app links to NFC settings if
   it's off.
2. Pick a tab, set any input, then hold a tag to the **upper-middle back** of the
   S8 (that's where its antenna is).
3. For **Emulate**, set the payload, then hold another phone/reader to the S8.
   If another NFC app grabs the tap, set this app as the default for "Other" HCE
   in Settings → Connections → NFC → Contactless payments/Other.

## Project layout
```
app/src/main/
  AndroidManifest.xml            NFC permission, launcher, HCE service
  res/xml/apduservice.xml        registers the NDEF Type 4 AID (D2760000850101)
  java/com/will/nfctool/
    MainActivity.kt              Compose UI + NFC reader-mode dispatch
    NfcOps.kt                    read / write / erase / lock logic (NfcA + Ndef)
    NdefHostApduService.kt       HCE Type 4 tag emulation
    HcePayloadStore.kt           stores the emulated NDEF payload
```
