# Public Gossip

A native Android hackathon MVP for exchanging short, public text posts with nearby phones without an internet connection or a server. Phones also pass on posts received from others, so a post can reach someone its author never met.

One Kotlin app module, one Activity, Jetpack Compose, Google Nearby Connections `P2P_CLUSTER`. Only foreground sync; leaving the Activity stops discovery, advertising, pending retries, and all connections.

## Requirements

- Android 12L / API 32 or newer, with Google Play services.
- Compile SDK 37 and target SDK 37. All three SDK settings are unchanged from the starter, as requested; Android API 26-31 is not supported.
- Android Studio's bundled JDK (or a compatible JDK 21), Android SDK platform 37, and the Gradle wrapper. The daemon JVM criteria request Java 21.
- Kotlin/Compose compiler 2.4.20: Nearby 19.5.1 carries Kotlin 2.4 metadata and could not compile with the starter's Kotlin 2.2.10. AGP and the Gradle wrapper are unchanged.
- A dependency constraint raises Nearby's transitive Fragment 1.1.0 to 1.3.6 for Activity Result permission compatibility; this app does not use fragments.
- Internet access to download build dependencies initially. The installed app does not declare `INTERNET`, use a backend, or need an internet connection for posts.

## Build

From the repository root in Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

On a POSIX shell with `JAVA_HOME` and the Android SDK configured:

```sh
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

JVM tests cover signing/verification, tampered text, author/key mismatches, the exact signed-byte order, JSON round trips, duplicate IDs, file persistence, the SDK permission matrix, the connection tie-breaker/retry budget, hello-before-snapshot ordering, local broadcasts, fake-sender receive validation, attribution, hop overflow, source exclusion and no duplicate forwarding. `org.json:json` is test-only; Android's built-in JSON implementation is used in the APK.

Optional non-radio instrumented checks cover preference reload and the 50-event log bound:

```powershell
.\gradlew.bat assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

The second command requires an emulator or attached device. Agent verification does not include real-phone radio behavior or these device-side checks.

## Install

Enable USB debugging on each phone and attach it. With Android SDK `platform-tools` on `PATH`:

