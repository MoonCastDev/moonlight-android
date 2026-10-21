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
- **Quick menu** while playing: keyboard, controllers, disconnect, quit, move to a TV.

## Install

Download the APKs from [Releases](https://github.com/MoonCastDev/moonlight-android/releases):

- `MoonCastHandheld-….apk` for phones and handhelds;
- `MoonCastTV-….apk` for Android TV and Google TV.

Both run on Android 5.0 and later. Builds marked *Pre-release* are test builds.

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
