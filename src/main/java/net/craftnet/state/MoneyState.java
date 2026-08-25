package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Балансы, флаги первой выдачи телефона и лог транзакций. */
public class MoneyState extends NbtState {

	public static final PersistentStateType<MoneyState> TYPE =
			type("craftnet_money", MoneyState::new, MoneyState::new, s -> s.data());

	public MoneyState() {
		super();
	}

	public MoneyState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
