# MoonCast

Play your PC, PS5 and Xbox on Android, on a handheld or on a TV.

MoonCast is two apps:

- **MoonCast**, for phones, handhelds, tablets and foldables. It plays games on its own screen, or sends a game to a TV running MoonCast TV, and then the handheld becomes the controller.
- **MoonCast TV**, for Android TV and Google TV. It has its own home screen for your consoles and games, and it receives the games your handheld sends it.

MoonCast grew from [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) and keeps its streaming engine. The home screens, the console support and the TV link are MoonCast's own.

## What it plays

| | How | What you need |
|---|---|---|
| **PC** | The Moonlight protocol, to [Sunshine](https://github.com/LizardByte/Sunshine) on your PC | Sunshine, with its default settings |
| **PS5** | Native PS Remote Play (built on [chiaki-ng](https://github.com/streetpea/chiaki-ng)), at home or away through PlayStation Network, with no VPN or port forwarding | A PlayStation Network sign-in, once, to register the console |
| **Xbox** | Native Xbox Remote Play (Google's WebRTC, following the [XStreaming](https://github.com/Geocld/XStreaming) protocol) | A Microsoft sign-in: the app shows a code, you enter it at microsoft.com/link |

## Features

- **Send a game to the TV.** Pick a game on the handheld and choose a TV. The TV plays it, and the handheld becomes the controller.
- **Home and away.** Each PC can have an address for away from home (an IP address or a domain name). The PS5 reaches you through PlayStation Network when you're out.
- **On-screen controller** for PC games, with PlayStation and Xbox layouts. The keys are see-through over the game and give a light tick under your finger. The PC sees a real PlayStation or Xbox pad, so games show the right button symbols.
- **Foldables.** The picture follows the screen when you fold or unfold, and Native resolution uses the whole screen.
- **Controllers.** Bluetooth and USB pads, a test screen for every button, and rumble on the device when a pad can't rumble.
- **Quick menu** while playing: streaming status, touch screen (off, mouse or touch), keyboard, controllers, Task Manager, disconnect, quit, move to a TV.

## Install

Download the APKs from [Releases](https://github.com/MoonCastDev/moonlight-android/releases). The betas (*MoonCast 1.0 beta …*) have:

- `MoonCast-….apk` for phones, handhelds and tablets;
- `MoonCastTV-….apk` for Android TV and Google TV.

Both run on Android 5.0 and later. Releases named `1.0-test…` are development builds (`MoonCast (Debug)`), installed next to the beta.

### Automatic updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs MoonCast from these releases and tells you when a new beta is out. Install Obtainium, then open one of these on the device:

- [Add MoonCast (phones and handhelds) to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.mooncast.handheld%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMoonCastDev%2Fmoonlight-android%22%2C%22author%22%3A%22MoonCastDev%22%2C%22name%22%3A%22MoonCast%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5EMoonCast%20%5B0-9.%5D%2B%28%20%7C%24%29%5C%22%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMoonCast-.%2A%5C%5C%5C%5C.apk%24%5C%22%2C%20%5C%22appName%5C%22%3A%20%5C%22MoonCast%5C%22%7D%22%7D)
- [Add MoonCast TV to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.mooncast.tv%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FMoonCastDev%2Fmoonlight-android%22%2C%22author%22%3A%22MoonCastDev%22%2C%22name%22%3A%22MoonCast%20TV%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5EMoonCast%20%5B0-9.%5D%2B%28%20%7C%24%29%5C%22%2C%20%5C%22apkFilterRegEx%5C%22%3A%20%5C%22%5EMoonCastTV-.%2A%5C%5C%5C%5C.apk%24%5C%22%2C%20%5C%22appName%5C%22%3A%20%5C%22MoonCast%20TV%5C%22%7D%22%7D)

Or add it by hand: **Add App**, the URL `https://github.com/MoonCastDev/moonlight-android`, then in its settings turn on **Include prereleases**, set **Filter release titles by regular expression** to `^MoonCast [0-9.]+( |$)` and **Filter APKs by regular expression** to `^MoonCast-.*\.apk$` (or `^MoonCastTV-.*\.apk$` on a TV).

### On the PC

Install Sunshine and pair MoonCast with it, as you would with Moonlight. Keep Sunshine's **gamepad** setting on `auto` (its default). Then each controller reaches the PC as what it is. A forced type such as `xone` makes every pad, including the on-screen PlayStation one, an Xbox pad.

MoonCast needs nothing else on the PC: no helper and no extra service.

## Building

You need:

- JDK 17;
- the Android SDK with NDK **29.0.14206865** (for the app) and NDK **28.2.13676358** and CMake **3.22.1** (for chiaki-ng);
- `protoc` (the Protocol Buffers compiler) on your `PATH`.

```sh
git clone --recurse-submodules https://github.com/MoonCastDev/moonlight-android
cd moonlight-android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleNonRootHandheldDebug assembleNonRootTvDebug
```

The APKs land in `app/build/outputs/apk/`. Two notes:

- **PS5 part.** The first build also compiles the PS5 part from `third-party/chiaki-ng` ([MoonCastDev/chiaki-ng-mooncast](https://github.com/MoonCastDev/chiaki-ng-mooncast): chiaki-ng plus MoonCast's changes). That takes a few minutes, and later builds skip it unless chiaki's sources change.
- **Existing clone.** If you already cloned without submodules, run `git submodule update --init --recursive`.

Test builds put the build number in the version: `BUILD=test123 ./gradlew …` gives `12.2-test123`.

## Privacy

MoonCast has no ads and no accounts of its own. Your PlayStation Network and Xbox sign-ins and your PC pairings stay on the device and go only to Sony, Microsoft and your own PC.

Official builds ask once whether you'll send an **anonymous usage check-in** (Settings, About). If you say yes, once a day it sends [TelemetryDeck](https://telemetrydeck.com) the app version, TV or handheld, the device model and Android version, and whether you played PC, PS5 or Xbox. The install is a random ID made on the device, sent hashed. It never sends names, addresses, games, accounts or anything you type. Say no, or turn it off later, and nothing is sent. Builds you make yourself never ask and never send. The code is in [`home/Usage.kt`](app/src/main/java/com/limelight/home/Usage.kt).

## Licence

MoonCast is free software under the [GNU General Public License v3](LICENSE.txt). The parts that come from chiaki-ng (PS5) and follow XStreaming (Xbox) are under the GNU Affero General Public License v3. [NOTICE](NOTICE) lists every open-source project MoonCast includes, with its licence. The app shows the same list under Settings, About, Open-source licences.

## Credits

MoonCast stands on the work of:

- **Moonlight for Android**, by Cameron Gutman, Diego Waxemberg, Aaron Neyer, Andrew Hennessy and its contributors. It's the streaming engine and the app MoonCast grew from.
- **chiaki-ng**, by streetpea and its contributors, and chiaki before it. It powers PS5 Remote Play.
- **XStreaming**, by Geocld. Its Xbox Remote Play protocol is the one MoonCast follows.
- **Sunshine**, by LizardByte, on the PC side.

Each project's own copyright and licence notices stay in its files.

MoonCast isn't affiliated with or endorsed by Sony Interactive Entertainment, Microsoft or NVIDIA. PlayStation, PS5 and DualSense are trademarks of Sony Interactive Entertainment Inc.; Xbox is a trademark of Microsoft Corporation.
