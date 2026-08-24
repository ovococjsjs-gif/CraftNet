package net.craftnet.state;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

/**
 * Удобный базовый персистентный стейт: всё хранится в одном NbtCompound,
 * кодек просто оборачивает ссылку на него. Менеджеры мутируют compound
 * и зовут markDirty().
 */
public abstract class NbtState extends PersistentState {
	protected final NbtCompound data;

	protected NbtState() {
		this.data = new NbtCompound();
	}

	protected NbtState(NbtCompound data) {
		this.data = data;
	}

	public NbtCompound data() {
		return data;
	}

	protected static <T extends NbtState> PersistentStateType<T> type(String id,
			java.util.function.Supplier<T> ctor,
			java.util.function.Function<NbtCompound, T> fromNbt,
			java.util.function.Function<T, NbtCompound> toNbt) {
		return new PersistentStateType<>(id, ctor, net.minecraft.nbt.NbtCompound.CODEC.xmap(
				fromNbt::apply, toNbt::apply), DataFixTypes.SAVED_DATA_SCOREBOARD);
	}
}
