package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Активные рабочие задания игроков. */
public class JobsState extends NbtState {

	public static final PersistentStateType<JobsState> TYPE =
			type("craftnet_jobs", JobsState::new, JobsState::new, s -> s.data());

	public JobsState() {
		super();
	}

	public JobsState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
