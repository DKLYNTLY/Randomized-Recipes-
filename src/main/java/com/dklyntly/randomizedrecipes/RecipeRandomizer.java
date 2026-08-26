package com.dklyntly.randomizedrecipes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class RecipeRandomizer {
    private static final Set<ResourceLocation> SAFE_OUTPUTS = Set.of(
            id("minecraft:stick"),
            id("minecraft:crafting_table"),
            id("minecraft:furnace"),
            id("minecraft:wooden_pickaxe"),
            id("minecraft:stone_pickaxe"),
            id("minecraft:iron_pickaxe"),
            id("minecraft:torch"),
            id("minecraft:chest")
    );
    private static final Set<TagKey<Item>> SAFE_OUTPUT_TAGS = Set.of(ItemTags.PLANKS);

    public static Result randomize(RecipeSnapshot snapshot, long seed, RandomizedRecipesMod.ConfigSnapshot config) {
        List<RecipeSnapshot.Entry> included = snapshot.entries().stream()
                .filter(entry -> isIncluded(entry, config))
                .toList();
        List<ItemStack> outputs = collectOutputs(included, config);
        if (included.isEmpty() || outputs.size() < 2) {
            return new Result(Map.of(), included.size(), snapshot.entries().size() - included.size(), 0);
        }

        List<ItemStack> shuffled = shuffledOutputs(included, outputs, seed, config);
        Map<ResourceLocation, ItemStack> mappings = new LinkedHashMap<>();
        int unchanged = 0;
        for (int index = 0; index < included.size(); index++) {
            RecipeSnapshot.Entry entry = included.get(index);
            ItemStack replacement = shuffled.get(index % shuffled.size()).copy();
            if (!config.shuffleOutputCounts()) replacement.setCount(entry.originalOutput().getCount());
            if (sameItem(entry.originalOutput(), replacement)) unchanged++;
            mappings.put(entry.id(), replacement);
        }
        return new Result(mappings, included.size(), snapshot.entries().size() - included.size(), unchanged);
    }

    private static List<ItemStack> collectOutputs(
            List<RecipeSnapshot.Entry> included,
            RandomizedRecipesMod.ConfigSnapshot config
    ) {
        List<ItemStack> outputs = new ArrayList<>();
        if (config.chaosMode()) {
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack stack = new ItemStack(item);
                if (!stack.isEmpty()) outputs.add(stack);
            }
            return outputs;
        }
        included.stream()
                .map(RecipeSnapshot.Entry::originalOutput)
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .forEach(outputs::add);
        return outputs;
    }

    private static List<ItemStack> shuffledOutputs(
            List<RecipeSnapshot.Entry> included,
            List<ItemStack> outputs,
            long seed,
            RandomizedRecipesMod.ConfigSnapshot config
    ) {
        if (!config.avoidUnchangedOutputs() || config.chaosMode() || included.size() != outputs.size()) {
            List<ItemStack> shuffled = copy(outputs);
            Collections.shuffle(shuffled, new Random(seed));
            return shuffled;
        }

        List<ItemStack> best = copy(outputs);
        int bestUnchanged = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 64; attempt++) {
            List<ItemStack> candidate = copy(outputs);
            Collections.shuffle(candidate, new Random(seed + 0x9E3779B97F4A7C15L * attempt));
            int unchanged = unchangedCount(included, candidate);
            if (unchanged < bestUnchanged) {
                best = candidate;
                bestUnchanged = unchanged;
            }
            if (unchanged == 0) break;
        }
        return best;
    }

    private static int unchangedCount(List<RecipeSnapshot.Entry> included, List<ItemStack> outputs) {
        int unchanged = 0;
        for (int index = 0; index < included.size(); index++) {
            if (sameItem(included.get(index).originalOutput(), outputs.get(index))) unchanged++;
        }
        return unchanged;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private static boolean isIncluded(
            RecipeSnapshot.Entry entry,
            RandomizedRecipesMod.ConfigSnapshot config
    ) {
        if (entry.originalOutput().isEmpty()) return false;
        if (config.chaosMode()) return true;
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(entry.originalOutput().getItem());
        if (outputId == null) return false;
        if (config.safeMode() && isProgressionOutput(entry.originalOutput(), outputId)) return false;
        if (config.recipeBlacklist().contains(entry.id())) return false;
        if (config.itemBlacklist().contains(outputId)) return false;
        if (config.modBlacklist().contains(entry.id().getNamespace())) return false;
        if (config.modBlacklist().contains(outputId.getNamespace())) return false;
        if (config.itemTagBlacklist().stream().anyMatch(entry.originalOutput()::is)) return false;
        if (!config.itemTagAllowlist().isEmpty()
                && config.itemTagAllowlist().stream().noneMatch(entry.originalOutput()::is)) return false;
        if (config.recipeTypeBlacklist().contains(entry.serializerId())) return false;
        return config.recipeTypeAllowlist().isEmpty()
                || config.recipeTypeAllowlist().contains(entry.serializerId());
    }

    private static boolean isProgressionOutput(ItemStack output, ResourceLocation outputId) {
        return SAFE_OUTPUTS.contains(outputId) || SAFE_OUTPUT_TAGS.stream().anyMatch(output::is);
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem();
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    public record Result(
            Map<ResourceLocation, ItemStack> mappings,
            int included,
            int excluded,
            int unchanged
    ) {
    }
}
