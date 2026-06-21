# SwiftShare

SwiftShare is an independent local-transfer product for one owner's macOS and
Android devices. It does not implement Google Quick Share, Nearby Share, Apple
AirDrop, or any cloud transfer protocol.

The repository contains two native applications and one language-neutral
contract area:

- `macos/`: Swift and SwiftUI menu-bar application.
- `android/`: Kotlin and Jetpack Compose application.
- `contracts/`: shared specifications and golden fixtures, not shared runtime
  application code.
- `scripts/`: public local build and test entry points used by the `Makefile`.

## Requirements

- macOS 14 or newer
- Xcode with the macOS SDK
- JDK 17 or newer (Android Studio's bundled JBR is supported)
- Android SDK API 36.1
- An Android 14/API 34+ device or emulator for launch and storage acceptance
- An API 34+ emulator or physical device for launch acceptance

Run `make doctor` for actionable diagnostics. The aggregate build and test
commands fail when either native toolchain is unavailable; platform-specific
commands remain available while setting up the other toolchain.

## Commands

```sh
make doctor
make build
make test

make build-macos
make test-macos
make build-android
make test-android
```

The applications are development builds. This repository intentionally has no
remote, public license, account service, backend, store packaging, or release
automation.

## LAN transfer

SwiftShare uses mutually pinned development identities and discovers paired
devices over Bonjour/DNS-SD. Private keys remain in Keychain and Android
Keystore; discovery advertisements contain only a rotating opaque identifier and
protocol version.

```sh
make build
./scripts/provision-macos-dev-identity
```

Install `android/app/build/outputs/apk/debug/app-debug.apk` on an Android 14/API
34+ Device, pair it with the Mac, and choose **While open** or explicit
**Receive Mode** on Android. Receive Mode requires notification permission and
shows an ongoing notification with a Stop action; it is never silently enabled.
The Mac menu-bar app shows the paired Device as online when authenticated LAN
discovery succeeds. Select one Mac file, review the destination and size, and
send. If multicast discovery is unavailable, scan or paste Android's endpoint QR
in the Mac app. Both Devices still need direct IP reachability; internet access is
not required.

Incoming transfers require approval by default. Auto-accept can be enabled and
revoked independently for each Paired Peer on the receiving Device. The Mac
settings also expose the system-managed launch-at-login choice.

The provisioning command rotates a 30-day self-signed development certificate,
imports its private key as non-exportable into the login Keychain, and writes only
the public certificate and pin under the ignored `.tools/dev-transfer/` directory.

## QR pairing

After provisioning the Mac identity, choose **Pair Android device** in the Mac
menu-bar app. Android can scan the displayed QR with its in-app CameraX scanner or
accept the copied `swiftshare://pair/v1/…` payload. Compare the six-digit code on
both Devices and approve both screens. Pairing stores the identities later used
to authenticate automatic discovery and endpoint QR connections.
