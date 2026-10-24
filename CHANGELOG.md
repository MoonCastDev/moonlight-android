# Changelog

## Unreleased
- **Xbox sign-in with an account that has no Xbox profile (test266, Saleh: "you can't sign out to start fresh"):** the sign-in kept the Microsoft account even when Xbox network refused it, so it ended "Signed in" with no gamertag, and signing in again went straight back to the same account because the phone's browser was still signed in to it. Now:
  - **Handheld:** "Open the page" opens Microsoft's page inside MoonCast, with the code already filled in and no Microsoft account remembered (a fresh start every time). It closes by itself once you're signed in; Close goes back to the code. Sign out forgets that page's account too.
  - Right after the sign-in MoonCast asks Xbox network about the account. If it has no Xbox profile, is a child account not yet in a family, or can't use Xbox network in its country, nothing is kept and the card says so in plain words ("This Microsoft account has no Xbox profile. Sign in with the account you use on your Xbox."). If Xbox network can't be reached, the sign-in is kept and it says it couldn't check.
  - Someone already in that state sees Settings, Xbox: "Signed in, no Xbox profile", and Sign out is picked in its question. Same words on the TV, which also gets the check (its page is on another device, so the browser part doesn't apply).
- **Xbox closed as soon as it connected (Odin, beta 3, Saleh):** libwebrtc hands MoonCast's low-latency H.264 decoder wrapper a null `DecodeInfo` with a frame; the Kotlin override declared it non-null, so the first frame threw (`NullPointerException … LowLatencyDecoder.decode, parameter info`) and the player closed. The parameter is nullable now and is passed on as is.

## 1.0-beta3 (2026-10-24)
- Beta 3: everything from test244 to test265 below (release-signed, version code 993). Power (Hibernate, Restart, Shut down) is in the Quick menu: Hibernate, Wake and resuming the game were confirmed on Windows by Saleh.
- **A stream that doesn't start now gives up with a reason (TV, test258):** a PC game sent to the TV 0.5 s after the sender left its own stream found the PC still holding that session; the TV's player waited in Moonlight's RTSP handshake for about 30 s with /status "starting" and no error. Now, if the stream hasn't started after 15 seconds, the player tries once more; if the second try hasn't started after 15 seconds either, it stops with "<PC> didn't start the stream. It may still be busy with another device; try again in a few seconds", which the sender reads from /status ("failed"). Handheld streams get the same one retry.
  - /status also no longer says "idle" for a few seconds after a new /play: the player it replaces closing reported it.
  - **Which PC:** a TV that knows more than one paired PC (the Streamer: xserv Gaming and Ahmeds-PC) sent games to "the first one", and the database's order changes as polls rewrite it, so a game could go to the other PC; that PC was running something else and the TV waited on a "quit the running game?" box with /status "starting". The TV now picks the PC the sender names (the handheld now sends pcUuid and pcName), else the one PC whose game list has the game, else (of those that answer) the one it played last, else the first by name; /mc-native's "pc" follows the same rule.
  - When a sent game would need another game quit first, /status now says so ("failed": "<PC> is running another game. Quit it first, then send this one again.") instead of "starting".
- **TV: Quit game from the sender didn't quit on the PC (Streamer, test258):** a TV that had received an identity (identity transfer) and was then force-stopped kept the session identity files, so after the next start it used them for everything: streams worked as the sender, /mc-native said "paired" though the TV itself isn't paired, and the sender sent no identity. /stop then removed those files *before* quitting, so the quit went out with the TV's own, unpaired identity and the PC refused it ("The client is not authorized"); the game kept running. Now:
  - leftover session identity files are removed when the TV app starts;
  - "paired" in /mc-native is asked of the PC with the identity the TV really has (no optimistic yes before the first answer), and asked again when the identity changes;
  - a stream keeps the identity it connected with, so Quit game uses the same one; /stop removes the session identity only after telling the streams to stop;
  - if the PC still refuses the quit, /status says so ("failed", "Couldn't quit <game> on <PC>: …").
- **Wake after Hibernate (handheld; test263, Saleh: "the Wake option doesn't work right away; I have to restart MoonCast"):** MoonCast sent one Wake-on-LAN packet and then only waited. Pressed right after a hibernate, that packet reached the PC while Windows was still writing its memory to disk, before the network card listens for it, so it was lost and nothing sent another (a later Wake, like after restarting MoonCast, worked). Now:
  - Wake sends the packet again every 3 seconds until the PC answers, for up to 2 minutes, also to the network's own broadcast address (some Wi-Fi access points drop 255.255.255.255). The PC's row says "Waking up…", and "<PC> is awake" when it answers (it's asked every 1.5 s meanwhile, with its old answer dropped).
  - "Wake it, then start <game> once it's online" now really starts the game once it's awake (it only woke the PC before); a game pressed while the PC wakes waits for it too. Same for sending to a TV.
  - Back from a stream that ended with Power, the PC's old "online" is dropped at once, so Wake is offered; after Restart MoonCast waits for the PC to come back (up to 4 minutes) and says so.
- **Theme, Background (handheld; test263, Saleh):** Settings, Theme now also picks the background: **Follows the game** (the selected game's colour washes over it, as before; the default) or **Static** (the theme's plain background, pure black with OLED). It applies on every page and is remembered.
- **Stats while playing, Lite (test263, Saleh: "make it like the previous version"):** Lite is the one-row card from test259 again (health dot, fps, delay, Mbps, lost, decode and the codec, big values with small labels), centred at the top of the screen, instead of the fps-and-delay pill. Detailed is unchanged. PC, PS5 and Xbox.
- **Quick menu (PC): Power moves up**, above Move to TV, Disconnect and Quit game. It was last, below Quit game, so on a phone it sat off the bottom of the list (OnePlus 8 Pro, test260). The red items that leave the game are last again, as on the iPad. Its icon, the ⏻ power sign, showed as an empty box with Android 12's fonts; Power, Shut down and Turn off the Xbox now use ◉.
- **Stats while playing: Off, Lite, Detailed (test259, Saleh):** Settings, Picture, Stats while playing is now a choice of three, and the Quick menu's Streaming status steps Off → Lite → Detailed. PC, PS5 and Xbox; handheld and TV.
  - **Lite:** a small pill centred at the top: the health dot, frame rate and delay. Nothing else.
  - **Detailed:** a panel in the top-left corner. On PC: resolution and codec (with 4:4:4 / HDR), frames shown and received, bitrate, network delay ± variance, decode time, the PC's encode time (average, min, max), frames lost on the network and dropped by the decoder, and the decoder's name. PS5 and Xbox show what their players measure (frame rate, delay, bitrate, lost packets, decode time on PS5).
  - Someone who had Stats while playing on gets Detailed (closest to Moonlight's old text); off stays off.
- **Quick menu stays open (test259, Saleh):** actions that don't leave the game now do their thing and keep the menu up: Task Manager, PS button, Touchpad, Xbox button, Streaming status, Controllers (and muting a pad), and Power while it only opens its list. The item's line briefly says what happened ("Opened on the PC", "Sent to the PS5"), or shows the new value (Streaming status). These still close it: Disconnect, Quit game, Power's final confirm, Move to TV, Put PS5 to rest, Turn off the Xbox, Pair a controller, and Keyboard (the on-screen keyboard needs the screen). Power's and Controllers' lists have a Back item; Cancel on a Power confirm goes back to the Power list. Handheld and TV.
- **Power (Quick menu) now runs the command (test259, Saleh):** Start opened and the command was typed, but Enter opened Start search's best match instead of running it. MoonCast now goes through Start to the **Run** box (Ctrl + Esc, types "run", Enter), then selects what's in Run, types the command and presses Enter. Known limit: "run" finds the Run app on English Windows; Windows in another display language may call it something else.
- **OLED theme (handheld):** Settings, Theme has a third choice, **OLED**: a pure black background (an OLED screen turns those pixels off, which saves battery), very dark grey panels and tiles, the same white type and cyan ring. The in-game Quick menu and the licence pages follow it.
- **Stats while playing, new look (PC, PS5, Xbox):** one compact card in the top-left corner instead of lines of text: a dot (green, amber or red by delay and loss), then the numbers in a row, big and bold with small labels: fps, delay, Mbps, lost, decode and, on PC, the codec ("HEVC", "HEVC 4:4:4", "AV1 HDR"). It keeps clear of a camera cutout and only redraws when a number changes. The Quick menu's Streaming status still turns it on and off.
- **"HEVC" instead of "H.265"** in Settings (Picture, PlayStation, Away) and the PS5's stream info, on the handheld and the TV. Saved choices don't change.
- **Power in the Quick menu (PC):** one item, **Power**, opens Hibernate, Restart and Shut down (and Back). Each asks first with Cancel picked, then types the command into Start on the PC as Hibernate did (`shutdown /h`, `shutdown /r /t 0`, `shutdown /s /t 0`), and the stream ends with "<PC> is hibernating / restarting / shutting down" once the PC stops answering. Windows only.
  - **Hibernate fix:** after waking, Steam Big Picture started fresh instead of showing the game (1.0-beta2 left Hibernate out for this). Sunshine forgets the running game across hibernation, so starting it again ran its command once more. MoonCast now remembers that the PC was hibernated while playing; the next start of a game on that PC opens the PC's **Desktop** app instead ("Resuming <game> on <PC>"), where the game still is. The mark goes once used, when the PC reports a running game, after 7 days, or after Restart or Shut down. **Needs a Windows test (Ahmed).**
- **YUV 4:4:4 for HEVC (handheld):** Settings, Picture, **YUV 4:4:4 (HEVC)** (off by default) asks the PC for full colour on every pixel, so small text and coloured edges stay sharp. It needs Sunshine with 4:4:4 on the PC (else the stream stays 4:2:0, as before) and an HEVC decoder that offers 4:4:4 output; on a device without one the row says so and nothing changes. It's part of the Home/Away profile like the other picture settings.
- **Touch screen (handheld):** the in-game Quick menu no longer has Touch screen (Off, Mouse, Touch). It's set in Settings, Handheld, Touch screen, and now starts as **Touch** (the pointer goes where you tap) unless you picked something else there. Off still moves the PC's pointer out of the picture.
- **Identity transfer on release builds (TV):** a release-built MoonCast TV (1.0-beta2, for one) answered with an empty `identityKey`, so a phone or iPad could never send it its identity and a TV with no pairing of its own couldn't play PC games from it. R8 had removed BouncyCastle's X25519 classes, which the provider only loads by name; a keep rule brings them back. Debug builds were never affected.
- **Store icon:** `ic_launcher-web.png` (the 512 px icon stores and some tools pick up) was still Moonlight's wheel, left from the fork. It's now the MoonCast moon on #1A1630, the same picture the launcher icon shows. Nothing on the device changes.

## 1.0-test257 (2026-10-23)
- **Add to home screen** (handheld): a PC game (ES-DE on xserv Gaming, for one), a PS5 or an Xbox gets an icon on the device's own home screen. Long press the tile (or X on Home), Add to home screen, and confirm in the launcher's box. The icon is your own picture, else the box art the tile shows, else its cover with the name. Tapping it opens MoonCast and starts the game here the usual way (it waits for the PC, offers to wake it, and asks first when another game runs). Android 8 and newer. (A box art held as a hardware bitmap crashed the icon drawing on the Odin; it's copied first.)

## 1.0-test256 (2026-10-22)
- Fixes from the UI review:
  - **Menus show all their options:** dialog rows are a little tighter and the room is worked out more truly, so a PC game's menu (Play here, Play on a TV, Change picture, Hide…) no longer hides options below a scroll.
  - **One Frame pacing** for PC and PS5, in Picture ("PC and PS5…; Xbox paces itself"). The PlayStation group's own Frame pacing is gone; the PS5 now reads the Picture one (Moonlight's Cap FPS counts as Balanced). A PS5-only choice saved before is no longer used.
  - **Add apps on Home** is a quiet tile (plain, with + and its name) instead of a bright cover.
  - **Top bar:** "Playing on <TV>" takes at most half the screen and a long TV name ends in "…" (it could run off the screen upright).
  - **Settings groups:** the selected group has the same rounded corner as the rest of the app, and the focus ring follows it.

## 1.0-test255 (2026-10-22)
- UI review on the Odin (test254, 20 screens): Home, All games (every tab), Add apps, the game menus, Consoles and every Settings group. Measured: cold start 327 ms; janky frames 0.6% moving along Home, 0.4% scrolling All games, 0.5% moving down the Settings groups (5-12 ms frames).
  - Fixed: lists ran on under the hint bar (Consoles' "Find my Xbox" drew over the controller name; a sliver of All games' next row showed below it). A view is clipped by its parent and the content area doesn't clip, so the All games grid, the list pages and the Settings list now sit in a frame that clips them.

## 1.0-test254 (2026-10-22)
- UI review, round 2:
  1. **Phones** (no built-in controller): tabs, chips, Back and the settings groups are at least 48 dp tall (they were 23-35 dp on a 1080p phone), and text follows Android's Font size (up to 1.3x) on the usual layout. Handhelds with controls (the Odin) look the same.
  2. **One row style** for Consoles, Settings and dialogs: the same icon square (72, corner 18), title weight, subtitle spacing and a value on the right in the quiet colour.
  3. **Quick menu**: Quit game and other dangerous items are red on the handheld too, the hints read A then B on both apps, the upright sheet's list scrolls so the hints never fall off, and TV rows grow for a long label instead of being cut.
  4. **All games**: no shadow per grid tile (smoother scrolling with many PC games), and a redraw in the background keeps your place in All games and in Home's row instead of jumping back to the start.
  5. **TV**: lays itself out again when the display changes (HDMI mode, resolution) instead of keeping the old scale until a restart, and covers without box art use the handheld's colours (`Covers`), so a game has the same colour on both.

## 1.0-test253 (2026-10-22)
- UI review, first round (handheld): faster and lighter, same look.
  - Settings: moving down the groups rebuilt the whole screen at every step; it now waits until focus rests on a group (160 ms).
  - Home and All games work out the games list once per redraw instead of 3 times, and "On this device" keeps its list, app names and icons in memory (refreshed when MoonCast comes back to the front, for installs and updates meanwhile).
  - Tile pictures: which tiles have one is read from disk once, not checked per tile per redraw; the picture cache counts bytes (about 24 MB), and each picture's colour is worked out once.
  - Add apps loads the app list and icons off the main thread; switching Games / All apps reuses them.
  - The focus ring's breathing stops while MoonCast isn't in front (it redrew the ring 60 times a second in the background).
  - Focus and scroll listeners were added again on every rotation or fold; now once.
  - Stats corner and Quick menu: the numbers are only set when they changed (a new text lays the player out again).
  - L1/R1 on All games skipped the This <device> tab.
  - The focus ring fits the Add apps chips and app cells.
  - TalkBack: tiles, the round buttons and Back say what they are.

## 1.0-test252 (2026-10-22)
- Adding more apps was hard to find (only All games, This <device>, Add apps). Now also: an **Add apps** tile on Home, always there before All games, and **Settings, Handheld, Apps on this <device>** (shows how many are added). All three open the same picker.

## 1.0-test251 (2026-10-22)
- Added Android apps "disappeared after a while" (Saleh on the Odin):
  - **Home** kept the last-played games first and cut the row at ten, so after about ten games played your added apps (and the PS5 and Xbox) fell off Home. Now the row is: what's running, the last five played, then every console and every added app (they always keep a place), then PC games up to ten.
  - **Add apps** started with the first app selected and the hints said "A OK", so an A meant to close the page took that app out (Azahar, first in the list). Focus now starts on Done, the hints say "B Done, A Add or remove", and each tap says "<app> added" or "<app> removed. Tap it again to add it back".

## 1.0-test250 (2026-10-22)
- **Change picture** for any game tile on the handheld: a PC game, a PS5, an Xbox or an Android app. Long press the tile (or X on Home), Change picture, and pick an image with Android's photo picker. MoonCast keeps a resized copy (longest side 900 px) in its own storage and shows it filling the tile, darkened at the bottom so the name stays readable; the background wash takes its colour. **Reset picture** goes back to MoonCast's own look.
  - `home/CustomArt.kt`. PS5 and Xbox tiles now have a menu too (Play, Change picture).

## 1.0-test249 (2026-10-22)
- On this device, a better look:
  - **Tiles** take their colour from the app's own icon (its strongest hue, toned down so the name stays readable), with a big faded copy of the icon leaning in from the corner and the icon itself larger, above the name. The background wash follows the same colour. Grey or white icons keep the usual covers.
  - **Add apps** is a wide page with a grid of the apps' real icons and names instead of a text list: Games / All apps chips with counts, "N added" at the top, and a ✓ and green ring on what's added. A tap adds or removes at once without the grid jumping back to the top. MoonCast's own copies (a beta next to a test build) aren't listed.

## 1.0-test248 (2026-10-22)
- On this device, tested on the Odin (test247): the tab, the picker (emulators listed first as games), adding, opening (Back returns to MoonCast) and Remove from MoonCast (the app stays installed) all work. The Add apps tile showed only "+": it now says Add apps.

## 1.0-test247 (2026-10-22)
- **On this device** (handheld): Android apps you pick show in MoonCast next to your PC, PS5 and Xbox games, like ES-DE's Android apps. Nothing is added by itself.
  - All games has a new tab, **This <device>** (This Odin), starting with **Add apps**: the installed apps, games first, each ticked or not. A pick adds or removes it straight away and the list stays open.
  - The apps are tiles with their own icon and an "Android" badge, on Home and in All games, and open the app. Long press (or X on Home): **Open** or **Remove from MoonCast** (the app stays installed).
  - `home/DeviceApps.kt`; the manifest's `<queries>` lets MoonCast see launcher apps only (no QUERY_ALL_PACKAGES).
  - Play on TV doesn't apply to these yet: they open on the device (#99).

## 1.0-test246 (2026-10-22)
- **Anonymous usage check-in** (opt-in), so the project can count daily and monthly active users (`home/Usage.kt`, TelemetryDeck's ingest API v2, no SDK):
  - Official builds ask once, a moment after the home screen opens: "Help improve MoonCast?" with **No thanks** selected. Settings, About has the switch afterwards.
  - With yes: at most once a day, one signal with the app version, TV or handheld, Android version, device model, and whether PC, PS5 or Xbox was played since the last one. The install is a random ID made on the device, sent hashed. No names, addresses, games or accounts.
  - Only builds made with an app ID (`-PmcTelemetryAppId` or `MOONCAST_TELEMETRY_APP_ID`, plus the TelemetryDeck organization's namespace, `-PmcTelemetryNamespace` or `MOONCAST_TELEMETRY_NAMESPACE`, sent to `/v2/namespace/<namespace>/` like TelemetryDeck's SDKs) have it; everything else, forks and this test build included, never asks and never sends. Debug builds send in TelemetryDeck's test mode.
  - TV: `confirm()` can pick its no button and run something on no.
- README: install section for the beta's file names, **Obtainium** links for automatic updates (prereleases on, release titles `^MoonCast [0-9.]+( |$)`, one APK filter per app), and a **Privacy** section.

## 1.0-test245 (2026-10-22)
- **"On battery, stream at" is removed** (Settings, Handheld, and `home/BatteryCap.kt`, added for #65 in test237). It saved little: the screen uses most of the battery and doesn't change, and Native resolution already avoids streaming more pixels than the screen shows. With it goes test244's Auto-bitrate fix. A saved setting is simply ignored.

## 1.0-test244 (2026-10-22)
- **Quit prompts pick Cancel.** "Quit X and start Y?", "Quit game?", Unpair and Remove now have Cancel selected, on the handheld and the TV, so pressing A (or OK on a remote) by habit doesn't do them. On 2026-10-21 an A on the pre-selected "Quit and start" quit Ryujinx. Wake it stays selected, since it's harmless.
  - Handheld: while a dialog is open the hints say "B Cancel, A OK" instead of the page's "A Start", and a tapped A hint presses the dialog's selected button, not the game behind it.
  - Handheld: a PC game moves to the front of Recent only once it really starts, not when the prompt opens, so Cancel no longer reshuffles the tiles.
- **On battery with bitrate Auto:** the bitrate was still the one for the uncapped size (80 Mbps for 4K on a 720p stream). It's now worked out for the capped size (10 Mbps for 720p60, Moonlight's usual Auto).

## 1.0-test243 (2026-10-22)
- Touch screen (Quick menu, PC), found by Saleh on test242:
  - The PC's pointer stayed in the picture with touch Off. With Off, MoonCast now moves it to the middle of the right edge, where it's off the screen, when the stream starts and when you switch to Off. (Not a corner: Windows' bottom-right corner is Show desktop.)
  - New: Settings, Handheld, **Touch screen** (Off, Mouse, Touch, default Off) sets how each PC stream starts. The Quick menu still changes it for the game you're in.
- Quick menu: with more items than fit (the PC menu now has up to 9), the last ones were out of reach with a controller, so Hibernate PC looked gone. The list now scrolls with the selection, on the handheld and the TV (the TV's list didn't scroll at all).

## 1.0-test242 (2026-10-22)
- Hibernate PC worked on Ahmed's PC (test240/241), but MoonCast stayed on the last picture, frozen: a hibernating PC just stops sending video, and Moonlight only notices after a long timeout. After Hibernate PC, MoonCast now watches the video: after 3 seconds without a new frame it checks whether the PC still answers (its http port), and if not it leaves the stream with "<PC> is hibernating". The game isn't quit; it stays as it was in the hibernated PC.

## 1.0-test241 (2026-10-22)
- In-game Quick menu, PC: **Touch screen** (Off, Mouse, Touch), for the stream it's in. Every stream starts Off, so a hand on a handheld's screen can't click on the PC.
  - Mouse: the screen is a trackpad (Moonlight's relative touch: drag moves the pointer, tap clicks, two fingers right-click and scroll).
  - Touch: the pointer goes where the finger is and a tap clicks there (Moonlight's absolute touch).
  - A stylus and a real mouse work in every mode. The on-screen controller isn't affected.
- Moonlight's "Use the touchscreen as a trackpad" (All settings) is gone; the Quick menu's switch replaces it.

## 1.0-test240 (2026-10-22)
- Hibernate PC still didn't work on Ahmed's PC: the Run box never opened, so Win + R didn't arrive (games often turn the Windows key off while they play). It now opens Start with Ctrl + Esc, which doesn't use the Windows key, types `shutdown /h` into Start's search and presses Enter 2 seconds later.

## 1.0-test239 (2026-10-22)
- Hibernate PC didn't work on Ahmed's PC (Task Manager and Streaming status did). The power menu's U and H only match on an English layout and English Windows. It now opens Run (Win + R), types `shutdown /h` as text (Sunshine types text the same on any keyboard layout) and presses Enter. That also works without Hibernate ticked in Power Options; it needs hibernation itself on, which most PCs have (`powercfg /h on` turns it on).

## 1.0-test238 (2026-10-22)
- In-game Quick menu, new items:
  - **Streaming status** (PC, PS5 and Xbox): turns the stats corner on or off right away, and saves it as Settings' "Stats while playing". The PC shows Moonlight's stats; PS5 and Xbox get a new one-line corner (frame rate, network delay, bitrate, lost packets, and decode time on PS5).
  - **Task Manager** (PC): presses Ctrl + Shift + Esc on the PC, which opens Task Manager on Windows. It does nothing on Linux or macOS PCs.
  - **Hibernate PC** (PC): asks first, with Cancel picked, then presses Win + X, U, H: the Windows power menu's Hibernate. Windows only lists Hibernate when it's turned on in Power Options; without it, the power menu stays open on the PC. When the PC goes down within a minute, the stream ends with "<PC> is hibernating" instead of an error. Wake the PC from MoonCast's home.

## 1.0-test237 (2026-10-21)
- Handheld, "On battery, stream at" (Settings, Handheld) now works (#65). It was saved but nothing read it. Unplugged, PC and PS5 streams go no higher than the chosen height (1080p, 720p or 540p); plugged in, the Picture settings apply as before.
  - PC: the size keeps the stream's shape (Native 2448 × 1696 at 720p becomes 1040 × 720). The fold check compares the screen with the size before the cap, so a capped stream doesn't keep reconnecting at the screen's size.
  - PS5: the lower of the PS5's own setting and the cap.
  - Xbox: not affected; its resolution comes from the Xbox service.

## 1.0-test236 (2026-10-21)
- Found on the JAL TV (AQUOS, Android 11, MediaTek with a Mali GPU), PS5 over PlayStation Network at 720p:
  - Super resolution didn't start there ("eglChooseConfig"): the GPU has no OpenGL ES 3 setup for both a window and an off-screen surface. FSR now tries several setups, and without an off-screen surface it works surfaceless. Before, it fell back to the plain picture, as designed.
  - PS5 decoder: the TV's decoder (OMX.MS.HEVC.Decoder) refused the low-latency settings, and MoonCast dropped all of them. Now it drops them one step at a time (vendor settings, then MediaTek's low-latency decode, then Android's standard low-latency mode, then real-time priority), so a decoder keeps as many as it takes. The log says which level was used.
- The same JAL TV test passed: PS5 over PSN on OpenSSL 3 (connected in 84 s including waking the console), 60 fps, 25 ms network delay, no lost packets, Decode time 20.5 ms, and Put PS5 to rest from the Quick menu.

## 1.0-test235 (2026-10-21)
- Super resolution now works on PC and Xbox streams too, not only PS5. The FSR passes are shared (`FsrPasses`), used by:
  - PC: Moonlight's decoder renders into FSR's surface. FSR stays off with HDR, since its passes are 8-bit. The screen surface keeps the screen's size instead of the stream's, so FSR has room to upscale into; with Stretch, the picture is stretched by FSR.
  - Xbox: WebRTC's renderer draws each frame through FSR's drawer (`FsrGlDrawer`), with WebRTC's hardware scaler off so the surface keeps the view's size. Frames that aren't hardware-decoded, and GPUs without OpenGL ES 3, are drawn by WebRTC's own drawer, as before.
  - PS5: as in test234.
- In all three, FSR only takes effect when the stream is clearly smaller than the screen; otherwise the picture shows as before.

## 1.0-test234 (2026-10-21)
- Super resolution (Settings, Picture, both apps): AMD FidelityFX Super Resolution 1.0, for the PS5 first (mockups in docs/mockups/fsr). With it on, and the stream clearly smaller than the screen (a PS5's 1080p or 720p on the Fold's 2448 × 1696, say), each frame takes three GPU passes before it shows:
  - a copy of the decoder's frame at the stream's size;
  - EASU, FSR's edge-adaptive upscale to the screen's size;
  - RCAS, FSR's sharpening, at the new Sharpening setting (0 to 100%, 60% by default).
- How it fits in:
  - The shaders are ports of AMD's ffx_fsr1.h (MIT) to OpenGL ES 3.0. Its 4-texel gathers are 4 texel reads, as ES 3.0 has no textureGather.
  - Frames keep the decoder's presentation time, so Frame pacing still works.
  - On a foldable, a fold only moves FSR's output; the decoder keeps drawing into FSR.
  - Off by default. When it's not worth it (the stream is as big as the screen) or the GPU can't set it up, the stream shows directly, as before.
- About, Open-source licences: AMD FidelityFX Super Resolution 1.0 (MIT).

## 1.0-test233 (2026-10-21)
- PS5: frame pacing (Settings, PlayStation, Frame pacing: Lowest delay, Balanced, Smoothest), like the PC's.
  - Lowest delay (the default, as before): each frame shows the moment it's decoded, so frames that arrive unevenly over Wi-Fi show unevenly.
  - Balanced and Smoothest: each frame is handed to Android to show one stream frame interval after the last (1/60 s at 60 fps), so they land on evenly spaced screen refreshes. That costs at most 1 (Balanced) or 3 (Smoothest) frames of extra delay. When frames pile up past that, it shows the next one at once, so the delay can't build up.

## 1.0-test232 (2026-10-21)
- PS5: a decoder setting (Settings, PlayStation, Decoder: Low latency, Ultra low latency, Standard). The PS5 player decodes in chiaki-ng, which set up the device's video decoder with nothing but the format and size. It now gets the same low-latency settings as MoonCast's PC player.
  - Low latency (the default): Android's standard low-latency mode, real-time priority, MediaTek and Amlogic's low-latency decode, and Qualcomm's low-latency decode.
  - Ultra low latency: Artemis' presets on top, notably software output fencing on Snapdragon 8 Gen 2 and newer (the Odin 2 Portal's chip), plus the MediaTek and NVIDIA presets.
  - Standard: as before.
  - A decoder that refuses any of these settings starts again without them.
- PS5 Quick menu: a fifth number, Decode time (from a frame reaching the decoder to its picture coming out), so the settings can be compared.

## 1.0-test231 (2026-10-21)
- Handheld, Settings, Picture: three settings that were only in About, All settings (Moonlight's screen); MoonCast TV already had them.
  - **Unlock all frame rates** (under Frame rate): adds 144 and 240 to Frame rate, and Custom frame rate goes up to 240. Turning it off brings a rate above 120 back down to 120.
  - **Custom bitrate** (under Bitrate): any bitrate from 0.5 to 150 Mbps in 0.5 steps, as Moonlight's slider allows. Bitrate shows "Custom" then.
  - **Full range colour** (under HDR): experimental, off by default.

## 1.0-beta1 (2026-10-21)
- The first beta for testers: test230's app as a signed release build (version 1.0-beta1, code 991, so 1.0 installs over it), published as a pre-release.
- Release builds: code shrinking (R8) had removed what PS5 Remote Play's native code reads by name (`ConnectInfo` with its `psnToken` and `videoProfile`, and `Session`), so a release build couldn't start a PS5 session. Debug builds don't shrink, so the test builds never showed it. `proguard-rules.pro` keeps `com.metallic.chiaki.**` and Google's WebRTC (`org.webrtc.**`, Xbox). All 48 fields and 9 methods the PS5 library looks up are now present in the release build.
- `scripts/release.sh`: `VERSION_NAME=1.0-beta1 VERSION_CODE=991` builds a beta; `app/build.gradle` takes `-PmcVersionName` and `-PmcVersionCode`.
- Issue forms: MoonCast's "Something doesn't work" (app, version, device, what was played, home or away, what happened) and "Idea or wish" replace Moonlight's. Moonlight's issue bots' settings (stale, no-response, auto-comment, which pointed to Moonlight's Discord) are gone.

## 1.0-test230 (2026-10-21)
- PS5: leaving the PS5 screen while it connects really stops the attempt. Before, an attempt through PlayStation Network carried on in the background for up to a minute and could still connect after you'd left. It then held the console, so the next try got "Connection refused" and the PS5 refused its stream (seen on the Odin).
  - Leaving cancels the PSN attempt in flight at once (a new `psnCancel` in chiaki-ng-mooncast).
  - An attempt that finishes after you've left is dropped, never started.
  - A new PS5 screen waits (at most 15 s, "Finishing the last attempt…") for the last one's attempt to end.
- PS5: when a console PSN just woke, or one just switched on, answers on the home network, MoonCast switches from PSN to the home connection: seconds instead of a minute-long hole-punch. It asks the home network every 4 s while a PSN attempt runs.

## 1.0-test229 (2026-10-21)
- Version: MoonCast's own numbering. The version is 1.0 (code 1000) instead of Moonlight's 12.2 (315); test builds are now 1.0-testN. The code only ever goes up (1.0.1 = 1001, 1.1 = 1100), so each release installs over the one before it.
- Release signing: release builds are signed with MoonCast's own key, which is never in git. `keystore.properties` sits next to `settings.gradle`, or `MOONCAST_KEYSTORE` names it; without it, release builds come out unsigned, as before. `.gitignore` keeps out `keystore.properties`, `*.jks` and `*.keystore`.
- `scripts/release.sh`: builds both signed release APKs (`out/MoonCast-<version>.apk`, `out/MoonCastTV-<version>.apk`) and prints their signing certificate.
- CI: `.github/workflows/build.yml` builds both apps and runs lint from a clean clone on every pull request and every push to master, keeping the APKs and lint reports. While chiaki-ng-mooncast is private, the runner needs a `SUBMODULES_TOKEN` secret to clone it.

## 12.2-test228 (2026-10-21)
- PS5: the encryption library inside chiaki-ng is OpenSSL 3.5.9 (the 3.5 long-term line, security fixes until 2030) instead of 1.1.1w, which has had no security fixes since September 2023. It secures the PSN sign-in, playing away from home and the PS5 connection itself.
  - chiaki-ng's own unit tests pass on OpenSSL 3.5 (114 of 114: PSN certificate chains, stream encryption, registration). The console's key exchange isn't among them, so a PS5 session is the real test.
  - The PS5 library is about 3 MB bigger (OpenSSL 3 is larger).
- About, Open-source licences: OpenSSL 3 is under Apache-2.0 (1.1.1 had its own licence).
- Build: buildChiaki also runs when chiaki's cmake files change.
- Build: chiaki's build script starts the native build clean when chiaki's build files change. The OpenSSL upgrade otherwise failed on a machine that had built before (old configured headers left in the cache).

## Unreleased
- CONTRIBUTING.md: how to report problems, build, and send changes (one topic per pull request, CHANGELOG in each, lint and CI, the chiaki-ng submodule, no game pictures or secrets, licences). SECURITY.md: report privately through GitHub's security advisories, and what MoonCast keeps where.
- README: rewritten for MoonCast (it was still Moonlight's). It covers what the two apps play and how, the features, install and Sunshine's gamepad setting, building with the chiaki-ng submodule, the licences and the credits.

## 12.2-test227 (2026-10-21)
- About, Open-source licences (both apps; mockups in docs/mockups/about): the 23 open-source projects MoonCast is built on, grouped The app, PS5, Xbox and More, each with what it does and its licence. Pressing one shows its full licence text; Back returns to About.
- About, Source code: github.com/MoonCastDev, GPL-3.0 with the PS5 and Xbox parts under AGPL-3.0 (on a phone it opens the page).
- The licence texts ship in the app (assets/licences, 176 KB; the GPL, AGPL and Apache texts once each), and NOTICE in the repo lists the same projects.
- Xbox: XStreaming, whose protocol the Xbox code follows, is AGPL-3.0 (not MIT, as assumed before), so that part is listed as AGPL.

## 12.2-test226 (2026-10-21)
- PS5 on Android 5 (5.0 and 5.1): starting a PS5 stream crashed. Picking the home bitrate asked Android for the active network, which only exists from Android 6, and the error it throws there isn't one the code caught. Below Android 6 it now skips the Ethernet check and goes by Wi-Fi speed.
- MoonCast TV on Android 5: the service that keeps the TV reachable for the phone crashed as it started (it looked up the notification service the Android 6 way).
- Only MoonCast TV tells Android it supports TVs (it has the TV launcher entry). The handheld app, which has none, no longer claims it. This was also the build check (lint) error.

## 12.2-test225 (2026-10-21)
- Build: PS5 Remote Play's native libraries are now built from source. Before, `libchiaki-jni.so`, `liboboe.so` and `libc++_shared.so` sat only on the build machine (git ignores `jniLibs`), so a fresh clone built an app without PS5, and the chiaki-ng changes behind them were in no repository.
  - The patched chiaki-ng is a submodule, `third-party/chiaki-ng` (MoonCastDev/chiaki-ng-mooncast: upstream chiaki-ng plus one MoonCast commit, AGPL-3.0).
  - A `buildChiaki` task runs before every build and calls `third-party/chiaki-ng/mooncast/build-jni.sh`; it does nothing when chiaki's sources haven't changed.
  - Needs `git submodule update --init --recursive`, CMake 3.22.1 in the Android SDK and protoc on the PATH.
- No change to the app itself.

## 12.2-test224 (2026-10-20)
- On-screen controller, Xbox skin (mockup vx2 in docs/mockups/onscreen-pad). Settings, Controls, "On-screen controller" now goes Off, PlayStation, Xbox, Classic.
  - The real Xbox arrangement: left stick up on the left, the d-pad lower beside it, A B X Y on the right with the right stick below.
  - Same sizes, spacing and transparency as the PlayStation skin: LT over LB, RT over RB, the Xbox (guide) button between the sticks, View, Share and Menu along the bottom middle, MoonCast's menu key at the top middle.
  - A B X Y in their Xbox colours; a one-piece d-pad on a round base; the triggers shaped like triggers.
  - The PC is told it's an Xbox pad (when no real controller is attached), so Share works.
- The PC needs Sunshine's gamepad setting on "auto" (its default) for either skin to show up as that pad; a forced type (like "xone") wins.

## 12.2-test223 (2026-10-20)
- On-screen controller, PlayStation skin, after the first try (mockups v9 to v14 in docs/mockups/onscreen-pad):
  - The d-pad and the shape buttons sit higher (60% down the screen instead of 73%); the sticks moved in closer under them.
  - The d-pad, the shape buttons and the shoulder keys are 10% bigger, with 5% more room between them, so a thumb doesn't catch the wrong one.
  - The shoulder keys are stacked in the top corners: L2 over L1, R2 over R1.
  - A PS button between the sticks, above the touchpad, as on the DualSense (the controller's home button).
  - Half see-through by default. Settings, Controls, "Controller transparency" (shown with the PlayStation skin): 0% to 85%.
  - Create, Touchpad and Options labels are drawn solid with a dark edge, so they read over bright scenes.

## 12.2-test222 (2026-10-20)
- On-screen controller, PlayStation skin (#74, first step), for PC games. Settings, Controls, "On-screen controller": Off, PlayStation or Classic (Moonlight's outline keys, as before).
  - Same engine as Moonlight's on-screen controller (touch, sliding a thumb from key to key, what's sent to the PC); only the look and the layout are new.
  - The look of the v8 mockup: dark keys over faint see-through plates, the game showing through.
  - Laid out on Delta's landscape positions: L1 L2 ... R2 R1 along the top, the d-pad lower left, the shape buttons lower right, both sticks low between them, Create, touchpad and Options along the bottom middle, and MoonCast's menu key at the top middle (opens the Quick menu; it replaces Moonlight's gear, which sat where L1 is).
  - A very light tick under the finger on each press, each new d-pad direction and each stick touch.
  - The PC is told it's a PlayStation pad (when no real controller is attached), so games show the PlayStation symbols and the touchpad click works. Double tap a stick for L3 / R3.
  - PS5 and Xbox Remote Play don't use it yet.

## 12.2-test221 (2026-10-20)
- MoonCast's own logo (R2, the synthwave moon: a sunset-striped moon on a night sky, mockups in docs/mockups/logo on design/handheld-compact) replaces Moonlight's wheel everywhere it showed:
  - the app icon on every device (all sizes; Android's adaptive icon with a night-sky background, and a one-colour version for themed icons);
  - the Android TV banner, which still said "Moonlight Game Streaming": now the logo with "MoonCast, Play your PC, PS5 and Xbox";
  - the TV app's channel icon on the Android TV home screen;
  - the handheld's Home: the logo instead of the orange circle with an initial.
- Moonlight's unused images (its app icon and the OUYA banner) are gone.

## 12.2-test220 (2026-10-20)
- Handheld on its side: everything sat far in from the left. Android reserves the whole edge for the small camera hole (104 px on the Fold's cover), and MoonCast's own margin came on top of it. Now the content keeps all of a system bar but only as much of the cutout as its margin doesn't already cover, so it moves about 90 px left and still clears the camera.

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
