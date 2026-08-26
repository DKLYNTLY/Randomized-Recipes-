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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

@Mod(RandomizedRecipesMod.MODID)
public class RandomizedRecipesMod {
    public static final String MODID = "randomized_recipes";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ITEMS =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                    String full = id.toString();
                    if (full.contains(remaining)) builder.suggest(full);
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_RECIPES =
            (ctx, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                for (Recipe<?> recipe : ctx.getSource().getServer().getRecipeManager().getRecipes()) {
                    String full = recipe.getId().toString();
                    if (full.contains(remaining)) builder.suggest(full);
                }
                return builder.buildFuture();
            };

    public RandomizedRecipesMod() {
        MinecraftForge.EVENT_BUS.register(this);
        Config.register();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        Path packRoot = generatedPackRoot(server);
        Path seedFile = packRoot.resolve(RandomizedRecipesDatapackGenerator.SEED_FILE);
        Path configFile = packRoot.resolve(RandomizedRecipesDatapackGenerator.CONFIG_FILE);

        LOGGER.info("Randomized Recipes config: enabled={} chaos_mode={} item_blacklist_size={} recipe_blacklist_size={} mod_blacklist_size={}",
                Config.randomizeRecipes(), Config.chaosMode(), Config.itemBlacklist().size(),
                Config.recipeBlacklist().size(), Config.modBlacklist().size());

        if (!Config.randomizeRecipes()) {
            LOGGER.info("Recipe randomization is disabled in config.");
            return;
        }

        if (Files.exists(seedFile)) {
            try {
                long seed = Long.parseLong(Files.readString(seedFile).trim());
                String currentConfigHash = buildConfigHash();
                String storedConfigHash = Files.exists(configFile) ? Files.readString(configFile).trim() : "";

                if (!currentConfigHash.equals(storedConfigHash)) {
                    LOGGER.info("Randomized Recipes config or mod list changed; rebuilding generated datapack.");
                    int written = generateAndSave(server, seed, currentConfigHash);
                    LOGGER.info("Rebuilt {} recipe files.", written);
                } else {
                    LOGGER.info("Found existing Randomized Recipes datapack for this world (seed: {}).", seed);
                }

                server.execute(() -> triggerReload(server, "auto-start"));
                return;
            } catch (Exception e) {
                LOGGER.warn("Could not read Randomized Recipes seed/config; regenerating. Reason: {}", e.getMessage());
            }
        }

        long seed = server.overworld().getSeed();
        int written = generateAndSave(server, seed, buildConfigHash());
        LOGGER.info("Generated {} recipe files with seed {}.", written, seed);
        if (written > 0) {
            server.execute(() -> triggerReload(server, "auto-start"));
        }
    }

