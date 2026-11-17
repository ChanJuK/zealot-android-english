# Zealot Android SDK

Android 组件提供为 Zealot 检查新版本和安装的服务，支持 Kotlin 和 Java。

## 安装

### Gradle

Use a fixed release tag or an immutable commit hash. Do **not** consume
`master-SNAPSHOT` or another mutable version in a release build. This repository
is directly consumable as the `:zealot` Gradle module.

## 使用

Configure the SDK from an `Activity`. The endpoint and every download host must
be a compile-time allowlist, and the verification key must be an X.509 RSA public
key bundled with the app (not received from remote configuration).

```kotlin
// Kotlin

Zealot.create(this)
      .setEndpoint("https://updates.example.com")
      .setAllowedUpdateHosts("updates.example.com", "downloads.example.com")
      .setUpdateVerificationKey(BuildConfig.ZEALOT_UPDATE_PUBLIC_KEY)
      .setChannelKey(BuildConfig.ZEALOT_CHANNEL)
      .setBuildType(BuildConfig.BUILD_TYPE)
      .launch()

// `channelKey` identifies a channel; it must not grant administrative or
// download authorization because it is recoverable from an APK.
```

```java
// Java

Zealot.create(this)
      .setEndpoint("https://updates.example.com")
      .setAllowedUpdateHosts("updates.example.com", "downloads.example.com")
      .setUpdateVerificationKey(BuildConfig.ZEALOT_UPDATE_PUBLIC_KEY)
      .setChannelKey(BuildConfig.ZEALOT_CHANNEL)
      .setBuildType(BuildConfig.BUILD_TYPE)
      .launch();
```

## Signed update response

The update endpoint must accept `X-Zealot-Channel-Key` and return this envelope:

```json
{
  "signed_payload": "base64url(UTF-8 JSON shown below)",
  "signature": "base64url(RSA SHA-256 signature of the decoded payload bytes)"
}
```

The decoded, signed payload must contain the package, build type, exact channel
identifier, and an expiry no more than 31 days in the future. The first release
must have a `build_version` greater than the installed version.

```json
{
  "bundle_id": "com.example.app",
  "build_type": "release",
  "channel_key": "public-channel-identifier",
  "expires_at": 1770000000,
  "releases": [{
    "release_version": "2.0.0",
    "build_version": "42",
    "install_url": "https://downloads.example.com/app-42.apk",
    "changelog": [{"message": "Security update"}]
  }]
}
```

Sign the raw UTF-8 bytes of the payload JSON with a private RSA key using
`SHA256withRSA`; give the app only the Base64 X.509 public key. HTTPS and the
signature are both required. Invalid, expired, unsigned, downgraded, oversized,
or non-allowlisted updates are ignored.

## Android app requirements

The host app needs network permission and must block cleartext traffic in release
builds. Never commit a production endpoint, signing key, or credential to a
sample application.

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Author

icyleaf, icyleaf.cn@gmail.com

## License

Zealot is available under the MIT license. See the LICENSE file for more info.
