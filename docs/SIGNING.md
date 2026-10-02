# Release signing

The v0.1.0 release uses a dedicated 4096-bit RSA key with a SHA256withRSA
certificate and 10,000-day validity. Android APK Signature Scheme v3 verifies
the package for the supported Android 10+ range. The published APK is the tested
R8/resource-shrunk release build, aligned and signed after assembly.

Certificate SHA-256:
`921853fe2ce9460e7d4304d8f4e146ad045c81b8144e111fc30c50a22da337d0`.

## Private files and backup

The publishing workspace retains these files under the ignored `.signing/` directory:

- `imageenhancer-release.p12`: the password-protected private signing key.
- `password.clixml`: its password, encrypted using Windows DPAPI for the current
  Windows user and machine. Directory access is restricted to that account.

Retain the same key for updates to this release. Back up the keystore securely
and retain its password in a password manager. The DPAPI file alone is not a
portable password backup: it cannot be decrypted by another user or machine.
Move the password into secure portable storage while the publishing account
is still accessible. Do not commit either file or upload it as a release asset.

No signing secrets are included in command arguments, documentation or GitHub.
The signing script decrypts the password locally for the SDK tools and clears
the temporary environment variable afterward.

## Repeatable signing

Use JDK 17 and Android SDK build-tools 36+ with Windows PowerShell. The helper
also discovers this workspace's ignored `.tools/jdk/` installation.

```powershell
.\scripts\build.ps1 -Tasks @(':app:assembleRelease')
.\scripts\sign-release.ps1
```

For a later app version, update the application's versionName/versionCode,
build it, and provide the corresponding output name:

```powershell
.\scripts\sign-release.ps1 -OutputApk 'dist\LocalPhotoEnhancer-v0.2.0.apk'
```

The script aligns the APK, signs it, verifies its certificate and alignment,
runs the bundled-model/offline-permission audit, and writes `dist/SHA256SUMS.txt`.
The default input APK is never overwritten. Release assets are excluded from Git.

For a separate new distribution identity, `-InitializeKey` creates a new key
only when no key or encrypted password exists. Do not use a new key as a
replacement for the published app's existing signing identity. A missing or
partial signing setup stops with an error instead of silently rotating keys.

Debug builds have a different certificate. Uninstall a debug build before
installing this release, after exporting any results you wish to retain.
