package net.craftnet.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import net.craftnet.state.MoneyState;
import net.craftnet.util.Nbt2;

/**
 * Валюта сервера — «кредиты» (CR). Все операции атомарны относительно
 * главного потока сервера и сразу помечают стейт грязным.
 */
public final class MoneyManager {
	private MoneyManager() {}

	private static final int TX_LOG_MAX = 16;

	/** Дневной банковский процент на остаток (доля), потолок и минимальный баланс. */
	public static final double DAILY_INTEREST = net.craftnet.config.CraftNetConfig.get().bankInterestPctPerDay / 100.0;
	public static final long INTEREST_CAP = net.craftnet.config.CraftNetConfig.get().bankInterestCap;
	public static final long INTEREST_MIN_BAL = net.craftnet.config.CraftNetConfig.get().bankInterestMinBalance;

	public static MoneyState state(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(MoneyState.TYPE);
	}

	private static NbtCompound players(NbtCompound root) {
		return Nbt2.sub(root, "players");
	}

	private static NbtCompound rec(MoneyState st, UUID id) {
		return Nbt2.sub(players(st.data()), id.toString());
	}

	private static void saveRec(MoneyState st, UUID id, NbtCompound rec) {
		NbtCompound players = players(st.data());
		players.put(id.toString(), rec);
		st.data().put("players", players);
		st.markDirty();
	}

	public static long balance(MinecraftServer server, UUID id) {
		return Nbt2.lng(rec(state(server), id), "bal");
	}

	/** @return новый баланс. */
	public static long add(MinecraftServer server, UUID id, long amount, String reason) {
		MoneyState st = state(server);
		NbtCompound rec = rec(st, id);
		long bal = Nbt2.lng(rec, "bal") + amount;
		if (bal < 0) bal = 0;
		rec.putLong("bal", bal);
		appendTx(rec, server, amount, reason);
		saveRec(st, id, rec);
		return bal;
	}

	/** @return true, если хватило средств и списание прошло. */
	public static boolean tryCharge(MinecraftServer server, UUID id, long amount, String reason) {
		if (balance(server, id) < amount) return false;
		add(server, id, -amount, reason);
		return true;
	}

	/** Журнал — структурный: {a: дельта CR, r: причина, at: игровое время}. */
	private static void appendTx(NbtCompound rec, MinecraftServer server, long amount, String reason) {
		NbtList log = rec.getListOrEmpty("tx");
		log = (NbtList) log.copy(); // защита от мутаций копий
		NbtCompound e = new NbtCompound();
		e.putLong("a", amount);
		e.putString("r", reason);
		e.putLong("at", server.getOverworld().getTime());
		log.add(e);
		while (log.size() > TX_LOG_MAX) log.remove(0);
		rec.put("tx", log);
	}

	/**
	 * Журнал операций (моложе — в конце). Записи нового формата — compounds
	 * {a, r, at}; легаси-строки из старых сейвов нормализуются в {r} без суммы.
	 */
	public static List<NbtCompound> txLog(MinecraftServer server, UUID id) {
		NbtList log = Nbt2.sub(players(state(server).data()), id.toString()).getListOrEmpty("tx");
		List<NbtCompound> out = new ArrayList<>(log.size());
		for (var el : log) {
			if (el instanceof NbtCompound c) {
				out.add(c);
			} else if (el instanceof NbtString s) {
				// старый текстовый формат "+240 работа: завод" — сумму не парсим
				NbtCompound legacy = new NbtCompound();
				legacy.putString("r", s.asString().orElse(""));
				out.add(legacy);
			}
		}
		return out;
	}

	/**
	 * Флаг «телефон ещё не выдавался». При первом вызове для игрока возвращает
	 * true и сразу ставит флаг (атомарно по главному потоку).
	 */
	public static boolean consumeFirstJoinFlag(MinecraftServer server, UUID id) {
		MoneyState st = state(server);
		NbtCompound rec = rec(st, id);
		if (Nbt2.i(rec, "phoneGiven") != 0) return false;
		rec.putInt("phoneGiven", 1);
		// стартовый капитал, чтобы экономика ожила сразу
		if (Nbt2.lng(rec, "bal") == 0) rec.putLong("bal", net.craftnet.config.CraftNetConfig.get().startBonus);
		saveRec(st, id, rec);
		return true;
	}

	/**
	 * Раз в игровые сутки начисляет процент на остаток всем игрокам
	 * (включая офлайн). При первом запуске только фиксирует день.
	 */
	public static void maybePayDailyInterest(MinecraftServer server) {
		long day = server.getOverworld().getTimeOfDay() / 24000L;
		MoneyState st = state(server);
		NbtCompound meta = Nbt2.sub(st.data(), "meta");
		boolean first = !meta.contains("lastIntDay");
		if (!first && meta.getLong("lastIntDay", -1L) >= day) return;
		meta.putLong("lastIntDay", day);
		st.data().put("meta", meta);
		st.markDirty();
		if (first) return;
		for (String key : players(st.data()).getKeys()) {
			UUID id;
			try {
				id = UUID.fromString(key);
			} catch (IllegalArgumentException ex) {
				continue;
			}
			long bal = balance(server, id);
			if (bal < INTEREST_MIN_BAL) continue;
			long interest = Math.min(INTEREST_CAP, Math.round(bal * DAILY_INTEREST));
			if (interest <= 0) continue;
			add(server, id, interest, "процент банка");
			ServerPlayerEntity pl = server.getPlayerManager().getPlayer(id);
			if (pl != null) {
				pl.sendMessage(Text.translatable("craftnet.bank.interest", interest), false);
			}
		}
	}

	public static boolean transfer(MinecraftServer server, UUID from, UUID to, long amount) {
		if (amount <= 0 || from.equals(to)) return false;
		if (!tryCharge(server, from, amount, "перевод")) return false;
		add(server, to, amount, "перевод");
		return true;
	}
}
