package net.craftnet.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import net.craftnet.jobs.JobManager;
import net.craftnet.state.CasinoState;
import net.craftnet.util.Nbt2;

/**
 * Казино-апгрейдер (в телефоне, «интернет-казино»):
 *  - игрок кладёт предметы в ставку (пул в PersistentState, из инвентаря списываются);
 *  - цель — любой торгуемый предмет; честный шанс = стоимость ставки (по цене продажи
 *    ПВЗ) / стоимость цели (по цене покупки в магазине);
 *    пример: ставка вдвое дешевле приза → ровно 50%;
 *  - шанс ограничен 1%..95% (нельзя заложить «бесплатный апгрейд»);
 *  - спин серверный и мгновенный, рулетка на клиенте — чистый театр по seed спина;
 *    проигрыш сжигает ставку, выигрыш выдаёт приз в инвентарь;
 *  - крупные выигрыши (шанс ≤ 20%) рассылаются в общий чат.
 */
public final class CasinoManager {
	private CasinoManager() {}

	public static final int MAX_KINDS = 4;
	public static final int MAX_UNITS = 64;
	public static final int BP_MIN = 100;   // 1.00%
	public static final int BP_MAX = 9500;  // 95.00%
	public static final int BIG_WIN_BP = 2000; // ≤20% — «крупный» выигрыш в общий чат

	public static final int SPIN_OK = 0;
	public static final int SPIN_EMPTY = 2;
	public static final int SPIN_BAD_TARGET = 3;
	public static final int SPIN_LOW = 4;

	private static final Random RNG = new Random();

