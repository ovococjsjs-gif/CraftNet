package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Казино-апгрейдер: ставки игроков и результаты последних спинов. */
public class CasinoState extends NbtState {

	public static final PersistentStateType<CasinoState> TYPE =
			type("craftnet_casino", CasinoState::new, CasinoState::new, s -> s.data());

	public CasinoState() {
		super();
	}

	public CasinoState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
