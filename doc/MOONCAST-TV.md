# MoonCast TV — native Android TV client (fork of moonlight-android)

This is MoonCast's native TV client, forked from the excellent
[moonlight-android](https://github.com/moonlight-stream/moonlight-android) (GPL-3). Moonlight already does the hard
part perfectly: **native MediaCodec hardware decode + Sunshine's own low-latency protocol** (`NvConnection`, RTSP,
ENet, via the `moonlight-common-c` native lib). That is why, on the same Mi TV Stick where a cast-browser page managed
~28 fps and seconds of lag, native Moonlight does 1080p60 with ~15 ms latency (measured 2026-10-15).

## Why fork rather than keep the WebView app
MoonCast's first TV app was a thin shell around the system WebView loading the helper stream. It fixed the picture
(hardware WebCodecs, 1080p60) but inherits the browser WebRTC stack. The native client removes that layer entirely.

## The two transports (the plan)
| Transport | When | How |
|---|---|---|
| **Native (this app, as-is)** | the PC is reachable: home LAN, or JAL over the home↔JAL WireGuard link | Moonlight's own protocol straight to Sunshine. Lowest latency. |
| **Helper fallback** | strict NAT / the PC is not directly reachable | the MoonCast PC helper (WebRTC P2P, no port forwarding). To be added. |

The phone stays the controller in both (MoonCast's InputSession), and launches the TV app by code / LAN discovery
(the MoonCast TV LAN listener is already in the WebView app; it will move here).

## Status
- [x] Forked to `MoonCastDev/moonlight-android`, submodules in.
- [x] Rebranded: applicationId `me.mooncast.tv`, app name **MoonCast TV**, own id (not Moonlight's, as upstream asks).
- [ ] Icon / banner (MoonCast blood-moon).
- [ ] Build + install, confirm native 1080p60 on the stick and the JAL TV.
- [ ] Helper fallback transport.
- [ ] Launch-from-phone (LAN discovery + the `/play` listener) ported from the WebView app.
- [ ] Release signing with MoonCast's key.

## Credit
Built on moonlight-stream/moonlight-android (GPL-3). Huge thanks to the Moonlight authors.
