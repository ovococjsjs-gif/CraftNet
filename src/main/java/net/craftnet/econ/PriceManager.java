package net.craftnet.econ;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Прайс-лист сервера.
 *  - торгуются ТОЛЬКО ванильные предметы (namespace minecraft) и явно
 *    разрешённые наши (ядро вышки) — блоки чужих модов в магазин,
 *    барахолку-оценку и апгрейдер не попадают;
 *  - базовая таблица покрывает самое ходовое ВКЛЮЧАЯ блоки руд и сжатые
 *    блоки ресурсов (кто-то же попробует поставить сноп сена против
 *    алмазной руды в казино — теперь честно: руда дороже);
 *  - для ванильных предметов вне таблицы — консервативная эвристика.
 * Цены покупки (buy). Продажа (sell) = 72% от buy.
 */
public final class PriceManager {
	private PriceManager() {}

	private static final Map<String, Integer> TABLE = new HashMap<>();
	private static final double SELL_RATIO = 0.72;

	static {
		// руды и ресурсы (сырьё)
		put(Items.COAL, 3); put(Items.CHARCOAL, 3); put(Items.RAW_IRON, 7); put(Items.IRON_INGOT, 10);
		put(Items.RAW_GOLD, 18); put(Items.GOLD_INGOT, 25); put(Items.RAW_COPPER, 3); put(Items.COPPER_INGOT, 4);
		put(Items.DIAMOND, 120); put(Items.EMERALD, 60); put(Items.REDSTONE, 5); put(Items.LAPIS_LAZULI, 6);
		put(Items.QUARTZ, 8); put(Items.AMETHYST_SHARD, 20); put(Items.NETHERITE_SCRAP, 220);
		put(Items.NETHERITE_INGOT, 900); put(Items.ANCIENT_DEBRIS, 850); put(Items.IRON_NUGGET, 2);
		put(Items.GOLD_NUGGET, 3); put(Items.FLINT, 3); put(Items.CLAY_BALL, 2);

		// БЛОКИ РУД — самая частая лазейка в апгрейдере: блок дороже содержимого
		put(Items.COAL_ORE, 5); put(Items.DEEPSLATE_COAL_ORE, 7);
		put(Items.IRON_ORE, 9); put(Items.DEEPSLATE_IRON_ORE, 12);
		put(Items.GOLD_ORE, 24); put(Items.DEEPSLATE_GOLD_ORE, 30);
		put(Items.COPPER_ORE, 5); put(Items.DEEPSLATE_COPPER_ORE, 7);
		put(Items.REDSTONE_ORE, 9); put(Items.DEEPSLATE_REDSTONE_ORE, 12);
		put(Items.LAPIS_ORE, 12); put(Items.DEEPSLATE_LAPIS_ORE, 15);
		put(Items.EMERALD_ORE, 75); put(Items.DEEPSLATE_EMERALD_ORE, 90);
		put(Items.DIAMOND_ORE, 150); put(Items.DEEPSLATE_DIAMOND_ORE, 180);
		put(Items.NETHER_GOLD_ORE, 26); put(Items.NETHER_QUARTZ_ORE, 12);

		// сжатые блоки ресурсов (9 шт исходника ±)
		put(Items.COAL_BLOCK, 27); put(Items.IRON_BLOCK, 90); put(Items.GOLD_BLOCK, 225);
		put(Items.COPPER_BLOCK, 36); put(Items.REDSTONE_BLOCK, 45); put(Items.LAPIS_BLOCK, 54);
		put(Items.EMERALD_BLOCK, 540); put(Items.DIAMOND_BLOCK, 1080); put(Items.NETHERITE_BLOCK, 8100);
		put(Items.QUARTZ_BLOCK, 34); put(Items.AMETHYST_BLOCK, 80); put(Items.RAW_IRON_BLOCK, 63);
		put(Items.RAW_GOLD_BLOCK, 162); put(Items.RAW_COPPER_BLOCK, 27);
		put(Items.HAY_BLOCK, 27); put(Items.SLIME_BLOCK, 135); put(Items.HONEY_BLOCK, 80);
		put(Items.HONEYCOMB_BLOCK, 60); put(Items.GLOWSTONE, 14); put(Items.GLOWSTONE_DUST, 4);
		put(Items.DRIED_KELP_BLOCK, 18); put(Items.BONE_BLOCK, 27); put(Items.NETHER_WART_BLOCK, 20);
		put(Items.MELON, 8); put(Items.PUMPKIN, 6); put(Items.CARVED_PUMPKIN, 8); put(Items.JACK_O_LANTERN, 14);

		// стройматериалы
		put(Items.OAK_LOG, 2); put(Items.SPRUCE_LOG, 2); put(Items.BIRCH_LOG, 2); put(Items.JUNGLE_LOG, 2);
		put(Items.ACACIA_LOG, 2); put(Items.DARK_OAK_LOG, 2); put(Items.MANGROVE_LOG, 2); put(Items.CHERRY_LOG, 3);
		put(Items.CRIMSON_STEM, 4); put(Items.WARPED_STEM, 4); put(Items.PALE_OAK_LOG, 3);
		put(Items.OAK_PLANKS, 1); put(Items.SPRUCE_PLANKS, 1); put(Items.BIRCH_PLANKS, 1);
		put(Items.COBBLESTONE, 1); put(Items.COBBLED_DEEPSLATE, 1); put(Items.STONE, 1); put(Items.STONE_BRICKS, 2);
		put(Items.SAND, 1); put(Items.RED_SAND, 1); put(Items.GLASS, 3); put(Items.BRICKS, 6); put(Items.OBSIDIAN, 30);
		put(Items.GRAVEL, 1); put(Items.BRICK, 3); put(Items.NETHER_BRICK, 3); put(Items.NETHER_BRICKS, 4);
		put(Items.TERRACOTTA, 2); put(Items.DEEPSLATE, 1); put(Items.BLACKSTONE, 2); put(Items.BASALT, 1);
		put(Items.DIRT, 1); put(Items.GRASS_BLOCK, 2); put(Items.MUD, 1); put(Items.CLAY, 8);
		put(Items.PRISMARINE_SHARD, 15); put(Items.PRISMARINE_CRYSTALS, 20); put(Items.SEA_LANTERN, 60);
		put(Items.ANDESITE, 1); put(Items.DIORITE, 1); put(Items.GRANITE, 1); put(Items.TUFF, 1);
		put(Items.CALCITE, 2); put(Items.DRIPSTONE_BLOCK, 2); put(Items.MOSS_BLOCK, 2);
		put(Items.SOUL_SAND, 4); put(Items.SOUL_SOIL, 4); put(Items.END_STONE, 3); put(Items.PURPUR_BLOCK, 6);
		put(Items.ICE, 3); put(Items.PACKED_ICE, 9); put(Items.BLUE_ICE, 27); put(Items.SNOW_BLOCK, 1);
		put(Items.WHITE_WOOL, 6); put(Items.WHITE_CONCRETE, 3); put(Items.WHITE_TERRACOTTA, 2);

		// еда
		put(Items.BREAD, 6); put(Items.COOKED_BEEF, 12); put(Items.COOKED_PORKCHOP, 12); put(Items.COOKED_CHICKEN, 10);
		put(Items.BAKED_POTATO, 7); put(Items.APPLE, 5); put(Items.GOLDEN_APPLE, 200); put(Items.ENCHANTED_GOLDEN_APPLE, 2500);
		put(Items.CARROT, 4); put(Items.POTATO, 3); put(Items.WHEAT, 3); put(Items.BEETROOT, 3);
		put(Items.PUMPKIN_PIE, 15); put(Items.COOKIE, 4); put(Items.MUSHROOM_STEW, 12); put(Items.BEETROOT_SOUP, 12);
		put(Items.BEEF, 8); put(Items.PORKCHOP, 8); put(Items.CHICKEN, 6); put(Items.MUTTON, 7); put(Items.RABBIT, 8);
		put(Items.COOKED_MUTTON, 11); put(Items.COOKED_RABBIT, 12); put(Items.RABBIT_STEW, 14);
		put(Items.MELON_SLICE, 2); put(Items.SWEET_BERRIES, 2);
		put(Items.GLOW_BERRIES, 4); put(Items.SUGAR, 2); put(Items.HONEY_BOTTLE, 20); put(Items.DRIED_KELP, 2);
		put(Items.COD, 6); put(Items.SALMON, 7); put(Items.COOKED_COD, 9); put(Items.COOKED_SALMON, 10);
		put(Items.TROPICAL_FISH, 15); put(Items.PUFFERFISH, 10); put(Items.CAKE, 45); put(Items.GOLDEN_CARROT, 60);
		put(Items.CHORUS_FRUIT, 8); put(Items.POISONOUS_POTATO, 1); put(Items.ROTTEN_FLESH, 1);

		// фермерство/материалы для крафта
		put(Items.WHEAT_SEEDS, 1); put(Items.BEETROOT_SEEDS, 2); put(Items.MELON_SEEDS, 3); put(Items.PUMPKIN_SEEDS, 3);
		put(Items.KELP, 1); put(Items.SUGAR_CANE, 2); put(Items.BAMBOO, 1); put(Items.CACTUS, 2);
		put(Items.VINE, 2); put(Items.LILY_PAD, 4); put(Items.COCOA_BEANS, 3); put(Items.NETHER_WART, 6);
		put(Items.BROWN_MUSHROOM, 4); put(Items.RED_MUSHROOM, 4); put(Items.BONE_MEAL, 2); put(Items.BONE, 3);
		put(Items.STICK, 1); put(Items.BOWL, 2); put(Items.MILK_BUCKET, 9); put(Items.EGG, 3);
		put(Items.BROWN_EGG, 4); put(Items.BLUE_EGG, 4);

		// дроп мобов
		put(Items.ENDER_PEARL, 45); put(Items.BLAZE_ROD, 60); put(Items.BLAZE_POWDER, 30); put(Items.GUNPOWDER, 8);
		put(Items.STRING, 4); put(Items.SLIME_BALL, 15); put(Items.ARROW, 2); put(Items.FEATHER, 3);
		put(Items.LEATHER, 6); put(Items.RABBIT_HIDE, 5); put(Items.SPIDER_EYE, 5);
		put(Items.FERMENTED_SPIDER_EYE, 25); put(Items.GHAST_TEAR, 60);
		put(Items.MAGMA_CREAM, 12); put(Items.PHANTOM_MEMBRANE, 35); put(Items.TOTEM_OF_UNDYING, 800);
		put(Items.SHULKER_SHELL, 250); put(Items.TRIDENT, 700); put(Items.NAUTILUS_SHELL, 80);
		put(Items.HEART_OF_THE_SEA, 300); put(Items.ECHO_SHARD, 120); put(Items.DRAGON_BREATH, 200);
		put(Items.EXPERIENCE_BOTTLE, 20); put(Items.ELYTRA, 5000); put(Items.NETHER_STAR, 1500);
		put(Items.BREEZE_ROD, 90); put(Items.HEAVY_CORE, 600); put(Items.WITHER_SKELETON_SKULL, 400);

		// редстоун и механизмы
		put(Items.REDSTONE_TORCH, 8); put(Items.REPEATER, 25); put(Items.COMPARATOR, 30); put(Items.PISTON, 35);
		put(Items.STICKY_PISTON, 55); put(Items.OBSERVER, 30); put(Items.DISPENSER, 40); put(Items.DROPPER, 20);
		put(Items.HOPPER, 55); put(Items.TARGET, 25); put(Items.DAYLIGHT_DETECTOR, 20); put(Items.NOTE_BLOCK, 8);
		put(Items.RAIL, 4); put(Items.POWERED_RAIL, 20); put(Items.ACTIVATOR_RAIL, 15); put(Items.DETECTOR_RAIL, 10);
		put(Items.REDSTONE_LAMP, 15); put(Items.TRIPWIRE_HOOK, 6); put(Items.LEVER, 3); put(Items.STONE_BUTTON, 2);
		put(Items.OAK_PRESSURE_PLATE, 4); put(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 12); put(Items.TNT, 45);

		// инструменты и снаряжение
		put(Items.BUCKET, 15); put(Items.WATER_BUCKET, 18); put(Items.LAVA_BUCKET, 25); put(Items.SHEARS, 20);
		put(Items.SHIELD, 25); put(Items.BOW, 25); put(Items.CROSSBOW, 35); put(Items.FLINT_AND_STEEL, 10);
		put(Items.FISHING_ROD, 15); put(Items.IRON_SWORD, 25); put(Items.IRON_PICKAXE, 35); put(Items.IRON_AXE, 35);
		put(Items.IRON_SHOVEL, 15); put(Items.IRON_HOE, 20); put(Items.STONE_SWORD, 8); put(Items.STONE_PICKAXE, 10);
		put(Items.DIAMOND_SWORD, 250); put(Items.DIAMOND_PICKAXE, 380); put(Items.DIAMOND_AXE, 380);
		put(Items.GOLDEN_SWORD, 80); put(Items.GOLDEN_PICKAXE, 80); put(Items.NETHERITE_SWORD, 1100);
		put(Items.IRON_HELMET, 55); put(Items.IRON_CHESTPLATE, 85); put(Items.IRON_LEGGINGS, 75); put(Items.IRON_BOOTS, 45);
		put(Items.DIAMOND_HELMET, 620); put(Items.DIAMOND_CHESTPLATE, 970); put(Items.DIAMOND_LEGGINGS, 850);
		put(Items.DIAMOND_BOOTS, 500); put(Items.MACE, 900);

		// книги, жители, декор
		put(Items.BOOK, 20); put(Items.PAPER, 5); put(Items.BOOKSHELF, 35); put(Items.CHISELED_BOOKSHELF, 25);
		put(Items.NAME_TAG, 60); put(Items.SADDLE, 80); put(Items.LEAD, 15); put(Items.COMPASS, 25);
		put(Items.CLOCK, 25); put(Items.MAP, 30); put(Items.WRITABLE_BOOK, 30); put(Items.ITEM_FRAME, 10);
		put(Items.GLOW_ITEM_FRAME, 20); put(Items.PAINTING, 15); put(Items.ARMOR_STAND, 12); put(Items.FLOWER_POT, 6);
		put(Items.TORCH, 2); put(Items.LANTERN, 12); put(Items.SOUL_LANTERN, 15); put(Items.CANDLE, 8);
		put(Items.CRAFTING_TABLE, 6); put(Items.FURNACE, 10); put(Items.BLAST_FURNACE, 30); put(Items.SMOKER, 25);
		put(Items.CHEST, 8); put(Items.BARREL, 10); put(Items.TRAPPED_CHEST, 15); put(Items.ANVIL, 90);
		put(Items.CHIPPED_ANVIL, 45); put(Items.ENCHANTING_TABLE, 500); put(Items.BREWING_STAND, 60); put(Items.CAULDRON, 45);
		put(Items.GRINDSTONE, 15); put(Items.LOOM, 12); put(Items.CARTOGRAPHY_TABLE, 15); put(Items.SMITHING_TABLE, 20);
		put(Items.STONECUTTER, 15); put(Items.SCAFFOLDING, 4); put(Items.LADDER, 4); put(Items.BELL, 60);
		put(Items.OAK_SIGN, 6); put(Items.BEEHIVE, 40); put(Items.CAMPFIRE, 12); put(Items.SOUL_CAMPFIRE, 18);

		// транспорт и другое
		put(Items.OAK_BOAT, 15); put(Items.MINECART, 35); put(Items.WHITE_BED, 40);
		put(Items.FIREWORK_ROCKET, 10); put(Items.ENDER_CHEST, 150); put(Items.ENDER_EYE, 80);
		put(Items.BEACON, 2500); put(Items.CONDUIT, 1500); put(Items.END_CRYSTAL, 300); put(Items.RESPAWN_ANCHOR, 400);
		put(Items.RECOVERY_COMPASS, 250); put(Items.SPYGLASS, 25); put(Items.BRUSH, 15); put(Items.BUNDLE, 30);
		put(Items.WIND_CHARGE, 10); put(Items.FIRE_CHARGE, 10); put(Items.SNOWBALL, 1);
		put(Items.HONEYCOMB, 15); put(Items.OAK_SAPLING, 2);

		// Наши предметы/блоки (явный белый список)
		TABLE.put("craftnet:tower_core", 500);
		TABLE.put("craftnet:food_box", 0); // не продаётся
	}

