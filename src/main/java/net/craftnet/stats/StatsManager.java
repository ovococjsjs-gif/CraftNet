package net.craftnet.stats;

import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import net.craftnet.econ.MoneyManager;
import net.craftnet.state.StatsState;
import net.craftnet.util.Nbt2;

/**
 * Профиль игрока: статистика, опыт, уровни, достижения.
 *
 * Все значимые события экономики (зарплаты, продажи, спины, сделки,
 * переводы, прокачка вышек) инкрементят счётчики через bump()/set().
 * После каждого изменения — прогон checkAchievements: достижения
 * разблокируются один раз, дают опыт и золотое сообщение в чат.
 *
 * Уровень профиля: xp для уровня N = 100·N² (ур.2 = 400, ур.5 = 2500,
 * ур.10 = 10000). Звания — чисто престижные, на геймплей не давят.
 */
public final class StatsManager {
	private StatsManager() {}

	// -------------------- ключи счётчиков --------------------
	public static final String XP = "xp";
	public static final String JOBS_DONE = "jobsDone";
	public static final String JOBS_FACTORY = "jobsFactory"; // мини-игра + крафт-заказы цеха
	public static final String JOBS_LOADER = "jobsLoader";
	public static final String JOBS_COOK = "jobsCook";
	public static final String JOBS_COURIER = "jobsCourier";
	public static final String JOBS_CANCELED = "jobsCanceled";
	public static final String FINES_PAID = "finesPaid";
	public static final String EARN_JOBS = "earnJobs";
	public static final String ORDERS_BOUGHT = "ordersBought";
	public static final String PARCELS = "parcelsClaimed";
	public static final String ITEMS_SOLD = "itemsSold";
	public static final String EARN_SALES = "earnSales";
	public static final String MARKET_LISTED = "marketListed";
	public static final String MARKET_BOUGHT = "marketBought";
	public static final String SPINS = "spins";
	public static final String SPIN_WINS = "spinWins";
	public static final String CASINO_WAGERED = "casinoWagered";
	public static final String CASINO_WON = "casinoWon";
	public static final String STOCKS_BOUGHT = "stocksBought";
	public static final String STOCKS_EARN = "stocksEarn"; // продажа акций + дивиденды
	public static final String DIVIDENDS = "dividendsGot";
	public static final String TRANSFERS_SENT = "transfersSent";
	public static final String TRANSFERS_GOT = "transfersGot";
	public static final String TOWER_UPGRADES = "towerUpgrades";
	public static final String TOWER_INVESTED = "towerInvested";
	public static final String BALANCE_NOW = "balanceNow";

	// -------------------- состояние --------------------

	public static StatsState state(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(StatsState.TYPE);
	}

	private static NbtCompound rec(StatsState st, UUID id) {
		return Nbt2.sub(Nbt2.sub(st.data(), "p"), id.toString());
	}

	private static void saveRec(StatsState st, UUID id, NbtCompound rec) {
		NbtCompound p = Nbt2.sub(st.data(), "p");
		p.put(id.toString(), rec);
		st.data().put("p", p);
		st.markDirty();
	}

	public static long get(MinecraftServer server, UUID id, String key) {
		return Nbt2.lng(rec(state(server), id), key);
	}

	/** Инкремент счётчика (+ проверка достижений). */
	public static void bump(MinecraftServer server, UUID id, String key, long delta) {
		StatsState st = state(server);
		NbtCompound rec = rec(st, id);
		rec.putLong(key, Nbt2.lng(rec, key) + delta);
		saveRec(st, id, rec);
		checkAchievements(server, id);
	}

	/** Установка значения «текущее состояние» (например, баланс). Проверяет ачивки при изменении. */
	public static void set(MinecraftServer server, UUID id, String key, long value) {
		StatsState st = state(server);
		NbtCompound rec = rec(st, id);
		if (Nbt2.lng(rec, key) == value) return;
		rec.putLong(key, value);
		saveRec(st, id, rec);
		checkAchievements(server, id);
	}

	// -------------------- опыт и уровни --------------------

	/** XP, необходимый для уровня n. */
	public static long xpForLevel(int n) {
		return 100L * n * n;
	}

	public static int levelOf(long xp) {
		return Math.max(0, Math.min(99, (int) Math.sqrt(xp / 100.0)));
	}

	/** Звания по уровню (чистый престиж). */
	public static final String[] RANKS = {
			"Новичок", "Житель", "Работяга", "Специалист",
			"Профи", "Эксперт", "Магнат", "Легенда"};

	public static String rankOf(int level) {
		return RANKS[Math.max(0, Math.min(RANKS.length - 1, level))];
	}

