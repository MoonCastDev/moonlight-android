# Changelog

## 12.2-test165 (2026-10-17)
- PC stream Quick menu shows Decode delay (average decoder time per frame), so Ultra low latency can be compared on a device. The Quick menu now shows up to five stat tiles.

## 12.2-test164 (2026-10-17)
- Handheld: a saved resolution from before the 16:9 fix moves to the new Native, so Native shows in the Resolution list again.
- Bitrate and frame rate: picking Custom opens the number box straight away (TV and handheld). Range: 2-50 Mbps, 15-60 fps.

## 12.2-test163 (2026-10-17)
- Streaming profile is now Home or Away (Auto removed). It moved out of the General tab, which is gone: TV in Video, handheld in Picture. A saved Auto counts as Home. Note: a PlayStation over PSN now uses Home settings unless Away is chosen.

## 12.2-test162 (2026-10-17)
- Profiles now keep all their streaming settings, not only the PC's resolution, frame rate, bitrate and codec: PC video (HDR, colour range, picture shape, zoom, frame pacing, ultra low latency, PC game tuning, unlock frame rate, refresh rate), audio (audio config, host audio, audio effects) and the Xbox resolution. Switching the General profile saves and restores them. Controller and device settings stay shared. PlayStation rows were already per profile.

## 12.2-test161 (2026-10-17)
- Profiles keep their own PC video settings (resolution, frame rate, bitrate, codec). Switching the General profile saves the current values into the profile being left and loads the saved values for the new one. Auto uses Home for the PC, which only streams on the home network. Xbox settings are not profile-specific yet.

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
