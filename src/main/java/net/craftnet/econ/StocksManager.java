package net.craftnet.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.craftnet.state.StocksState;
import net.craftnet.stats.StatsManager;
import net.craftnet.util.Nbt2;

/**
 * Биржа CraftNet 3.0 — живой рынок:
 *  - цены дышут ПОСТОЯННО: микро-пульс каждые 100 тиков (5 с) — небольшой
 *    гауссов шаг + мягкий возврат к блуждающему «якорю»;
 *  - у каждой компании свой интересный коридор и характер: спокойный банк,
 *    дикая крипер-энергетика, растущие эндер-технологии и т.д.;
 *  - редкие спайки ±3..9% на пульсе, новости с шоком ±6..20% каждые 2-4 мин;
 *  - дивиденды раз в игровые сутки; каждая выплата логируется в консоль
 *    сервера (в расследовании фантомных начислений — прецедент смотреть там);
 *  - история 96 точек с шагом 20 с — график всегда живой.
 */
public final class StocksManager {
	private StocksManager() {}

	public static final double SPREAD = 0.02;
	private static final int HISTORY_MAX = 96;
	private static final int NEWS_MAX = 8;
	private static final double MIN_PRICE = 5.0;
	private static final double MAX_PRICE = 5000.0;
	private static final int HIST_EVERY = 4;      // каждый 4-й пульс (20 с)
	private static final double SPIKE_CHANCE = 0.012; // на пульс, ±3..9%
	private static final long NEWS_MIN_GAP = 2400;  // тиков (2 мин)
	private static final long NEWS_MAX_GAP = 4800;  // (4 мин)

	public enum Company {
		//                    id      ruName            lo    hi    vol     revert  divYield
		// divYield — доля цены пакета за ИГРОВЫЕ сутки (20 мин реальных):
		// ~0.001 ≈ 0.3% в час пассива. Ориентир баланса: хуже завода ×3–5,
		// лучше нуля — биржа добавка к работе, а не замена. См. ECONOMY.md.
		REDR("REDR", "РедстоунКорп",  90, 170, 0.006, 0.06, 0.0025),
		CRPR("CRPR", "КриперЭнерджи", 35, 150, 0.014, 0.04, 0.003),
		VLBK("VLBK", "ЖительБанк",   140, 230, 0.0035, 0.08, 0.004),
		ENDT("ENDT", "ЭндерТех",     180, 400, 0.009, 0.05, 0.001),
		NFSH("NFSH", "НезерСталь",   240, 520, 0.011, 0.05, 0.002);

		public final String id;
		public final String ruName;
		/** Интересный коридор, внутри которого блуждает якорь цены. */
		public final double lo;
		public final double hi;
		/** σ микро-пульса (доля цены) — «как сильно дышит». */
		public final double microVol;
		/** Сила возврата к якорю за пульс (доля от расстояния). */
		public final double revert;
		/** Дивидендная доходность: доля от рыночной цены пакета за игровые сутки. */
		public final double divYield;

		Company(String id, String ruName, double lo, double hi, double microVol, double revert, double divYield) {
			this.id = id;
			this.ruName = ruName;
			this.lo = lo;
			this.hi = hi;
			this.microVol = microVol;
			this.revert = revert;
			this.divYield = divYield;
		}

		public static Company byId(String id) {
			if (id == null) return null;
			for (Company c : values()) if (c.id.equalsIgnoreCase(id)) return c;
			return null;
		}
	}

	private static final String[] GOOD_NEWS = {
			"%s заключила контракт с Советом Деревень",
			"%s открыла новую шахту изумрудов",
			"%s отчиталась о рекордной прибыли",
			"Совет директоров %s объявил о расширении",
			"%s запустила филиал в Незере",
			"%s выкупила мелкого конкурента",
	};
	private static final String[] BAD_NEWS = {
			"В шахтах %s завелись криперы — добыча встала",
			"%s потеряла караван с товаром в Пустошах",
			"Аудиторы нашли ошибки в отчётах %s",
			"Главный инженер %s перешёл к конкурентам",
			"Пожар на складе %s уничтожил партию товара",
			"Совет Деревень оштрафовал %s за шум",
	};

	private static final Random RNG = new Random();

