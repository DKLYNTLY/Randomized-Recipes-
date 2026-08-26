package com.dklyntly.randomizedrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record ShuffleManifest(
        long seed,
        String configHash,
        String datasetHash,
        int included,
        int excluded,
        int written,
        int skipped,
        int failed,
        int unchanged,
        List<ResourceLocation> skippedRecipes,
        List<ResourceLocation> failedRecipes,
        Map<ResourceLocation, ItemStack> mappings
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ShuffleManifest {
        Map<ResourceLocation, ItemStack> copies = new LinkedHashMap<>();
        mappings.forEach((id, stack) -> copies.put(id, stack.copy()));
        mappings = Collections.unmodifiableMap(copies);
        skippedRecipes = List.copyOf(skippedRecipes);
        failedRecipes = List.copyOf(failedRecipes);
    }

    public static ShuffleManifest empty() {
        return new ShuffleManifest(0, "", "", 0, 0, 0, 0, 0, 0, List.of(), List.of(), Map.of());
    }

    public static ShuffleManifest read(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<ResourceLocation, ItemStack> mappings = new LinkedHashMap<>();
        JsonObject mappingJson = root.getAsJsonObject("mappings");
        if (mappingJson != null) {
            for (Map.Entry<String, JsonElement> entry : mappingJson.entrySet()) {
                ResourceLocation recipeId = ResourceLocation.tryParse(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                ResourceLocation itemId = ResourceLocation.tryParse(value.get("item").getAsString());
                if (recipeId == null || itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) continue;
                Item item = BuiltInRegistries.ITEM.get(itemId);
                ItemStack stack = new ItemStack(item, value.has("count") ? value.get("count").getAsInt() : 1);
                if (value.has("nbt")) {
                    try {
                        stack.setTag(TagParser.parseTag(value.get("nbt").getAsString()));
                    } catch (Exception ignored) {
                    }
                }
                mappings.put(recipeId, stack);
            }
        }
        return new ShuffleManifest(
                root.get("seed").getAsLong(),
                text(root, "config_hash"),
                text(root, "dataset_hash"),
                number(root, "included"),
                number(root, "excluded"),
                number(root, "written"),
                number(root, "skipped"),
                number(root, "failed"),
                number(root, "unchanged"),
                resourceLocations(root, "skipped_recipes"),
                resourceLocations(root, "failed_recipes"),
                mappings
        );
    }

    public void write(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.addProperty("seed", seed);
        root.addProperty("config_hash", configHash);
        root.addProperty("dataset_hash", datasetHash);
        root.addProperty("included", included);
        root.addProperty("excluded", excluded);
        root.addProperty("written", written);
        root.addProperty("skipped", skipped);
        root.addProperty("failed", failed);
        root.addProperty("unchanged", unchanged);
        root.add("skipped_recipes", GSON.toJsonTree(skippedRecipes.stream().map(ResourceLocation::toString).toList()));
        root.add("failed_recipes", GSON.toJsonTree(failedRecipes.stream().map(ResourceLocation::toString).toList()));
        JsonObject mappingJson = new JsonObject();
        mappings.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject value = new JsonObject();
            value.addProperty("item", BuiltInRegistries.ITEM.getKey(entry.getValue().getItem()).toString());
            value.addProperty("count", entry.getValue().getCount());
            if (entry.getValue().hasTag()) value.addProperty("nbt", entry.getValue().getTag().toString());
            mappingJson.add(entry.getKey().toString(), value);
        });
        root.add("mappings", mappingJson);
        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static String text(JsonObject root, String key) {
        return root.has(key) ? root.get(key).getAsString() : "";
    }

    private static int number(JsonObject root, String key) {
        return root.has(key) ? root.get(key).getAsInt() : 0;
    }

    private static List<ResourceLocation> resourceLocations(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return List.of();
        return root.getAsJsonArray(key).asList().stream()
                .map(JsonElement::getAsString)
                .map(ResourceLocation::tryParse)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
