# Contributing to MoonCast

Thanks for helping! A few rules keep the history easy to follow and the apps easy to release.

## Reporting a problem

Use **New issue → Something doesn't work**. Say:
- which app (MoonCast or MoonCast TV) and which version (Settings, About);
- your device and Android version;
- what you played (PC, PS5 or Xbox), at home or away;
- what happened.

A screenshot or a short video helps a lot. For ideas, use **Idea or wish**.

Security problems go privately, not in an issue: see [SECURITY.md](SECURITY.md).

## Building

See [Building](README.md#building) in the README. Clone with `--recurse-submodules`: the PS5 part is built from `third-party/chiaki-ng`.

## Changes

- **One topic per branch and pull request.** A fix and a feature are two pull requests. Name branches by kind and topic: `fix/battery-res`, `feat/fsr`, `docs/readme`.
- **CHANGELOG.md in every pull request.** Add an entry at the top that says what changed for someone using the app, in plain words. Then add what changed in the code.
- **Match the code around you.** Use the same naming, comment style and amount of comments. Keep it simple: no new libraries or abstractions without a reason.
- **Both apps.** Settings and behaviour that make sense on TVs and handhelds go into both (`src/tv` and `src/handheld`); shared code lives in `src/main`.
- **Lint and CI must pass.** CI builds both apps and runs lint on every pull request. Locally: `./gradlew lintNonRootHandheldDebug lintNonRootTvDebug`.
- **Test on a device** before asking for review, and say in the pull request what you tested, on which device, and what you couldn't test.
- **PS5 native code** lives in [MoonCastDev/chiaki-ng-mooncast](https://github.com/MoonCastDev/chiaki-ng-mooncast). Change it there first, then move the `third-party/chiaki-ng` submodule to the new commit in your pull request.
- **No copyrighted game pictures** in the repository (screenshots, box art, mockups). Use made-up scenes, or free games.
- **No secrets.** Never commit keys, passwords, tokens, sign-in data or `keystore.properties`.

## Test builds

Put the build name in the version: `BUILD=test123 ./gradlew assembleNonRootHandheldDebug assembleNonRootTvDebug` gives `1.0-test123`. Pre-releases on GitHub carry these test builds; releases are signed with the MoonCast key, built with `scripts/release.sh`.

## Licence

MoonCast is GPL-3.0, with the parts from chiaki-ng (PS5) and XStreaming (Xbox) under AGPL-3.0. By contributing, you agree that your work is published under the same licences. When you add a library, also add it to `app/src/main/java/com/limelight/home/Licences.kt`, to `NOTICE`, and add its licence text to `app/src/main/assets/licences/`.
