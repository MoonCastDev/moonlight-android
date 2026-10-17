# Changelog

## 12.2-test160 (2026-10-17)
- Handheld: Native resolution is now the largest 16:9 size that fits the screen (Retroid Pocket 1240x1080 gives 1240x696), so widescreen games fill the screen instead of leaving black bars.

## 12.2-test159 (2026-10-17)
- TV receiver: a game sent by any handheld (phone, tablet or Odin) is looked up by name in this TV's own PC list. The sender's game ID is only the fallback. Fixes a sender whose PC numbers games differently (for example a tablet paired with another PC) leaving the TV stuck on 'starting'.

## 12.2-test158 (2026-10-17)
- PlayStation Frame rate and Bitrate rows (Home and Away) get a Custom option. Choose Custom, then tap the Custom value row to type a number: frame rate 15-60 fps, bitrate 2-50 Mbps. The stream uses the typed value.

## 12.2-test157 (2026-10-17)
- PlayStation settings: the same four rows (Resolution, Frame rate, Bitrate, Video codec) for each profile. The General profile decides which set they change. Frame rate can be 60 or 30 fps, per profile.
- Fix: the Away settings had no frame rate and a different resolution row, so changes in Home didn't apply to Away.

## 12.2-test156 (2026-10-17)
- Streaming profile moved to a General tab (TV and handheld). PlayStation settings show the rows for the chosen profile: Home rows, Away rows, or both under Auto. PC and Xbox settings are not switched by the profile yet.

## 12.2-test155 (2026-10-17)
- Home and Away streaming profiles, first slice (issue #35): `stream_profile` = Auto / Home / Away. Auto uses Away only when the PS5 is reached through PlayStation Network. Away uses "Quality away from home", plus new "Bitrate away" and "Video codec away" settings. Shared module `home/Profile.kt`, in the TV and handheld apps. PC and Xbox profiles are not in yet. Not yet tested on a device.

## 12.2-test154 (2026-10-17)
- Quick menu panel is 80% opaque, so the game shows behind it.
