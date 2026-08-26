package com.dklyntly.randomizedrecipes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;

public class RandomizedRecipesDatapackGenerator {
    public static final String PACK_DIR = "randomized_recipes_generated";
    public static final String PACK_ID = "file/" + PACK_DIR;
    public static final String SEED_FILE = "randomized_recipes_seed.txt";
    public static final String CONFIG_FILE = "randomized_recipes_config.txt";
    private static final String BUILDING_PACK_DIR = PACK_DIR + "_building";
    private static final String BACKUP_PACK_DIR = PACK_DIR + "_backup";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static int generate(MinecraftServer server, Map<ResourceLocation, ItemStack> replacements) {
        Path datapackDir = server.getWorldPath(LevelResource.DATAPACK_DIR);
        Path targetPath = datapackDir.resolve(PACK_DIR);
        Path buildingPath = datapackDir.resolve(BUILDING_PACK_DIR);
        File buildingDir = buildingPath.toFile();
        int written = 0;

        deleteRecursive(buildingDir);
        buildingDir.mkdirs();

        try (FileWriter fw = new FileWriter(new File(buildingDir, "pack.mcmeta"), StandardCharsets.UTF_8)) {
            fw.write("{\"pack\":{\"pack_format\":15,\"description\":\"Randomized Recipes generated recipes\"}}");
        } catch (IOException e) {
            LOGGER.error("Could not create Randomized Recipes datapack metadata.", e);
            deleteRecursive(buildingDir);
            return 0;
        }

        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            ItemStack repl = replacements.get(r.getId());
            if (repl == null) continue;
            try {
                ResourceLocation path = ResourceLocation.fromNamespaceAndPath(r.getId().getNamespace(), "recipes/" + r.getId().getPath() + ".json");
                Optional<Resource> opt = server.getResourceManager().getResource(path);
                if (opt.isEmpty()) continue;

                Resource res = opt.get();
                JsonObject root;
                try (Reader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(reader).getAsJsonObject();
}
                if (!replaceResult(root, repl)) continue;

                File recipesDir = new File(buildingDir, "data/" + r.getId().getNamespace() + "/recipes");
                File outFile = new File(recipesDir, r.getId().getPath() + ".json");
                outFile.getParentFile().mkdirs();
                try (FileWriter fw = new FileWriter(outFile, StandardCharsets.UTF_8)) {
                    fw.write(root.toString());
                }
                written++;

            } catch (Exception ex) {
                LOGGER.warn("Could not shuffle recipe {}. The original recipe will be kept.", r.getId(), ex);
            }
        }

        try {
            replacePack(targetPath, buildingPath, datapackDir.resolve(BACKUP_PACK_DIR));
        } catch (IOException e) {
            LOGGER.error("Could not replace the generated Randomized Recipes datapack.", e);
            deleteRecursive(buildingDir);
            return 0;
        }
        return written;
    }

    static boolean replaceResult(JsonObject root, ItemStack replacement) {
        if (replacement.isEmpty() || !root.has("result")) return false;

        ResourceLocation replacementId = BuiltInRegistries.ITEM.getKey(replacement.getItem());
        if (replacementId == null || replacementId.equals(BuiltInRegistries.ITEM.getDefaultKey())) return false;

        if (root.get("result").isJsonPrimitive()) {
            JsonPrimitive result = root.getAsJsonPrimitive("result");
            if (!result.isString()) return false;
            root.addProperty("result", replacementId.toString());
            return true;
        }

        if (!root.get("result").isJsonObject()) return false;
        JsonObject result = root.getAsJsonObject("result");
        if (!result.has("item") || !result.get("item").isJsonPrimitive()) return false;
        JsonPrimitive item = result.getAsJsonPrimitive("item");
        if (!item.isString()) return false;

        result.addProperty("item", replacementId.toString());
        if (result.has("count") || replacement.getCount() != 1) {
            result.addProperty("count", replacement.getCount());
        }
        return true;
    }

    private static void replacePack(Path target, Path building, Path backup) throws IOException {
        deleteRecursive(backup.toFile());
        boolean movedTarget = false;
        try {
            if (Files.exists(target)) {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
                movedTarget = true;
            }
            Files.move(building, target, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursive(backup.toFile());
        } catch (IOException e) {
            if (movedTarget && !Files.exists(target) && Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File child : kids) deleteRecursive(child);
            }
        }
        f.delete();
    }
}
