package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Профили игроков: счётчики статистики, опыт, разблокированные достижения. */
public class StatsState extends NbtState {

	public static final PersistentStateType<StatsState> TYPE =
			type("craftnet_stats", StatsState::new, StatsState::new, s -> s.data());

	public StatsState() {
		super();
	}

	public StatsState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
