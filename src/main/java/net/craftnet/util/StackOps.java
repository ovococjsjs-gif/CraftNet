package net.craftnet.util;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Exact ItemStack round-trips for every user-owned item that crosses a CraftNet
 * boundary. Registry id + count is not enough: damage, enchantments, names,
 * container contents and arbitrary data components must survive unchanged.
 */
public final class StackOps {
	private StackOps() {}

	private static RegistryOps<NbtElement> ops(MinecraftServer server) {
		return RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
	}

	public static NbtCompound encode(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return new NbtCompound();
		return (NbtCompound) ItemStack.CODEC.encodeStart(ops(server), stack).result()
				.orElseGet(NbtCompound::new);
	}

	public static ItemStack decode(MinecraftServer server, NbtCompound nbt) {
		if (nbt == null || nbt.isEmpty()) return ItemStack.EMPTY;
		return ItemStack.CODEC.parse(ops(server), nbt).result().orElse(ItemStack.EMPTY);
	}

	/** Identity sent to the client: all components, normalized to count=1. */
	public static NbtCompound identity(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return new NbtCompound();
		ItemStack one = stack.copy();
		one.setCount(1);
		return encode(server, one);
	}

	/** Exact item+components comparison without relying on a version-specific helper. */
	public static boolean sameIdentity(MinecraftServer server, ItemStack stack, NbtCompound expected) {
		if (stack == null || stack.isEmpty() || expected == null || expected.isEmpty()) return false;
		return identity(server, stack).equals(expected);
	}

	/**
	 * Remove an exact stack from a concrete inventory slot. The identity from the
	 * last server snapshot prevents an inventory update from silently selecting a
	 * different enchanted/damaged/container stack of the same item.
	 */
	public static ItemStack takeExact(ServerPlayerEntity player, MinecraftServer server,
			int slot, NbtCompound expected, int count) {
		if (count <= 0 || slot < 0 || slot >= player.getInventory().size()) return ItemStack.EMPTY;
		ItemStack current = player.getInventory().getStack(slot);
		if (current.isEmpty() || current.getCount() < count || !sameIdentity(server, current, expected)) {
			return ItemStack.EMPTY;
		}
		return current.split(count);
	}

	/** True only when a stack has exactly the item's default components. */
	public static boolean isPlain(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		ItemStack def = stack.getItem().getDefaultStack();
		return identity(server, stack).equals(identity(server, def));
	}

	/** Offer any legal count as max-sized stacks, preserving all components. */
	public static void offerOrDropSplit(ServerPlayerEntity player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		while (!stack.isEmpty()) {
			int n = Math.min(stack.getMaxCount(), stack.getCount());
			player.getInventory().offerOrDrop(stack.split(Math.max(1, n)));
		}
	}
}
