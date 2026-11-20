package com.example.donutclient.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * An ingredient the bot knows about: a name, a test that recognises matching item stacks and, when the bot can
 * craft it, the shaped recipe. The constants below form the bot's whole crafting tree.
 */
public final class Ing {
    public final String name;
    public final Predicate<ItemStack> pred;
    /** Shaped recipe producing this ingredient, or null for raw materials that must be gathered. */
    public Recipe recipe;

    private Ing(String name, Predicate<ItemStack> pred) {
        this.name = name;
        this.pred = pred;
    }

    private static Ing of(Item item) {
        return new Ing(item.toString(), s -> s.is(item));
    }

    private static Ing of(String name, TagKey<Item> tag) {
        return new Ing(name, s -> s.is(tag));
    }

    /**
     * Attaches a shaped recipe.
     *
     * @param out     number of items one craft yields
     * @param pattern grid rows, a space meaning an empty cell
     * @param key     alternating pattern character and the {@link Ing} it stands for
     */
    private Ing craft(int out, String[] pattern, Object... key) {
        Map<Character, Ing> map = new LinkedHashMap<>();
        for (int i = 0; i < key.length; i += 2) {
            map.put((Character) key[i], (Ing) key[i + 1]);
        }
        recipe = new Recipe(out, pattern, map);
        return this;
    }

    /** Shorthand for writing a pattern as varargs. */
    private static String[] p(String... rows) {
        return rows;
    }

    @Override
    public String toString() {
        return name;
    }

    public static final Ing LOG = of("log", ItemTags.LOGS);
    public static final Ing PLANK = of("planks", ItemTags.PLANKS).craft(4, p("L"), 'L', LOG);
    public static final Ing STICK = of(Items.STICK).craft(4, p("P", "P"), 'P', PLANK);
    public static final Ing TABLE = of(Items.CRAFTING_TABLE).craft(1, p("PP", "PP"), 'P', PLANK);
    public static final Ing COBBLE = of("cobblestone", ItemTags.STONE_TOOL_MATERIALS);
    public static final Ing IRON = of(Items.IRON_INGOT);
    public static final Ing FLINT = of(Items.FLINT);
    public static final Ing BLAZE_ROD = of(Items.BLAZE_ROD);
    public static final Ing PEARL = of(Items.ENDER_PEARL);
    public static final Ing WOOD_PICK = of(Items.WOODEN_PICKAXE).craft(1, p("PPP", " S ", " S "), 'P', PLANK, 'S', STICK);
    public static final Ing STONE_PICK = of(Items.STONE_PICKAXE).craft(1, p("CCC", " S ", " S "), 'C', COBBLE, 'S', STICK);
    public static final Ing STONE_AXE = of(Items.STONE_AXE).craft(1, p("CC", "CS", " S"), 'C', COBBLE, 'S', STICK);
    public static final Ing STONE_SWORD = of(Items.STONE_SWORD).craft(1, p("C", "C", "S"), 'C', COBBLE, 'S', STICK);
    public static final Ing STONE_SHOVEL = of(Items.STONE_SHOVEL).craft(1, p("C", "S", "S"), 'C', COBBLE, 'S', STICK);
    public static final Ing FURNACE = of(Items.FURNACE).craft(1, p("CCC", "C C", "CCC"), 'C', COBBLE);
    public static final Ing IRON_PICK = of(Items.IRON_PICKAXE).craft(1, p("III", " S ", " S "), 'I', IRON, 'S', STICK);
    public static final Ing IRON_SWORD = of(Items.IRON_SWORD).craft(1, p("I", "I", "S"), 'I', IRON, 'S', STICK);
    public static final Ing BUCKET = of(Items.BUCKET).craft(1, p("I I", " I "), 'I', IRON);
    public static final Ing SHIELD = of(Items.SHIELD).craft(1, p("PIP", "PPP", " P "), 'P', PLANK, 'I', IRON);
    public static final Ing FLINT_STEEL = of(Items.FLINT_AND_STEEL).craft(1, p("IF"), 'I', IRON, 'F', FLINT);
    public static final Ing HELMET = of(Items.IRON_HELMET).craft(1, p("III", "I I"), 'I', IRON);
    public static final Ing CHESTPLATE = of(Items.IRON_CHESTPLATE).craft(1, p("I I", "III", "III"), 'I', IRON);
    public static final Ing LEGGINGS = of(Items.IRON_LEGGINGS).craft(1, p("III", "I I", "I I"), 'I', IRON);
    public static final Ing BOOTS = of(Items.IRON_BOOTS).craft(1, p("I I", "I I"), 'I', IRON);
    public static final Ing BLAZE_POWDER = of(Items.BLAZE_POWDER).craft(2, p("R"), 'R', BLAZE_ROD);
    public static final Ing EYE = of(Items.ENDER_EYE).craft(1, p("BP"), 'B', BLAZE_POWDER, 'P', PEARL);

    /**
     * A shaped crafting recipe.
     *
     * @param out     items produced per craft
     * @param pattern grid rows; each character is looked up in {@code key}, a space is an empty cell
     * @param key     pattern character to ingredient
     */
    public record Recipe(int out, String[] pattern, Map<Character, Ing> key) {
        /** True if the pattern fits the 2x2 inventory grid, so no crafting table is needed. */
        public boolean fits2x2() {
            if (pattern.length > 2) {
                return false;
            }
            for (String row : pattern) {
                if (row.length() > 2) {
                    return false;
                }
            }
            return true;
        }

        /** How many of each ingredient one craft consumes. */
        public Map<Ing, Integer> inputs() {
            Map<Ing, Integer> counts = new LinkedHashMap<>();
            for (String row : pattern) {
                for (char c : row.toCharArray()) {
                    if (c != ' ') {
                        counts.merge(key.get(c), 1, Integer::sum);
                    }
                }
            }
            return counts;
        }
    }
}
