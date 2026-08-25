package net.craftnet.econ;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import net.craftnet.config.CraftNetConfig;

/**
 * Прайс-лист сервера (модель описана в ECONOMY.md).
 *
 * <p>Принципы, по которым построена таблица:
 * <ol>
 * <li><b>Честная трудоёмкость</b>: цена = редкость × усилие добычи. Сжатый блок
 *     = ровно 9× исходника (если декрафт обратим), наггет = floor(слиток/9),
 *     руда = содержимое × ~1.2 + премия глубины (deepslate &gt; камень).</li>
 * <li><b>Найденное дешевле материалов</b>: снаряжение класса «лут» (броня,
 *     инструменты, лук/щит) = ~0.72× стоимости материалов — продать 4 золотых
 *     слитка заведомо выгоднее, чем золотые ботинки. Плюс это убивает
 *     арбитраж «купить материалы → скрафтить → продать».</li>
 * <li><b>Фармилки обесценены</b>: возобновимое (железо с фермы, культура,
 *     дроп спаунеров) — в нижней половине диапазона своей редкости.</li>
 * <li><b>Эндгейм-стены</b>: элитра 8000, звезда незера 2000, маяк 2800 —
 *     ~5 часов фарма каждая при доходе ~1700 CR/ч.</li>
 * </ol>
 *
 * <p>Продажа игрок→сервер — ступенчатая (конфиг): ≤9 CR ×0.55, 10–499 ×0.68,
 * ≥500 CR ×0.80, округление ВНИЗ (floor) — поэтому продажа хлама (buy ≤ 2 CR)
 * даёт 0 и предмет не принимается вовсе: иначе «2 доски → 4 палки → продать»
 * была бы бесконечной петлёй денег.
 *
 * <p>Админ может переопределить любую цену в {@code config/craftnet_prices.json}
 * (см. {@link CraftNetPrices}); 0 = вывести предмет из торговли.
 */
public final class PriceManager {
	private PriceManager() {}

	/** Потолки ступеней продажи (buy): ≤ TIER_CHEAP_MAX — хлам, ≥ TIER_EXP_MIN — эндгейм. */
	private static final int TIER_CHEAP_MAX = 9;
	private static final int TIER_EXP_MIN = 500;

	private static final Map<String, Integer> TABLE = new HashMap<>();

