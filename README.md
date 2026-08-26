# Randomized Recipes

**Turn every world into a new crafting puzzle by shuffling vanilla and modded recipe outputs.**

Randomized Recipes reassigns the outputs of supported crafting, cooking, stonecutting, and modded data-driven recipes. Every world receives a deterministic shuffle that is generated and activated during startup, before players begin crafting.

## ✨ Features

- 🔀 **Broad recipe shuffling** — supports vanilla recipes and modded data-driven recipes with recognizable output fields.
- 🧩 **Modpack compatibility** — generated overrides retain each recipe's original namespace, allowing modded recipes to be replaced correctly.
- 🔎 **JEI support** — shuffled recipes are real datapack recipe overrides, so JEI displays the active shuffled outputs after recipes load or reload. JEI is optional and is not a required dependency.
- 🌱 **Seed-based worlds** — the same recipe set, configuration, and seed produce the same mapping. Each world remembers its active seed.
- 🚀 **Ready on world load** — new worlds are shuffled during server startup before players can join.
- ♻️ **Automatic rebuilding** — changes to recipes, datapacks, mods, or shuffle settings trigger a clean rebuild.
- 🛡️ **Safe, Balanced, and Chaos presets** — protect progression, use configured filters, or pull from every registered item.
- 🚫 **Detailed filtering** — allow or block items, item tags, recipes, recipe serializers, and entire mod namespaces.
- 🔢 **Output-count control** — preserve each recipe's original count or move the count with the shuffled item.
- 🔄 **Derangement option** — minimizes recipes that retain their original output.
- 🧯 **Safe recovery** — failed generation or reload attempts preserve the previous working pack.
- 🛠️ **In-game tools** — inspect the mapping, preview compatibility, reshuffle, validate settings, and export diagnostics.

## 📥 Installation

1. Install **Forge 47** for **Minecraft 1.20.1**.
2. Put `randomized_recipes-1.2.0.jar` in the instance or server `mods` folder.
3. Optionally install JEI to browse the active shuffled recipes.
4. Create or load a world. The shuffle is generated automatically.

Use the same Randomized Recipes version and configuration on the client and server.

## 💬 Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/rrhelp` | Everyone | Show the available commands |
| `/rrstatus` | Everyone | Show pack state, preset, seed, coverage, failures, and configuration fingerprints |
| `/rrfind <item>` | Everyone | Find shuffled recipes that now produce an item |
| `/rrrecipe <recipe>` | Everyone | Show the current output of a recipe |
| `/rrreshuffle` | Operator | Generate and activate a new random seed |
| `/rrreshuffle <seed>` | Operator | Generate a repeatable mapping from a specific seed |
| `/rrdebug` | Operator | Rebuild from the current seed and original recipe baseline |
| `/rrdryrun [seed]` | Operator | Preview coverage and compatibility without changing the world |
| `/rrcheck` | Operator | Validate filters and report unsupported or failed recipes |
| `/rrdump` | Operator | Export the exact active recipe mapping |

The old `/rsh...` and `/dkrsreload` command names are not used by version 1.2.0.

## ⚙️ Configuration

Config file: `config/randomized_recipes-common.toml`

| Option | Default | Description |
| --- | --- | --- |
| `randomize_recipes` | `true` | Enable or disable randomization; disabling restores original recipes |
| `shuffle_preset` | `BALANCED` | Select `SAFE`, `BALANCED`, or `CHAOS` behavior |
| `avoid_unchanged_outputs` | `true` | Minimize recipes that keep their original output |
| `shuffle_output_counts` | `false` | Move output quantities with items instead of preserving recipe counts |
| `item_blacklist` | Safety defaults | Block specific output items |
| `recipe_blacklist` | Empty | Leave specific recipe IDs untouched |
| `recipe_type_allowlist` | Empty | When populated, shuffle only listed recipe serializers |
| `recipe_type_blacklist` | Empty | Block listed recipe serializers |
| `item_tag_allowlist` | Empty | When populated, use outputs only from listed item tags |
| `item_tag_blacklist` | Empty | Block outputs belonging to listed item tags |
| `mod_blacklist` | Empty | Exclude recipe and output namespaces from entire mods |
| `show_welcome_message` | `true` | Show the command hint once per player per world |
| `reset_welcome_message` | `false` | Show the welcome again to the next joining player, then reset |
| `safe_mode` | `false` | Legacy override for progression-safe behavior |
| `chaos_mode` | `false` | Legacy override for chaos behavior |

**SAFE** protects planks, sticks, crafting tables, furnaces, basic pickaxes, torches, and chests. **BALANCED** shuffles recipe outputs while respecting all configured filters. **CHAOS** ignores filters and chooses from every registered non-empty item.

## ❓ FAQ

### Does this work with modded recipes?

Yes. Modded data-driven recipes with recognized `result`, `output`, `outputs`, `result_item`, or `output_item` structures are supported and written into the correct mod namespace. Recipes with dynamic, programmatic, or unrecognized output structures remain unchanged instead of being corrupted. Use `/rrdryrun` or `/rrcheck` to see exact compatibility results for your modpack.

### Does this work with JEI?

Yes. Randomized Recipes changes the server's actual recipe data, so JEI reads the shuffled outputs when recipes load. JEI is optional; no extra integration add-on is required. After an operator reshuffle, the recipe reload updates the active recipe collection.

### Will every new world already be shuffled?

Yes. Generation and activation happen during world startup before the server reports that the world is ready.

### Will I get the same shuffle every time?

Yes, when the recipe dataset, configuration, and seed are the same. Use `/rrreshuffle <seed>` for a repeatable result.

### Can I restore normal recipes?

Yes. Set `randomize_recipes` to `false`. The generated pack is deselected and removed, then the original recipes are restored.

### Why did a recipe remain unchanged?

It may be protected by the selected preset or filters, have no fixed output, use a programmatic serializer, store its output in an unknown custom structure, or have no valid alternative in the selected output pool. `/rrstatus`, `/rrcheck`, and `/rrdryrun` report these cases.

## 🧪 Building and testing

    ./gradlew build
    ./gradlew runGameTestServer

The release JAR is written to `build/libs`.

## 🧾 Version

**Current version:** `1.2.0`  
**Mod ID:** `randomized_recipes`  
**Loader:** Forge  
**Minecraft:** 1.20.1

## License

See [LICENSE](LICENSE). Public and private modpack use is permitted under the included custom license.
