package com.dklyntly.randomizedrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class RecipeSnapshot {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<Entry> entries;
    private final String datasetHash;

    private RecipeSnapshot(List<Entry> entries, String datasetHash) {
        this.entries = List.copyOf(entries);
        this.datasetHash = datasetHash;
    }

    public static RecipeSnapshot capture(MinecraftServer server) {
        RegistryAccess access = server.registryAccess();
        List<Recipe<?>> loaded = new ArrayList<>(server.getRecipeManager().getRecipes());
        loaded.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        List<Entry> entries = new ArrayList<>();
        MessageDigest digest = digest();

        for (Recipe<?> loadedRecipe : loaded) {
            ResourceLocation id = loadedRecipe.getId();
            JsonObject sourceJson = findOriginalJson(server, id);
            Recipe<?> originalRecipe = deserializeOriginal(id, sourceJson, loadedRecipe);
            ItemStack output = safeOutput(originalRecipe, access);
            String serializerId = String.valueOf(BuiltInRegistries.RECIPE_SERIALIZER.getKey(originalRecipe.getSerializer()));
            entries.add(new Entry(id, originalRecipe, output, sourceJson, serializerId));
            update(digest, id.toString());
            update(digest, serializerId);
            update(digest, sourceJson == null ? "<programmatic>" : sourceJson.toString());
        }

        return new RecipeSnapshot(entries, HexFormat.of().formatHex(digest.digest()));
    }

    private static JsonObject findOriginalJson(MinecraftServer server, ResourceLocation recipeId) {
        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                recipeId.getNamespace(), "recipes/" + recipeId.getPath() + ".json");
        List<Resource> stack = server.getResourceManager().getResourceStack(resourceId);
        for (int index = stack.size() - 1; index >= 0; index--) {
            Resource resource = stack.get(index);
            if (resource.sourcePackId().contains(RandomizedRecipesDatapackGenerator.PACK_DIR)) continue;
            try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception exception) {
                LOGGER.warn("Could not read original recipe {} from {}.", recipeId, resource.sourcePackId(), exception);
                return null;
            }
        }
        return null;
    }

    private static Recipe<?> deserializeOriginal(ResourceLocation id, JsonObject json, Recipe<?> fallback) {
        if (json == null) return fallback;
        try {
            Recipe<?> parsed = RecipeManager.fromJson(id, json.deepCopy());
            return parsed == null ? fallback : parsed;
        } catch (Exception exception) {
            LOGGER.warn("Could not deserialize original recipe {}. Compatibility rewriting will still be attempted.", id, exception);
            return fallback;
        }
    }

    private static ItemStack safeOutput(Recipe<?> recipe, RegistryAccess access) {
        try {
            return recipe.getResultItem(access).copy();
        } catch (Exception exception) {
            LOGGER.warn("Could not inspect output for recipe {}.", recipe.getId(), exception);
            return ItemStack.EMPTY;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    public List<Entry> entries() {
        return entries;
    }

    public String datasetHash() {
        return datasetHash;
    }

    public record Entry(
            ResourceLocation id,
            Recipe<?> recipe,
            ItemStack originalOutput,
            JsonObject originalJson,
            String serializerId
    ) {
    }
}