	/** Начислить опыт; при повышении уровня — золотое сообщение. */
	public static void addXp(MinecraftServer server, UUID id, long delta) {
		if (delta <= 0) return;
		StatsState st = state(server);
		NbtCompound rec = rec(st, id);
		long xp = Nbt2.lng(rec, XP) + delta;
		rec.putLong(XP, xp);
		saveRec(st, id, rec);
		int oldLvl = levelOf(xp - delta);
		int newLvl = levelOf(xp);
		if (newLvl > oldLvl) {
			net.craftnet.CraftNet.LOGGER.info("[CraftNet] Профиль {}: уровень {}",
					id.toString().substring(0, 8), newLvl);
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal("§b⬆ Уровень профиля: §f" + newLvl
						+ " §7— «" + rankOf(newLvl) + "»"), false);
				p.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.0f);
			}
		}
	}

	// -------------------- достижения --------------------

	/** Достижение: сумма счётчиков key1(+key2) должна достичь need. */
	public record Ach(String id, String key1, String key2, long need, long xp,
			String name, String desc) {}

	public static final List<Ach> ACHIEVEMENTS = List.of(
			new Ach("first_job", JOBS_DONE, "", 1, 50, "Первая смена",
					"выполни первое задание"),
			new Ach("chief", JOBS_FACTORY, "", 10, 120, "Мастер цеха",
					"10 смен на заводе"),
			new Ach("logistics", JOBS_LOADER, JOBS_COURIER, 15, 120, "Логист",
					"15 доставок грузчиком или курьером"),
			new Ach("workaholic", JOBS_DONE, "", 25, 200, "Трудяга",
					"25 завершённых смен"),
			new Ach("shopper", ORDERS_BOUGHT, "", 5, 60, "Шопоголик",
					"5 заказов в онлайн-магазине"),
			new Ach("merchant", EARN_SALES, "", 4000, 150, "Торговец",
					"заработай продажами 4000 CR"),
			new Ach("gambler", SPINS, "", 20, 100, "Лудоман",
					"20 спинов в апгрейдере"),
			new Ach("lucky", SPIN_WINS, "", 5, 150, "Счастливчик",
					"5 выигрышей в апгрейдере"),
			new Ach("investor", STOCKS_BOUGHT, "", 40, 120, "Инвестор",
					"купи 40 акций на бирже"),
			new Ach("giver", TRANSFERS_SENT, "", 2500, 120, "Меценат",
					"отправь переводами 2500 CR"),
			new Ach("engineer", TOWER_UPGRADES, "", 3, 150, "Инженер связи",
					"прокачай вышки 3 раза"),
			new Ach("millionaire", BALANCE_NOW, "", 10000, 300, "Миллионер",
					"держи 10 000 CR на счёте"));

	public static boolean unlocked(NbtCompound rec, String achId) {
		return Nbt2.sub(rec, "ach").getInt(achId, 0) != 0;
	}

	/** Прогон всех достижений по текущим счётчикам; новые — с наградой и фанфарами. */
	public static void checkAchievements(MinecraftServer server, UUID id) {
		StatsState st = state(server);
		NbtCompound rec = rec(st, id);
		NbtCompound ach = Nbt2.sub(rec, "ach");
		long bonusXp = 0;
		boolean changed = false;
		for (Ach a : ACHIEVEMENTS) {
			if (ach.getInt(a.id(), 0) != 0) continue;
			long cur = Nbt2.lng(rec, a.key1())
					+ (a.key2().isEmpty() ? 0 : Nbt2.lng(rec, a.key2()));
			if (cur < a.need()) continue;
			ach.putInt(a.id(), 1);
			changed = true;
			bonusXp += a.xp();
			net.craftnet.CraftNet.LOGGER.info("[CraftNet] Достижение {}: «{}»",
					id.toString().substring(0, 8), a.name());
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) {
				p.sendMessage(Text.literal("§6★ Достижение: «§e" + a.name() + "§6»§7 — "
						+ a.desc() + " §8(+" + a.xp() + " XP)"), false);
				p.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.0f);
			}
		}
		if (changed) {
			rec.put("ach", ach);
			saveRec(st, id, rec);
			addXp(server, id, bonusXp);
		}
	}

	// -------------------- вид для телефона --------------------

	/**
	 * Полный снапшот профиля для вкладки «Профиль»:
	 * опыт/уровень/звание, все счётчики (ключ counter → long),
	 * строки достижений {id, cur, need, un}.
	 */
	public static NbtCompound profileView(MinecraftServer server, ServerPlayerEntity player) {
		// «Миллионер» реагирует на текущий баланс — освежаем перед показом
		set(server, player.getUuid(), BALANCE_NOW, MoneyManager.balance(server, player.getUuid()));
		StatsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());

		NbtCompound out = new NbtCompound();
		long xp = Nbt2.lng(rec, XP);
		int lvl = levelOf(xp);
		out.putLong("xp", xp);
		out.putInt("lvl", lvl);
		out.putLong("xpBase", xpForLevel(lvl));
		out.putLong("xpNext", xpForLevel(lvl + 1));
		out.putString("rank", rankOf(lvl));

		NbtCompound c = new NbtCompound();
		for (String k : rec.getKeys()) {
			if (!"ach".equals(k)) c.putLong(k, Nbt2.lng(rec, k));
		}
		out.put("c", c);

		NbtList rows = new NbtList();
		NbtCompound un = Nbt2.sub(rec, "ach");
		for (Ach a : ACHIEVEMENTS) {
			NbtCompound r = new NbtCompound();
			r.putString("id", a.id());
			r.putLong("cur", Math.min(a.need(), Nbt2.lng(rec, a.key1())
					+ (a.key2().isEmpty() ? 0 : Nbt2.lng(rec, a.key2()))));
			r.putLong("need", a.need());
			r.putInt("un", un.getInt(a.id(), 0));
			rows.add(r);
		}
		out.put("ach", rows);
		return out;
	}
}
