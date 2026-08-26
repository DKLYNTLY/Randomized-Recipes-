package com.dklyntly.randomizedrecipes;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(RandomizedRecipesMod.MODID)
public class RandomizedRecipesMod {
    public static final String MODID = "randomized_recipes";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean REBUILDING = new AtomicBoolean();
    private static volatile ShuffleManifest currentManifest = ShuffleManifest.empty();

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ITEMS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    if (id.toString().contains(remaining)) builder.suggest(id.toString());
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RECIPES =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Recipe<?> recipe : context.getSource().getServer().getRecipeManager().getRecipes()) {
                    if (recipe.getId().toString().contains(remaining)) builder.suggest(recipe.getId().toString());
                }
                return builder.buildFuture();
            };

    public RandomizedRecipesMod() {
        Config.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        synchronizeWorld(event.getServer(), "world startup", null);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Config.showWelcome() || !Config.randomizeRecipes() || currentManifest.written() == 0) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Path marker = welcomeRoot(server).resolve(player.getUUID() + ".txt");
        boolean show = Config.resetWelcome() || !Files.exists(marker);
        if (!show) return;
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "welcomed", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Could not save Randomized Recipes welcome marker.", exception);
        }
        if (Config.resetWelcome()) Config.clearResetWelcome();
        player.sendSystemMessage(buildAnnouncement());
        if (Config.chaosMode()) {
            player.sendSystemMessage(Component.literal("Chaos Mode is active. All safety filters are disabled.")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        registerHelp(dispatcher);
        registerStatus(dispatcher);
        registerReshuffle(dispatcher);
        registerDebug(dispatcher);
        registerFind(dispatcher);
        registerRecipe(dispatcher);
        registerCheck(dispatcher);
        registerDryRun(dispatcher);
        registerDump(dispatcher);
    }

    private static void synchronizeWorld(MinecraftServer server, String reason, CommandSourceStack source) {
        if (!Config.randomizeRecipes()) {
            deactivate(server, reason, source);
            return;
        }
        if (REBUILDING.get()) {
            if (source != null) send(source, "§eRandomized Recipes: A rebuild is already running.");
            return;
        }
        RecipeSnapshot snapshot = RecipeSnapshot.capture(server);
        String configHash = buildConfigHash();
        ShuffleManifest stored = readManifest(server);
        if (stored != null
                && stored.configHash().equals(configHash)
                && stored.datasetHash().equals(snapshot.datasetHash())) {
            currentManifest = stored;
            ensurePackEnabled(server, false).whenComplete((unused, error) -> {
                if (error != null) LOGGER.error("Could not enable the existing Randomized Recipes datapack.", error);
            });
            LOGGER.info("Loaded {} saved recipe mappings with seed {}.", stored.written(), stored.seed());
            return;
        }
        long seed = stored == null ? server.getWorldData().worldGenOptions().seed() : stored.seed();
        rebuild(server, snapshot, seed, configHash, reason, source);
    }

    private static void rebuild(
            MinecraftServer server,
            RecipeSnapshot snapshot,
            long seed,
            String configHash,
            String reason,
            CommandSourceStack source
    ) {
        if (!REBUILDING.compareAndSet(false, true)) {
            if (source != null) send(source, "§eRandomized Recipes: A rebuild is already running.");
            return;
        }
        if (source != null) send(source, "§6Randomized Recipes: §7Building recipe overrides...");
        try {
            ConfigSnapshot config = Config.snapshot();
            RecipeRandomizer.Result randomized = RecipeRandomizer.randomize(snapshot, seed, config);
            ShuffleManifest generated = RandomizedRecipesDatapackGenerator.generate(
                    server, snapshot, randomized, seed, configHash);
            ensurePackEnabled(server, true).whenComplete((unused, error) -> {
                REBUILDING.set(false);
                if (error != null) {
                    LOGGER.error("Randomized Recipes reload failed after {}.", reason, error);
                    if (source != null) send(source, "§cRandomized Recipes: Reload failed. Check the server log.");
                    return;
                }
                currentManifest = generated;
                LOGGER.info(
                        "Randomized Recipes ready after {}: seed={} written={} skipped={} failed={} unchanged={}.",
                        reason,
                        generated.seed(),
                        generated.written(),
                        generated.skipped(),
                        generated.failed(),
                        generated.unchanged()
                );
                if (source != null) {
                    send(source, "§aRandomized Recipes: Ready. §f" + generated.written()
                            + " §7recipes shuffled, §f" + generated.skipped() + " §7unsupported, seed §f" + seed + "§7.");
                }
            });
        } catch (Exception exception) {
            REBUILDING.set(false);
            LOGGER.error("Could not rebuild Randomized Recipes after {}.", reason, exception);
            if (source != null) send(source, "§cRandomized Recipes: Build failed. The previous mapping was preserved.");
        }
    }

    private static void deactivate(MinecraftServer server, String reason, CommandSourceStack source) {
        server.getPackRepository().reload();
        List<String> currentSelection = selectedPacks(server);
        boolean wasSelected = currentSelection.contains(RandomizedRecipesDatapackGenerator.PACK_ID);
        List<String> selected = withoutGeneratedPack(currentSelection);
        CompletableFuture<Void> reload = wasSelected
                ? server.reloadResources(selected)
                : CompletableFuture.completedFuture(null);
        reload.whenComplete((unused, error) -> {
            if (error != null) {
                LOGGER.error("Could not disable Randomized Recipes after {}.", reason, error);
                if (source != null) send(source, "§cRandomized Recipes: Could not disable the generated datapack.");
                return;
            }
            try {
                RandomizedRecipesDatapackGenerator.deleteGeneratedPack(server);
                currentManifest = ShuffleManifest.empty();
                LOGGER.info("Randomized Recipes disabled and generated datapack removed after {}.", reason);
                if (source != null) send(source, "§aRandomized Recipes: Disabled and original recipes restored.");
            } catch (IOException exception) {
                LOGGER.error("Randomized Recipes was disabled, but its generated files could not be removed.", exception);
            }
        });
    }

    private static CompletableFuture<Void> ensurePackEnabled(MinecraftServer server, boolean forceReload) {
        server.getPackRepository().reload();
        List<String> currentSelection = selectedPacks(server);
        boolean added = !currentSelection.contains(RandomizedRecipesDatapackGenerator.PACK_ID);
        List<String> selected = withGeneratedPack(currentSelection);
        if (!forceReload && !added) return CompletableFuture.completedFuture(null);
        return server.reloadResources(selected);
    }

    private static List<String> selectedPacks(MinecraftServer server) {
        return new ArrayList<>(server.getPackRepository().getSelectedPacks().stream().map(Pack::getId).toList());
    }

    static List<String> withoutGeneratedPack(Iterable<String> packs) {
        List<String> result = new ArrayList<>();
        packs.forEach(pack -> {
            if (!pack.equals(RandomizedRecipesDatapackGenerator.PACK_ID)) result.add(pack);
        });
        return result;
    }

    static List<String> withGeneratedPack(Iterable<String> packs) {
        List<String> result = withoutGeneratedPack(packs);
        result.add(RandomizedRecipesDatapackGenerator.PACK_ID);
        return result;
    }

    private static ShuffleManifest readManifest(MinecraftServer server) {
        Path path = generatedPackRoot(server).resolve(RandomizedRecipesDatapackGenerator.MANIFEST_FILE);
        if (!Files.exists(path)) return null;
        try {
            return ShuffleManifest.read(path);
        } catch (Exception exception) {
            LOGGER.warn("Could not read the saved Randomized Recipes manifest. It will be rebuilt.", exception);
            return null;
        }
    }

    private static void registerHelp(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrhelp")
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    send(source, "§8§m                                    ");
                    send(source, " §6§lRandomized Recipes §8- §7Shuffled recipe outputs");
                    send(source, "§8§m                                    ");
                    send(source, " §f/rrstatus             §8» §7Show the active seed and coverage");
                    send(source, " §f/rrfind §3<item>       §8» §7Find recipes producing an item");
                    send(source, " §f/rrrecipe §3<recipe>   §8» §7Show a recipe's current output");
                    send(source, "§8§m                                    ");
                    send(source, " §8§oAdmin commands - requires OP");
                    send(source, " §7/rrreshuffle [seed]   §8» §8Create a new mapping");
                    send(source, " §7/rrdebug              §8» §8Rebuild with the current seed");
                    send(source, " §7/rrcheck              §8» §8Validate config and coverage");
                    send(source, " §7/rrdryrun [seed]      §8» §8Preview without changing the world");
                    send(source, " §7/rrdump               §8» §8Export the active mapping");
                    send(source, "§8§m                                    ");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerStatus(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrstatus")
                .executes(context -> {
                    ShuffleManifest manifest = currentManifest;
                    MinecraftServer server = context.getSource().getServer();
                    String currentFingerprint = buildConfigHash();
                    String packState = packState(server);
                    send(context.getSource(), "§6Randomized Recipes status");
                    send(context.getSource(), " §7Enabled: §f" + Config.randomizeRecipes());
                    send(context.getSource(), " §7Pack state: §f" + packState);
                    send(context.getSource(), " §7Preset: §f" + Config.preset());
                    send(context.getSource(), " §7Seed: §f" + manifest.seed());
                    send(context.getSource(), " §7Shuffled: §f" + manifest.written());
                    send(context.getSource(), " §7Unsupported: §f" + manifest.skipped());
                    send(context.getSource(), " §7Failed: §f" + manifest.failed());
                    send(context.getSource(), " §7Unchanged outputs: §f" + manifest.unchanged());
                    send(context.getSource(), " §7Config fingerprint: §f" + shortHash(currentFingerprint));
                    send(context.getSource(), " §7Active fingerprint: §f" + shortHash(manifest.configHash()));
                    send(context.getSource(), " §7Configuration current: §f"
                            + currentFingerprint.equals(manifest.configHash()));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerReshuffle(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrreshuffle")
                .requires(source -> source.hasPermission(2))
                .executes(context -> reshuffle(context.getSource(), new Random().nextLong()))
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(context -> reshuffle(
                                context.getSource(), LongArgumentType.getLong(context, "seed")))));
    }

    private static int reshuffle(CommandSourceStack source, long seed) {
        if (!Config.randomizeRecipes()) {
            send(source, "§cRandomized Recipes is disabled in config.");
            return Command.SINGLE_SUCCESS;
        }
        MinecraftServer server = source.getServer();
        rebuild(server, RecipeSnapshot.capture(server), seed, buildConfigHash(), "/rrreshuffle", source);
        return Command.SINGLE_SUCCESS;
    }

    private static void registerDebug(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrdebug")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    long seed = currentManifest.seed() == 0
                            ? server.getWorldData().worldGenOptions().seed()
                            : currentManifest.seed();
                    rebuild(
                            server,
                            RecipeSnapshot.capture(server),
                            seed,
                            buildConfigHash(),
                            "/rrdebug",
                            context.getSource()
                    );
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerFind(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrfind")
                .executes(context -> {
                    send(context.getSource(), "§eUsage: §f/rrfind <item>");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(SUGGEST_ITEMS)
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            ResourceLocation itemId = ResourceLocationArgument.getId(context, "item");
                            if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
                                send(source, "§cUnknown item: §f" + itemId);
                                return Command.SINGLE_SUCCESS;
                            }
                            List<ResourceLocation> matches = currentManifest.mappings().entrySet().stream()
                                    .filter(entry -> BuiltInRegistries.ITEM.getKey(entry.getValue().getItem()).equals(itemId))
                                    .map(Map.Entry::getKey)
                                    .sorted()
                                    .toList();
                            send(source, "§6Recipes producing §f" + itemId + "§6:");
                            if (matches.isEmpty()) {
                                send(source, " §7No shuffled recipe currently produces that item.");
                            } else {
                                matches.stream().limit(12).forEach(id -> send(source, " §f" + id));
                                if (matches.size() > 12) send(source, " §8...and " + (matches.size() - 12) + " more.");
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private static void registerRecipe(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrrecipe")
                .executes(context -> {
                    send(context.getSource(), "§eUsage: §f/rrrecipe <recipe>");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("recipe", ResourceLocationArgument.id())
                        .suggests(SUGGEST_RECIPES)
                        .executes(context -> {
                            ResourceLocation recipeId = ResourceLocationArgument.getId(context, "recipe");
                            ItemStack output = currentManifest.mappings().get(recipeId);
                            if (output == null || output.isEmpty()) {
                                send(context.getSource(), "§7That recipe is excluded, unsupported, or unknown.");
                            } else {
                                send(context.getSource(), "§6" + recipeId + " §7produces §f"
                                        + output.getCount() + "x "
                                        + BuiltInRegistries.ITEM.getKey(output.getItem()));
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private static void registerCheck(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrcheck")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    ConfigSnapshot config = Config.snapshot();
                    List<String> problems = new ArrayList<>();
                    config.itemBlacklist().stream()
                            .filter(id -> !BuiltInRegistries.ITEM.containsKey(id))
                            .sorted()
                            .forEach(id -> problems.add("Unknown item: " + id));
                    config.recipeBlacklist().stream()
                            .filter(id -> source.getServer().getRecipeManager().byKey(id).isEmpty())
                            .sorted()
                            .forEach(id -> problems.add("Unknown recipe: " + id));
                    config.recipeTypeAllowlist().stream()
                            .map(ResourceLocation::parse)
                            .filter(id -> !BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id))
                            .sorted()
                            .forEach(id -> problems.add("Unknown allowed recipe type: " + id));
                    config.recipeTypeBlacklist().stream()
                            .map(ResourceLocation::parse)
                            .filter(id -> !BuiltInRegistries.RECIPE_SERIALIZER.containsKey(id))
                            .sorted()
                            .forEach(id -> problems.add("Unknown blocked recipe type: " + id));
                    config.itemTagAllowlist().stream()
                            .filter(tag -> BuiltInRegistries.ITEM.getTag(tag).isEmpty())
                            .map(TagKey::location)
                            .sorted()
                            .forEach(id -> problems.add("Unknown allowed item tag: " + id));
                    config.itemTagBlacklist().stream()
                            .filter(tag -> BuiltInRegistries.ITEM.getTag(tag).isEmpty())
                            .map(TagKey::location)
                            .sorted()
                            .forEach(id -> problems.add("Unknown blocked item tag: " + id));
                    Set<String> installedMods = new HashSet<>();
                    ModList.get().getMods().forEach(info -> installedMods.add(info.getModId()));
                    config.modBlacklist().stream()
                            .filter(id -> !installedMods.contains(id))
                            .sorted()
                            .forEach(id -> problems.add("Unknown mod: " + id));
                    send(source, "§6Randomized Recipes check");
                    send(source, " §7Item blacklist: §f" + config.itemBlacklist().size());
                    send(source, " §7Recipe blacklist: §f" + config.recipeBlacklist().size());
                    send(source, " §7Recipe-type allowlist: §f" + config.recipeTypeAllowlist().size());
                    send(source, " §7Recipe-type blacklist: §f" + config.recipeTypeBlacklist().size());
                    send(source, " §7Item-tag allowlist: §f" + config.itemTagAllowlist().size());
                    send(source, " §7Item-tag blacklist: §f" + config.itemTagBlacklist().size());
                    send(source, " §7Mod blacklist: §f" + config.modBlacklist().size());
                    send(source, " §7Coverage: §f" + currentManifest.written() + " / " + currentManifest.included());
                    currentManifest.skippedRecipes().stream().limit(12)
                            .forEach(id -> problems.add("Unsupported recipe layout: " + id));
                    currentManifest.failedRecipes().stream().limit(12)
                            .forEach(id -> problems.add("Failed recipe: " + id));
                    if (problems.isEmpty()) {
                        send(source, " §aNo invalid blacklist entries found.");
                    } else {
                        problems.stream().limit(12).forEach(problem -> send(source, " §e" + problem));
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerDryRun(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrdryrun")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    long seed = currentManifest.seed() == 0
                            ? server.getWorldData().worldGenOptions().seed()
                            : currentManifest.seed();
                    return dryRun(context.getSource(), seed);
                })
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(context -> dryRun(
                                context.getSource(),
                                LongArgumentType.getLong(context, "seed")
                        ))));
    }

    private static int dryRun(CommandSourceStack source, long seed) {
        if (REBUILDING.get()) {
            send(source, "§eRandomized Recipes: Wait for the current rebuild to finish.");
            return Command.SINGLE_SUCCESS;
        }
        RecipeSnapshot snapshot = RecipeSnapshot.capture(source.getServer());
        RecipeRandomizer.Result randomized = RecipeRandomizer.randomize(snapshot, seed, Config.snapshot());
        RandomizedRecipesDatapackGenerator.Preview preview =
                RandomizedRecipesDatapackGenerator.preview(snapshot, randomized);
        send(source, "§6Randomized Recipes dry run");
        send(source, " §7Seed: §f" + seed);
        send(source, " §7Preset: §f" + Config.preset());
        send(source, " §7Would shuffle: §f" + preview.compatible());
        send(source, " §7Excluded: §f" + preview.excluded());
        send(source, " §7Unsupported: §f" + preview.unsupported());
        send(source, " §7Failed inspection: §f" + preview.failed());
        send(source, " §7Unchanged outputs: §f" + preview.unchanged());
        send(source, " §7Fingerprint: §f" + shortHash(buildConfigHash()));
        preview.unsupportedRecipes().stream().limit(12)
                .forEach(id -> send(source, " §eUnsupported: §f" + id));
        send(source, " §aNo files, mappings, or pack selections were changed.");
        return Command.SINGLE_SUCCESS;
    }

    private static void registerDump(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rrdump")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    Path dump = generatedPackRoot(context.getSource().getServer())
                            .resolve("randomized_recipes_dump.txt");
                    StringBuilder text = new StringBuilder();
                    text.append("Randomized Recipes mapping\n");
                    text.append("Seed: ").append(currentManifest.seed()).append('\n');
                    text.append("Written: ").append(currentManifest.written()).append('\n');
                    text.append("Skipped: ").append(currentManifest.skipped()).append('\n');
                    text.append("Failed: ").append(currentManifest.failed()).append("\n\n");
                    currentManifest.mappings().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> text.append(entry.getKey())
                                    .append(" -> ")
                                    .append(entry.getValue().getCount())
                                    .append("x ")
                                    .append(BuiltInRegistries.ITEM.getKey(entry.getValue().getItem()))
                                    .append('\n'));
                    try {
                        Files.createDirectories(dump.getParent());
                        Files.writeString(dump, text.toString(), StandardCharsets.UTF_8);
                        send(context.getSource(), "§aRandomized Recipes: Dump written to §f" + dump.toAbsolutePath());
                    } catch (IOException exception) {
                        LOGGER.error("Could not write Randomized Recipes dump.", exception);
                        send(context.getSource(), "§cRandomized Recipes: Could not write the dump.");
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static Component buildAnnouncement() {
        return Component.literal("Randomized Recipes is ready. ")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal("Type ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/rrhelp").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" for commands.").withStyle(ChatFormatting.GRAY));
    }

    private static String buildConfigHash() {
        ConfigSnapshot config = Config.snapshot();
        TreeSet<String> values = new TreeSet<>();
        values.add("schema=3");
        values.add("enabled=" + config.randomizeRecipes());
        values.add("preset=" + config.preset());
        values.add("avoid_unchanged=" + config.avoidUnchangedOutputs());
        values.add("shuffle_counts=" + config.shuffleOutputCounts());
        config.itemBlacklist().forEach(id -> values.add("item=" + id));
        config.recipeBlacklist().forEach(id -> values.add("recipe=" + id));
        config.recipeTypeAllowlist().forEach(id -> values.add("type_allow=" + id));
        config.recipeTypeBlacklist().forEach(id -> values.add("type_block=" + id));
        config.itemTagAllowlist().forEach(tag -> values.add("tag_allow=" + tag.location()));
        config.itemTagBlacklist().forEach(tag -> values.add("tag_block=" + tag.location()));
        config.modBlacklist().forEach(id -> values.add("mod=" + id));
        ModList.get().getMods().forEach(info -> values.add("loaded=" + info.getModId() + "@" + info.getVersion()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.forEach(value -> {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path generatedPackRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve(RandomizedRecipesDatapackGenerator.PACK_DIR);
    }

    private static Path welcomeRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(".randomized_recipes_players");
    }

    private static String packState(MinecraftServer server) {
        if (REBUILDING.get()) return "rebuilding";
        boolean selected = selectedPacks(server).contains(RandomizedRecipesDatapackGenerator.PACK_ID);
        boolean present = Files.exists(generatedPackRoot(server));
        if (selected && present) return "active";
        if (selected) return "selected but missing";
        if (present) return "present but inactive";
        return "inactive";
    }

    private static String shortHash(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.substring(0, Math.min(16, value.length()));
    }

    private static void send(CommandSourceStack source, String message) {
        if (source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(message));
        } else {
            source.sendSuccess(() -> Component.literal(message), false);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ConfigEvents {
        @SubscribeEvent
        public static void onConfigReload(ModConfigEvent.Reloading event) {
            if (event.getConfig().getSpec() != Config.SPEC) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(() -> synchronizeWorld(server, "config reload", null));
        }
    }

    public enum ShufflePreset {
        SAFE,
        BALANCED,
        CHAOS
    }

    public record ConfigSnapshot(
            boolean randomizeRecipes,
            ShufflePreset preset,
            boolean avoidUnchangedOutputs,
            boolean shuffleOutputCounts,
            Set<ResourceLocation> itemBlacklist,
            Set<ResourceLocation> recipeBlacklist,
            Set<String> recipeTypeAllowlist,
            Set<String> recipeTypeBlacklist,
            Set<TagKey<Item>> itemTagAllowlist,
            Set<TagKey<Item>> itemTagBlacklist,
            Set<String> modBlacklist
    ) {
        public boolean chaosMode() {
            return preset == ShufflePreset.CHAOS;
        }

        public boolean safeMode() {
            return preset == ShufflePreset.SAFE;
        }
    }

    public static final class Config {
        private static ForgeConfigSpec.BooleanValue RANDOMIZE_RECIPES;
        private static ForgeConfigSpec.BooleanValue CHAOS_MODE;
        private static ForgeConfigSpec.BooleanValue SAFE_MODE;
        private static ForgeConfigSpec.EnumValue<ShufflePreset> SHUFFLE_PRESET;
        private static ForgeConfigSpec.BooleanValue AVOID_UNCHANGED_OUTPUTS;
        private static ForgeConfigSpec.BooleanValue SHUFFLE_OUTPUT_COUNTS;
        private static ForgeConfigSpec.BooleanValue SHOW_WELCOME;
        private static ForgeConfigSpec.BooleanValue RESET_WELCOME;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> RECIPE_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> RECIPE_TYPE_ALLOWLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> RECIPE_TYPE_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_TAG_ALLOWLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_TAG_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> MOD_BLACKLIST;
        static ForgeConfigSpec SPEC;

        static void register() {
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            builder.push("general");
            RANDOMIZE_RECIPES = builder.define("randomize_recipes", true);
            CHAOS_MODE = builder.define("chaos_mode", false);
            SAFE_MODE = builder.define("safe_mode", false);
            SHUFFLE_PRESET = builder.defineEnum("shuffle_preset", ShufflePreset.BALANCED);
            AVOID_UNCHANGED_OUTPUTS = builder.define("avoid_unchanged_outputs", true);
            SHUFFLE_OUTPUT_COUNTS = builder.define("shuffle_output_counts", false);
            SHOW_WELCOME = builder.define("show_welcome_message", true);
            RESET_WELCOME = builder.define("reset_welcome_message", false);
            ITEM_BLACKLIST = builder.defineListAllowEmpty("item_blacklist", List.of(
                    "minecraft:air",
                    "minecraft:barrier",
                    "minecraft:bedrock",
                    "minecraft:command_block",
                    "minecraft:chain_command_block",
                    "minecraft:repeating_command_block",
                    "minecraft:structure_block",
                    "minecraft:structure_void",
                    "minecraft:jigsaw",
                    "minecraft:light",
                    "minecraft:debug_stick",
                    "minecraft:knowledge_book"
            ), Config::validResourceLocation);
            RECIPE_BLACKLIST = builder.defineListAllowEmpty(
                    "recipe_blacklist", List.of(), Config::validResourceLocation);
            RECIPE_TYPE_ALLOWLIST = builder.defineListAllowEmpty(
                    "recipe_type_allowlist", List.of(), Config::validResourceLocation);
            RECIPE_TYPE_BLACKLIST = builder.defineListAllowEmpty(
                    "recipe_type_blacklist", List.of(), Config::validResourceLocation);
            ITEM_TAG_ALLOWLIST = builder.defineListAllowEmpty(
                    "item_tag_allowlist", List.of(), Config::validResourceLocation);
            ITEM_TAG_BLACKLIST = builder.defineListAllowEmpty(
                    "item_tag_blacklist", List.of(), Config::validResourceLocation);
            MOD_BLACKLIST = builder.defineListAllowEmpty(
                    "mod_blacklist", List.of(), Config::validModId);
            builder.pop();
            SPEC = builder.build();
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        }

        static ConfigSnapshot snapshot() {
            return new ConfigSnapshot(
                    randomizeRecipes(),
                    preset(),
                    avoidUnchangedOutputs(),
                    shuffleOutputCounts(),
                    resourceLocations(ITEM_BLACKLIST.get()),
                    resourceLocations(RECIPE_BLACKLIST.get()),
                    strings(RECIPE_TYPE_ALLOWLIST.get()),
                    strings(RECIPE_TYPE_BLACKLIST.get()),
                    itemTags(ITEM_TAG_ALLOWLIST.get()),
                    itemTags(ITEM_TAG_BLACKLIST.get()),
                    strings(MOD_BLACKLIST.get())
            );
        }

        public static boolean randomizeRecipes() {
            return RANDOMIZE_RECIPES.get();
        }

        public static boolean chaosMode() {
            return preset() == ShufflePreset.CHAOS;
        }

        public static boolean safeMode() {
            return preset() == ShufflePreset.SAFE;
        }

        public static ShufflePreset preset() {
            if (CHAOS_MODE.get()) return ShufflePreset.CHAOS;
            if (SAFE_MODE.get()) return ShufflePreset.SAFE;
            return SHUFFLE_PRESET.get();
        }

        public static boolean avoidUnchangedOutputs() {
            return AVOID_UNCHANGED_OUTPUTS.get();
        }

        public static boolean shuffleOutputCounts() {
            return SHUFFLE_OUTPUT_COUNTS.get();
        }

        public static boolean showWelcome() {
            return SHOW_WELCOME.get();
        }

        public static boolean resetWelcome() {
            return RESET_WELCOME.get();
        }

        public static void clearResetWelcome() {
            RESET_WELCOME.set(false);
            SPEC.save();
        }

        private static Set<ResourceLocation> resourceLocations(List<? extends String> values) {
            Set<ResourceLocation> result = new LinkedHashSet<>();
            for (String value : values) {
                ResourceLocation id = ResourceLocation.tryParse(value.trim().toLowerCase(Locale.ROOT));
                if (id != null) result.add(id);
            }
            return Set.copyOf(result);
        }

        private static Set<String> strings(List<? extends String> values) {
            Set<String> result = new LinkedHashSet<>();
            for (String value : values) result.add(value.trim().toLowerCase(Locale.ROOT));
            return Set.copyOf(result);
        }

        private static Set<TagKey<Item>> itemTags(List<? extends String> values) {
            Set<TagKey<Item>> result = new LinkedHashSet<>();
            for (ResourceLocation id : resourceLocations(values)) {
                result.add(TagKey.create(Registries.ITEM, id));
            }
            return Set.copyOf(result);
        }

        private static boolean validResourceLocation(Object value) {
            return value instanceof String text && ResourceLocation.tryParse(text.trim().toLowerCase(Locale.ROOT)) != null;
        }

        private static boolean validModId(Object value) {
            return value instanceof String text && text.trim().toLowerCase(Locale.ROOT).matches("[a-z][a-z0-9_-]{0,63}");
        }
    }
}