	static {
		// ---- руды и ресурсы (сырьё; фармилки обесценены) ----
		put(Items.COAL, 4); put(Items.CHARCOAL, 4);
		put(Items.RAW_IRON, 8); put(Items.IRON_INGOT, 10);       // железо фармится — дёшево
		put(Items.RAW_GOLD, 19); put(Items.GOLD_INGOT, 25);
		put(Items.RAW_COPPER, 4); put(Items.COPPER_INGOT, 5);
		put(Items.DIAMOND, 140);                                 // невозобновляемый — премия
		put(Items.EMERALD, 70);                                  // якорь жительских торгов
		put(Items.REDSTONE, 6); put(Items.LAPIS_LAZULI, 6); put(Items.QUARTZ, 8);
		put(Items.AMETHYST_SHARD, 24);
		put(Items.ANCIENT_DEBRIS, 800); put(Items.NETHERITE_SCRAP, 240);
		put(Items.NETHERITE_INGOT, 1000);                        // 4 scraps + 4 gold + труд ≈ 1060 → 1000
		put(Items.IRON_NUGGET, 1); put(Items.GOLD_NUGGET, 3);    // 9 × nugget ≤ слиток — без арбитража
		put(Items.FLINT, 3); put(Items.CLAY_BALL, 3);

		// ---- блоки руд: содержимое ×1.15–1.25, deepslate дороже (глубже/медленнее) ----
		put(Items.COAL_ORE, 6); put(Items.DEEPSLATE_COAL_ORE, 8);
		put(Items.IRON_ORE, 11); put(Items.DEEPSLATE_IRON_ORE, 14);
		put(Items.GOLD_ORE, 26); put(Items.DEEPSLATE_GOLD_ORE, 32);
		put(Items.COPPER_ORE, 6); put(Items.DEEPSLATE_COPPER_ORE, 8);
		put(Items.REDSTONE_ORE, 24); put(Items.DEEPSLATE_REDSTONE_ORE, 30);
		put(Items.LAPIS_ORE, 30); put(Items.DEEPSLATE_LAPIS_ORE, 36);
		put(Items.EMERALD_ORE, 85); put(Items.DEEPSLATE_EMERALD_ORE, 100);
		put(Items.DIAMOND_ORE, 170); put(Items.DEEPSLATE_DIAMOND_ORE, 200);
		put(Items.NETHER_GOLD_ORE, 28); put(Items.NETHER_QUARTZ_ORE, 14);

		// ---- сжатые блоки: ровно 9× (декрафт обратим); необратимые — по урожаю ----
		put(Items.COAL_BLOCK, 36); put(Items.IRON_BLOCK, 90); put(Items.GOLD_BLOCK, 225);
		put(Items.COPPER_BLOCK, 45); put(Items.REDSTONE_BLOCK, 54); put(Items.LAPIS_BLOCK, 54);
		put(Items.EMERALD_BLOCK, 630); put(Items.DIAMOND_BLOCK, 1260); put(Items.NETHERITE_BLOCK, 9000);
		put(Items.QUARTZ_BLOCK, 34); put(Items.AMETHYST_BLOCK, 96);
		put(Items.RAW_IRON_BLOCK, 72); put(Items.RAW_GOLD_BLOCK, 171); put(Items.RAW_COPPER_BLOCK, 36);
		put(Items.HAY_BLOCK, 27); put(Items.SLIME_BLOCK, 135);
		put(Items.HONEY_BLOCK, 72); put(Items.HONEYCOMB_BLOCK, 56);
		put(Items.GLOWSTONE, 20); put(Items.GLOWSTONE_DUST, 5);  // 4 dust = блок
		put(Items.DRIED_KELP_BLOCK, 18);                         // 9 kelp×2 (обратимо)
		put(Items.BONE_BLOCK, 20);                               // 9 костной муки(2) + 2
		put(Items.NETHER_WART_BLOCK, 22);                        // необратимо — декор
		put(Items.MELON, 12); put(Items.PUMPKIN, 6);
		put(Items.CARVED_PUMPKIN, 8); put(Items.JACK_O_LANTERN, 14);

		// ---- стройматериалы ----
		put(Items.OAK_LOG, 2); put(Items.SPRUCE_LOG, 2); put(Items.BIRCH_LOG, 2); put(Items.JUNGLE_LOG, 2);
		put(Items.ACACIA_LOG, 2); put(Items.DARK_OAK_LOG, 2); put(Items.MANGROVE_LOG, 2);
		put(Items.CHERRY_LOG, 3); put(Items.PALE_OAK_LOG, 3);
		put(Items.CRIMSON_STEM, 4); put(Items.WARPED_STEM, 4);
		put(Items.OAK_PLANKS, 1); put(Items.SPRUCE_PLANKS, 1); put(Items.BIRCH_PLANKS, 1);
		put(Items.COBBLESTONE, 1); put(Items.COBBLED_DEEPSLATE, 1); put(Items.STONE, 2);
		put(Items.STONE_BRICKS, 3);
		put(Items.SAND, 1); put(Items.RED_SAND, 1); put(Items.GLASS, 3);
		put(Items.BRICK, 5); put(Items.BRICKS, 20);              // clay 3 → печь → 4×5
		put(Items.NETHER_BRICK, 3); put(Items.NETHER_BRICKS, 12); // 4×3
		put(Items.TERRACOTTA, 2); put(Items.CLAY, 12);           // блок = 4 комка
		put(Items.DEEPSLATE, 1); put(Items.BLACKSTONE, 2); put(Items.BASALT, 1);
		put(Items.ANDESITE, 1); put(Items.DIORITE, 1); put(Items.GRANITE, 1); put(Items.TUFF, 1);
		put(Items.CALCITE, 2); put(Items.DRIPSTONE_BLOCK, 2); put(Items.MOSS_BLOCK, 2);
		put(Items.DIRT, 1); put(Items.GRASS_BLOCK, 2); put(Items.MUD, 1);
		put(Items.OBSIDIAN, 32); put(Items.SOUL_SAND, 5); put(Items.SOUL_SOIL, 4);
		put(Items.END_STONE, 4); put(Items.PURPUR_BLOCK, 8);
		put(Items.PRISMARINE_SHARD, 14); put(Items.PRISMARINE_CRYSTALS, 22);
		put(Items.SEA_LANTERN, 85);
		put(Items.ICE, 3); put(Items.PACKED_ICE, 28); put(Items.BLUE_ICE, 255); // 9× цепочка
		put(Items.SNOW_BLOCK, 1);
		put(Items.WHITE_WOOL, 6); put(Items.WHITE_CONCRETE, 3); put(Items.WHITE_TERRACOTTA, 2);

		// ---- еда: по сытости; приготовленное > сырое; топ-еда — премия ----
		put(Items.WHEAT, 3); put(Items.CARROT, 4); put(Items.POTATO, 3); put(Items.BEETROOT, 2);
		put(Items.APPLE, 5); put(Items.MELON_SLICE, 2); put(Items.SWEET_BERRIES, 2);
		put(Items.GLOW_BERRIES, 4); put(Items.SUGAR, 2); put(Items.DRIED_KELP, 2);
		put(Items.BREAD, 7); put(Items.BAKED_POTATO, 7); put(Items.COOKIE, 2);
		put(Items.BEEF, 9); put(Items.PORKCHOP, 9); put(Items.CHICKEN, 7);
		put(Items.MUTTON, 8); put(Items.RABBIT, 9);
		put(Items.COOKED_BEEF, 15); put(Items.COOKED_PORKCHOP, 15); put(Items.COOKED_CHICKEN, 12);
		put(Items.COOKED_MUTTON, 13); put(Items.COOKED_RABBIT, 13);
		put(Items.COD, 6); put(Items.SALMON, 7); put(Items.COOKED_COD, 9); put(Items.COOKED_SALMON, 11);
		put(Items.MUSHROOM_STEW, 11); put(Items.BEETROOT_SOUP, 12); put(Items.RABBIT_STEW, 16);
		put(Items.PUMPKIN_PIE, 14); put(Items.CAKE, 45);
		put(Items.GOLDEN_CARROT, 55);                            // топ-сытость в ваниле
		put(Items.GOLDEN_APPLE, 200); put(Items.ENCHANTED_GOLDEN_APPLE, 3000);
		put(Items.HONEY_BOTTLE, 18); put(Items.TROPICAL_FISH, 14); put(Items.PUFFERFISH, 12);
		put(Items.CHORUS_FRUIT, 6); put(Items.POISONOUS_POTATO, 1); put(Items.ROTTEN_FLESH, 1);

		// ---- фермерство ----
		put(Items.WHEAT_SEEDS, 1); put(Items.BEETROOT_SEEDS, 2);
		put(Items.MELON_SEEDS, 3); put(Items.PUMPKIN_SEEDS, 3);
		put(Items.KELP, 1); put(Items.SUGAR_CANE, 2); put(Items.BAMBOO, 1); put(Items.CACTUS, 2);
		put(Items.VINE, 2); put(Items.LILY_PAD, 4); put(Items.COCOA_BEANS, 3);
		put(Items.NETHER_WART, 6); put(Items.BROWN_MUSHROOM, 4); put(Items.RED_MUSHROOM, 4);
		put(Items.BONE_MEAL, 2); put(Items.BONE, 4); put(Items.STICK, 1); put(Items.BOWL, 2);
		put(Items.MILK_BUCKET, 12); put(Items.EGG, 4); put(Items.BROWN_EGG, 5); put(Items.BLUE_EGG, 5);

		// ---- дроп мобов: фармилки ниже, боссовый дроп выше ----
		put(Items.STRING, 6); put(Items.GUNPOWDER, 9); put(Items.ARROW, 2); put(Items.FEATHER, 3);
		put(Items.LEATHER, 6); put(Items.RABBIT_HIDE, 5); put(Items.SPIDER_EYE, 5);
		put(Items.FERMENTED_SPIDER_EYE, 14);                     // eye 5 + sugar + гриб — крафтовое, не 25
		put(Items.SLIME_BALL, 15); put(Items.MAGMA_CREAM, 14);
		put(Items.ENDER_PEARL, 45); put(Items.BLAZE_ROD, 65); put(Items.BLAZE_POWDER, 30);
		put(Items.GHAST_TEAR, 68); put(Items.PHANTOM_MEMBRANE, 38);
		put(Items.TOTEM_OF_UNDYING, 850);                        // рейд с маской — ~полчаса на шт
		put(Items.SHULKER_SHELL, 280); put(Items.SHULKER_BOX, 600); // 2 панциря + сундук + труд
		put(Items.TRIDENT, 750); put(Items.NAUTILUS_SHELL, 85); put(Items.HEART_OF_THE_SEA, 320);
		put(Items.ECHO_SHARD, 140); put(Items.DRAGON_BREATH, 220);
		put(Items.NETHER_STAR, 2000); put(Items.ELYTRA, 8000);   // эндгейм-стены
		put(Items.WITHER_SKELETON_SKULL, 450); put(Items.BREEZE_ROD, 90); put(Items.HEAVY_CORE, 650);
		put(Items.EXPERIENCE_BOTTLE, 20); put(Items.INK_SAC, 3); put(Items.GLOW_INK_SAC, 8);

		// ---- редстоун и механизмы: материалы + труд сборки ----
		put(Items.REDSTONE_TORCH, 7); put(Items.REPEATER, 26); put(Items.COMPARATOR, 48);
		put(Items.PISTON, 26); put(Items.STICKY_PISTON, 40); put(Items.OBSERVER, 26);
		put(Items.DISPENSER, 34); put(Items.DROPPER, 14); put(Items.HOPPER, 58);
		put(Items.TARGET, 48);                                   // hay 27 + 4 redstone — не 25
		put(Items.DAYLIGHT_DETECTOR, 34); put(Items.NOTE_BLOCK, 12);
		put(Items.RAIL, 4); put(Items.POWERED_RAIL, 24);
		put(Items.DETECTOR_RAIL, 12); put(Items.ACTIVATOR_RAIL, 12);
		put(Items.REDSTONE_LAMP, 36);                            // glowstone 20 + 4 dust — не 15
		put(Items.TRIPWIRE_HOOK, 6); put(Items.LEVER, 2); put(Items.STONE_BUTTON, 1);
		put(Items.OAK_PRESSURE_PLATE, 2); put(Items.HEAVY_WEIGHTED_PRESSURE_PLATE, 14);
		put(Items.TNT, 48);                                      // 5 пороха + 4 песка

		// ---- инструменты и снаряжение: ~0.72× материалов (найденное дешевле сырья) ----
		put(Items.STONE_SWORD, 5); put(Items.STONE_PICKAXE, 6);
		put(Items.IRON_SWORD, 15); put(Items.IRON_PICKAXE, 24); put(Items.IRON_AXE, 24);
		put(Items.IRON_SHOVEL, 9); put(Items.IRON_HOE, 16);
		put(Items.IRON_HELMET, 38); put(Items.IRON_CHESTPLATE, 60);
		put(Items.IRON_LEGGINGS, 52); put(Items.IRON_BOOTS, 30);
		put(Items.GOLDEN_SWORD, 38); put(Items.GOLDEN_PICKAXE, 55);
		put(Items.GOLDEN_HELMET, 90); put(Items.GOLDEN_CHESTPLATE, 145);
		put(Items.GOLDEN_LEGGINGS, 126); put(Items.GOLDEN_BOOTS, 72);
		put(Items.DIAMOND_SWORD, 205); put(Items.DIAMOND_PICKAXE, 305); put(Items.DIAMOND_AXE, 305);
		put(Items.DIAMOND_SHOVEL, 100); put(Items.DIAMOND_HOE, 200);
		put(Items.DIAMOND_HELMET, 500); put(Items.DIAMOND_CHESTPLATE, 810);
		put(Items.DIAMOND_LEGGINGS, 705); put(Items.DIAMOND_BOOTS, 400);
		put(Items.NETHERITE_SWORD, 1050); put(Items.NETHERITE_PICKAXE, 1300);
		put(Items.NETHERITE_AXE, 1300); put(Items.NETHERITE_SHOVEL, 1150); put(Items.NETHERITE_HOE, 1150);
		put(Items.NETHERITE_HELMET, 1275); put(Items.NETHERITE_CHESTPLATE, 1500);
		put(Items.NETHERITE_LEGGINGS, 1450); put(Items.NETHERITE_BOOTS, 1200);
		put(Items.MACE, 950); put(Items.TRIDENT, 750);
		put(Items.BUCKET, 24); put(Items.WATER_BUCKET, 26); put(Items.LAVA_BUCKET, 38);
		put(Items.SHEARS, 18); put(Items.SHIELD, 12); put(Items.BOW, 18); put(Items.CROSSBOW, 38);
		put(Items.FLINT_AND_STEEL, 12); put(Items.FISHING_ROD, 12);

		// ---- книги, жители, декор, станции ----
		put(Items.PAPER, 3); put(Items.BOOK, 20);
		put(Items.BOOKSHELF, 60); put(Items.CHISELED_BOOKSHELF, 25);
		put(Items.NAME_TAG, 65); put(Items.SADDLE, 90); put(Items.LEAD, 22);
		put(Items.COMPASS, 30); put(Items.CLOCK, 45); put(Items.MAP, 40); put(Items.WRITABLE_BOOK, 30);
		put(Items.ITEM_FRAME, 12); put(Items.GLOW_ITEM_FRAME, 24); put(Items.PAINTING, 14);
		put(Items.ARMOR_STAND, 10); put(Items.FLOWER_POT, 12);
		put(Items.TORCH, 2); put(Items.LANTERN, 13); put(Items.SOUL_LANTERN, 16); put(Items.CANDLE, 16);
		put(Items.CRAFTING_TABLE, 6); put(Items.FURNACE, 10);
		put(Items.BLAST_FURNACE, 48); put(Items.SMOKER, 24);
		put(Items.CHEST, 8); put(Items.BARREL, 10); put(Items.TRAPPED_CHEST, 14);
		put(Items.ANVIL, 240);                                   // 31 железо = 310, изнашивается → 240
		put(Items.CHIPPED_ANVIL, 100);
		put(Items.ENCHANTING_TABLE, 480); put(Items.BREWING_STAND, 72); put(Items.CAULDRON, 52);
		put(Items.GRINDSTONE, 12); put(Items.LOOM, 13); put(Items.CARTOGRAPHY_TABLE, 14);
		put(Items.SMITHING_TABLE, 22); put(Items.STONECUTTER, 15);
		put(Items.SCAFFOLDING, 4); put(Items.LADDER, 3); put(Items.BELL, 60);
		put(Items.OAK_SIGN, 3); put(Items.BEEHIVE, 45); put(Items.CAMPFIRE, 14); put(Items.SOUL_CAMPFIRE, 16);

		// ---- транспорт и особое ----
		put(Items.OAK_BOAT, 15); put(Items.MINECART, 38); put(Items.WHITE_BED, 40);
		put(Items.FIREWORK_ROCKET, 12); put(Items.WIND_CHARGE, 20); put(Items.FIRE_CHARGE, 12);
		put(Items.ENDER_CHEST, 340);                             // 8 обсидиана + око — не 150
		put(Items.ENDER_EYE, 85);                                // жемчуг + порошок + труд
		put(Items.BEACON, 2800);                                 // звезда 2000 + стекло + обсидиан
		put(Items.CONDUIT, 1100); put(Items.END_CRYSTAL, 260);
		put(Items.RESPAWN_ANCHOR, 480); put(Items.RECOVERY_COMPASS, 880); // 8 эхо-осколков — не 250
		put(Items.SPYGLASS, 30); put(Items.BRUSH, 14); put(Items.BUNDLE, 20);
		put(Items.SNOWBALL, 1); put(Items.HONEYCOMB, 14); put(Items.OAK_SAPLING, 2);

		// ---- наши предметы (явный белый список) ----
		TABLE.put("craftnet:tower_core", 500);
		TABLE.put("craftnet:food_box", 0); // не продаётся
	}

