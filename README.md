# Just-Farming

> A feature-rich Hypixel Skyblock Garden automation mod for **Fabric 1.21.10** (Java Edition).

Just-Farming is built specifically for Hypixel Skyblock's Garden. It automates crop farming across all 24 garden plots, detects and visualises pests in real time, and gives you a clean tabbed GUI to configure every aspect of the macro without ever touching a config file.

---

## Table of Contents

- [Features](#features)
  - [Farming Automation](#farming-automation)
  - [Pest Detection & Visualisation](#pest-detection--visualisation)
  - [GUI & Configuration](#gui--configuration)
  - [Keybinds](#keybinds)
- [Installation](#installation)
- [Building from Source](#building-from-source)
- [Usage](#usage)
- [Future Plans](#future-plans)
- [License](#license)

---

## Features

### Farming Automation

Just-Farming drives a full state-machine macro that handles every movement pattern your farm layout needs.

**Supported Crops (20 selectable entries)**

| Mode | Crops |
|---|---|
| Standard | Wheat, Carrot, Potato, Melon, Pumpkin, Sugar Cane, Cactus, Mushroom, Cocoa Beans, Nether Wart |
| S-Shape movement variant | Wheat, Carrot, Potato, Melon, Pumpkin, Sugar Cane, Nether Wart, Moonflower, Sunflower, Wild Rose |

Standard and S-Shape entries use different movement patterns optimised for the two most common garden row layouts. Each appears as its own selectable entry in the crop picker.

**Automation Highlights**

- **State-machine movement** – 11 movement states cover back/forward/left/right and strafe-only patterns, handling any standard garden farm layout automatically.
- **Auto Tool Switch** – Detects and equips the best hoe from your hotbar when the macro starts or when you switch crops.
- **End-of-Row Detection** – Monitors player velocity; if movement stalls for 8+ ticks the macro flips direction automatically.
- **Rewarp System** – Automatically warps back to your farm start position using `/jf rewarp` when you reach a configurable trigger radius, with a configurable delay.
- **Squeaky Mousemat Support** – Auto-activates the Squeaky Mousemat item ability with configurable swap, pre-use, and post-use delays.
- **Lane Swap Delays** – Set minimum and maximum delay ranges for direction switches, with built-in randomisation to keep timings natural.
- **Garden-Only Safety** – The macro prevents itself from running outside the Garden and auto-stops if you leave, protecting your account.
- **Per-Crop Custom Settings** – Override camera yaw/pitch and movement key bindings individually for each crop type.

---

### Pest Detection & Visualisation

Just-Farming parses the Hypixel scoreboard and tab list in real time to track pests across all 24 garden plots.

- **Scoreboard & Tab Parsing** – Reads the "Plots: X, Y, Z" scoreboard widget to know exactly which plots are infested and how many pests are present.
- **Chat Alerts** – Sends a chat notification the moment pests appear in any plot.
- **Plot Border Highlights** – Draws white cube outlines around every infested plot so you can see them at a glance.
- **Floating Labels** – Renders "Plot N (X pests)" labels above each infested plot, with a configurable title scale.
- **Pest ESP** – Draws see-through bounding boxes around individual pest entities so you can spot them through walls.
- **Tracer Lines** – Draws lines from your player position to each pest mob for instant directional awareness.

All visualisation options can be toggled independently from the Pests tab in the GUI.

---

### GUI & Configuration

Press **`I`** (rebindable) at any time to open the configuration screen. Settings are divided into four tabs:

| Tab | What you can configure |
|---|---|
| **Farming** | Crop selection, rewarp position & radius, macro start/stop, per-crop camera & movement overrides |
| **Pests** | Plot highlights, floating labels, ESP boxes, tracer lines, title scale slider |
| **Misc** | Freelook toggle, unlocked mouse (cursor release while macro runs) |
| **Delays** | Rewarp delay, lane-swap min/max timing, Squeaky Mousemat swap/pre/post delays |

All settings are saved automatically to `config/just-farming.json` and persist across restarts.

---

### Keybinds

All keybinds are rebindable from the standard Minecraft Controls menu.

| Key | Action |
|---|---|
| `R` | Toggle macro on / off |
| `I` | Open config GUI |
| `L` | Toggle freelook mode (camera floats behind player; scroll wheel adjusts distance 1.5 – 20 blocks) |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.10**.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Drop `just-farming-<version>.jar` into your `mods/` folder.
4. Launch the game and press `I` to configure before starting your first farm session.

---

## Building from Source

```bash
./gradlew build
```

The compiled jar will be at `build/libs/just-farming-<version>.jar`.

---

## Usage

1. Join your Hypixel Skyblock Garden.
2. Stand at one end of your crop rows.
3. Press `I` to open the GUI:
   - Select your crop from the **Farming** tab.
   - Set a rewarp position if you want the macro to reset automatically.
   - Tune delays and visualisation options to your preference.
4. Click **Start Macro** in the GUI, or press `R` to begin farming.
5. Press `R` again at any time to stop the macro.
6. Use `L` to enable freelook and monitor the farm from a birds-eye view while the macro runs.

> **Note:** Use this mod responsibly. Automated macros may violate Hypixel's rules. This project is provided for educational purposes only.

---

## Future Plans

The following features are actively being planned for future releases:

### 🐛 Auto Pest Killer
An intelligent pest-elimination system that takes full control so you never have to stop farming manually.

- **Pathfinding to pests** – When pests are detected in a garden plot, the mod will automatically navigate to their location using smart pathfinding.
- **Aspect of the Void** – The mod will activate the *Aspect of the Void* sword ability to teleport directly to each pest, dramatically reducing travel time.
- **`/tptoplot <plot>` integration** – Before pathing within a plot, the mod will issue the `/tptoplot` command to jump straight to the correct plot number, combining plot-level teleportation with on-the-ground pathfinding for the fastest possible pest response.
- Fully automatic: the macro pauses farming, kills all pests, then resumes farming — no player input required.

### 🛒 Auto Visitor Offer Acceptance
Automate visitor management without risking bad trades.

- When a visitor appears in your Garden, the mod automatically inspects their trade offer.
- It calculates the **total cost** of the required items and compares it against a **player-configured spending limit**.
- If the trade falls within your limit, the offer is accepted automatically; if it exceeds the limit, the mod skips it and notifies you in chat.
- The cost limit is fully adjustable from the GUI so you stay in control of your coin-per-copper efficiency.

### 🛡️ Auto Armor Set Swap
Seamlessly switch between different armor loadouts without opening your inventory.

- Define multiple named armor sets and assign them to hotkey slots in the GUI.
- The mod will automatically swap to the appropriate armor set based on triggers you configure (e.g. entering a plot, pest combat mode, or a manual keybind).
- Keeps your farming set equipped during normal macro operation and can switch to a combat set when the pest killer activates, then switch back once pests are cleared.

---

## License

MIT
