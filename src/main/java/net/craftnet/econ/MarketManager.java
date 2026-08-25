package net.craftnet.econ;

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
import net.minecraft.util.math.BlockPos;

import net.craftnet.jobs.JobManager;
import net.craftnet.orders.OrderManager;
import net.craftnet.state.MarketState;
import net.craftnet.stats.StatsManager;
import net.craftnet.util.Nbt2;

/**
 * Барахолка — маркетплейс игроков.
 *  - лот выставляется из ПВЗ по своей цене, живёт 3 игровых дня;
 *  - покупка из телефона (нужен 2G), товар приезжает в ПВЗ ближайшей
 *    онлайн-деревни покупателя с обычной скоростью доставки;
 *  - комиссия 5% удерживается из выплаты продавца; выплата — с задержкой,
 *    как за обычную продажу в ПВЗ;
 *  - снятие лота / истечение срока — возврат почтой в ПВЗ продавца (1 мин).
 */
public final class MarketManager {
	private MarketManager() {}

	public static final double FEE = 0.05;
	public static final long TTL_TICKS = 72000; // 3 игровых дня
	public static final int RETURN_TICKS = 1200; // 1 минута
	public static final int MAX_PER_PLAYER = 6;
	public static final int PRICE_MAX = 1_000_000;

	// коды результата buy()
	public static final int BUY_OK = 0;
	public static final int BUY_GONE = 1;
	public static final int BUY_OWN = 2;
	public static final int BUY_NO_MONEY = 3;

	public static MarketState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(MarketState.TYPE);
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

	private static long nextId(MarketState st) {
		long id = Nbt2.lng(st.data(), "nextId") + 1;
		st.data().putLong("nextId", id);
		return id;
	}

	public static int listingsOf(MinecraftServer server, UUID seller) {
		int n = 0;
		for (NbtCompound l : all(server)) {
			if (seller.toString().equals(Nbt2.str(l, "seller"))) n++;
		}
		return n;
	}

