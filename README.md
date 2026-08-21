# X9 Ultra GBL Chainload

Standalone Android app for the OPPO Find X9 Ultra. It packages the validated
temporary-root `preload.so` and the two separately supplied GBL chainload
payloads. It writes those payloads directly and contains no `unlocker` binary.

This app does **not** unlock the Android bootloader. It enables the supplied
GBL chainload path by writing ABL to both slots and `installed-mode2.efi` to
the `efisp` partition.

The Uninstall page obtains temporary root, writes the packaged all-zero
`efisp.img` to `efisp`, and reads the entire image back before offering a
reboot to recovery / fastbootd. The user must then enter Recovery and format
data manually; the app never formats user data itself.

Created by [koaaN](https://github.com/koaaN). The matching CVE-2026-43499
exploit and preload builder is available at
[koaaN/x9u-preload-builder](https://github.com/koaaN/x9u-preload-builder).

## Workflow

1. Obtain temporary root: confirm a supported project ID and the exact target
   kernel, run the embedded preload, then verify uid 0 through a
   restricted app-private root broker.
2. After the user types `FLASH`, install the GBL chainload payloads:
   - `abl.img` to `abl_a` and `abl_b`;
   - `installed-mode2.efi` to `efisp`.
3. After all three writes pass read-back verification, reboot to recovery /
   fastbootd and manually format data in Recovery.

The broker accepts only `PING`, the fixed three-partition install operation,
and `STOP`; it does not expose a general root shell. Before success is shown,
the app reads the exact payload length back from each partition and compares
it with the staged image. It then stops the broker, removes the preload's
temporary `su` files, and restores SELinux enforcing. An unused broker also
expires after 15 minutes.

The app does not unlock the Android bootloader, reboot to fastboot, or format
user data. The preload race can panic or reboot the device, and interrupted
partition writes can make it unbootable. The native backend repeats the model
and kernel checks before root and flash operations; the disabled UI buttons
are not the only guard.

## Supported targets

The supported project IDs are:

```text
25021
25022
25211
```

The project ID is the compatibility gate and is read from the standard OPlus
boot properties, beginning with `ro.boot.prjname`. The reported Android model
is informational and is not paired with a project ID because firmware can
change it. Known labels include `PMA110`, `PMA120`, and `CPH2841`.

The only accepted kernel target is:

```text
6.12.58-android16-6-g7704a1ae279b-ab15213644-4k
```

Any other kernel is rejected before the preload or partition-writing code can
run.

## Embedded payloads

| File | SHA-256 |
|---|---|
| `libx9upreload.so` | `32ac2f03f56955c41032157aa53f23590c3f6a1595fccc025ad991322e07f7f6` |
| `abl.img` | `4ad7f1db0c92f0a358e28bf18170a20533011299827d8d9a25cffd2218f1415d` |
| `installed-mode2.efi` | `35560f8e6fd706a3768f26f692467491c90425881a8d80bf237341215c04cac5` |
| `efisp.img` (3 MiB, all zero) | `bbd05cf6097ac9b1f89ea29d2542c1b7b67ee46848393895f5a9e43fa1f621e5` |

## Build and install

A complete JDK with `javac` and Android SDK 36 are required.

```sh
ANDROID_SDK_ROOT=/tmp/android-sdk ./build-app.sh
adb install -r dist/x9u-root-flasher-v0.2.1.apk
```

The current local release build uses the Android debug signing key so it is
directly installable for testing. Use a private production keystore before
public distribution.

## Automated releases

`.github/workflows/release.yml` builds and publishes an APK whenever a tag
matching the app version is pushed, such as `v0.2.1`. It can also be run
manually with the same tag. The workflow checks the APK checksum, uploads a
workflow artifact, and creates or updates the corresponding GitHub Release.

The following repository secrets provide a stable signing identity across
releases:

- `X9U_SIGNING_KEYSTORE_BASE64`
- `X9U_SIGNING_STORE_PASSWORD`
- `X9U_SIGNING_KEY_ALIAS`
- `X9U_SIGNING_KEY_PASSWORD`
