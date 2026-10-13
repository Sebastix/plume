# Feather

A lightweight Android client for Nostr.

[Website](https://feather.dergigi.com) · [Get it on Zapstore](https://zapstore.dev/apps/org.dergigi.feather) · [Download APK](https://github.com/dergigi/feather/releases/latest)

Swipe through notes, photos, videos, articles and more. Post and reply, react with custom emoji, and send one-tap zaps through Nostr Wallet Connect. Native Android, full-screen media, signer login, and system light/dark mode.

<p>
  <img src="website/assets/screens/notifications.webp" width="230" alt="Notifications with replies, reactions and zaps">
  <img src="website/assets/screens/feed.webp" width="230" alt="Feather’s main feed">
  <img src="website/assets/screens/photos.webp" width="230" alt="Full-screen photo feed">
</p>

## Code & issues on Nostr

Report bugs and suggest features from Feather, or use [GitWorkshop](https://gitworkshop.dev/npub1dergggklka99wwrs92yz8wdjs952h2ux2ha2ed598ngwu9w7a6fsh9xzpc/relay.ngit.dev/feather). No GitHub account needed. Source and release tags are mirrored with **ngit** on two **GRASP** servers.

[Clone & contribute](docs/NGIT.md) · [Design](docs/DESIGN.md) · [Changelog](CHANGELOG.md)

## Build

JDK 17+, Android SDK 37. Set `ANDROID_HOME` or `sdk.dir` in local.properties.

```sh
./gradlew :app:assembleDebug
```

[Release workflow](docs/RELEASE.md) · [Privacy](https://feather.dergigi.com/privacy.html) · [MIT license](LICENSE)
