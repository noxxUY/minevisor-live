# minevisor live

Watch your Minecraft server live in the [minevisor](https://minevisor.cc) workspace:
3D terrain, the map, players moving and blocks changing in real time,
inventories and NBT.

The plugin (or mod) runs a small web server inside your Minecraft server and
the browser connects to it directly. There is no minevisor backend in
between, so nothing to pay for and your world never leaves your machine.

## Platforms

| Platform | Minecraft | File |
|---|---|---|
| Paper | 1.21 – 26.3 | `minevisor-live-bukkit-<version>.jar` |
| Spigot | 1.21 – 26.3 | same jar as Paper |
| Fabric | 26.1 | `minevisor-live-fabric-mc26.1-<version>.jar` (needs Fabric API) |
| Fabric | 26.2 | `minevisor-live-fabric-mc26.2-<version>.jar` (needs Fabric API) |
| Fabric | 26.3 | `minevisor-live-fabric-mc26.3-<version>.jar` (needs Fabric API) |

Tested on real servers: Paper 26.1, 26.2 and 26.3, Spigot 26.3, Fabric 26.1,
26.2 and 26.3, including with a real network client on 26.1.
The Fabric mod also works in singleplayer (the integrated server).

## Use it

1. Drop the jar in `plugins/` (Paper/Spigot) or `mods/` (Fabric) and start the server.
2. Open port `8100` (or whatever `http.port` is).
3. In game, an op runs `/minevisor link` and clicks the link.

Browsers block an https page from talking to plain http, except on
localhost. On a public server, put the plugin behind https (Cloudflare
Tunnel, Caddy, nginx) and set `public-url` to that address.

### Commands

| Command | Who | What |
|---|---|---|
| `/minevisor link` | ops (`minevisor.link`) | The server's link to the live workspace: one per server, the same every time, kept across restarts. Also works from the console (handy on hosting panels) |
| `/minevisor revoke` | `minevisor.admin` (ops) | Replace the link; the old one stops working for everyone (use it if it leaked) |
| `/minevisor status` | ops | Show the address the plugin answers on |
| `/minevisor webhook <url\|off>` | `minevisor.admin` (ops) | Set the Discord webhook; posts a test message to confirm |
| `/minevisor token <token\|off>` | `minevisor.admin` (ops) | Set the Discord bot token; replies with the bot's name or the error |
| `/minevisor status_channel_id <id\|off>` | `minevisor.admin` (ops) | Channel for the live status message |
| `/minevisor status_interval <minutes>` | `minevisor.admin` (ops) | How often the status message is edited (default 10) |
| `/minevisor discord` | `minevisor.admin` (ops) | Show the Discord settings, secrets masked |

The Discord commands save to the config and restart Discord on the spot, no
server restart needed. Secrets are never echoed back in full, and on
Paper/Spigot the console line that would contain them (`issued server
command: …`) is dropped; a line without the secret is logged instead. On
Fabric they need permission level 3.

### Config

Paper/Spigot: `plugins/MinevisorLive/config.yml`. Fabric: `config/minevisor-live.json`.
Same settings on both:

| Setting | Default | |
|---|---|---|
| `http.port` | `8100` | Port the web server listens on |
| `http.public-read` | `false` | Let anyone with the address watch without a link |
| `public-url` | `http://localhost:8100` | How browsers reach this server |
| `web-url` | `self` | Page direct links open (`self`: the page this plugin serves) |
| `relay.url` (`relay-url` on Fabric) | `wss://minevisor-relay.noxxzera.workers.dev` | Relay that lets minevisor.cc reach this server; `off` to turn it off |
| `relay.web-url` (`relay-web-url`) | `https://minevisor.cc/workspace/` | Page relay links open |
| `player-updates-per-second` | `10` | Player updates (position, head, pose, swings, held items); nothing is sent while everyone stands still |
| `discord.status-interval-minutes` | `10` | Minutes between status message edits |
| `refresh-seconds` | `300` | Viewers reread the world this often (and after every full save) to catch changes no event covers; saves nothing |
| `save-interval-seconds` | `0` | Force a save this often while someone watches; off by default since Paper warns plugin-forced saves hurt performance |
| `discord.webhook-url` | empty | Announce server start/stop, joins, leaves and deaths |
| `discord.bot-token` | empty | Bot with `/status` and `/players` |
| `discord.status-channel-id` | empty | Channel where the bot keeps one status message up to date |
| `discord.announce-joins`, `discord.announce-deaths` | `true` | Which webhook announcements to send |

## How it works

```
Minecraft server + plugin  ──HTTP + live events──►  browser (minevisor workspace)
```

- `GET /api/fs/*` serves the world folder (regions, level.dat, player data)
  so the workspace reads it like a local save. Only those files are
  exposed: no server.properties, plugins or logs. Both the classic layout and
  the 26.x one (`dimensions/`, `players/`) work, including Spigot's
  separate `world_nether` / `world_the_end` folders.
- `GET /api/events` streams Server-Sent Events: `players` twice a second,
  `blocks` once per tick with every changed block's full state
  (`minecraft:oak_stairs[facing=north,...]`), `join`, `quit`, `death`, and
  `saved` after each save.
- Paper/Spigot catch block changes through events (breaking, placing,
  explosions, pistons, fluids, fire, growth, doors...). Fabric hooks
  `Level.setBlock` directly, so it sees everything, commands and other mods
  included, even while the server is paused with nobody online.

## Build

Needs Java 25 (Fabric targets it; core and the Bukkit plugin target Java 21).

```bash
./gradlew :bukkit:jar                    # Paper + Spigot
./gradlew :fabric:build                  # Fabric, Minecraft 26.3
./gradlew :fabric:build -Pmc=26.2        # Fabric, Minecraft 26.2
./gradlew :fabric:build -Pmc=26.1.2      # Fabric, Minecraft 26.1
```

Jars end up in `bukkit/build/libs` and `fabric/build/libs`. To add another
Minecraft version, add its Fabric API version to `fabricApiFor` in
`fabric/build.gradle.kts`.

## Layout

```
core/    platform-independent: HTTP server, events, link token, file allowlist, block batching
bukkit/  Paper + Spigot plugin (Spigot API only)
fabric/  Fabric mod (Mojang names, 26.x is unobfuscated)
run/     local test servers (git-ignored)
         run/start.sh <dir> <cmd>            start one with a console FIFO
         run/test-server.sh <dir> <port> <cmd>  start one, run the API/live/view-only checks, stop it
```

## Roadmap

- [x] Live player positions in the 3D viewer
- [x] One view-only link per server, kept across restarts
- [x] Live block updates in 3D, only the touched chunks get rebuilt
- [x] Paper, Spigot and Fabric (26.2, 26.3)
- [x] Discord webhook and bot
- [x] View-only workspace: click a player to follow them with the camera
- [x] Animated players: walking, head and body turning, sneaking, swimming, gliding, sleeping, riding, swings, hits, held items, name tags
