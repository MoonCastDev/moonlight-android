# Changelog

## 12.2-test219 (2026-10-20)
- Handheld on its side (the Fold's cover screen): the background and its colour wash stopped short of the camera cutout and the status bar, leaving a strip down the left and across the top. They now go edge to edge; the content and dialogs stay clear of the bars and the cutout.
- The row of tiles fades out at its edges, so a tile cut at the edge reads as "more this way" instead of a hard cut.
- On a touch screen (no controller connected) the round buttons always show their labels, spaced wider so long ones don't touch, and Home shares out spare height (a third above, the rest below) on its side too, as it does upright.

## 12.2-test218 (2026-10-20)
- Handheld: "Hidden games" in a PC's menu (Consoles), when it has any: lists the games hidden from All games (also ones the PC no longer lists), each shown again with a press, or "Show all". Hiding a game now points there.
- "Manage PCs" (Moonlight's own PC list) is gone from the handheld, in Consoles and in Settings, About: everything it offered for players is in MoonCast now (pair, wake, unpair, remove, resume, quit, games, network test, add by address, the away address, and now hidden games).
- The two remaining ways into Moonlight's PC list open MoonCast's home instead: the full settings screen after a language change (Android 12 and older), and a shortcut that names a PC but no game.

## 12.2-test217 (2026-10-20)
- Clean-up: the test-only fold trace (the yellow box on PC streams, test211) is gone; the fold steps are still written to the log ("MoonCast fold:").
- No "Moonlight" anywhere you can see it, in both apps:
  - Handheld: "Manage PCs" now says "The full PC list" (its badge is PC, not ML); hiding a game says to show it again from the PC's full game list; "All Moonlight settings" is "All settings" (also on the TV); a pad-link error says "The PC manager didn't start".
  - Text from Moonlight (network test, decoder crash, a few setting descriptions, the language setting, the local-network permission) now says MoonCast, in every language. The network test no longer tells you to install Moonlight's Internet Hosting Tool: to play away from home it says to connect a VPN to home or open Sunshine's ports.
  - No more Help buttons that opened Moonlight's website: the old error dialogs, the PC list's help button and its GameStream FAQ, and the network test's Help.

## 12.2-test216 (2026-10-20)
- Foldables: one stream shape for both screens, so folding or unfolding mid-game just re-fits the picture, instantly, with no reconnect and no game restart. MoonCast remembers the screens it runs on; once it has seen two clearly different shapes (the Fold's cover, 1.58 : 1, and its inner screen, 1.32 : 1), Native is a shape between them (1.45 : 1, 2448 × 1696 on the Fold). Picture shape decides how it fills each screen: Fill (the default on a foldable) trims about 8% (4% per edge), Stretch keeps everything and bends it about 8%, Fit leaves thin bars. Settings shows "Native suits both your screens". Until both screens have been seen, the first fold still restarts the game once (test215); after that a stream that ends on a fold only reconnects. Phones and handhelds with one screen keep the screen's own Native.

## 12.2-test215 (2026-10-20)
- PC streams with Native, at Saleh's request: folding or unfolding mid-game now restarts the game by itself on the new screen ("Restarting the game for this screen…"): it quits the game on the PC and launches it again, so the PC uses the new screen's shape and the picture fills it. A reconnect alone kept the other screen's size inside the new frame. Unsaved progress in the game is lost on each fold or unfold. The Quick menu's "Restart at this size" and its hint (test213) are gone.

## 12.2-test214 (2026-10-20)
- PS5 streams now keep the picture's 16:9 shape and follow Picture shape and Zoom (Settings, Video), like the PC and Xbox players: the video view used to fill the whole screen, so the PS5's 16:9 picture was stretched to the screen's shape (a little on the Fold's cover, about a third taller on the open screen). Fit keeps bars, Fill crops to cover the screen, Stretch fills it. It re-fits by itself when the screen changes size, so a fold or unfold keeps the right shape. On a 16:9 TV, Fit looks as before.
- Xbox streams: Stretch is worked out again when the screen changes size (it was worked out once at the start). Fit and Fill already followed the screen.
- PS5 and Xbox never get "Restart at this size": a console streams 16:9 at its own resolution whatever the phone asks for, and Remote Play can't restart a console game. (A fold doesn't hide the app on the Fold, it only swaps the video surface, which the PS5 and Xbox players already carry over; to confirm on the device, #68.)

## 12.2-test213 (2026-10-20)
- PC streams: after a fold or unfold reconnects the stream, the PC can still be drawing at the other screen's size (a PC sets its resolution when a game launches), so bars stay. A hint now says so, and the Quick menu (Back, or Select + Start + LB + RB) has "Restart at this size": it quits the game on the PC and starts it again here, so it launches in this screen's shape. Unsaved progress in the game is lost, so it's only offered, never done by itself. Only PC streams, only after a fold reconnect.

## 12.2-test212 (2026-10-20)
- Fix, found with the test211 trace on the Fold ("Native false"): picking Resolution in Settings could leave it showing Native with the screen's size saved but Native's switch off, so the stream kept that size after a fold. The page redrew before the pick's switch was saved, and the redraw put the screen size back. The pick is now saved first. A saved size that is the current screen's own now counts as Native, which repairs phones already in that state (open MoonCast or Settings on that screen once). The test trace stays for one more try.

## 12.2-test211 (2026-10-20)
- TEST ONLY: a small yellow text box at the top left of a PC stream traces what the phone reports, with the time: FOLDED or UNFOLDED and the stream's size at start, each screen-size change, the video surface lost or replaced, the stream screen hidden or shown again, and the check 1.5 seconds after a change (screen size, stream size, connected, Native, and whether it reconnects). To find why unfolding doesn't reconnect; it comes out again once that's fixed.

## 12.2-test210 (2026-10-20)
- Fix (test207-209): unfolding mid-game kept the stream going at the cover's size and never reconnected. The size check ran only at the moment the screen changed, when the new screen's size may not be known yet, and it measured the display's mode, which can still report the other panel for a moment. It now checks again once the screen settles, after every change, and measures the app's own full-screen window. The "MoonCast:" log lines show the sizes it saw.

## 12.2-test209 (2026-10-20)
- Fix (test208): unfolding mid-game still left a stopped stream. On the Fold the screen switch replaces the video surface without hiding the app, and losing the surface ends the stream (Moonlight's own behaviour); the new surface only ever started the first connection. Now a new surface after one lost mid-stream reconnects, at the new screen's size with Native ("Fitting the stream to this screen…"). The other two ways in (shown again within 2 seconds, or a size change) stay, and only one reconnect happens. Log lines starting "MoonCast:" trace it.

## 12.2-test208 (2026-10-20)
- Fix (test207): unfolding the Fold mid-game ended the PC stream instead of reconnecting. Switching screens hides the app for a moment, and the stream screen closed as soon as it was hidden (Moonlight's own behaviour), so the reconnect never ran. Now, when hidden, it ends the stream as before but waits 2 seconds before closing; if it comes back in that time (a fold or unfold), it reconnects at the new screen's size ("Fitting the stream to this screen…"). Leaving the app or turning the screen off closes it 2 seconds later, as before. PS5 and Xbox streams still end on a fold (issue filed).

## 12.2-test207 (2026-10-20)
- PC streams with Resolution on Native: folding or unfolding a foldable mid-game now reconnects by itself at the new screen's size, about 1.5 seconds after the screen settles ("Fitting the stream to this screen…"; a short blackout, the game keeps running on the PC). The Quick menu's "Fit this screen" is gone. Not in picture-in-picture. (With a PC that sets its own resolution only when a game launches, the PC keeps its desktop size until the game is started again.)

## 12.2-test206 (2026-10-20)
- Handheld: no 16:9 choice any more. Resolution is 720p, 1080p, 1440p, 4K or Native, and Native is always the whole screen in its own shape (an open Fold 2448 × 1848, folded 1968 × 1248; on other handhelds their full screen, rounded to multiples of 8), following the screen a stream starts on. An earlier Native (16:9) becomes this one.

## 12.2-test205 (2026-10-20)
- Handheld, from trying test204 on the Fold: "Native" showed twice (in Resolution and in its own Native shape row). There's now one list: 720p, 1080p, 1440p, 4K, 16:9 and Native. Native is the whole screen in its own shape (an open Fold 2448 × 1848, folded 1968 × 1248); 16:9 is the widest 16:9 that fits (2448 × 1376 / 1972 × 1108). Both follow the screen a stream starts on. On a 16:9 screen they're the same, so only Native shows. The Native shape row is gone. A new install starts on Native; an earlier Native stays 16:9 until changed.

## 12.2-test204 (2026-10-20)
- Handheld: "Native shape" under Resolution (with Native on): 16:9 (as before, the largest 16:9 that fits) or Whole screen, which streams in the screen's own shape, rounded to multiples of 8 for the PC's display modes: an open Fold 2448 × 1848 (about 4:3), folded 1968 × 1248. The picture then fills the screen with no bars and goes under the camera cutout too; the PC draws its desktop and menus (ES-DE) in that shape, and a 16:9 game shows its bars on the PC side instead. "Fit this screen" in the Quick menu uses the chosen shape.

## 12.2-test203 (2026-10-20)
- Handheld: Resolution "Native" now means the screen you play on, each time a PC stream starts: on a foldable, the cover screen's size when folded and the inner screen's when open (2448 × 1376 instead of the cover's 1972 × 1108 on the Fold). It used to be saved once, as the size of whichever screen the app first opened on. A size you pick (720p to 4K) stays fixed. An earlier saved screen size counts as Native.
- In-game Quick menu (PC streams): after folding or unfolding mid-game, "Fit this screen" reconnects at the new screen's size; the game keeps running on the PC. It shows only with Native on and when the size really differs. (With a PC that sets its own resolution when a game launches, the PC side stays at the size it launched at until the game is started again.)

## 12.2-test202 (2026-10-20)
- Handheld polish: a dialog with many options (a PC's menu) showed four and cut the fifth near its top edge. It now shows as many as the screen has room for, and when it has to scroll it cuts a row through the middle so it's plain there's more. The symbols in the Consoles list (+) fill their squares like the dialogs'.

## 12.2-test201 (2026-10-20)
- Handheld: a PC keeps a home address and an address for away (a name like home.example.com, or a public IP), and this device tries both and uses whichever answers. The PC's menu in Consoles now lists them ("At home", "Away", which one is in use) and has "Address for away" to add, change or remove it. It's saved without trying it, because at home an internet address usually can't be reached. Adding a PC by its address did keep a typed internet name already, but nothing showed it.
- Adding a PC by an internet name from home, when nothing answers, now says to use Address for away on the PC instead.

## 12.2-test200 (2026-10-20)
- Fix (test199): on the Fold's cover screen Home's bottom row of round buttons ran under the bottom bar. Home measured its height including the status bar and navigation, so it thought two rows of tiles fit. It now lays out inside them (also Samsung's taskbar on an open Fold).
- Handheld on a big screen on its side (an open Fold): the 1920 x 1080 canvas made everything 1.3 times a phone's size, so only one row of tiles fit and the grid never showed. On its side it now keeps a phone's real size too: a centred grid of tiles (five across, two rows on the Fold) with every round button labelled.

## 12.2-test199 (2026-10-20)
- Handheld UI polish, no new features (mockups in docs/mockups/small-touches on design/handheld-compact):
  - Spacing: the two rows of round buttons sit 48 apart (were 24); the spare height on a tall screen goes a third above Home and the rest below, so there's no big gap over the tiles; the name line sits closer to its tiles.
  - Header: the avatar and the "This device" pill are the same height, on one line.
  - Tiles: rounder corners, a soft shadow, equal gaps; the focus ring follows the rounder corners.
  - Round-button labels a step bigger and brighter.
  - The bottom bar lines up with the content edge (it sat further in).
  - Dialogs: symbols fill their icon squares (the + and dot were tiny), rounder cards and rows, and the buttons are real rounded buttons side by side instead of text under a line.
  - The Network test shows its result in the app's own dialog instead of the old grey Moonlight one (same text, with Help and OK).
  - Tiles, round buttons and dialog buttons press in a little under a finger.
- Handheld on big screens (an open foldable, a tablet: 600 dp and more): upright keeps a phone's real size and fits more (three tiles across on an open Fold, as many rows as fit); on its side a screen with room for two rows gets a centred grid instead of one row in the corner, with every round button labelled.

## 12.2-test198 (2026-10-20)
- Back to test193, without the two-player work: player 2 on a TV (test194) and player 2 on its own screen (test196/197, #58) are parked for later on their branches feat/player-two and fix/joiner-resolution. The TV link is back to version 6.
- Kept from that work, because it isn't two-player: the fix for a keyboard or TV-remote Back that closed the in-game Quick menu and then opened it again (#57). PC, PS5 and Xbox streams.

## 12.2-test193 (2026-10-19)
- Fix: clicking a DualSense's (or DualShock 4's) touchpad did nothing in PS5 Remote Play on newer devices (Pixel, Retroid, Odin). There Android shows the touchpad as a separate small mouse, so its click came as a mouse button, not a controller button. The PS5 screen now sends it as the touchpad button. Only a Sony touchpad counts.

## 12.2-test192 (2026-10-19)
- Handheld: the status bar and navigation bar take the app's background colour instead of black (Android 14 and older), so the menus look like one surface.

## 12.2-test191 (2026-10-19)
- Handheld: the menus keep the phone's own status bar and navigation, like any app, instead of hiding them and drawing MoonCast's own clock and battery. Content stays clear of the status bar, the navigation and the camera cutout. Games are still full screen.

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
