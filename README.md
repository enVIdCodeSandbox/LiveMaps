# LiveMaps

Multi-tile item-frame maps in Minecraft (**Paper 1.20.4+**) powered by **BlueMap** (embedded WebGL snapshots) and **squaremap** (live auto-updating top-down views).

Players design maps in an interactive web designer served directly by the plugin, receive a kit item in-game, and apply it to any wall, floor, or ceiling grid of item frames.

---

## Features

- **Embedded BlueMap WebGL Studio**
  - Opens your server's own BlueMap Web UI right inside the LiveMaps designer (`/bluemap/`), so players can fly around freely, toggle **Perspective / Flat / Free Flight**, switch **Day / Night** lighting, view caves or markers, align the `width × height` crop box, and click **Take Screenshot**.
  - **No extra open port required:** When BlueMap is installed locally, LiveMaps serves `plugins/BlueMap/web` (`bluemap/web` or BlueMap's configured `webRoot`) and BlueMap's `AssetStorage` directly—you do not need to expose port `8100` to the internet.
  - **In-place snapshot updates:** Reopen `/livemap web`, choose an existing snapshot under **Action**, jump back to its exact saved camera hash, and click **Update** to refresh all placed frames without breaking them.
- **Live Auto-Updating squaremap Views**
  - Create top-down orthographic maps (`1×1` up to configurable `width × height` grids) with configurable rotation, zoom, relief shading, smoothing, and dithering.
  - Re-renders automatically on a per-map **Refresh interval** set in the designer (or `/livemap create ... refresh=<seconds>`), falling back to `update.interval-seconds` in `config.yml` (**10 minutes / 600s** by default) while a player is nearby or holding a tile.
- **High-Accuracy Map Rendering**
  - Linear-light **Lanczos-3** resampling paired with **Oklab** perceptual color quantization (`64×64×64` LUT) and serpentine Floyd–Steinberg or ordered dithering for sharp detail and accurate Minecraft map colors.
- **Clean In-Game Placement & Drops**
  - **No hover holograms:** Placed tiles inside item frames strip custom display names so hovering over a wall map never shows floating nametag clutter.
  - **Smart breaking behavior:** In `INDIVIDUAL` mode (or `ENTIRE` + `entire-drop: TILES`), broken tiles drop as numbered items (`Live Map #<id> (#<tile>)`) matching their position in the grid; in `ENTIRE` + `entire-drop: KIT` mode, picking up a map drops a single unnumbered kit (`Live Map #<id>`).
  - Supports walls, floors, and ceilings, plus protection plugin hooks (WorldGuard, claims, etc.).
- **Survival-Friendly & Reverse-Proxy Ready**
  - Optional per-tile survival item cost (default: 1 empty map per tile; creative & bypass permission are free).
  - One-time `/livemap web` login tokens, `SameSite=Lax` cookies, dynamic asset cache-busting for CDNs/Cloudflare, and craftable **Live Map Designer** compass.

---

## Requirements

- **Server:** Paper `1.20.4` or newer (or Paper-based forks)
- **Java:** Java `17+`
- **Map Plugins (soft dependencies):** [BlueMap](https://bluemap.bluecolored.de/) and/or [squaremap](https://github.com/jpenilla/squaremap) (either one alone works; LiveMaps reads already-rendered map data and never triggers chunk/world renders on its own)

---

## Installation

1. Download `LiveMaps-1.0.3.jar` from [Releases](https://github.com/enVIdCodeSandbox/LiveMaps/releases).
2. Drop the jar into your server's `plugins/` folder and start the server to generate `plugins/LiveMaps/config.yml`.
3. Configure `web.port` (default `8185`) and `web.public-url` (e.g. `https://livemaps.example.com` if behind a reverse proxy for HTTPS).
4. Run `/livemap reload` (or restart the server).

---

## How It Works

1. Run `/livemap web` (or craft and right-click a **Live Map Designer**) to receive a personal one-time browser link.
2. Choose **BlueMap** or **squaremap**, pick the grid size (`width × height` in 128×128 map tiles), dithering, and refresh interval:
   - **BlueMap:** Fly to your shot in the embedded BlueMap viewer, frame it inside the overlay box, and click **Take Screenshot**.
   - **squaremap:** Pan/zoom/rotate the live top-down preview and optionally set a custom per-map refresh interval.
3. Click **Create live map** to receive your kit item in-game (in survival, the configured empty-map cost is deducted).
4. Place an empty item frame on every tile slot of the grid (wall, floor, or ceiling) and right-click the **top-left** frame with the kit item. If any frame is missing or occupied, LiveMaps cancels placement and highlights the missing/occupied coordinates with particles.

---

## Commands & Permissions

Command aliases: `/livemap`, `/lm`, `/livemaps`

| Command | Description | Permission |
|---|---|---|
| `/livemap web` | Sends your personal one-time link to the web designer | `livemaps.use` |
| `/livemap create <bluemap\|squaremap> <map> <w> <h> [key=value…] [confirm]` | Creates a live map directly from chat/console (`x=`, `z=`, `zoom=`, `yaw=`, `tilt=`, `mode=`, `proj=`, `dither=`, `refresh=<sec>`, etc.) | `livemaps.command.create` |
| `/livemap list [all]` | Lists your live maps (or all maps with `all` for admins) | `livemaps.use` / `livemaps.admin` |
| `/livemap info <id>` | Shows source, dimensions, view parameters, refresh interval, and last render status | `livemaps.use` / `livemaps.admin` |
| `/livemap delete <id>` | Deletes a live map and clears its placed item frames | `livemaps.use` / `livemaps.admin` |
| `/livemap give <player> <id>` | Gives a placement kit for an existing live map | `livemaps.admin` |
| `/livemap refresh <id\|all>` | Forces an immediate re-render of auto-updating maps | `livemaps.admin` |
| `/livemap sources` | Lists detected BlueMap / squaremap targets | `livemaps.use` |
| `/livemap reload` | Reloads `config.yml` and worker configuration | `livemaps.admin` |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `livemaps.use` | Everyone | Use `/livemap web`, place kits, use the *Live Map Designer*, list/manage own maps |
| `livemaps.craft` | Everyone | Craft the *Live Map Designer* |
| `livemaps.command.create` | OP | Create live maps via `/livemap create` |
| `livemaps.admin` | OP | Give, delete, refresh, and view all players' live maps; `/livemap reload` |
| `livemaps.bypass.cost` | OP | Create live maps without consuming survival materials |
| `livemaps.bypass.limits` | OP | Bypass size and per-player live map count limits |

---

## Configuration (`config.yml`)

| Section | Key Highlights |
|---|---|
| `web` | `bind-address`, `port` (`8185`), `public-url`, `link-minutes`, `session-minutes`, `max-concurrent-previews` |
| `limits` | `min-width`, `min-height`, `max-width`, `max-height` (`8`), `warn-total-maps` (`12`), `max-live-maps-per-player` (`10`) |
| `cost` | `enabled` (`true`), `material` (`MAP`), `per-map` (`1`) |
| `update` | `interval-seconds` (default `600` = 10 minutes), `active-radius` (`96`), `render-threads` (`2`), `tile-cache-size` (`40`) |
| `breaking` | `mode` (`INDIVIDUAL` or `ENTIRE`), `entire-drop` (`KIT` or `TILES`) |
| `placement` | `check-protection` (`true` — checks protection/claim plugins before filling frames) |
| `bluemap` | `enabled`, `tiles-mode` (`AUTO` \| `FILE` \| `HTTP`), `tiles-root`, `http-base-url`, `public-url` |
| `squaremap` | `enabled` |
| `rendering` | `view-distance`, `max-view-distance`, `detail-bias`, `max-tiles-per-render`, `void-color`, `sky-color`, `defaults.*` |
| `crafting` | `enabled`, custom shaped `recipe` for the *Live Map Designer* |

---

## Building from Source

LiveMaps is written in Kotlin `2.2.21` and built with Gradle (compiled using a Java 21 toolchain targeting Java 17 bytecode, with the Kotlin runtime relocated via ShadowJar to prevent plugin conflicts).

```bash
./gradlew clean test build
```

The shaded plugin jar is output to:

```text
build/libs/LiveMaps-1.0.3.jar
```

---

## License

Licensed under the [MIT License](LICENSE) — Copyright © 2026 Erick Tran.
