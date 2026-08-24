package net.craftnet.state;

import net.minecraft.world.PersistentStateType;

/** Заказы магазина: входящие посылки и отложенные выплаты. */
public class OrdersState extends NbtState {

	public static final PersistentStateType<OrdersState> TYPE =
			type("craftnet_orders", OrdersState::new, OrdersState::new, s -> s.data());

	public OrdersState() {
		super();
	}

	public OrdersState(net.minecraft.nbt.NbtCompound data) {
		super(data);
	}
}
