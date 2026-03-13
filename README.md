# Just-Farming

> A Hypixel Skyblock Garden automation mod for **Fabric 1.21.10**.

---

## Features

### Farming Automation
- Supports all **20 crop types** across standard and S-Shape row layouts:
  - Standard: Wheat, Carrot, Potato, Melon, Pumpkin, Sugar Cane, Cactus, Mushroom, Cocoa Beans, Nether Wart
  - S-Shape: Potato, Carrot, Wheat, Pumpkin, Melon, Sugar Cane, Nether Wart, Moonflower, Sunflower, Wild Rose
- State-machine movement engine with automatic end-of-row detection
- Auto tool switch — selects the best hoe from your hotbar
- **Rewarp system** — teleports back to a configured start position; supports multiple rewarp positions
- Lane-swap delays with configurable min/random jitter
- **Squeaky Mousemat support** — item-ability activation with configurable pre/post/resume delays
- Garden-only safety — stops if you leave the Garden
- Unlocked mouse while macro runs
- **Alternate direction keybind** (`N`) — instantly swaps movement direction mid-row

### Pest Detection & Management
- Reads scoreboard and tab list to track pests across all **24 garden plots**
- Chat alerts when pests appear
- Visual overlays (all toggleable):
  - **Plot border highlights**
  - **Floating labels** — plot number and pest count in-world
  - **ESP boxes** — bounding boxes around pest entities, visible through walls
  - **Tracer lines** — lines from player to each pest
- **Auto pest killer** — pauses farming, flies to each pest, vacuum-fires, resumes farming
- **Warp-to-plot** support

### Visitor Automation
- Automatically walks to each visitor NPC at the barn
- Correctly detects Jacob, Anita, Sam, Phillip, and Pamela as visitors using position-based detection (avoids confusing the permanent garden NPC with the visitor)
- Reads the visitor's offer from the trade GUI
- Accepts or declines based on your configured price limit
- **Auto-buy from Bazaar** — purchases required items before accepting
- **Visitor blacklist** — skip specific visitor NPCs
- **Insta-sell** — optionally sells your inventory before visiting

### Profit HUD
- Tracks farming and pest-kill profits via inventory diff and sack/chat messages
- Shows per-item counts and NPC sell values
- **Profit/Hour** — forward-looking calculation based on current BPS × fortune × NPC price (SkyHanni formula)
- **BPS** — real-time blocks-per-second using a per-second sliding window average
- **Farming Fortune** — auto-detected from the tab list
- Time elapsed counter (pauses when not farming)

### GUI & Config
- Press **`I`** to open the tabbed config screen
- Four tabs: **Farming**, **Pests**, **Misc**, **Delays**
- Settings persist to `config/just-farming.json`

---

## Keybinds

| Key | Action |
|---|---|
| `R` | Toggle macro on / off |
| `I` | Open config GUI |
| `L` | Toggle freelook |
| `N` | Alternate farming direction |

All keybinds are rebindable from the Minecraft Controls menu.

---

## Commands

| Command | Description |
|---|---|
| `/just rewarp` | Set rewarp position to current location |
| `/just rewarp clear` | Clear all rewarp positions |
| `/just visitor` | Start visitor automation |
| `/just pest` | Start pest killer manually |
| `/just farm` | Warp to Garden and start farming |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.10**
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `just-farming-<version>.jar` into your `mods/` folder
4. Launch and press `I` to configure

---

## Building from Source

Requires Java 21.

```bash
JAVA_HOME=/path/to/java21 ./gradlew build
```

Output: `build/libs/just-farming-<version>.jar`

---

## Usage

1. Join your Hypixel Skyblock Garden
2. Press `I`, select your crop, and set a rewarp position
3. Press `R` to start farming
4. Use the **Pests** tab to enable the auto pest killer and visual overlays
5. Use the **Misc** tab to configure visitor automation

> **Note:** Use responsibly. Automated macros may violate Hypixel's rules.

---

## License

MIT
