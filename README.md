# Emage 🎨 (Custom Server Fork)

> **Note:** This is a custom fork of Emage, maintained and specifically optimized with improvements for our custom server. It includes critical bug fixes (e.g., DecoderException chunk ban patches), memory leak fixes, and protocol alignment for Minecraft 1.21.

A highly-optimized image and GIF renderer built for modern Paper servers. By completely bypassing Minecraft’s heavy default map API, Emage uses a custom packet pipeline to stream media directly to your players. You get the freedom to decorate your world with smooth animations without compromising server performance.

## Requirements
* **Java 22**
* **Paper 1.21** or higher
* **[PacketEvents](https://modrinth.com/plugin/packetevents)** (Required dependency)

## Installation
1. Place the `PacketEvents.jar` and `Emage-VERSION.jar` into your `plugins/` folder.
2. Start the server.
3. Edit `plugins/Emage/config.yml` to adjust various limits or customize messages.

## Usage
To display an image, place Item Frames on a wall in a rectangular grid. Look at one of the frames and run the apply command.

### Commands
| Command                                    | Description                                            |
|--------------------------------------------|--------------------------------------------------------|
| `/emage apply-grid <columns> <rows> <url>` | Manually specify the grid size to apply the image/GIF. |
| `/emage apply <url>`                       | Auto-detects the frame grid and applies the image/GIF. |
| `/emage remove`                            | Remove the grid and its files. (Requires creator or `emage.admin` permission) |
| `/emage reload`                            | Reload the configuration.                              |
| `/emage debug`                             | Show the plugin version, limits, every player's send rate / queue / measured ping, and every animated GIF with who watches it. The full report with every detail is also saved to `plugins/Emage/debug/report-<time>.txt` (`emage.admin`). |

## How pictures reach players
Pictures are stored on the server (`plugins/Emage/data`); a URL is only used once, when you run `/emage apply`. After that
every player is sent exactly the map data they need, never faster than their connection and their game can take it.

- Every player has their own sender that keeps a tiny ping packet running through the same connection while pictures are
  transferred and slows down as soon as Emage's own traffic makes that player wait (`performance.target-queue-delay-ms`).
  The default target is 20 ms, short random spikes (VPN, far-away players) are filtered out, and the speed follows the
  player's real connection up to 8 MB/s (`performance.max-bytes-per-second-per-player`), so a fast connection gets big GIFs
  at full speed and a slow or distant one gets what fits without its ping going up. The whole server is capped too
  (`performance.max-total-bytes-per-second`).
- Still pictures are sent on demand: nearest and most-looked-at maps first.
- A GIF is not a video stream with a fixed schedule. The server keeps the current state of every GIF and sends each player only
  the part that changed since the last thing they received. A weak connection or a busy client simply gets fewer frames per
  second, but always up-to-date ones; nothing ever has to wait for the loop to start over.
- GIFs animate for players who are close (`performance.animated-view-distance-blocks`, 48 blocks by default, measured to the
  nearest frame of the picture) and look towards them; farther GIFs update less often. Far away they show a still frame at no cost.
- A player's game rebuilds a whole map texture on every map update, so it can only digest a limited number of updates per
  second. When the visible GIFs together need more than the player's budget (`performance.max-bytes-per-second-per-player`,
  8 MB/s by default), Emage animates the GIF the player is looking at and the cheapest ones, and pauses the rest on their
  current frame, instead of making all of them stutter and raising the ping (`performance.limit-animated-gifs`). `/emage debug`
  shows how many GIFs animate for each player and which ones are paused.
- A GIF in which no frame changes is treated as a still picture, exactly like a PNG/JPG.
- Memory: pictures nobody has been near for a while are dropped from memory and loaded again when somebody comes back
  (`cache.idle-release-seconds`).

- When several GIFs compete for one player's connection, the bandwidth is shared by bytes (smaller pictures get a slightly bigger
  share), so small and medium GIFs keep playing smoothly next to huge ones, which get what is left. A huge GIF with motion on every
  pixel needs far more data than any connection has (a 16x16 grid is about 4 MB per frame), so it plays at the frame rate the
  player's connection allows; nothing queues up and nobody's ping rises.

## Behind Velocity / BungeeCord
Emage measures the whole path (player - proxy - server), so a proxy needs no special setting and every player gets the speed of
his own connection. For the best result set `network-compression-threshold=-1` in the `server.properties` of every backend
server behind the proxy (the Velocity FAQ recommends this: most of the proxy's CPU goes to decompressing and recompressing
packets, so only the proxy should compress): otherwise each map packet is compressed by the backend, decompressed by the proxy
and compressed again, which costs a lot of CPU when many maps are streamed. Emage warns about this at startup and in `/emage debug`. Keep
`performance.max-total-bytes-per-second` at what your backend-to-proxy link and the proxy's CPU can carry.

`/emage debug` shows per player the send rate, queue and measured delay, and per GIF how many players it is being played for.
Pictures up to 16x16 and new GIFs up to 10x10 are allowed (`quality.max-image-grid-size`, `quality.max-gif-grid-size`); GIFs that already exist are not affected.
The first start of a new version raises old default values in `config.yml` (`config-version: 5`) and keeps anything you changed.

## Building
```bash
mvn clean package
```
The compiled jar will be output to the `target/` directory.
