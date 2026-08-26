package com.dklyntly.randomizedrecipes;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.*;

public class RecipeRandomizer {
    public static Map<ResourceLocation, ItemStack> randomize(MinecraftServer server, long seed) {
        List<Recipe<?>> all = new ArrayList<>(server.getRecipeManager().getRecipes());
        RegistryAccess access = server.registryAccess();

        List<ItemStack> outputs = collectOutputs(all, access);

        if (outputs.size() < 2) return Collections.emptyMap();

        Collections.shuffle(outputs, new Random(seed));

        Map<ResourceLocation, ItemStack> remap = new HashMap<>();
        int i = 0;
        for (Recipe<?> r : all) {
            try {
                if (!isIncluded(r, access)) continue;
                ItemStack orig = r.getResultItem(access);
                if (orig.isEmpty() || !isAllowedOutput(orig)) continue;
                ItemStack newOut = outputs.get(i % outputs.size()).copy();
                newOut.setCount(orig.getCount());
                remap.put(r.getId(), newOut);
                i++;
            } catch (Exception ignored) {}
        }
        return remap;
    }

    private static List<ItemStack> collectOutputs(List<Recipe<?>> recipes, RegistryAccess access) {
        List<ItemStack> outputs = new ArrayList<>();
        if (RandomizedRecipesMod.Config.chaosMode()) {
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack stack = new ItemStack(item);
                if (!stack.isEmpty()) outputs.add(stack);
            }
            return outputs;
        }

        for (Recipe<?> recipe : recipes) {
            try {
                if (!isIncluded(recipe, access)) continue;
                ItemStack result = recipe.getResultItem(access);
                if (!result.isEmpty() && isAllowedOutput(result)) outputs.add(result.copy());
            } catch (Exception ignored) {}
        }
        return outputs;
    }

    public static Map<ResourceLocation, ItemStack> randomize(MinecraftServer server) {
        return randomize(server, server.overworld().getSeed());
    }

    private static boolean isIncluded(Recipe<?> recipe, RegistryAccess access) {
        if (RandomizedRecipesMod.Config.chaosMode()) return true;

        ResourceLocation recipeId = recipe.getId();
        if (RandomizedRecipesMod.Config.recipeBlacklist().contains(recipeId)) return false;
        if (RandomizedRecipesMod.Config.modBlacklist().contains(recipeId.getNamespace())) return false;

        ItemStack output = recipe.getResultItem(access);
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
        if (RandomizedRecipesMod.Config.itemBlacklist().contains(outputId)) return false;
        return !RandomizedRecipesMod.Config.modBlacklist().contains(outputId.getNamespace());
    }

    private static boolean isAllowedOutput(ItemStack stack) {
        if (RandomizedRecipesMod.Config.chaosMode()) return true;

        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (RandomizedRecipesMod.Config.itemBlacklist().contains(outputId)) return false;
        return !RandomizedRecipesMod.Config.modBlacklist().contains(outputId.getNamespace());
    }
}