```powershell
adb devices
adb -s PHONE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Replace `PHONE_SERIAL` with a serial printed by `adb devices`. Repeat for each phone, then open **Public Gossip**. Each fresh installation asks for a nonempty display name (maximum 24 characters). Identity and name persist across restarts; clearing app data or uninstalling removes the local identity and posts unless the OS restores app data.

## Real-Phone Manual Test

Use two or three real phones; an emulator is not a substitute for the Nearby radio test.

1. Install the debug APK on phones A and B. Grant permissions. Turn on Bluetooth, Wi-Fi and location. Cellular data/internet is not needed; Wi-Fi must remain enabled.
2. Open the app on both. Within about 30 seconds each should show **1 nearby connected**. The lexicographically smaller author ID initiates the connection; both sides accept automatically.
3. Post on A. It should appear on B as **direct**. A's row is marked **(you)** and **own post**.
4. Close the app on A. Open phone C next to B. A's post should appear on C as **via B's display name · 2 hops**. Keep B foregrounded throughout this step.
5. Restart the apps: names, author IDs, and stored posts should persist. Reconnecting or receiving the same post through another phone must not add another row.
6. Deny permissions on a fresh installation: discovery/advertising must not start. Use **Retry permissions** after allowing them. If Android will not prompt again, enable them in the app's system Settings and return. Put the app in the background: connections should close and the count should reset.

Expand **Debug log** to inspect the last 50 events. Every Nearby discovery, connection and payload callback, plus task results, permission results and retries, is logged there and under Logcat tag `PublicGossip`. Transfer updates also consume log slots. Invalid/malformed posts produce only a log entry, not an error dialog. If connection attempts exhaust the initial request plus three retries, reopen the app to start a new discovery session. Start failures, disabled radios, and missing/outdated Google Play services are visible in the log.

Real-device discovery timing, `neverForLocation` behavior, permission denial/retry and API 32/API 37 radio compatibility remain unverified until this test is run.

## Permissions

The [Nearby Get started page](https://developers.google.com/nearby/connections/android/get-started) has an API 32 boundary inconsistent with the [Android Wi-Fi permission guide](https://developer.android.com/develop/connectivity/wifi/wifi-permissions) and [official WalkieTalkie sample](https://github.com/android/connectivity-samples/blob/main/NearbyConnectionsWalkieTalkie/app/src/main/AndroidManifest.xml). This app follows the platform/sample for Android 12L and the current Nearby page for API 37:

| Device API | Runtime requests |
| --- | --- |
| 32 | Bluetooth advertise/connect/scan and coarse + fine location together |
| 33-36 | Bluetooth advertise/connect/scan and Nearby Wi-Fi Devices |
| 37+ | The same, plus Local Network |

Wi-Fi state/change permissions are normal manifest permissions, not runtime prompts. Location is capped at API 32. Bluetooth Scan and Nearby Wi-Fi Devices use `neverForLocation`, as in the sample; the app does not derive physical location. No storage permission is needed because every message is a BYTES payload. Legacy Bluetooth permissions are unnecessary at minSdk 32. Permissions not present on older platforms are runtime-gated, without undocumented permission `minSdkVersion` attributes. The merged manifest explicitly prevents transitive `INTERNET` declarations and removes Nearby's unused Exposure Notification wake-up service; the app APK declares no service.

## Data And Protocol

- First launch creates an EC P-256 keypair. Base64 PKCS8 private and X.509 public keys are kept in app-private SharedPreferences. `authorId` is the first 16 lowercase hex characters of SHA-256 of the encoded public key.
- Posts use random UUIDs and `SHA256withECDSA`. UTF-8 signed fields, joined with `\n`, are `id`, `authorId`, `authorName`, `pubKey`, `text`, decimal `createdAt`, `audience`. Audience is always `public`, text is at most 500 characters. `hops` and `via` are not signed.
- A map keyed by post ID deduplicates posts; a newest-first `StateFlow` drives the feed. The whole map is saved in `filesDir/posts.json` through an atomic file replacement after every insertion, and validated on load. Persistence errors are logged; local save errors keep the draft visible.
- Endpoint names are `authorId|displayName`, service ID is `com.example.localpost`, and connection strategy is `P2P_CLUSTER`.
- Each message is one UTF-8 JSON BYTES payload: `{"type":"hello","authorId":"...","name":"..."}` or `{"type":"post","post":{...}}`. Hello is sent before the store snapshot. Posts are sent individually with their stored hop counts; payload size is checked before sending.
- Valid new incoming posts gain one hop and the immediate sender's hello name. They are stored, then forwarded to every connected peer except the source. Duplicates/invalid posts are not forwarded. All post/peer mutations are serialized on the main thread for this small MVP.

## Limitations And Privacy

- Anyone nearby running the app receives every post. There are no private posts or access controls.
- Signatures prove which key wrote a post; they do not prove who a person is.
- Display names are not unique and can be copied.
- The private key is stored in app storage without hardware protection.
- Connections are auto-accepted without comparing Nearby authentication tokens. This deliberately skips Google's recommended peer-authentication step; signatures do not authenticate a person's identity, their hello name, or the radio endpoint. Do not use this MVP for sensitive content.
- Hop counts and `via` names are unsigned, untrusted metadata. Duplicate arrival order determines the retained route, not necessarily the shortest path. Remote timestamps are untrusted and clocks can disagree.
- Foreground only, no delivery guarantees, no retry queue for failed payload transfers, and no post deletion/expiry or size-bounded store. Reconnecting resends the full store. JSON file writes happen on the main thread; large histories are outside this hackathon MVP's scope.
- No app-added analytics. However, the [Nearby SDK collects performance metrics and device information through Google Play services](https://developers.google.com/nearby/connections/overview), including connection/discovery reliability, latency/throughput, device model, country, build version and app package name. The documented opt-out is the end user's **Settings > Google > Usage & diagnostics** control; no developer-side opt-out was confirmed. Removing this app's internet permission does not disable telemetry performed by the separate Google Play services process. This is disclosed SDK behavior, not a promise that no diagnostics ever leave the phone.
- Android backup/restore policies can restore app-private data; this MVP does not provide hardware-key isolation or a separate backup security mechanism.

Out of scope: friends, audiences other than public, extra post-content encryption, background sync, AI filtering, images, deletion/expiry and iOS.

## Milestones

All six milestones were implemented and committed in order: skeleton/identity, local signed posts, Nearby link, sync, forwarding, documentation/final checks. Each implementation milestone passed `assembleDebug` before its commit. Device checks and the real-phone test still require a human.