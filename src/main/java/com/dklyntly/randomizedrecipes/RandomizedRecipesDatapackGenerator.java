package com.dklyntly.randomizedrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RandomizedRecipesDatapackGenerator {
    public static final String PACK_DIR = "randomized_recipes_generated";
    public static final String PACK_ID = "file/" + PACK_DIR;
    public static final String SEED_FILE = "randomized_recipes_seed.txt";
    public static final String CONFIG_FILE = "randomized_recipes_config.txt";
    public static final String DATASET_FILE = "randomized_recipes_dataset.txt";
    public static final String MANIFEST_FILE = "randomized_recipes_manifest.json";
    private static final String BUILDING_PACK_DIR = PACK_DIR + "_building";
    private static final String BACKUP_PACK_DIR = PACK_DIR + "_backup";
    private static final Set<String> RESULT_KEYS = Set.of(
            "result", "output", "outputs", "result_item", "output_item", "recipe"
    );
    private static final List<String> ITEM_KEYS = List.of("item", "id", "name");
    private static final List<String> COUNT_KEYS = List.of("count", "amount", "Count");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static ShuffleManifest generate(
            MinecraftServer server,
            RecipeSnapshot snapshot,
            RecipeRandomizer.Result randomized,
            long seed,
            String configHash
    ) throws IOException {
        Path datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR);
        Path targetPath = datapackDir.resolve(PACK_DIR);
        Path buildingPath = datapackDir.resolve(BUILDING_PACK_DIR);
        Path backupPath = datapackDir.resolve(BACKUP_PACK_DIR);
        deleteTree(buildingPath);
        Files.createDirectories(buildingPath);
        Files.writeString(
                buildingPath.resolve("pack.mcmeta"),
                GSON.toJson(packMetadata()),
                StandardCharsets.UTF_8
        );

        Map<ResourceLocation, RecipeSnapshot.Entry> entries = new LinkedHashMap<>();
        snapshot.entries().forEach(entry -> entries.put(entry.id(), entry));
        Map<ResourceLocation, ItemStack> writtenMappings = new LinkedHashMap<>();
        List<ResourceLocation> skippedRecipes = new ArrayList<>();
        List<ResourceLocation> failedRecipes = new ArrayList<>();
        int skipped = 0;
        int failed = 0;

        for (Map.Entry<ResourceLocation, ItemStack> mapping : randomized.mappings().entrySet()) {
            RecipeSnapshot.Entry entry = entries.get(mapping.getKey());
            if (entry == null || entry.originalJson() == null) {
                skipped++;
                skippedRecipes.add(mapping.getKey());
                continue;
            }
            try {
                JsonObject root = entry.originalJson().deepCopy();
                ResourceLocation originalId = BuiltInRegistries.ITEM.getKey(entry.originalOutput().getItem());
                ResourceLocation replacementId = BuiltInRegistries.ITEM.getKey(mapping.getValue().getItem());
                String nbt = mapping.getValue().hasTag() ? mapping.getValue().getTag().toString() : null;
                if (originalId == null || replacementId == null
                        || rewriteResults(root, originalId, replacementId, mapping.getValue().getCount(), nbt) == 0) {
                    skipped++;
                    skippedRecipes.add(mapping.getKey());
                    continue;
                }
                Path output = buildingPath.resolve("data")
                        .resolve(entry.id().getNamespace())
                        .resolve("recipes")
                        .resolve(entry.id().getPath() + ".json");
                Files.createDirectories(output.getParent());
                Files.writeString(output, GSON.toJson(root), StandardCharsets.UTF_8);
                writtenMappings.put(entry.id(), mapping.getValue().copy());
            } catch (Exception exception) {
                failed++;
                failedRecipes.add(mapping.getKey());
                LOGGER.warn("Could not shuffle recipe {}. The original recipe will be kept.", entry.id(), exception);
            }
        }

        if (!randomized.mappings().isEmpty() && writtenMappings.isEmpty()) {
            deleteTree(buildingPath);
            throw new IOException("No compatible recipe files could be generated");
        }

        ShuffleManifest manifest = new ShuffleManifest(
                seed,
                configHash,
                snapshot.datasetHash(),
                randomized.included(),
                randomized.excluded(),
                writtenMappings.size(),
                skipped,
                failed,
                countUnchanged(entries, writtenMappings),
                skippedRecipes,
                failedRecipes,
                writtenMappings
        );
        Files.writeString(buildingPath.resolve(SEED_FILE), String.valueOf(seed), StandardCharsets.UTF_8);
        Files.writeString(buildingPath.resolve(CONFIG_FILE), configHash, StandardCharsets.UTF_8);
        Files.writeString(buildingPath.resolve(DATASET_FILE), snapshot.datasetHash(), StandardCharsets.UTF_8);
        manifest.write(buildingPath.resolve(MANIFEST_FILE));
        replacePack(targetPath, buildingPath, backupPath);
        return manifest;
    }

    public static Preview preview(RecipeSnapshot snapshot, RecipeRandomizer.Result randomized) {
        Map<ResourceLocation, RecipeSnapshot.Entry> entries = new LinkedHashMap<>();
        snapshot.entries().forEach(entry -> entries.put(entry.id(), entry));
        List<ResourceLocation> unsupportedRecipes = new ArrayList<>();
        List<ResourceLocation> failedRecipes = new ArrayList<>();
        int compatible = 0;
        for (Map.Entry<ResourceLocation, ItemStack> mapping : randomized.mappings().entrySet()) {
            RecipeSnapshot.Entry entry = entries.get(mapping.getKey());
            if (entry == null || entry.originalJson() == null) {
                unsupportedRecipes.add(mapping.getKey());
                continue;
            }
            try {
                ResourceLocation originalId = BuiltInRegistries.ITEM.getKey(entry.originalOutput().getItem());
                ResourceLocation replacementId = BuiltInRegistries.ITEM.getKey(mapping.getValue().getItem());
                String nbt = mapping.getValue().hasTag() ? mapping.getValue().getTag().toString() : null;
                if (originalId == null || replacementId == null
                        || rewriteResults(
                        entry.originalJson().deepCopy(),
                        originalId,
                        replacementId,
                        mapping.getValue().getCount(),
                        nbt
                ) == 0) {
                    unsupportedRecipes.add(mapping.getKey());
                } else {
                    compatible++;
                }
            } catch (Exception exception) {
                failedRecipes.add(mapping.getKey());
            }
        }
        return new Preview(
                randomized.included(),
                randomized.excluded(),
                compatible,
                unsupportedRecipes.size(),
                failedRecipes.size(),
                randomized.unchanged(),
                unsupportedRecipes,
                failedRecipes
        );
    }

    static int rewriteResults(
            JsonObject root,
            ResourceLocation originalId,
            ResourceLocation replacementId,
            int replacementCount,
            String replacementNbt
    ) {
        int[] changed = {0};
        rewriteNested(root, originalId.toString(), replacementId.toString(), replacementCount, replacementNbt, changed);
        return changed[0];
    }

    private static void rewriteNested(
            JsonObject object,
            String originalId,
            String replacementId,
            int replacementCount,
            String replacementNbt,
            int[] changed
    ) {
        for (String key : new ArrayList<>(object.keySet())) {
            JsonElement value = object.get(key);
            if (RESULT_KEYS.contains(key)) {
                object.add(key, rewriteCandidate(
                        value, originalId, replacementId, replacementCount, replacementNbt, changed));
            } else if (value.isJsonObject()) {
                rewriteNested(value.getAsJsonObject(), originalId, replacementId, replacementCount, replacementNbt, changed);
            } else if (value.isJsonArray()) {
                rewriteNestedArray(value.getAsJsonArray(), originalId, replacementId, replacementCount, replacementNbt, changed);
            }
        }
    }

    private static void rewriteNestedArray(
            JsonArray array,
            String originalId,
            String replacementId,
            int replacementCount,
            String replacementNbt,
            int[] changed
    ) {
        for (JsonElement value : array) {
            if (value.isJsonObject()) {
                rewriteNested(value.getAsJsonObject(), originalId, replacementId, replacementCount, replacementNbt, changed);
            } else if (value.isJsonArray()) {
                rewriteNestedArray(value.getAsJsonArray(), originalId, replacementId, replacementCount, replacementNbt, changed);
            }
        }
    }

    private static JsonElement rewriteCandidate(
            JsonElement value,
            String originalId,
            String replacementId,
            int replacementCount,
            String replacementNbt,
            int[] changed
    ) {
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() && primitive.getAsString().equals(originalId)) {
                changed[0]++;
                return new JsonPrimitive(replacementId);
            }
            return value;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                array.set(index, rewriteCandidate(
                        array.get(index), originalId, replacementId, replacementCount, replacementNbt, changed));
            }
            return array;
        }
        if (!value.isJsonObject()) return value;
        JsonObject object = value.getAsJsonObject();
        for (String itemKey : ITEM_KEYS) {
            if (!object.has(itemKey) || !object.get(itemKey).isJsonPrimitive()) continue;
            JsonPrimitive itemValue = object.getAsJsonPrimitive(itemKey);
            if (!itemValue.isString() || !itemValue.getAsString().equals(originalId)) continue;
            object.addProperty(itemKey, replacementId);
            boolean countWritten = false;
            for (String countKey : COUNT_KEYS) {
                if (object.has(countKey)) {
                    object.addProperty(countKey, replacementCount);
                    countWritten = true;
                }
            }
            if (!countWritten && replacementCount != 1) object.addProperty("count", replacementCount);
            object.remove("nbt");
            if (replacementNbt != null) object.addProperty("nbt", replacementNbt);
            changed[0]++;
            return object;
        }
        rewriteNested(object, originalId, replacementId, replacementCount, replacementNbt, changed);
        return object;
    }

    public static void deleteGeneratedPack(MinecraftServer server) throws IOException {
        Path datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR);
        deleteTree(datapackDir.resolve(PACK_DIR));
        deleteTree(datapackDir.resolve(BUILDING_PACK_DIR));
        deleteTree(datapackDir.resolve(BACKUP_PACK_DIR));
    }

    private static int countUnchanged(
            Map<ResourceLocation, RecipeSnapshot.Entry> entries,
            Map<ResourceLocation, ItemStack> mappings
    ) {
        int unchanged = 0;
        for (Map.Entry<ResourceLocation, ItemStack> mapping : mappings.entrySet()) {
            RecipeSnapshot.Entry entry = entries.get(mapping.getKey());
            if (entry != null && entry.originalOutput().getItem() == mapping.getValue().getItem()) unchanged++;
        }
        return unchanged;
    }

    private static JsonObject packMetadata() {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        pack.addProperty("description", "Randomized Recipes generated recipe overrides");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    static void replacePack(Path target, Path building, Path backup) throws IOException {
        deleteTree(backup);
        boolean movedTarget = false;
        try {
            if (Files.exists(target)) {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                movedTarget = true;
            }
            Files.move(building, target, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(backup);
        } catch (IOException exception) {
            if (movedTarget && !Files.exists(target) && Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw exception;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record Preview(
            int included,
            int excluded,
            int compatible,
            int unsupported,
            int failed,
            int unchanged,
            List<ResourceLocation> unsupportedRecipes,
            List<ResourceLocation> failedRecipes
    ) {
        public Preview {
            unsupportedRecipes = List.copyOf(unsupportedRecipes);
            failedRecipes = List.copyOf(failedRecipes);
        }
    }
}
