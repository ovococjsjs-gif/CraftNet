package net.craftnet.orders;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import net.craftnet.econ.MoneyManager;
import net.craftnet.state.OrdersState;
import net.craftnet.util.Nbt2;

/**
 * Заказы онлайн-магазина:
 *  - DELIVERY: купленный предмет едет в ПВЗ ближайшей деревни;
 *  - PAYOUT:  деньги за проданный в ПВЗ товар приходят с задержкой.
 */
public final class OrderManager {
	private OrderManager() {}

	public static final int KIND_DELIVERY = 0;
	public static final int KIND_PAYOUT = 1;

	public static final int BASE_TRAVEL_TICKS = 3600; // 3 игровых минуты

	public static OrdersState state(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(OrdersState.TYPE);
	}

	private static long nextId(OrdersState st) {
		long id = Nbt2.lng(st.data(), "nextId") + 1;
		st.data().putLong("nextId", id);
		return id;
	}

	private static RegistryOps<NbtElement> ops(MinecraftServer server) {
		return RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
	}

	private static NbtCompound encodeStack(MinecraftServer server, ItemStack stack) {
		return (NbtCompound) ItemStack.CODEC.encodeStart(ops(server), stack).result()
				.orElseGet(NbtCompound::new);
	}

	private static ItemStack decodeStack(MinecraftServer server, NbtCompound nbt) {
		return ItemStack.CODEC.parse(ops(server), nbt).result().orElse(ItemStack.EMPTY);
	}

	/** Создать заказ на доставку в ПВЗ. @return id заказа. */
	public static long newDelivery(MinecraftServer server, UUID owner, ItemStack stack,
			int villageX, int villageY, int villageZ, String villageName, long readyTick) {
		OrdersState st = state(server);
		NbtCompound o = new NbtCompound();
		o.putLong("id", nextId(st));
		o.putString("owner", owner.toString());
		o.putInt("kind", KIND_DELIVERY);
		o.put("item", encodeStack(server, stack));
		o.putInt("vx", villageX);
		o.putInt("vy", villageY);
		o.putInt("vz", villageZ);
		o.putString("vname", villageName);
		o.putLong("ready", readyTick);
		o.putLong("start", server.getOverworld().getTime());
		NbtList list = st.data().getListOrEmpty("orders");
		list = (NbtList) list.copy();
		list.add(o);
		st.data().put("orders", list);
		st.markDirty();
		return o.getLong("id", 0L);
	}

	/** Создать отложенную выплату за продажу товара. */
	public static long newPayout(MinecraftServer server, UUID owner, long amount, String comment, long readyTick) {
		OrdersState st = state(server);
		NbtCompound o = new NbtCompound();
		o.putLong("id", nextId(st));
		o.putString("owner", owner.toString());
		o.putInt("kind", KIND_PAYOUT);
		o.putLong("payout", amount);
		o.putString("comment", comment);
		o.putLong("ready", readyTick);
		NbtList list = st.data().getListOrEmpty("orders");
		list = (NbtList) list.copy();
		list.add(o);
		st.data().put("orders", list);
		st.markDirty();
		return o.getLong("id", 0L);
	}

