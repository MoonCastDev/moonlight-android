# Changelog

## 12.2-test190 (2026-10-19)
- Handheld on tall phones (Pixel): Home used only the top half of the screen. Upright, the tiles now go two across the full width and wrap into as many rows as fit above the round buttons (the last tile is All games); with height to spare, Home is centred. A near-square screen upright (Retroid) keeps one row.
- Handheld: a back button at the top left of every page but Home, for touch screens (same as B). The bottom bar's button hints can be tapped (they do what the button does), and they're hidden when no controller is connected.

## 12.2-test189 (2026-10-19)
- Handheld: portrait shows the same screens as landscape, only fitted to the upright screen (it had its own Home with a big banner and a dock, and Settings without the side menu). Upright: the bottom hint bar, the tile row with tiles about a third of the screen high, the six round buttons in two rows of three with their labels beside them, and Settings with the side menu. Upright always uses the compact sizes, scaled by the screen's width, so text is the same size both ways round. This applies to phones held upright too.
- Compact layout: a little more space between a row's title and its description, and taller rows.

## 12.2-test188 (2026-10-18)
- Handheld compact layout, from the first look on the Retroid: the round-button labels were crowding each other, so compact uses shorter ones ("Play on TV", "Network") and spaces the buttons wider; Home tiles a little bigger (350) to use more of the screen.

## 12.2-test187 (2026-10-18)
- Handheld: a compact layout for small screens. On a small or near-square screen (Retroid Pocket Mini V2: 1240 x 1080) the normal 1920-wide layout was drawn at 65%, with tiny text and 40% of Home empty. The app now picks Compact by itself when the normal layout would be drawn below 80%: a 1240-wide layout with about 1.5x bigger text, tiles, buttons and rows, labels under the round buttons, and a narrower side menu in Settings. Android's own large Font size makes it a step bigger. New setting Handheld > Screen size: Auto (default), Compact, Normal, Large. The Odin and phones stay on Normal. Mockups: docs/mockups/handheld-compact (branch design/handheld-compact).

## 12.2-test186 (2026-10-18)
- Fix (#41 follow-up): quitting a PC game from the TV's Quick menu left the TV's link reporting "failed: The stream from the PC stopped (error -1)". Quit makes the PC close the game, which ends the stream with an error code. A stream the user ended (Quit game, Disconnect, Back) no longer counts as a failure, so the link goes back to "idle".

## 12.2-test185 (2026-10-18)
- Fix (#44): the "can't open database" error was still logged on every check from a handheld, from Moonlight's two other old databases (computers2.db, computers3.db). Those are skipped too when they don't exist.

## 12.2-test184 (2026-10-18)
- Small fixes from the test173 pass (#44):
  - TV: the PS5 and Xbox detail panels show the picture the profile in use streams with (for example "1080p, 60 fps (Home)") instead of always "60 fps, set by this TV's link".
  - TV: when a box or menu closes (Save, Cancel, Back), focus goes back to the row that opened it, not the first item of the page.
  - Handheld: choosing a theme keeps you on the Theme page; before, the app came back on Home, so the next Back closed it.
  - Handheld: before sending a PC game to an unpaired TV, it asks the TV for its key again (the TV makes a new one each time its app starts), so a remembered key can't be stale.
  - Both: no more "can't open computers.db" error in the log on every check (an old Moonlight database that doesn't exist on new installs).
  - Decided: no Retro tab in the handheld's All games (#25); retro games stay under PC as the ES-DE app.

## 12.2-test183 (2026-10-18)
- PC streams get a Custom frame rate (#42), TV and handheld: a "Custom frame rate" row under Frame rate takes any rate from 15 to 120 fps, like Custom resolution and Custom bitrate. It's saved per profile like the other PC settings. "Turn off" on the TV goes back to 60.

## 12.2-test182 (2026-10-18)
- Fix (#39): the PS5 only streams at 30 or 60 fps, and a Custom frame rate such as 45 gave a broken picture (0 fps, all packets lost). The PS5 Frame rate rows (Home and Away, TV and handheld) now offer 60 or 30 only; a Custom value saved earlier uses the nearer of the two. Custom bitrate for the PS5 stays.

## 12.2-test181 (2026-10-18)
- Fix (#40): after a game sent by a handheld ended, the TV kept the handheld's address, and a PS5 or Xbox game started later on the TV itself kept calling that handheld for its controller every few seconds. The TV now forgets the sender when the last stream screen closes; the next game a handheld sends sets it again.

## 12.2-test180 (2026-10-18)
- Fix (#41): the TV's link (GET /status) goes back to "idle" when the last stream screen closes, after a Stop from the handheld, a stop on the TV or the game ending. Before, it kept saying "playing" until the next game. A failed start still reports "failed" with its message until the next game, so the handheld can show why.

## 12.2-test179 (2026-10-18)
- Fix (#43): in a TV number box (Custom bitrate, Custom frame rate, custom resolution), Down on the remote or pad now leaves the box for the Save button, and A in the box saves, as its glyph says. Before, only the keyboard's Enter saved. Also, closing a Custom box without saving no longer leaves the row on Custom: it moves on to the first option.

## 12.2-test178 (2026-10-18)
- Fix (#37 follow-up): ending a PS5 game could still crash the TV app (seen once in test177) when the screen closed before the decoder let go of its surface: the decoder then failed and chiaki copied a frame into a missing buffer. The PS5 screen now releases the decoder first and waits for it (at most half a second) before its surface goes, also when the PS5 itself ended the game; and chiaki drops a frame that has no buffer instead of crashing (native patch `native/mc_decoder_nullbuf_patch.py`).

## 12.2-test177 (2026-10-18)
- Fix (#37 follow-up): ending a PS5 game could still crash the TV app at random on Android 9 (1 of 2 stops on test176, 2 of 3 on test175). On Android 9 any decoder call after the decoder is stopped aborts the app, and chiaki stopped it first: the stream thread then fed it one more frame, and the output thread's wait returned into it. The decoder now stops taking frames and lets the output thread finish first, and is stopped and deleted last (native patch `native/mc_decoder_shutdown_patch.py`, which replaces test176's `mc_decoder_join_patch.py`).

## 12.2-test176 (2026-10-18)
- Fix (#37 follow-up): ending a PS5 game after the PS5 had already closed the stream (Disconnect, then rest mode) could crash the TV app. The decoder was deleted while its output thread was still running; it now waits for that thread first (native patch `native/mc_decoder_join_patch.py`). Only reachable since test174 freed the session.

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
