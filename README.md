# Just-Farming

A Hypixel Skyblock farming macro mod for **Fabric 1.21.10** (Java Edition).

## Features

- **GUI Configuration Screen** – press `I` (default) to open
  - Select which crop to farm (Wheat, Carrot, Potato, Melon, Pumpkin, Sugar Cane, Cactus, Mushroom, Cocoa Beans, Nether Wart)
  - Adjust pitch/yaw angle (how far down the player looks while farming)
  - Toggle auto-tool-switch (automatically equips the best hoe in your hotbar)
  - Start / Stop the macro directly from the GUI
- **Keybinds** (all rebindable in Minecraft Controls menu)
  - `R` – Toggle macro on/off
  - `I` – Open config GUI
  - freelook
  - autorewarp to start (jf rewarp)
- **Persistent Config** – settings saved to `config/just-farming.json`

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.10
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `just-farming-<version>.jar` into your `mods/` folder

## Building from Source

```bash
./gradlew build
```

The built jar will be in `build/libs/just-farming-<version>.jar`.

## Usage

1. Join a Hypixel Skyblock plot or island with a farm set up.
2. Stand at one end of your crop rows.
3. Press `I` to open the GUI and configure the macro settings for your crop.
4. Press **Save & Close**, then press `R` (or click **Start Macro** in the GUI) to begin farming.
5. Press `R` again to stop the macro at any time.

> **Note:** Use this mod responsibly. Automated macros on Hypixel may violate their rules. This project is provided for educational purposes.

## License

MIT
