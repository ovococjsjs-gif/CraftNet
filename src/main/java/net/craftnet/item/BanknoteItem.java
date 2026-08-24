package net.craftnet.item;

import java.util.function.Consumer;

import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.craftnet.component.ModComponents;

public class BanknoteItem extends Item {
	public BanknoteItem(Settings settings) {
		super(settings);
	}

	public static int valueOf(ItemStack stack) {
		Integer v = stack.get(ModComponents.BANKNOTE_VALUE);
		return v == null ? 1 : v;
	}

	public static ItemStack ofValue(int value) {
		ItemStack s = new ItemStack(net.craftnet.item.ModItems.BANKNOTE);
		s.set(ModComponents.BANKNOTE_VALUE, value);
		return s;
	}

	@Override
	public Text getName(ItemStack stack) {
		int v = valueOf(stack);
		return Text.translatable("item.craftnet.banknote.v", v);
	}

	@Override
	public void appendTooltip(ItemStack stack, Item.TooltipContext context, TooltipDisplayComponent displayComponent,
			Consumer<Text> textConsumer, TooltipType type) {
		super.appendTooltip(stack, context, displayComponent, textConsumer, type);
		textConsumer.accept(Text.translatable("item.craftnet.banknote.tip", valueOf(stack)).formatted(Formatting.GRAY));
	}
}
