package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Барахолка: активные лоты игроков. */
public class MarketState extends NbtState {

	public static final PersistentStateType<MarketState> TYPE =
			type("craftnet_market", MarketState::new, MarketState::new, s -> s.data());

	public MarketState() {
		super();
	}

	public MarketState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
