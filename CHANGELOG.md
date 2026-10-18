# Changelog

## 12.2-test175 (2026-10-18)
- Fix (#38): Wake and play with the PS5 in rest mode no longer gives up at once with "Couldn't reach your PlayStation". A console still waking refuses the session within milliseconds, often before the session start returned, so the retry wasn't armed yet and the screen closed. It's now armed before the start, so the app keeps retrying for about a minute while the PS5 wakes.

## 12.2-test174 (2026-10-18)
- Fix (#37): every PS5 game kept a hardware video decoder after it ended, so after a few PS5 games PC streams on the TV failed to start (error -5) until the app was restarted. Cause: in chiaki's Android decoder, removing the screen surface took a lock it already held and froze, so the session was never freed. The lock is now released first (native patch `native/mc_surface_lock_patch.py`).

## 12.2-test173 (2026-10-18)
- TV receiver reports a PC as paired only when the PC really accepts this TV's identity (checked in the background), so a handheld sends its identity whenever the TV can't stream on its own.

## 12.2-test172 (2026-10-18)
- TV home: a PC card added for a sent session goes away as soon as the session ends, without reopening the app.

## 12.2-test171 (2026-10-18)
- A PC added for a sent session joins the TV's PC list at once, so the stream can start straight away. Build fix: `@Override` kept on `onCreate`.

## 12.2-test170 (2026-10-18)
- TV receiver: a handheld's identity for an unpaired PC is used for that session only. It is saved to session files, the PC is added for the session, and both are removed when the stream stops or the app closes. No prompt on the TV.

## 12.2-test169 (2026-10-18)
- Handheld: when the TV is not paired with the PC, the handheld sends its Sunshine identity encrypted to the TV's key (X25519 + HKDF-SHA256 + AES-256-GCM). Only ciphertext crosses the network.

## 12.2-test168 (2026-10-18)
- TV receiver publishes an identity key and whether it is paired with the PC, so a handheld knows when to send its identity.

## 12.2-test167 (2026-10-17)
- Decode delay now measures the decoder directly (frame in to frame out), not from host timestamps that read 0.

## 12.2-test166 (2026-10-17)
- Quick menu shows up to five stat tiles, so Decode delay is visible.

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
