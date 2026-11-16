# ChatCraft Server Plugin (owner program)

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

This Spigot/Paper plugin (1.8.8+, Java 8) gets your server **free visibility in
the ChatCraft app's server list**. After a one-time verification, the app shows
your server to players browsing for servers — featured placement, language
matched, no payment and no ads on your side.

## Install

1. Drop `ChatCraft-<version>.jar` into your server's `plugins/` folder and
   start the server once (it creates `plugins/ChatCraft/config.yml`).
2. Optionally edit `plugins/ChatCraft/config.yml`:
   - `name` — display name in the app (default: your `server.properties` motd)
   - `listing: false` — stay verified but stop appearing in the Featured list
   - `languages: [en, pt]` — languages your server is listed under
   - `address: play.example.com:25565` — your public address (used by `/chatcraft verify`)
   - `vote-links:` — up to 3 https vote links shown in the app
3. In game, as an operator: `/chatcraft verify` (or `/chatcraft verify host[:port]`).
4. Open the printed link **on your phone with the ChatCraft app** and confirm.
   The app relays a one-time token to your server; verification completes and the
   plugin stores credentials in `plugins/ChatCraft/credentials.yml`
   (file permissions are set to owner-only, `rw-------`).
5. Check `/chatcraft status` any time: verified or not, listed or not, and the
   last heartbeat result.

## What the plugin sends

Every 60 minutes (and once at startup and right after a successful
verification) the plugin sends one HTTPS heartbeat to
`https://api.chatcraft.app/owner/v1/heartbeat` with exactly these fields —
**nothing else, no player data is ever sent**:

```json
{
  "name": "My Server",
  "languages": ["en"],
  "listing": true,
  "premium": true,
  "gameVersion": "1.8.8",
  "pluginVersion": "1.0.0",
  "voteLinks": ["https://vote.example.com"]
}
```

The heartbeat is authenticated with `Authorization: Bearer <affiliationId>:<key>`
from `plugins/ChatCraft/credentials.yml`. **The key is a bearer secret: it is
never logged and never sent anywhere except that header.** If the backend
answers 401, the plugin logs "key rejected, run /chatcraft verify again" —
re-run `/chatcraft verify` to get fresh credentials.

Verification itself (`/chatcraft verify`) sends only your server address
(`host`, `port`) and your player UUID; the token that comes back is single-use,
expires after 15 minutes, and is never logged.

A Featured server that sends no heartbeat for 1 day stops being served, so
keep the plugin enabled.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/chatcraft` or `/chatcraft help` | everyone | Lists only the commands the sender may use (click one to type it), with a hint when the server is not verified yet |
| `/chatcraft verify [host[:port]]` | `chatcraft.admin` (ops by default) | Starts verification; prints the link to open in the ChatCraft app |
| `/chatcraft status` | `chatcraft.admin` (ops by default) | Shows verified/listed state and the last heartbeat result |
| `/chatcraft staff [host[:port]]` | `chatcraft.staff` (ops by default) | Prints the single-use enrolment link (15 min) for a staff phone |
| `/chatcraft staff list` | `chatcraft.admin` (ops by default) | Lists the enrolled staff phones by name, platform and last seen |
| `/chatcraft staff remove <name>` | `chatcraft.admin` (ops by default) | Removes a staff member's enrolled devices by name |
| `/chatcraft alert <text>` | `chatcraft.admin` (ops by default) | Raises a console alert to all enrolled staff devices |
| `/report <player> [reason]` | any player, when `reports: true` | Raises a report alert (rate-limited by `report-cooldown-seconds`) |

## Staff toolkit (stage 2)

Players you grant `chatcraft.staff` to (in your permission plugin; ops have it by
default) enrol a phone once: `/chatcraft staff` prints a link
(`https://api.chatcraft.app/s?...`); open it in the ChatCraft app, which connects to
your server, registers the `chatcraft:staff` plugin channel and enrols the device.
From then on every alert — a player's `/report` or your `/chatcraft alert <text>` —
is sent to every enrolled device as an **encrypted push** (AES-256-GCM with a
per-device key made by the app; the relay only sees ciphertext, push tokens and IPs)
and, when the staff member is online, also in-session. In the app the staff member
sees the alert and can run your `staff-actions` templates (kick, mute, ...), which
the app sends as ordinary chat commands **as the staff player** — your server's own
permissions apply; the plugin never runs commands for staff.

Limits and rules: 3 devices per player, 30 per server; devices not seen for 30 days
are pruned, and a join without `chatcraft.staff` removes the player's devices on the
spot. The plugin sends at most 100 pushes per hour across all staff, counted per device;
players' `/report` alerts may use at most 60 of them so console alerts always have
room. A batch that only partly fits is sent in part; the excess is dropped with a log
line and in-session delivery is unaffected. Pushes are sent on a background thread, so
a slow relay never stalls the server. A staff phone that is connected but in the
background gets the alert in-session silently, so if its push is dropped it is not
notified at all. A join without `chatcraft.staff` removes the player's devices, so a
permission plugin that loads late or grants it per world can drop a valid enrolment
(the staff member enrols again). Behind ViaVersion the channel name can differ between
plugin and app; staff messages are then not delivered. Pushes go through the
relay's signed `POST /v1/push` route with per-server, per-IP and global budgets.

Known gap: a staff member who loses `chatcraft.staff` and never joins again keeps
receiving report pushes until you run `/chatcraft staff remove <name>` or their
devices go stale after 30 days. Removing the ChatCraft app / deleting the server in
the app stops alerts on the phone immediately, but the plugin keeps the device entry
until removal or staleness (the phone may be offline, so the plugin is not told).

## Build

```
./gradlew build
```

produces a single `build/libs/ChatCraft-<version>.jar` (shadow jar, no
obfuscation). Compile target is Java 8; the Spigot API 1.8.8 is `compileOnly`
and Gson is the one shipped inside the server jar, so the plugin bundles
nothing extra.

## Releasing

Versions come from `version` in `build.gradle` (the plugin's `plugin.yml` and the
heartbeat's `pluginVersion` follow it). To release, bump it, commit, then push a tag
that matches it:

```
git tag v1.0.1 && git push origin v1.0.1
```

The `Release` workflow checks that the tag equals the version, runs the tests, builds
the jar, checks that it is Java 8 bytecode, and publishes a GitHub release with
`ChatCraft-<version>.jar` and its SHA-256. Uploading to Hangar, SpigotMC and Modrinth
is a manual step for now; the release jar is the file to upload.

## License

Apache License 2.0, see `LICENSE`. The plugin talks to the ChatCraft service
(`api.chatcraft.app`, `relay.chatcraft.app`); the service itself is not part of this
repository.
