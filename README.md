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
| `/emage debug`                             | Show the live send rate, queue and measured delay of every player (`emage.admin`). |

## Slow connections
Map data is never pushed at full speed. Every player has their own sender that keeps a tiny ping packet running through the
same connection while pictures are transferred and slows down as soon as Emage's own traffic makes that player wait
(`performance.target-queue-delay-ms`), so ping stays low even on weak internet. Nearby pictures are sent first, pictures
that are out of range or no longer needed are dropped from the queue, and animations are only streamed to players whose
connection can keep up with them (they see the still first frame otherwise, never a blank frame).

A GIF in which no frame changes is treated as a still picture, exactly like a PNG/JPG.

Large pictures are fed on demand: only what the player can use soon is queued, ordered by distance and by what they are looking at, so even a 32x32 wall never builds a backlog. To allow grids that big raise `quality.max-image-grid-size`.

The limits live in the `performance` section of `config.yml`.

## Building
```bash
mvn clean package
```
The compiled jar will be output to the `target/` directory.