	/** Серверный тик: обработка дозревших выплат (раз в 20 тиков). */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 20 != 0) return;
		OrdersState st = state(server);
		NbtList list = st.data().getListOrEmpty("orders");
		if (list.isEmpty()) return;
		long now = server.getOverworld().getTime();
		List<Integer> toRemove = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			final int idx = i;
			NbtElement el = list.get(idx);
			if (!(el instanceof NbtCompound o)) continue;
			if (o.getLong("ready", Long.MAX_VALUE) > now) continue;
			if (o.getInt("kind", 0) == KIND_DELIVERY) {
				// доставка созрела: одноразовое уведомление игроку в чат
				if (o.getBoolean("ntf", false)) continue;
				o.putBoolean("ntf", true);
				st.markDirty();
				try {
					UUID owner = UUID.fromString(o.getString("owner", ""));
					ServerPlayerEntity pl = server.getPlayerManager().getPlayer(owner);
					if (pl != null) {
						ItemStack got = decodeStack(server,
								o.getCompound("item").orElseGet(NbtCompound::new));
						pl.sendMessage(Text.translatable("craftnet.order.arrived",
								got.isEmpty() ? Text.literal("?") : got.getName(),
								o.getString("vname", "")), false);
						pl.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
					}
				} catch (IllegalArgumentException ignored) {
				}
				continue;
			}
			if (o.getInt("kind", 0) != KIND_PAYOUT) continue;
			String ownerStr = o.getString("owner", "");
			try {
				UUID owner = UUID.fromString(ownerStr);
				long amount = o.getLong("payout", 0L);
				MoneyManager.add(server, owner, amount, "продажа онлайн");
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(owner);
				if (p != null) {
					p.sendMessage(Text.translatable("craftnet.payout.arrived", amount,
							o.getString("comment", "")), false);
				}
				toRemove.add(i);
			} catch (IllegalArgumentException ignored) {
				toRemove.add(i);
			}
		}
		if (toRemove.isEmpty()) return;
		NbtList nl = (NbtList) list.copy();
		for (int i = toRemove.size() - 1; i >= 0; i--) nl.remove(toRemove.get(i).intValue());
		st.data().put("orders", nl);
		st.markDirty();
	}

	/** Все заказы игрока (доставки) для экрана ПВЗ. */
	public static List<NbtCompound> deliveriesOf(MinecraftServer server, UUID owner, long now) {
		List<NbtCompound> out = new ArrayList<>();
		NbtList list = state(server).data().getListOrEmpty("orders");
		for (int i = 0; i < list.size(); i++) {
			final int idx = i;
			NbtElement el = list.get(idx);
			if (!(el instanceof NbtCompound o)) continue;
			if (o.getInt("kind", 0) != KIND_DELIVERY) continue;
			if (!o.getString("owner", "").equals(owner.toString())) continue;
			NbtCompound view = new NbtCompound();
			view.putLong("id", o.getLong("id", 0L));
			ItemStack stack = decodeStack(server, Nbt2.sub(o, "item"));
			view.putString("itemId", net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString());
			view.putInt("count", stack.getCount());
			view.putString("name", stack.getName().getString());
			view.putString("vname", o.getString("vname", ""));
			view.putInt("vx", o.getInt("vx", 0));
			view.putInt("vy", o.getInt("vy", 0));
			view.putInt("vz", o.getInt("vz", 0));
			long ready = o.getLong("ready", 0L);
			view.putInt("readyNow", ready <= now ? 1 : 0);
			view.putLong("etaSec", Math.max(0, (ready - now) / 20));
			long start = o.getLong("start", 0L);
			int frac = start <= 0 || ready <= start ? (ready <= now ? 100 : 0)
					: (int) Math.max(0, Math.min(100, (now - start) * 100 / (ready - start)));
			view.putInt("frac", frac);
			out.add(view);
		}
		return out;
	}

	/**
	 * Забрать посылку. Проверяет владельца, готовность и близость к деревне ПВЗ.
	 * @return true, если выдали.
	 */
	public static boolean claim(MinecraftServer server, ServerPlayerEntity player, long orderId) {
		OrdersState st = state(server);
		NbtList list = st.data().getListOrEmpty("orders");
		long now = server.getOverworld().getTime();
		for (int i = 0; i < list.size(); i++) {
			final int idx = i;
			NbtElement el = list.get(idx);
			if (!(el instanceof NbtCompound o)) continue;
			if (o.getInt("kind", 0) != KIND_DELIVERY) continue;
			if (o.getLong("id", -1) != orderId) continue;
			if (!o.getString("owner", "").equals(player.getUuidAsString())) continue;
			if (o.getLong("ready", Long.MAX_VALUE) > now) return false;
			double dx = player.getX() - o.getInt("vx", 0);
			double dz = player.getZ() - o.getInt("vz", 0);
			if (dx * dx + dz * dz > 96.0 * 96.0) return false;
			ItemStack stack = decodeStack(server, Nbt2.sub(o, "item"));
			if (stack.isEmpty()) return false;
			player.getInventory().offerOrDrop(stack);
			NbtList nl = (NbtList) list.copy();
			nl.remove(i);
			st.data().put("orders", nl);
			st.markDirty();
			return true;
		}
		return false;
	}
}