	private static void put(Item item, int price) {
		TABLE.put(Registries.ITEM.getId(item).toString(), price);
	}

	/** Торгуется ли вообще: только ваниль + явно разрешённые наши. */
	private static boolean allowedNamespace(String itemId) {
		return itemId.startsWith("minecraft:") || itemId.equals("craftnet:tower_core");
	}

	/** Цена покупки в CR за 1 шт. 0 = нельзя купить/продать. */
	public static int buyPrice(String itemId) {
		if (!allowedNamespace(itemId)) return 0;
		Integer t = TABLE.get(itemId);
		if (t != null) return t;
		Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
		if (item == null) return 0;
		return heuristic(itemId, item);
	}

	private static int heuristic(String itemId, Item item) {
		int rarityBase = switch (item.getDefaultStack().getRarity()) {
			case UNCOMMON -> 25;
			case RARE -> 120;
			case EPIC -> 400;
			default -> 4;
		};
		int stackFactor = Math.max(1, (int) Math.round(64.0 / Math.max(1, item.getDefaultStack().getMaxCount()) * 4));
		int jitter = Math.abs(itemId.hashCode()) % 5;
		return Math.max(1, rarityBase + stackFactor + jitter);
	}

	/** Цена продажи в CR за 1 шт. */
	public static int sellPrice(String itemId) {
		int b = buyPrice(itemId);
		if (b <= 0) return 0;
		return Math.max(1, (int) Math.round(b * SELL_RATIO));
	}

	public static boolean tradeable(String itemId) {
		return buyPrice(itemId) > 0;
	}
}
