package net.craftnet.item;

import java.util.function.Function;

import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;

import net.craftnet.CraftNet;

public final class ModItems {
	private ModItems() {}

	/** Смартфон — сердце мода. */
	public static final Item PHONE = registerRaw(itemKey("phone"), PhoneItem::new,
			new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON));

	/** Банкнота с номиналом (компонент banknote_value). Текстуру пришлёт заказчик. */
	public static final Item BANKNOTE = registerRaw(itemKey("banknote"), BanknoteItem::new,
			new Item.Settings().maxCount(64));

	/** Пакет еды для курьерки. */
	public static final Item FOOD_BOX = registerRaw(itemKey("food_box"), Item::new,
			new Item.Settings().maxCount(16));

	/** Творческая вкладка мода. */
	public static final ItemGroup CRAFTNET_GROUP = Registry.register(Registries.ITEM_GROUP, net.craftnet.CraftNet.id("craftnet"),
			FabricItemGroup.builder()
					.icon(() -> new ItemStack(PHONE))
					.displayName(Text.translatable("itemGroup.craftnet.tab"))
					.entries((displayContext, entries) -> {
						entries.add(PHONE);
						entries.add(BANKNOTE);
						entries.add(FOOD_BOX);
						entries.add(net.craftnet.block.ModBlocks.PVZ_COUNTER);
						entries.add(net.craftnet.block.ModBlocks.BANK_TERMINAL);
						entries.add(net.craftnet.block.ModBlocks.FACTORY_STATION);
						entries.add(net.craftnet.block.ModBlocks.CAFE_COUNTER);
						entries.add(net.craftnet.block.ModBlocks.TOWER_CORE);
						entries.add(net.craftnet.block.ModBlocks.CARGO_CRATE);
					})
					.build());

	public static RegistryKey<Item> itemKey(String name) {
		return RegistryKey.of(RegistryKeys.ITEM, CraftNet.id(name));
	}

	public static Item registerRaw(RegistryKey<Item> key, Function<Item.Settings, Item> factory, Item.Settings settings) {
		return Registry.register(Registries.ITEM, key, factory.apply(settings.registryKey(key)));
	}

	public static void register() {
		// статическая инициализация
	}
}
