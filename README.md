# OkcAgent

This companion app connects [okc-agents](https://github.com/reo101/okc-agents) to OpenKeychain. SSH and OpenPGP private-key operations remain in OpenKeychain.

Companion 0.3.0 pairs with CLI 0.4.0 through GPG protocol 2. Older GPG clients are rejected rather than silently ignoring their options. SSH retains protocol 0.

This fork adds explicit public export, OpenKeychain backup, permission checks, metadata, signing-key selectors, cancellation, and binary-stream repairs. It removes Bugsnag and its native libraries. It does not expose a GnuPG agent socket.

## Build

The checkout contains the public API sources. No local submodule directories or unpublished Maven artifacts are required. Their revisions and licenses are listed in [libraries/README.md](libraries/README.md).

With Nix, enter the pinned build environment:

```sh
nix develop
./gradlew --no-daemon assembleDebug assembleRelease
python3 tests/test-agent-runtime.py
```

Without Nix, use JDK 11, Android platform 33, and build tools 31.0.0. Set `ANDROID_SDK_ROOT` to that SDK. Gradle downloads its Maven dependencies on the first build. Release output is unsigned.

The APK contains no native `.so` files. There are no Bugsnag libraries to trigger a 16 KB alignment warning. The app still targets SDK 30. A target-SDK modernization needs a separate foreground-service review.

## Setup

1. Install OpenKeychain and this companion APK.
2. Open OkcAgent and allow notifications.
3. Select the GPG signing key and SSH authentication subkeys.
4. Install the matching CLI fork.
5. If an authorization notification appears, open it and complete the OpenKeychain request.

If you replace an APK signed by another certificate, Android requires an uninstall first. That removes companion preferences. OpenKeychain authorizations also depend on the signing certificate. Select the keys again after replacement.

NNS can supervise the SSH client for a NoD consumer. Its module is `nix/nns-system/okc-agents.nix` in [NNS](https://github.com/reo101/NNS). Do not replace the system `gpg` command globally.

## Supported commands

| CLI action | Provider action |
| --- | --- |
| `--clear-sign`, `--clearsign` | Cleartext signature |
| `--detach-sign`, `-b` | Detached signature |
| `--encrypt`, `-e` | Encrypt |
| `--sign --encrypt` | Sign and encrypt |
| `--decrypt`, `--verify` | Decrypt and verify |
| `--export` | Public OpenPGP key export |
| `--export-ssh-key` | SSH public key export |
| `--backup` | Encrypted OpenKeychain backup |
| `--backup --backup-secret` | OpenKeychain backup with secret parts |
| `--check-permission` | Provider authorization check |
| `--decrypt --metadata` | Diagnostic metadata |
| `--list-config` | Companion version and provider |

Public export accepts full 16-digit key IDs and email addresses. With no selector, it exports the configured GPG key. `--armor` controls the export encoding. SSH export uses an authentication subkey ID, not a primary-key fingerprint.

`--local-user` / `-u`, `--default-key`, and `--default-user` select a signing key. Repeated recipients and `--encrypt-to` are combined. Unsupported commands fail explicitly in both the CLI and companion.

Key listings, colon records, fingerprints, GnuPG secret-key export, import, creation, deletion, editing, and keyserver operations are not implemented. Perform key management in OpenKeychain. `--list-secret-keys` never starts a backup. `--backup` is not a GnuPG secret-key export. OpenKeychain controls its authorization and recovery code.

Signed-packet `--sign` without encryption, hidden recipients, batch mode, passphrase injection, and status descriptors other than 2 are rejected. Only a minimal signing-success status marker is available. Full GnuPG status compatibility is not claimed.

## Cancellation and tests

GPG client disconnection interrupts pending authorization. SSH uses a bounded input pipe to detect client disconnection during authorization. UI waits have a two-minute limit. Pending UI does not hold the provider lock. Notification cleanup runs on completion and cancellation.

The host regression script compiles the actual request lifecycle, GPG service, argument parser, and streams against Android/provider stubs. It tests cancellation, independent requests, explicit command rejection, binary replay, truncation, export encoding, selector failures, mixed recipients, output staging, metadata, and asynchronous SSH binding. It does not test OpenKeychain cryptography, Android SELinux, or hardware-token interaction.

The repaired APK requires a separate interactive device test. Building it is not evidence that a token signature completed. Do not automate permission approval or key administration on a personal device.

## License

The application retains its MIT license. The included OpenKeychain API libraries retain their Apache-2.0 licenses. These are client API libraries, not OpenKeychain application code.
