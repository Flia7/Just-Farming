# Just-Farming

> A Hypixel Skyblock Garden automation mod for **Fabric 1.21.10**.

---

## Features

**Farming Automation**
- Supports all 20 crop types across standard and S-Shape row layouts
- State-machine movement with automatic end-of-row detection
- Auto tool switch, rewarp system, and lane-swap delays with randomisation
- Squeaky Mousemat support with configurable timing
- Garden-only safety — stops automatically if you leave

**Pest Detection**
- Reads the scoreboard and tab list to track pests across all 24 plots
- Chat alerts, plot border highlights, floating labels, ESP boxes and tracer lines
- Auto pest killer: flies to each pest, vacuum-fires, and resumes farming

**Visitor Automation**
- Walks to each visitor, reads the offer, and accepts or declines based on your price limit
- Optionally buys required items from the bazaar before accepting

**GUI & Config**
- Press **`I`** to open the tabbed config screen
- Four tabs: **Farming**, **Pests**, **Misc**, **Delays**
- Settings save automatically to `config/just-farming.json`

---

## Keybinds

| Key | Action |
|---|---|
| `R` | Toggle macro on / off |
| `I` | Open config GUI |
| `L` | Toggle freelook (scroll wheel adjusts distance 1.5–20 blocks) |

All keybinds are rebindable from the Minecraft Controls menu.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.10**
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `just-farming-<version>.jar` into your `mods/` folder
4. Launch and press `I` to configure

---

## Building from Source

```bash
./gradlew build
```

Output: `build/libs/just-farming-<version>.jar`

---

## Usage

1. Join your Hypixel Skyblock Garden
2. Press `I`, select your crop, and set a rewarp position
3. Press `R` or click **Start Macro** to begin
4. Press `L` for freelook while the macro runs

> **Note:** Use responsibly. Automated macros may violate Hypixel's rules. This project is for educational purposes only.

---

## License

MIT
