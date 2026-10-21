# Security

MoonCast handles pairing with your PC, and sign-ins to PlayStation Network and Xbox. If you find a way to get at
someone's pairing, sign-in or console, or a way into MoonCast or the devices it talks to, please report it **privately**:

- **[Report a vulnerability](https://github.com/MoonCastDev/moonlight-android/security/advisories/new)** (GitHub's private security advisory). Only the maintainers see it.

Please don't open a public issue for it. Include what you found, how to reproduce it, and which version (Settings, About).
We'll reply as soon as we can and agree with you on when the fix and the report are made public.

What MoonCast keeps, and where:
- **PC pairing certificates:** stay on the device. When you send a game to a MoonCast TV, they go to that TV for the session only, encrypted, over your own network.
- **PlayStation Network and Xbox sign-ins, and a PS5's registration:** stay on your own devices (moving a game to your MoonCast TV hands what it needs to that TV, over your own network) and go only to Sony and Microsoft.
- **Release builds:** signed with the MoonCast key (certificate SHA-256 `ac7cce06b53c84cdfdaf7f05c29479e332b434bd5799dc5716cba632173dff07`). Don't install a "MoonCast" signed with anything else.

Supported versions: the latest release and the latest beta.
