# Conversations+

Unofficial fork of [Conversations](https://codeberg.org/iNPUTmice/Conversations) (XMPP chat for Android).
It installs **next to** the official app (package `eu.siacs.conversations.plus`, name "Conversations+").

## What's added
- **Screen sharing in video calls**: in a call, tap the screen → ⋮ → *Share screen*. Android asks for permission each time.
- **Group calls** (XEP-0272 Muji, compatible with monocles chat and my Gajim plugin): in a private group (members-only, members see each other's addresses) tap the phone icon. Everyone in the group sees the call and can join; up to 8 people, everyone connected to everyone.

Group calls are ported from [monocles chat](https://codeberg.org/monocles/monocles_chat) (GPLv3).

## Install + get updates (Obtainium)
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app → `https://github.com/devfbpup/conversations-plus`.
3. Obtainium picks the `arm64-v8a` APK (most phones) and tells you when a new release is out.

Versions are `<Conversations version>.<N>`, e.g. `2.21.0.1`. Each new upstream Conversations release gets merged in.

Not affiliated with the Conversations project. GPLv3, like upstream.
