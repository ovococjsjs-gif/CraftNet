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

	/** Reload-safe bank config. */
	private static double dailyInterest() {
		return net.craftnet.config.CraftNetConfig.get().bankInterestPctPerDay / 100.0;
	}

	private static long interestCap() {
		return net.craftnet.config.CraftNetConfig.get().bankInterestCap;
	}

	private static long interestMinBalance() {
		return net.craftnet.config.CraftNetConfig.get().bankInterestMinBalance;
	}

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
		long before = Nbt2.lng(rec, "bal");
		long bal;
		try {
			bal = Math.addExact(before, amount);
		} catch (ArithmeticException overflow) {
			bal = amount >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
		rec.putLong("bal", bal);
		appendTx(rec, server, amount, reason);
		saveRec(st, id, rec);
		return bal;
	}

	/** @return true, если хватило средств и списание прошло. */
	public static boolean tryCharge(MinecraftServer server, UUID id, long amount, String reason) {
		if (amount <= 0 || balance(server, id) < amount) return false;
		add(server, id, -amount, reason);
		return true;
	}

	/**
	 * Fines create an enforceable negative balance instead of disappearing when
	 * the player has zero CR. Future income automatically repays this debt.
	 */
	public static long chargeFine(MinecraftServer server, UUID id, long amount, String reason) {
		if (amount <= 0) return balance(server, id);
		return add(server, id, -amount, reason);
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
			if (bal < interestMinBalance()) continue;
			long interest = Math.min(interestCap(), Math.round(bal * dailyInterest()));
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