	/** Все лоты (копии). */
	public static List<NbtCompound> all(MinecraftServer server) {
		List<NbtCompound> out = new ArrayList<>();
		NbtList list = get(server).data().getListOrEmpty("lots");
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) instanceof NbtCompound c) out.add(c);
		}
		return out;
	}

	/**
	 * Выставить лот из инвентаря игрока.
	 * @return 0 при успехе, 1 — лимит лотов, 2 — нет предмета, 3 — кривая цена.
	 */
	public static int create(MinecraftServer server, ServerPlayerEntity player, String itemId,
			int count, int priceEach, int vx, int vy, int vz, String vname) {
		if (priceEach <= 0 || priceEach > PRICE_MAX) return 3;
		if (listingsOf(server, player.getUuid()) >= MAX_PER_PLAYER) return 1;
		var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.tryParse(itemId));
		if (item == null || !PriceManager.tradeable(itemId)) return 2;
		// рабочее имущество (◆ материалы/грузы) выставлять нельзя — анти-фарм
		int have = JobManager.countSellable(player, item);
		count = Math.min(Math.min(count, 64), have);
		if (count <= 0) return 2;
		JobManager.removeSellable(player, item, count);

		MarketState st = get(server);
		NbtCompound l = new NbtCompound();
		l.putLong("id", nextId(st));
		l.putString("seller", player.getUuidAsString());
		l.putString("sellerName", player.getName().getString());
		l.put("item", encodeStack(server, new ItemStack(item, count)));
		l.putInt("price", priceEach);
		l.putLong("created", server.getOverworld().getTime());
		l.putInt("vx", vx);
		l.putInt("vy", vy);
		l.putInt("vz", vz);
		l.putString("vname", vname);
		NbtList list = st.data().getListOrEmpty("lots");
		list.add(l);
		st.data().put("lots", list);
		st.markDirty();
		StatsManager.bump(server, player.getUuid(), StatsManager.MARKET_LISTED, 1);
		return 0;
	}

	/**
	 * Купить лот: снимает деньги с покупателя, шлёт предмет в его ПВЗ,
	 * продавцу ставит отложенную выплату минус комиссия.
	 * @param buyerVillageX/Y/Z/Name онлайн-деревня покупателя (уже проверена вызывающим)
	 */
	public static int buy(MinecraftServer server, ServerPlayerEntity buyer, long lid,
			int buyerVillageX, int buyerVillageY, int buyerVillageZ, String buyerVillageName,
			long readyTick) {
		MarketState st = get(server);
		NbtList list = st.data().getListOrEmpty("lots");
		int idx = -1;
		NbtCompound lot = null;
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) instanceof NbtCompound c && c.getLong("id", -1L) == lid) {
				idx = i;
				lot = c;
				break;
			}
		}
		if (lot == null) return BUY_GONE;
		if (buyer.getUuidAsString().equals(Nbt2.str(lot, "seller"))) return BUY_OWN;

		ItemStack stack = decodeStack(server, Nbt2.sub(lot, "item"));
		if (stack.isEmpty()) return BUY_GONE;
		long total = (long) Nbt2.i(lot, "price") * stack.getCount();
		if (!MoneyManager.tryCharge(server, buyer.getUuid(), total, "барахолка: покупка")) {
			return BUY_NO_MONEY;
		}

		// доставка покупателю
		OrderManager.newDelivery(server, buyer.getUuid(), stack,
				buyerVillageX, buyerVillageY, buyerVillageZ, buyerVillageName, readyTick);

		// выплата продавцу минус комиссия (с задержкой, как продажа в ПВЗ)
		long sellerGet = Math.max(1, total - Math.round(total * FEE));
		try {
			UUID seller = UUID.fromString(Nbt2.str(lot, "seller"));
			long payTick = server.getOverworld().getTime() + OrderManager.BASE_TRAVEL_TICKS;
			OrderManager.newPayout(server, seller, sellerGet,
					"барахолка: " + stack.getName().getString() + " ×" + stack.getCount(), payTick);
		} catch (IllegalArgumentException ignored) {
		}

		list.remove(idx);
		st.data().put("lots", list);
		st.markDirty();
		StatsManager.bump(server, buyer.getUuid(), StatsManager.MARKET_BOUGHT, 1);
		StatsManager.addXp(server, buyer.getUuid(), 3);
		return BUY_OK;
	}

	/** Снять все свои лоты (возврат почтой). @return сколько снято. */
	public static int cancelMine(MinecraftServer server, ServerPlayerEntity player) {
		MarketState st = get(server);
		NbtList list = st.data().getListOrEmpty("lots");
		long now = server.getOverworld().getTime();
		List<Integer> toRemove = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			if (!(list.get(i) instanceof NbtCompound l)) continue;
			if (!player.getUuidAsString().equals(Nbt2.str(l, "seller"))) continue;
			returnLot(server, l, now);
			toRemove.add(i);
		}
		for (int i = toRemove.size() - 1; i >= 0; i--) list.remove(toRemove.get(i).intValue());
		if (!toRemove.isEmpty()) {
			st.data().put("lots", list);
			st.markDirty();
		}
		return toRemove.size();
	}

	/** Истечение срока лотов (раз в минуту из главного тика). */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 1200 != 0) return;
		MarketState st = get(server);
		NbtList list = st.data().getListOrEmpty("lots");
		if (list.isEmpty()) return;
		long now = server.getOverworld().getTime();
		List<Integer> toRemove = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			if (!(list.get(i) instanceof NbtCompound l)) continue;
			if (now - l.getLong("created", 0L) < TTL_TICKS) continue;
			returnLot(server, l, now);
			toRemove.add(i);
		}
		for (int i = toRemove.size() - 1; i >= 0; i--) list.remove(toRemove.get(i).intValue());
		if (!toRemove.isEmpty()) {
			st.data().put("lots", list);
			st.markDirty();
		}
	}

	private static void returnLot(MinecraftServer server, NbtCompound l, long now) {
		ItemStack stack = decodeStack(server, Nbt2.sub(l, "item"));
		if (stack.isEmpty()) return;
		try {
			UUID seller = UUID.fromString(Nbt2.str(l, "seller"));
			OrderManager.newDelivery(server, seller, stack,
					Nbt2.i(l, "vx"), Nbt2.i(l, "vy"), Nbt2.i(l, "vz"),
					Nbt2.str(l, "vname").isEmpty() ? "ПВЗ" : Nbt2.str(l, "vname"),
					now + RETURN_TICKS);
		} catch (IllegalArgumentException ignored) {
		}
	}

	/** Строки для телефона: [{lid,itemId,name,count,price,seller}] — свежие первыми. */
	public static NbtList clientRows(MinecraftServer server) {
		NbtList out = new NbtList();
		List<NbtCompound> all = all(server);
		for (int i = all.size() - 1; i >= 0; i--) {
			NbtCompound l = all.get(i);
			ItemStack stack = decodeStack(server, Nbt2.sub(l, "item"));
			if (stack.isEmpty()) continue;
			NbtCompound c = new NbtCompound();
			c.putLong("lid", l.getLong("id", 0L));
			c.putString("itemId", net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString());
			c.putString("name", stack.getName().getString());
			c.putInt("count", stack.getCount());
			c.putInt("price", Nbt2.i(l, "price"));
			c.putString("seller", Nbt2.str(l, "sellerName"));
			out.add(c);
		}
		return out;
	}
}
