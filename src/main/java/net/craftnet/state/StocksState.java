package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Биржа: цены, истории, портфели игроков. */
public class StocksState extends NbtState {

	public static final PersistentStateType<StocksState> TYPE =
			type("craftnet_stocks", StocksState::new, StocksState::new, s -> s.data());

	public StocksState() {
		super();
	}

	public StocksState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
