package net.craftnet.block;

import java.util.function.Function;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;

import net.craftnet.CraftNet;
import net.craftnet.item.JobCargoBlockItem;
import net.craftnet.item.ModItems;

public final class ModBlocks {
	private ModBlocks() {}

	/** Стойка пункта выдачи заказов. */
	public static final Block PVZ_COUNTER = register("pvz_counter",
			s -> new StationBlock(s, StationBlock.Kind.PVZ),
			AbstractBlock.Settings.create().mapColor(MapColor.ORANGE).strength(2.5f).sounds(BlockSoundGroup.WOOD));

	/** Банковский терминал. */
	public static final Block BANK_TERMINAL = register("bank_terminal",
			s -> new StationBlock(s, StationBlock.Kind.BANK),
			AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(3.0f).sounds(BlockSoundGroup.METAL));

	/** Сборочный станок завода. */
	public static final Block FACTORY_STATION = register("factory_station",
			s -> new StationBlock(s, StationBlock.Kind.FACTORY),
			AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(4.0f).sounds(BlockSoundGroup.METAL));

	/** Стойка кафе. */
	public static final Block CAFE_COUNTER = register("cafe_counter",
			s -> new StationBlock(s, StationBlock.Kind.CAFE),
			AbstractBlock.Settings.create().mapColor(MapColor.BROWN).strength(2.0f).sounds(BlockSoundGroup.WOOD));

	/** Ядро вышки связи: сломал — деревня офлайн. */
	public static final Block TOWER_CORE = register("tower_core",
			TowerCoreBlock::new,
			AbstractBlock.Settings.create().mapColor(MapColor.RED)
					.strength(1.5f).sounds(BlockSoundGroup.GLASS)
					.luminance(state -> 12).nonOpaque());

	/** Тяжёлый грузовой ящик (работа грузчиком). */
	public static final Block CARGO_CRATE = register("cargo_crate",
			Block::new,
			AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(1.0f).sounds(BlockSoundGroup.WOOD));

	private static Block register(String name, Function<AbstractBlock.Settings, Block> factory, AbstractBlock.Settings settings) {
		RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, CraftNet.id(name));
		Block block = Registry.register(Registries.BLOCK, key, factory.apply(settings.registryKey(key)));
		// BlockItem с тем же именем. Рабочий cargo_crate запрещает placement,
		// иначе item component job_tag исчезает в block state и отмывается loot table.
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, CraftNet.id(name));
		if ("cargo_crate".equals(name)) {
			ModItems.registerRaw(itemKey, s -> new JobCargoBlockItem(block, s), new Item.Settings());
		} else {
			ModItems.registerRaw(itemKey, s -> new BlockItem(block, s), new Item.Settings());
		}
		return block;
	}

	public static void register() {
		// статическая инициализация
	}
}