	public static CasinoState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(CasinoState.TYPE);
	}

	private static NbtCompound poolRec(CasinoState st, UUID uuid) {
		return Nbt2.sub(Nbt2.sub(st.data(), "p"), uuid.toString());
	}

	private static void savePoolRec(CasinoState st, UUID uuid, NbtCompound rec) {
		NbtCompound pools = Nbt2.sub(st.data(), "p");
		pools.put(uuid.toString(), rec);
		st.data().put("p", pools);
		st.markDirty();
	}

	/** Стоимость ставки (по цене продажи в ПВЗ). */
	public static long poolValue(MinecraftServer server, UUID uuid) {
		long v = 0;
		for (NbtCompound e : poolList(get(server), uuid)) {
			v += (long) PriceManager.sellPrice(Nbt2.str(e, "id")) * Nbt2.i(e, "count");
		}
		return v;
	}

	private static List<NbtCompound> poolList(CasinoState st, UUID uuid) {
		List<NbtCompound> out = new ArrayList<>();
		NbtList items = poolRec(st, uuid).getListOrEmpty("items");
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i) instanceof NbtCompound c) out.add(c);
		}
		return out;
	}

	/** Строки пула для телефона. */
	public static List<NbtCompound> poolRows(MinecraftServer server, UUID uuid) {
		List<NbtCompound> out = new ArrayList<>();
		for (NbtCompound e : poolList(get(server), uuid)) {
			String id = Nbt2.str(e, "id");
			Item item = Registries.ITEM.get(Identifier.tryParse(id));
			if (item == null) continue;
			NbtCompound c = new NbtCompound();
			c.putString("id", id);
			c.putString("name", new ItemStack(item).getName().getString());
			c.putInt("count", Nbt2.i(e, "count"));
			c.putInt("val", PriceManager.sellPrice(id) * Nbt2.i(e, "count"));
			out.add(c);
		}
		return out;
	}

	/**
	 * Добавить предмет в ставку (из инвентаря).
	 * @return 0 ок, 1 — нет предмета, 2 — лимит видов, 3 — достигнут потолок 64 шт.
	 */
	public static int addStake(MinecraftServer server, ServerPlayerEntity player, String itemId, int count) {
		Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
		if (item == null || !PriceManager.tradeable(itemId) || PriceManager.sellPrice(itemId) <= 0) return 1;
		CasinoState st = get(server);
		List<NbtCompound> items = poolList(st, player.getUuid());
		NbtCompound slot = null;
		int kinds = 0;
		for (NbtCompound e : items) {
			kinds++;
			if (Nbt2.str(e, "id").equals(itemId)) slot = e;
		}
		if (slot == null && kinds >= MAX_KINDS) return 2;
		if (slot != null && Nbt2.i(slot, "count") >= MAX_UNITS) return 3;
		int canHave = JobManager.countInInventory(player, item);
		if (canHave <= 0) return 1;
		count = Math.min(count, canHave);
		if (slot != null) count = Math.min(count, MAX_UNITS - Nbt2.i(slot, "count"));
		if (count <= 0) return 3;
		JobManager.removeFromInventory(player, item, count);

		if (slot == null) {
			slot = new NbtCompound();
			slot.putString("id", itemId);
			slot.putInt("count", 0);
			items.add(slot);
		}
		slot.putInt("count", Nbt2.i(slot, "count") + count);
		NbtCompound rec = poolRec(st, player.getUuid());
		NbtList list = new NbtList();
		for (NbtCompound e : items) list.add(e);
		rec.put("items", list);
		savePoolRec(st, player.getUuid(), rec);
		return 0;
	}

	/** Вернуть всю ставку в инвентарь. @return возвращено ли что-то. */
	public static boolean clearPool(MinecraftServer server, ServerPlayerEntity player) {
		CasinoState st = get(server);
		List<NbtCompound> items = poolList(st, player.getUuid());
		if (items.isEmpty()) return false;
		for (NbtCompound e : items) {
			Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(e, "id")));
			if (item != null) {
				player.getInventory().offerOrDrop(new ItemStack(item, Nbt2.i(e, "count")));
			}
		}
		savePoolRec(st, player.getUuid(), new NbtCompound());
		return true;
	}

	/**
	 * Спин: сжигает ставку, бросает ролл, применяет результат и шлёт сообщения.
	 * @return код результата (SPIN_OK — ролл состоялся).
	 */
	public static int spin(MinecraftServer server, ServerPlayerEntity player, String targetId) {
		CasinoState st = get(server);
		List<NbtCompound> items = poolList(st, player.getUuid());
		if (items.isEmpty()) return SPIN_EMPTY;
		Item target = Registries.ITEM.get(Identifier.tryParse(targetId));
		if (target == null || !PriceManager.tradeable(targetId) || PriceManager.buyPrice(targetId) <= 0) {
			return SPIN_BAD_TARGET;
		}
		long stake = poolValue(server, player.getUuid());
		long targetVal = PriceManager.buyPrice(targetId);
		if (stake <= 0) return SPIN_EMPTY;
		long bp = Math.min(BP_MAX, stake * 10000L / targetVal);
		if (bp < BP_MIN) return SPIN_LOW;

		// ставка сгорает всегда
		savePoolRec(st, player.getUuid(), new NbtCompound());

		boolean win = RNG.nextInt(10000) < bp;
		String pct = String.format(Locale.ROOT, "%.1f", bp / 100.0);
		String tname = new ItemStack(target).getName().getString();
		if (win) {
			player.getInventory().offerOrDrop(new ItemStack(target));
			player.sendMessage(Text.translatable("craftnet.casino.win", tname, pct), false);
			player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
			if (bp <= BIG_WIN_BP) {
				Text b = Text.translatable("craftnet.casino.broadcast",
						player.getName().getString(), tname, pct).formatted(Formatting.GOLD);
				for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
					p.sendMessage(b, false);
				}
			}
		} else {
			player.sendMessage(Text.translatable("craftnet.casino.lose", stake, pct), false);
			player.playSound(SoundEvents.ENTITY_ITEM_BREAK.value(), 0.8f, 0.9f);
		}

		// запись последнего спина (для рулетки на клиенте)
		long spinId = Nbt2.lng(st.data(), "spinSeq") + 1;
		st.data().putLong("spinSeq", spinId);
		NbtCompound lasts = Nbt2.sub(st.data(), "last");
		NbtCompound last = new NbtCompound();
		last.putLong("id", spinId);
		last.putInt("win", win ? 1 : 0);
		last.putInt("bp", (int) bp);
		last.putLong("sv", stake);
		last.putLong("tv", targetVal);
		last.putString("target", targetId);
		last.putString("sicon", items.isEmpty() ? "" : Nbt2.str(items.get(0), "id"));
		last.putString("tname", tname);
		lasts.put(player.getUuid().toString(), last);
		st.data().put("last", lasts);
		st.markDirty();
		return SPIN_OK;
	}

	/** Последний спин для синхронизации (или пустой compound). */
	public static NbtCompound lastSpinView(MinecraftServer server, UUID uuid) {
		return Nbt2.sub(Nbt2.sub(get(server).data(), "last"), uuid.toString());
	}
}