	public static StocksState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(StocksState.TYPE);
	}

	private static NbtCompound comps(NbtCompound root) {
		return Nbt2.sub(root, "comp");
	}

	/** Создаёт компании при первом запуске мира / мигрирует старые записи. */
	public static void ensureDefaults(MinecraftServer server) {
		StocksState st = get(server);
		NbtCompound comp = comps(st.data());
		boolean dirty = false;
		for (Company c : Company.values()) {
			NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
			boolean eDirty = false; // L11: флаг — на компанию, а не на весь цикл
			if (Nbt2.dbl(e, "price") <= 0) {
				double start = (c.lo + c.hi) / 2.0 * (1 + RNG.nextGaussian() * 0.03);
				e.putDouble("price", start);
				e.putIntArray("hist", new int[]{(int) Math.round(start * 100)});
				eDirty = true;
			}
			if (Nbt2.dbl(e, "anchor") <= 0) {
				e.putDouble("anchor", clampPrice(Nbt2.dbl(e, "price"), c));
				eDirty = true;
			}
			if (eDirty) {
				comp.put(c.id, e);
				dirty = true;
			}
		}
		NbtCompound meta = Nbt2.sub(st.data(), "meta");
		if (!meta.contains("lastDivDay")) {
			meta.putLong("lastDivDay", worldDay(server));
			dirty = true;
		}
		if (!meta.contains("nextNews") || meta.getLong("nextNews", 0L) <= 0) {
			meta.putLong("nextNews", server.getTicks() + NEWS_MIN_GAP);
			dirty = true;
		}
		if (dirty) {
			st.data().put("comp", comp);
			st.data().put("meta", meta);
			st.markDirty();
		}
	}

	private static long worldDay(MinecraftServer server) {
		return server.getOverworld().getTimeOfDay() / 24000L;
	}

	/**
	 * Пульс биржи — вызывается каждые 100 тиков (5 с):
	 * дивиденды по смене дня → микро-шаг цен → история → новости по расписанию.
	 */
	public static void tick(MinecraftServer server) {
		ensureDefaults(server);
		StocksState st = get(server);

		// --- дивиденды раз в игровые сутки ---
		NbtCompound meta = Nbt2.sub(st.data(), "meta");
		long lastDiv = meta.getLong("lastDivDay", 0L);
		long day = worldDay(server);
		if (day > lastDiv) {
			meta.putLong("lastDivDay", day);
			st.data().put("meta", meta);
			payDividends(server);
		}

		// --- микро-пульс: якорь блуждает в коридоре, цена тянется к якорю ---
		NbtCompound comp = comps(st.data());
		long pulse = meta.getLong("pulse", 0L) + 1;
		boolean takeHist = pulse % HIST_EVERY == 0;
		for (Company c : Company.values()) {
			NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
			double price = price(e);
			double anchor = anchor(e);
			// якорь: медленное блуждание внутри именного коридора
			anchor += RNG.nextGaussian() * 0.0012 * anchor;
			anchor = Math.max(c.lo, Math.min(c.hi, anchor));
			// цена: постоянное малое дыхание + мягкий возврат + редкие спайки
			double step = RNG.nextGaussian() * c.microVol * price
					+ (anchor - price) * c.revert;
			if (RNG.nextDouble() < SPIKE_CHANCE) {
				step += price * (RNG.nextBoolean() ? 1 : -1) * (0.03 + RNG.nextDouble() * 0.06);
			}
			price = clampPrice(price + step, c);
			e.putDouble("price", price);
			e.putDouble("anchor", anchor);
			if (takeHist) {
				int[] hist = e.getIntArray("hist").orElse(new int[0]);
				int[] nh = new int[Math.min(HISTORY_MAX, hist.length + 1)];
				System.arraycopy(hist, Math.max(0, hist.length - (HISTORY_MAX - 1)),
						nh, 0, Math.min(hist.length, HISTORY_MAX - 1));
				nh[nh.length - 1] = (int) Math.round(price * 100);
				e.putIntArray("hist", nh);
			}
			comp.put(c.id, e);
		}
		meta.putLong("pulse", pulse);
		st.data().put("comp", comp);
		st.data().put("meta", meta);
		st.markDirty();

		// --- новость по расписанию ---
		if (server.getTicks() >= meta.getLong("nextNews", Long.MAX_VALUE)) {
			fireNewsEvent(server);
			NbtCompound meta2 = Nbt2.sub(st.data(), "meta");
			meta2.putLong("nextNews", server.getTicks() + NEWS_MIN_GAP
					+ RNG.nextLong(NEWS_MAX_GAP - NEWS_MIN_GAP));
			st.data().put("meta", meta2);
			st.markDirty();
		}
	}

	private static double price(NbtCompound e) {
		return Nbt2.dbl(e, "price");
	}

	private static double anchor(NbtCompound e) {
		return Nbt2.dbl(e, "anchor");
	}

	private static double clampPrice(double p, Company c) {
		// цена может вылетать за коридор якоря, но не более чем на ~35%
		return Math.max(MIN_PRICE, Math.min(MAX_PRICE,
				Math.max(c.lo * 0.65, Math.min(c.hi * 1.35, p))));
	}

	/** Событие: у случайной компании шок цены + новость в ленту и в чат. */
	private static void fireNewsEvent(MinecraftServer server) {
		Company c = Company.values()[RNG.nextInt(Company.values().length)];
		boolean good = RNG.nextBoolean();
		int dir = good ? 1 : -1;
		double pct = 0.06 + RNG.nextDouble() * 0.14; // 6..20%
		String[] pool = good ? GOOD_NEWS : BAD_NEWS;
		String txt = String.format(pool[RNG.nextInt(pool.length)], c.ruName);

		StocksState st = get(server);
		NbtCompound comp = comps(st.data());
		NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
		double price = clampPrice(price(e) * (1.0 + dir * pct), c);
		e.putDouble("price", price);
		comp.put(c.id, e);
		st.data().put("comp", comp);

		NbtList news = st.data().getListOrEmpty("news");
		NbtCompound n = new NbtCompound();
		n.putString("id", c.id);
		n.putInt("dir", dir);
		n.putString("txt", txt);
		n.putDouble("pct", dir * pct * 100.0);
		n.putLong("t", server.getOverworld().getTimeOfDay());
		news.add(n);
		while (news.size() > NEWS_MAX) news.remove(0);
		st.data().put("news", news);
		st.markDirty();

		Text msg = Text.literal("[Биржа] " + txt + " (" + (dir > 0 ? "+" : "")
				+ String.format(java.util.Locale.ROOT, "%.0f", pct * 100.0) + "%)")
				.formatted(dir > 0 ? Formatting.GREEN : Formatting.RED);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			p.sendMessage(msg, false);
		}
	}

	/**
	 * Выплата дивидендов всем держателям (включая офлайн).
	 * ЖЁСТКАЯ защита: платим только за >0 акций; каждая выплата — в лог сервера.
	 */
	private static void payDividends(MinecraftServer server) {
		StocksState st = get(server);
		NbtCompound hold = Nbt2.sub(st.data(), "hold");
		for (String uuidStr : hold.getKeys()) {
			UUID uuid;
			try {
				uuid = UUID.fromString(uuidStr);
			} catch (IllegalArgumentException ex) {
				continue;
			}
			NbtCompound rec = hold.getCompound(uuidStr).orElseGet(NbtCompound::new);
			long total = 0;
			StringBuilder dbg = new StringBuilder();
			for (Company c : Company.values()) {
				int n = rec.getInt(c.id, 0);
				if (n <= 0) continue; // нет акций — нет дивидендов, точка
				double price = price(comps(st.data()).getCompound(c.id).orElseGet(NbtCompound::new));
				long cap = net.craftnet.config.CraftNetConfig.get().stocksDividendCapPerCompany;
				long pay = Math.min(cap, Math.round(price * n * c.divYield));
			if (pay <= 0) continue;
			MoneyManager.add(server, uuid, pay, "дивиденды " + c.id);
			StatsManager.bump(server, uuid, StatsManager.DIVIDENDS, pay);
			StatsManager.bump(server, uuid, StatsManager.STOCKS_EARN, pay);
			total += pay;
				if (dbg.length() > 0) dbg.append(", ");
				dbg.append(c.id).append("×").append(n).append("=+").append(pay);
			}
			if (total > 0) {
				net.craftnet.CraftNet.LOGGER.info("[CraftNet] Дивиденды (день {}) {}: +{} CR ({})",
						worldDay(server), uuidStr.substring(0, 8), total, dbg);
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
				if (p != null) {
					p.sendMessage(Text.translatable("craftnet.stocks.dividend", total), false);
					p.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
				}
			}
		}
	}

	public static double price(MinecraftServer server, String id) {
		return price(comps(get(server).data()).getCompound(id.toUpperCase(java.util.Locale.ROOT)).orElseGet(NbtCompound::new));
	}

	public static double prevPrice(MinecraftServer server, String id) {
		int[] hist = history(server, id);
		if (hist.length < 2) return price(server, id);
		return hist[hist.length - 2] / 100.0;
	}

	public static int[] history(MinecraftServer server, String id) {
		return comps(get(server).data()).getCompound(id.toUpperCase(java.util.Locale.ROOT)).orElseGet(NbtCompound::new)
				.getIntArray("hist").orElse(new int[0]);
	}

	public static int owned(MinecraftServer server, UUID player, String id) {
		return Nbt2.sub(get(server).data(), "hold")
				.getCompound(player.toString()).orElseGet(NbtCompound::new)
				.getInt(id.toUpperCase(java.util.Locale.ROOT), 0);
	}

	private static void setOwned(MinecraftServer server, UUID player, String id, int n) {
		StocksState st = get(server);
		NbtCompound hold = Nbt2.sub(st.data(), "hold");
		NbtCompound rec = hold.getCompound(player.toString()).orElseGet(NbtCompound::new);
		rec.putInt(id.toUpperCase(java.util.Locale.ROOT), Math.max(0, n));
		hold.put(player.toString(), rec);
		st.data().put("hold", hold);
		st.markDirty();
	}

	public static final int BUY_OK = 0;
	public static final int BUY_FAIL = 1;      // нет такой компании / нет денег / n вне рамок
	public static final int BUY_LIMIT = 2;     // лимит рыночной стоимости портфеля

	/**
	 * Покупка со спредом. Анти «пассивный ультрадоход»: после сделки рыночная
	 * стоимость всего портфеля не должна превышать stocksMaxExposure (конфиг).
	 * @return {@link #BUY_OK}/{@link #BUY_FAIL}/{@link #BUY_LIMIT}.
	 */
	public static int buy(MinecraftServer server, UUID player, String id, int n) {
		Company c = Company.byId(id);
		if (c == null || n <= 0 || n > 1000) return BUY_FAIL;
		double price = price(server, c.id);
		if (price <= 0) return BUY_FAIL;
		long cost = Math.max(1, Math.round(price * n * (1 + SPREAD)));
		long cap = net.craftnet.config.CraftNetConfig.get().stocksMaxExposure;
		if (portfolioValue(server, player) + cost > cap) return BUY_LIMIT;
		if (!MoneyManager.tryCharge(server, player, cost, "акции " + c.id)) return BUY_FAIL;
		setOwned(server, player, c.id, owned(server, player, c.id) + n);
		StatsManager.bump(server, player, StatsManager.STOCKS_BOUGHT, n);
		StatsManager.addXp(server, player, 2);
		return BUY_OK;
	}

	/** Рыночная стоимость всех акций игрока по текущим ценам, CR. */
	public static long portfolioValue(MinecraftServer server, UUID player) {
		long v = 0;
		for (Company c : Company.values()) {
			int n = owned(server, player, c.id);
			if (n > 0) v += Math.round(price(server, c.id) * n);
		}
		return v;
	}

	/** Продажа со спредом. @return выручка, или -1 при ошибке. */
	public static long sell(MinecraftServer server, UUID player, String id, int n) {
		Company c = Company.byId(id);
		if (c == null || n <= 0) return -1;
		int have = owned(server, player, c.id);
		if (have < n) return -1;
		double price = price(server, c.id);
		long gain = Math.max(1, Math.round(price * n * (1 - SPREAD)));
		setOwned(server, player, c.id, have - n);
		MoneyManager.add(server, player, gain, "акции " + c.id);
		StatsManager.bump(server, player, StatsManager.STOCKS_EARN, gain);
		return gain;
	}

	/** Строки для биржевого экрана телефона. */
	public static List<NbtCompound> clientRows(MinecraftServer server, UUID player) {
		List<NbtCompound> out = new ArrayList<>();
		for (Company c : Company.values()) {
			NbtCompound row = new NbtCompound();
			row.putString("id", c.id);
			row.putString("name", c.ruName);
			double p = price(server, c.id);
			double prev = prevPrice(server, c.id);
			row.putDouble("price", p);
			row.putDouble("delta", prev <= 0 ? 0 : (p - prev) / prev * 100.0);
			row.putIntArray("hist", history(server, c.id));
			row.putInt("owned", owned(server, player, c.id));
			row.putDouble("div", c.divYield * 100.0); // доходность %/день
			row.putInt("lo", (int) c.lo);
			row.putInt("hi", (int) c.hi);
			out.add(row);
		}
		return out;
	}

	/** Последние новости (свежие первыми) для тикера телефона. */
	public static NbtList clientNews(MinecraftServer server, int limit) {
		NbtList news = get(server).data().getListOrEmpty("news");
		NbtList out = new NbtList();
		int from = Math.max(0, news.size() - limit);
		for (int i = news.size() - 1; i >= from; i--) {
			NbtCompound n = news.get(i) instanceof NbtCompound c ? c : new NbtCompound();
			NbtCompound copy = new NbtCompound();
			copy.putString("txt", Nbt2.str(n, "txt"));
			copy.putInt("dir", n.getInt("dir", 0));
			copy.putDouble("pct", Nbt2.dbl(n, "pct"));
			out.add(copy);
		}
		return out;
	}
}