	private static void put(Item item, int price) {
		TABLE.put(Registries.ITEM.getId(item).toString(), price);
	}

	/** Неизменяемая карта дефолтных цен (для записи prices-файла и доков). */
	public static Map<String, Integer> defaults() {
		return java.util.Collections.unmodifiableMap(TABLE);
	}

	/** Торгуется ли вообще: только ваниль + явно разрешённые наши. */
	private static boolean allowedNamespace(String itemId) {
		return itemId.startsWith("minecraft:") || itemId.equals("craftnet:tower_core");
	}

	/** Цена покупки в CR за 1 шт. 0 = нельзя купить/продать. */
	public static int buyPrice(String itemId) {
		if (!allowedNamespace(itemId)) return 0;
		Integer o = CraftNetPrices.override(itemId);
		if (o != null) return o;
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

	/**
	 * Цена продажи (игрок → сервер) за 1 шт: ступенчатая доля от buy, floor.
	 * 0 = предмет не принимается (хлам buy ≤ 2 CR при дефолтной кривой) —
	 * защита от петли «купить материал → скрафтить → продать с прибылью».
	 */
	public static int sellPrice(String itemId) {
		int b = buyPrice(itemId);
		if (b <= 0) return 0;
		CraftNetConfig cfg = CraftNetConfig.get();
		double ratio = b <= TIER_CHEAP_MAX ? cfg.sellRatioCheap
				: b >= TIER_EXP_MIN ? cfg.sellRatioExpensive : cfg.sellRatioMid;
		return Math.max(0, (int) Math.floor(b * ratio));
	}

	public static boolean tradeable(String itemId) {
		return buyPrice(itemId) > 0;
	}
}
