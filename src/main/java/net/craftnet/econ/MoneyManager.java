package net.craftnet.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;

import net.craftnet.state.MoneyState;
import net.craftnet.util.Nbt2;

/**
 * Валюта сервера — «кредиты» (CR). Все операции атомарны относительно
 * главного потока сервера и сразу помечают стейт грязным.
 */
public final class MoneyManager {
	private MoneyManager() {}

	private static final int TX_LOG_MAX = 16;

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
		appendTx(rec, (amount >= 0 ? "+" : "") + amount + " " + reason);
		saveRec(st, id, rec);
		return bal;
	}

	/** @return true, если хватило средств и списание прошло. */
	public static boolean tryCharge(MinecraftServer server, UUID id, long amount, String reason) {
		if (balance(server, id) < amount) return false;
		add(server, id, -amount, reason);
		return true;
	}

	private static void appendTx(NbtCompound rec, String entry) {
		NbtList log = rec.getListOrEmpty("tx");
		log = (NbtList) log.copy(); // защита от мутаций копий
		log.add(NbtString.of(entry));
		while (log.size() > TX_LOG_MAX) log.remove(0);
		rec.put("tx", log);
	}

	public static List<String> txLog(MinecraftServer server, UUID id) {
		NbtList log = Nbt2.sub(players(state(server).data()), id.toString()).getListOrEmpty("tx");
		List<String> out = new ArrayList<>(log.size());
		for (int i = 0; i < log.size(); i++) {
			final int idx = i;
			out.add(log.get(idx).asString().orElse(""));
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
		if (Nbt2.lng(rec, "bal") == 0) rec.putLong("bal", 100);
		saveRec(st, id, rec);
		return true;
	}

	public static boolean transfer(MinecraftServer server, UUID from, UUID to, long amount) {
		if (amount <= 0 || from.equals(to)) return false;
		if (!tryCharge(server, from, amount, "перевод")) return false;
		add(server, to, amount, "перевод");
		return true;
	}
}