    private static Component buildAnnouncement() {
        return Component.literal("Randomized Recipes is active. ")
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal("Type ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/rrhelp").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" for commands.").withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.showWelcome()) return;
        event.getEntity().sendSystemMessage(buildAnnouncement());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        registerHelp(d);
        registerDebug(d);
        registerReshuffle(d);
        registerFind(d);
        registerRecipe(d);
        registerCheck(d);
        registerDump(d);
    }

    private static void registerHelp(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrhelp")
                .requires(src -> true)
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    send(src, "§8§m                                    ");
                    send(src, " §6§lRandomized Recipes §8- §7Shuffled recipe outputs");
                    send(src, "§8§m                                    ");
                    send(src, " §f/rrreshuffle         §8» §7Re-shuffle recipes");
                    send(src, " §f/rrreshuffle §3<seed>  §8» §7Use a specific seed");
                    send(src, " §f/rrfind §3<item>       §8» §7Which recipes now make an item");
                    send(src, " §f/rrrecipe §3<recipe>   §8» §7What a recipe outputs now");
                    send(src, "§8§m                                    ");
                    send(src, " §8§oDebug §8- §8§orequires OP");
                    send(src, " §7/rrdebug  §8» §8Force rebuild and reload");
                    send(src, " §7/rrcheck  §8» §8Show blacklist/config status");
                    send(src, " §7/rrdump   §8» §8Export all recipe changes");
                    send(src, "§8§m                                    ");
                    send(src, " §7Config: §fconfig/randomized_recipes-common.toml");
                    send(src, "§8§m                                    ");
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerDebug(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrdebug")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    long seed = readSeedOrWorldSeed(server);
                    send(ctx.getSource(), "§6Randomized Recipes: §7Building datapack...");
                    int written = generateAndSave(server, seed, buildConfigHash());
                    send(ctx.getSource(), "§aRandomized Recipes: Done! §f" + written + " §7recipe files written. Reloading...");
                    server.execute(() -> triggerReload(server, "/rrdebug"));
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerReshuffle(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrreshuffle")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> doReshuffle(ctx.getSource(), new Random().nextLong(), false))
                .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> doReshuffle(ctx.getSource(), LongArgumentType.getLong(ctx, "seed"), true))));
    }

    private static int doReshuffle(CommandSourceStack src, long seed, boolean userProvided) {
        MinecraftServer server = src.getServer();
        send(src, "§6Randomized Recipes: §7Re-shuffling recipes...");
        int written = generateAndSave(server, seed, buildConfigHash());

        if (written == 0) {
            send(src, "§cRandomized Recipes: No recipes written. Check config blacklists and server logs.");
            return Command.SINGLE_SUCCESS;
        }

        if (userProvided) {
            send(src, "§aRandomized Recipes: Done! §f" + written + " §7recipes written. Seed: §f" + seed);
        } else {
            send(src, "§aRandomized Recipes: Done! §f" + written + " §7recipes written with a new random seed.");
        }
        send(src, "§7Reloading...");
        server.execute(() -> triggerReload(server, "/rrreshuffle"));
        return Command.SINGLE_SUCCESS;
    }

    private static void registerFind(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrfind")
                .requires(src -> true)
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(SUGGEST_ITEMS)
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            ResourceLocation itemId = ResourceLocationArgument.getId(ctx, "item");
                            Map<ResourceLocation, ItemStack> map = currentShuffle(src.getServer());
                            List<ResourceLocation> matches = new ArrayList<>();

                            for (Map.Entry<ResourceLocation, ItemStack> entry : map.entrySet()) {
                                if (BuiltInRegistries.ITEM.getKey(entry.getValue().getItem()).equals(itemId)) {
                                    matches.add(entry.getKey());
                                }
                            }

                            send(src, "§6Randomized Recipes: §7Recipes that now output §f" + itemId + "§7:");
                            if (matches.isEmpty()) {
                                send(src, " §8No shuffled recipes currently output that item.");
                            } else {
                                matches.stream().sorted().limit(12).forEach(id -> send(src, " §f" + id));
                                if (matches.size() > 12) send(src, " §8...and " + (matches.size() - 12) + " more.");
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private static void registerRecipe(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrrecipe")
                .requires(src -> true)
                .then(Commands.argument("recipe", ResourceLocationArgument.id())
                        .suggests(SUGGEST_RECIPES)
                        .executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            ResourceLocation recipeId = ResourceLocationArgument.getId(ctx, "recipe");
                            ItemStack output = currentShuffle(src.getServer()).get(recipeId);

                            if (output == null || output.isEmpty()) {
                                send(src, "§6Randomized Recipes: §7That recipe is not currently shuffled.");
                            } else {
                                ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
                                send(src, "§6Randomized Recipes: §f" + recipeId + " §7now outputs §f" + output.getCount() + "x " + outputId);
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    private static void registerCheck(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrcheck")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    send(src, "§6Randomized Recipes config");
                    send(src, " §7randomize_recipes: §f" + Config.randomizeRecipes());
                    send(src, " §7chaos_mode: §f" + Config.chaosMode());
                    send(src, " §7item_blacklist: §f" + Config.itemBlacklist().size());
                    send(src, " §7recipe_blacklist: §f" + Config.recipeBlacklist().size());
                    send(src, " §7mod_blacklist: §f" + Config.modBlacklist().size());
                    send(src, " §7current shuffled recipes: §f" + currentShuffle(src.getServer()).size());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void registerDump(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rrdump")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    Path dump = generatedPackRoot(server).resolve("randomized_recipes_dump.txt");
                    StringBuilder sb = new StringBuilder();
                    sb.append("Randomized Recipes dump\n");
                    sb.append("Seed: ").append(readSeedOrWorldSeed(server)).append('\n');
                    sb.append("Config hash: ").append(buildConfigHash()).append("\n\n");

                    currentShuffle(server).entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(entry.getValue().getItem());
                                sb.append(entry.getKey()).append(" -> ")
                                        .append(entry.getValue().getCount()).append("x ")
                                        .append(outputId).append('\n');
                            });

                    try {
                        Files.createDirectories(dump.getParent());
                        Files.writeString(dump, sb.toString(), StandardCharsets.UTF_8);
                        send(ctx.getSource(), "§aRandomized Recipes: Dump written to §f" + dump);
                    } catch (IOException e) {
                        send(ctx.getSource(), "§cRandomized Recipes: Could not write dump: " + e.getMessage());
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static Map<ResourceLocation, ItemStack> currentShuffle(MinecraftServer server) {
        return RecipeRandomizer.randomize(server, readSeedOrWorldSeed(server));
    }

    private static int generateAndSave(MinecraftServer server, long seed, String configHash) {
        Map<ResourceLocation, ItemStack> remap = RecipeRandomizer.randomize(server, seed);
        int written = RandomizedRecipesDatapackGenerator.generate(server, remap);
        Path packRoot = generatedPackRoot(server);
        try {
            Files.createDirectories(packRoot);
            Files.writeString(packRoot.resolve(RandomizedRecipesDatapackGenerator.SEED_FILE), String.valueOf(seed), StandardCharsets.UTF_8);
            Files.writeString(packRoot.resolve(RandomizedRecipesDatapackGenerator.CONFIG_FILE), configHash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save Randomized Recipes metadata: {}", e.getMessage());
        }
        return written;
    }

    private static long readSeedOrWorldSeed(MinecraftServer server) {
        Path seedFile = generatedPackRoot(server).resolve(RandomizedRecipesDatapackGenerator.SEED_FILE);
        try {
            if (Files.exists(seedFile)) return Long.parseLong(Files.readString(seedFile).trim());
        } catch (Exception e) {
            LOGGER.warn("Could not read Randomized Recipes seed: {}", e.getMessage());
        }
        return server.overworld().getSeed();
    }

    private static Path generatedPackRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(RandomizedRecipesDatapackGenerator.PACK_DIR);
    }

    private static String buildConfigHash() {
        TreeSet<String> mods = new TreeSet<>();
        net.minecraftforge.fml.ModList.get().getMods().forEach(
                info -> mods.add(info.getModId() + "@" + info.getVersion())
        );
        return String.valueOf(Objects.hash(
                Config.randomizeRecipes(),
                Config.chaosMode(),
                new TreeSet<>(Config.itemBlacklist()),
                new TreeSet<>(Config.recipeBlacklist()),
                new TreeSet<>(Config.modBlacklist()),
                mods
        ));
    }

    private static void triggerReload(MinecraftServer server, String triggeredBy) {
        server.getPackRepository().reload();
        List<String> packs = new ArrayList<>(
                server.getPackRepository().getSelectedPacks()
                        .stream().map(Pack::getId).toList()
        );
        if (!packs.contains(RandomizedRecipesDatapackGenerator.PACK_ID)) {
            packs.add(RandomizedRecipesDatapackGenerator.PACK_ID);
            LOGGER.info("Force-added {} to pack list.", RandomizedRecipesDatapackGenerator.PACK_ID);
        }

        LOGGER.info("Reloading Randomized Recipes with packs: {}", packs);
        server.reloadResources(packs);
        LOGGER.info("Randomized Recipes reload complete (triggered by {}).", triggeredBy);
    }

    private static void send(CommandSourceStack src, String msg) {
        if (src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            player.sendSystemMessage(Component.literal(msg));
        } else {
            src.sendSuccess(() -> Component.literal(msg), false);
        }
    }

    public static final class Config {
        private static ForgeConfigSpec.BooleanValue RANDOMIZE_RECIPES;
        private static ForgeConfigSpec.BooleanValue CHAOS_MODE;
        private static ForgeConfigSpec.BooleanValue SHOW_WELCOME;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> RECIPE_BLACKLIST;
        private static ForgeConfigSpec.ConfigValue<List<? extends String>> MOD_BLACKLIST;

        static ForgeConfigSpec SPEC;

        static void init(ForgeConfigSpec.Builder b) {
            b.comment("Randomized Recipes settings");
            b.push("general");

            RANDOMIZE_RECIPES = b
                    .comment("When true, generated datapacks randomize recipe outputs.")
                    .define("randomize_recipes", true);

            CHAOS_MODE = b
                    .comment(
                            "Chaos Mode skips all blacklists.",
                            "Use this if you want every recipe/output included even if it may create progression issues."
                    )
                    .define("chaos_mode", false);

            ITEM_BLACKLIST = b
                    .comment(
                            "Items that should never be shuffled from or into recipe outputs.",
                            "Use registry IDs, e.g. \"minecraft:dragon_egg\"."
                    )
                    .defineListAllowEmpty("item_blacklist", List.of(
                            "minecraft:air",
                            "minecraft:barrier",
                            "minecraft:bedrock",
                            "minecraft:command_block",
                            "minecraft:chain_command_block",
                            "minecraft:repeating_command_block",
                            "minecraft:structure_block",
                            "minecraft:structure_void",
                            "minecraft:jigsaw",
                            "minecraft:debug_stick",
                            "minecraft:knowledge_book"
                    ), e -> e instanceof String);

            RECIPE_BLACKLIST = b
                    .comment(
                            "Recipe IDs that should never be randomized.",
                            "Use registry IDs, e.g. [\"minecraft:stick\", \"mymod:special_machine\"]."
                    )
                    .defineListAllowEmpty("recipe_blacklist", List.of(), e -> e instanceof String);

            MOD_BLACKLIST = b
                    .comment(
                            "Mod IDs to fully exclude from recipe randomization.",
                            "Recipes and output items from these namespaces are skipped."
                    )
                    .defineListAllowEmpty("mod_blacklist", List.of(), e -> e instanceof String);

            SHOW_WELCOME = b
                    .comment("Show a short command hint when players join.")
                    .define("show_welcome_message", true);

            b.pop();
        }

        static void register() {
            ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
            init(builder);
            SPEC = builder.build();
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        }

        public static boolean randomizeRecipes() {
            return RANDOMIZE_RECIPES.get();
        }

        public static boolean chaosMode() {
            return CHAOS_MODE.get();
        }

        public static boolean showWelcome() {
            return SHOW_WELCOME.get();
        }

        public static Set<ResourceLocation> itemBlacklist() {
            Set<ResourceLocation> set = new HashSet<>();
            for (String s : ITEM_BLACKLIST.get()) {
                try { set.add(ResourceLocation.parse(s)); } catch (Exception ignored) {}
            }
            return set;
        }

        public static Set<ResourceLocation> recipeBlacklist() {
            Set<ResourceLocation> set = new HashSet<>();
            for (String s : RECIPE_BLACKLIST.get()) {
                try { set.add(ResourceLocation.parse(s)); } catch (Exception ignored) {}
            }
            return set;
        }

        public static Set<String> modBlacklist() {
            Set<String> set = new HashSet<>();
            for (String s : MOD_BLACKLIST.get()) {
                set.add(s.trim().toLowerCase());
            }
            return set;
        }
    }
}
