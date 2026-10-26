<h1 align="center">Randomized Recipes</h1>

Randomized Recipes is a Minecraft Forge 1.20.1 mod that shuffles recipe outputs for each world. It supports vanilla recipes and compatible modded data-driven recipes.

The shuffle is generated and activated while the world starts, before players can begin crafting. Each world keeps a stable seed, so the same recipes, settings, and seed produce the same mapping.

<p align="center"><a href="https://www.curseforge.com/minecraft/mc-mods/randomized-recipes">Download on CurseForge</a> · <a href="https://discord.com/invite/mUGHmQxHG8">Discord</a> · <a href="https://github.com/DKLYNTLY/Randomized-Recipes-">GitHub source</a></p>

## What you can do

- Supports vanilla and compatible modded recipes.
- Uses real datapack overrides in each recipe's original namespace.
- Works with JEI without requiring JEI as a dependency.
- Generates the shuffle before a new world is ready.
- Rebuilds when recipes, datapacks, mods, or settings change.
- Includes Safe, Balanced, and Chaos presets.
- Supports item, item-tag, recipe, recipe-type, and mod filters.
- Can preserve output counts or shuffle counts with items.
- Can minimize recipes that retain their original output.
- Restores original recipes when randomization is disabled.
- Preserves the previous working pack if generation fails.

<details>
<summary><strong>Install, update, and check compatibility</strong></summary>

### Installation

1. Install Forge 47 for Minecraft 1.20.1.
2. Put `randomized_recipes-1.2.0.jar` in the `mods` folder.
3. Create or load a world.

JEI is optional. Install it if you want to browse the active shuffled recipes.

---

### Compatibility

JEI reads the active shuffled recipes because the mod changes the actual server recipe data. A recipe reload updates the collection JEI receives. No separate JEI integration add-on is required.

Modded data-driven recipes are supported when they use recognized result structures such as `result`, `output`, `outputs`, `result_item`, or `output_item`. Dynamic, programmatic, or unrecognized recipes remain unchanged to prevent corruption.

Use `/rrdryrun`, `/rrcheck`, or `/rrstatus` to see exact compatibility results for a modpack.

</details>

<details>
<summary><strong>Play: controls, commands, and progression</strong></summary>

### Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/rrhelp` | Everyone | Show available commands |
| `/rrstatus` | Everyone | Show the pack state, preset, seed, coverage, failures, and configuration fingerprints |
| `/rrfind <item>` | Everyone | Find recipes that now produce an item |
| `/rrrecipe <recipe>` | Everyone | Show the current output of a recipe |
| `/rrreshuffle` | Operator | Generate a new random mapping |
| `/rrreshuffle <seed>` | Operator | Generate a mapping from a specific seed |
| `/rrdebug` | Operator | Rebuild using the current seed |
| `/rrdryrun [seed]` | Operator | Preview compatibility without changing the world |
| `/rrcheck` | Operator | Validate filters and report recipe compatibility |
| `/rrdump` | Operator | Export the active mapping |

The old `/rsh...` and `/dkrsreload` command names are not used in version 1.2.0.

</details>

<details>
<summary><strong>Configure and customize</strong></summary>

### Configuration

The configuration file is `config/randomized_recipes-common.toml`.

| Option | Default | Description |
| --- | --- | --- |
| `randomize_recipes` | `true` | Enable or disable randomization |
| `shuffle_preset` | `BALANCED` | Select `SAFE`, `BALANCED`, or `CHAOS` |
| `avoid_unchanged_outputs` | `true` | Minimize recipes that keep their original output |
| `shuffle_output_counts` | `false` | Move output quantities with shuffled items |
| `item_blacklist` | Safety defaults | Block specific output items |
| `recipe_blacklist` | Empty | Leave specific recipes unchanged |
| `recipe_type_allowlist` | Empty | Shuffle only listed recipe serializers |
| `recipe_type_blacklist` | Empty | Block listed recipe serializers |
| `item_tag_allowlist` | Empty | Use outputs only from listed item tags |
| `item_tag_blacklist` | Empty | Block outputs from listed item tags |
| `mod_blacklist` | Empty | Exclude entire mod namespaces |
| `show_welcome_message` | `true` | Show the command hint once per player per world |
| `reset_welcome_message` | `false` | Show the welcome to the next joining player again |
| `safe_mode` | `false` | Legacy override for Safe behavior |
| `chaos_mode` | `false` | Legacy override for Chaos behavior |

Safe protects essential early-game outputs such as planks, sticks, crafting tables, furnaces, basic pickaxes, torches, and chests. Balanced respects all configured filters. Chaos ignores filters and selects from every registered non-empty item.

</details>

<details>
<summary><strong>Backups and recovery</strong></summary>

### Before updating

*Back up your world and configuration files before updating or changing settings. Keep a working copy of the old instance until you have checked the new setup.*

</details>

<details>
<summary><strong>Troubleshooting, logs, and technical details</strong></summary>

### Building

./gradlew build

The release JAR is written to `build/libs`.

---

### Project information

- Version: `1.2.0`
- Mod ID: `randomized_recipes`
- Loader: Forge
- Minecraft: 1.20.1
- License: See [LICENSE](LICENSE)

</details>

<details>
<summary><strong>About this project</strong></summary>

### Author and links

I create and maintain Randomized Recipes as **Dnydd (formerly known as dklyntly)**.

[Official CurseForge page](https://www.curseforge.com/minecraft/mc-mods/randomized-recipes) · [Discord](https://discord.com/invite/mUGHmQxHG8)

### License

Read [LICENSE](LICENSE) for this branch’s terms. Permissions granted with earlier releases remain in effect.

</details>

## Support my work

If you enjoy my projects, you can leave a tip on Ko-fi. Support is always optional.

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/A3G129JCLF)
