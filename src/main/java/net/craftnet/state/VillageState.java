package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Обнаруженные деревни: центр, имя, вышка, статус сети. */
public class VillageState extends NbtState {

	public static final PersistentStateType<VillageState> TYPE =
			type("craftnet_villages", VillageState::new, VillageState::new, s -> s.data());

	public VillageState() {
		super();
	}

	public VillageState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
