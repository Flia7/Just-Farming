# Just-Farming

> A Hypixel Skyblock Garden automation mod for **Fabric 1.21.10**.

---

## Features

### Farming Automation
- Supports all **20 crop types** across standard and S-Shape row layouts:
  - Standard: Wheat, Carrot, Potato, Melon, Pumpkin, Sugar Cane, Cactus, Mushroom, Cocoa Beans, Nether Wart
  - S-Shape: Potato, Carrot, Wheat, Pumpkin, Melon, Sugar Cane, Nether Wart, Moonflower, Sunflower, Wild Rose
- State-machine movement engine with automatic end-of-row detection (stuck threshold)
- Auto tool switch — selects the best hoe from your hotbar automatically
- **Rewarp system** — teleports back to a configured start position at a set radius; supports multiple rewarp positions
- Lane-swap delays with configurable min/random jitter for human-like timing
- **Squeaky Mousemat support** — triggers block-attack packets for item-ability activation with configurable pre/post/resume delays
- Garden-only safety — stops the macro automatically if you leave the Garden
- Unlocked mouse — cursor remains accessible while the macro runs
- Macro-enabled-in-GUI — continue farming while the config screen is open
- **Alternate direction keybind** (`N`) — instantly swaps the movement direction mid-row

### Pest Detection & Management
- Reads the scoreboard and tab list to track pests across all **24 garden plots**
- Chat alerts when pests appear
- Visual overlays (all toggleable):
  - **Plot border highlights** — coloured borders around infested plots
  - **Floating labels** — plot number and pest count displayed in-world (configurable scale)
  - **ESP boxes** — bounding boxes drawn around pest entities, visible through walls
  - **Tracer lines** — lines drawn from the player to each pest entity
- **Auto pest killer** — automatically pauses farming, flies to each pest, vacuum-fires, and seamlessly resumes farming afterwards
- **Warp-to-plot** support — teleports to the infested plot before killing

### Visitor Automation
- Automatically walks to each visitor NPC at the barn
- Reads the visitor's offer from the trade GUI
- Accepts or declines offers based on your configured price limit per visitor
- **Auto-buy from Bazaar** — optionally purchases required items from the Bazaar before accepting
- **Visitor blacklist** — permanently skip specific visitor NPCs

### Randomisation & Timing
- Per-crop configurable speed (BPS), yaw, and pitch overrides
- Randomised jitter applied to rewarp delays, lane-swap delays, and mousemat timing
- Smooth camera rotation driven at render-frame rate (~3° per frame at 60 FPS) for natural-looking movement

### GUI & Config
- Press **`I`** to open the tabbed config screen
- Four tabs: **Farming**, **Pests**, **Misc**, **Delays**
- Settings persist automatically to `config/flia.json`

---

## Keybinds

| Key | Action |
|---|---|
| `R` | Toggle macro on / off |
| `I` | Open config GUI |
| `L` | Toggle freelook (scroll wheel adjusts camera distance 1.5–20 blocks) |
| `N` | Alternate farming direction (instant row swap) |

All keybinds are rebindable from the Minecraft Controls menu.

---

## Commands

| Command | Description |
|---|---|
| `/just rewarp` | Set the rewarp position to your current location |
| `/just rewarp clear` | Clear all rewarp positions |
| `/just visitor` | Start the visitor automation routine |
| `/just pest` | Start the pest killer manually |
| `/just farm` | Warp to the Garden and start farming |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.10**
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `flia-<version>.jar` into your `mods/` folder
4. Launch and press `I` to configure

---

## Building from Source

```bash
./gradlew build
```

Output: `build/libs/flia-<version>.jar`

---

## Usage

1. Join your Hypixel Skyblock Garden
2. Press `I`, select your crop, and set a rewarp position
3. Press `R` or click **Start Macro** to begin farming
4. Press `L` for freelook while the macro runs
5. Use the **Pests** tab to enable the auto pest killer and visual overlays
6. Use the **Misc** tab to configure visitor automation and safety options

> **Note:** Use responsibly. Automated macros may violate Hypixel's rules. This project is for educational purposes only.

---

## License

MIT
