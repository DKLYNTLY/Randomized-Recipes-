package com.dklyntly.randomizedrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GameTestHolder(RandomizedRecipesMod.MODID)
@PrefixGameTestTemplate(false)
public final class RandomizedRecipesGameTests {
    private static final String BATCH = "randomized_recipes";
    @GameTest(template = "empty", batch = BATCH)
    public static void deterministicSeeds(GameTestHelper helper) {
        RecipeSnapshot snapshot = snapshot(helper);
        RecipeRandomizer.Result first = RecipeRandomizer.randomize(snapshot, 913579L, balanced());
        RecipeRandomizer.Result second = RecipeRandomizer.randomize(snapshot, 913579L, balanced());
        require(helper, identities(first.mappings()).equals(identities(second.mappings())),
                "The same seed produced different recipe mappings");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void blacklistExcludesOriginalOutputs(GameTestHelper helper) {
        RecipeSnapshot snapshot = snapshot(helper);
        RecipeSnapshot.Entry target = snapshot.entries().stream()
                .filter(entry -> !entry.originalOutput().isEmpty())
                .findFirst()
                .orElseThrow();
        ResourceLocation blockedItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(target.originalOutput().getItem());
        RandomizedRecipesMod.ConfigSnapshot config = new RandomizedRecipesMod.ConfigSnapshot(
                true,
                RandomizedRecipesMod.ShufflePreset.BALANCED,
                true,
                false,
                Set.of(blockedItem),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
        );
        RecipeRandomizer.Result result = RecipeRandomizer.randomize(snapshot, 42L, config);
        boolean blockedRecipePresent = snapshot.entries().stream()
                .filter(entry -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(entry.originalOutput().getItem()).equals(blockedItem))
                .anyMatch(entry -> result.mappings().containsKey(entry.id()));
        require(helper, !blockedRecipePresent, "A blacklisted output remained in the mapping");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void disablingRemovesGeneratedPackSelection(GameTestHelper helper) {
        List<String> selected = List.of("vanilla", RandomizedRecipesDatapackGenerator.PACK_ID, "mod/example");
        List<String> disabled = RandomizedRecipesMod.withoutGeneratedPack(selected);
        List<String> enabled = RandomizedRecipesMod.withGeneratedPack(disabled);
        require(helper, !disabled.contains(RandomizedRecipesDatapackGenerator.PACK_ID),
                "Disabling retained the generated pack");
        require(helper, enabled.stream()
                        .filter(RandomizedRecipesDatapackGenerator.PACK_ID::equals)
                        .count() == 1,
                "Enabling did not select exactly one generated pack");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void repeatedReshufflesUseOriginalBaseline(GameTestHelper helper) {
        RecipeSnapshot firstSnapshot = snapshot(helper);
        RecipeRandomizer.Result first = RecipeRandomizer.randomize(firstSnapshot, 77123L, balanced());
        RecipeSnapshot secondSnapshot = snapshot(helper);
        RecipeRandomizer.Result second = RecipeRandomizer.randomize(secondSnapshot, 77123L, balanced());
        require(helper, firstSnapshot.datasetHash().equals(secondSnapshot.datasetHash()),
                "The original recipe dataset changed after the generated pack was active");
        require(helper, identities(first.mappings()).equals(identities(second.mappings())),
                "Repeated reshuffling compounded the active mapping");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void nbtOutputReplacement(GameTestHelper helper) {
        JsonObject root = JsonParser.parseString("""
                {
                  "type": "minecraft:crafting_shapeless",
                  "result": {
                    "item": "minecraft:stone",
                    "nbt": "{old:1b}"
                  }
                }
                """).getAsJsonObject();
        int changed = RandomizedRecipesDatapackGenerator.rewriteResults(
                root,
                ResourceLocation.parse("minecraft:stone"),
                ResourceLocation.parse("minecraft:diamond"),
                2,
                "{new:1b}"
        );
        JsonObject result = root.getAsJsonObject("result");
        require(helper, changed == 1, "NBT result was not rewritten");
        require(helper, result.get("item").getAsString().equals("minecraft:diamond"),
                "Replacement item was incorrect");
        require(helper, result.get("count").getAsInt() == 2, "Replacement count was incorrect");
        require(helper, result.get("nbt").getAsString().equals("{new:1b}"),
                "Replacement NBT was incorrect");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void unsupportedSerializersAreReported(GameTestHelper helper) {
        RecipeSnapshot snapshot = snapshot(helper);
        RecipeRandomizer.Result randomized = RecipeRandomizer.randomize(snapshot, 11881L, balanced());
        RandomizedRecipesDatapackGenerator.Preview preview =
                RandomizedRecipesDatapackGenerator.preview(snapshot, randomized);
        require(helper, preview.compatible() > 0, "No compatible recipes were detected");
        require(helper, preview.failed() == 0, "Recipe inspection unexpectedly failed");
        require(helper, preview.unsupported() == preview.unsupportedRecipes().size(),
                "Unsupported recipe diagnostics were incomplete");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH)
    public static void failedPackSwapRestoresPreviousPack(GameTestHelper helper) {
        Path root = null;
        try {
            root = Files.createTempDirectory("randomized-recipes-gametest-");
            Path target = root.resolve("target");
            Path building = root.resolve("missing-building");
            Path backup = root.resolve("backup");
            Files.createDirectories(target);
            Files.writeString(target.resolve("marker.txt"), "previous", StandardCharsets.UTF_8);
            try {
                RandomizedRecipesDatapackGenerator.replacePack(target, building, backup);
                helper.fail("A missing building pack did not fail");
                return;
            } catch (IOException expected) {
                require(helper, Files.readString(target.resolve("marker.txt")).equals("previous"),
                        "The previous pack was not restored after failure");
            }
            helper.succeed();
        } catch (Exception exception) {
            helper.fail("Recovery test failed: " + exception.getMessage());
        } finally {
            deleteTree(root);
        }
    }

    private static RecipeSnapshot snapshot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        return RecipeSnapshot.capture(server);
    }

    private static RandomizedRecipesMod.ConfigSnapshot balanced() {
        return new RandomizedRecipesMod.ConfigSnapshot(
                true,
                RandomizedRecipesMod.ShufflePreset.BALANCED,
                true,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
        );
    }

    private static Map<ResourceLocation, String> identities(Map<ResourceLocation, ItemStack> mappings) {
        Map<ResourceLocation, String> result = new LinkedHashMap<>();
        mappings.forEach((id, stack) -> result.put(
                id,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + "@" + stack.getCount()
                        + "@" + String.valueOf(stack.getTag())
        ));
        return result;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
