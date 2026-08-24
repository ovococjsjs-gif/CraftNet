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
import net.craftnet.util.Nbt2;

/**
 * Биржа CraftNet 2.0:
 *  - 5 компаний, случайное блуждание цены (пульс каждые 600 тиков), спред 2%;
 *  - дивиденды: раз в игровые сутки держателям капает доля от стоимости пакета
 *    (работает и для офлайн-игроков — баланс начисляется в PersistentState);
 *  - рыночные события: случайный шок цены ±6..20% с новостью в ленте,
 *    лента (последние 8) видна в телефоне, свежие — в чате всем онлайн.
 * История цен хранится в санти-кредитах (int = CR * 100).
 */
public final class StocksManager {
	private StocksManager() {}

	public static final double SPREAD = 0.02;
	private static final int HISTORY_MAX = 48;
	private static final int NEWS_MAX = 8;
	private static final double MIN_PRICE = 5.0;
	private static final double MAX_PRICE = 5000.0;
	private static final double NEWS_CHANCE = 0.10; // на каждый пульс цен

	public enum Company {
		REDR("REDR", "РедстоунКорп", 120.0, 0.030, 0.008),
		ENDT("ENDT", "ЭндерТех", 260.0, 0.045, 0.004),
		CRPR("CRPR", "КриперЭнерджи", 75.0, 0.055, 0.012),
		VLBK("VLBK", "ЖительБанк", 180.0, 0.020, 0.016),
		NFSH("NFSH", "НезерСталь", 340.0, 0.035, 0.006);

		public final String id;
		public final String ruName;
		public final double basePrice;
		public final double volatility;
		/** Дивидендная доходность: доля от рыночной цены пакета за игровые сутки. */
		public final double divYield;

		Company(String id, String ruName, double basePrice, double volatility, double divYield) {
			this.id = id;
			this.ruName = ruName;
			this.basePrice = basePrice;
			this.volatility = volatility;
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

	/** Создаёт компании при первом запуске мира. */
	public static void ensureDefaults(MinecraftServer server) {
		StocksState st = get(server);
		NbtCompound comp = comps(st.data());
		boolean dirty = false;
		for (Company c : Company.values()) {
			NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
			if (Nbt2.dbl(e, "price") <= 0) {
				double start = c.basePrice * (1 + RNG.nextGaussian() * 0.05);
				e.putDouble("price", start);
				e.putIntArray("hist", new int[]{(int) Math.round(start * 100)});
				comp.put(c.id, e);
				dirty = true;
			}
		}
		// метка последнего дивидендного дня — без ретро-выплат при первом запуске
		NbtCompound meta = Nbt2.sub(st.data(), "meta");
		if (!meta.contains("lastDivDay")) {
			meta.putLong("lastDivDay", worldDay(server));
			st.data().put("meta", meta);
			dirty = true;
		}
		if (dirty) {
			st.data().put("comp", comp);
			st.markDirty();
		}
	}

	private static long worldDay(MinecraftServer server) {
		return server.getOverworld().getTimeOfDay() / 24000L;
	}

	/**
	 * Тик биржи (вызывается раз в 600 тиков = 30 секунд):
	 * сначала дневные дивиденды, затем пульс цен и возможное событие.
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

		// --- пульс цен ---
		NbtCompound comp = comps(st.data());
		for (Company c : Company.values()) {
			NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
			double price = Nbt2.dbl(e, "price");
			double drift = RNG.nextGaussian() * 0.004;
			double shock = RNG.nextGaussian() * c.volatility;
			if (RNG.nextDouble() < 0.01) {
				shock += (RNG.nextBoolean() ? 1 : -1) * (0.10 + RNG.nextDouble() * 0.15);
			}
			price = clampPrice(price * (1.0 + drift + shock));
			e.putDouble("price", price);
			int[] hist = e.getIntArray("hist").orElse(new int[0]);
			int[] nh = new int[Math.min(HISTORY_MAX, hist.length + 1)];
			System.arraycopy(hist, Math.max(0, hist.length - (HISTORY_MAX - 1)),
					nh, 0, Math.min(hist.length, HISTORY_MAX - 1));
			nh[nh.length - 1] = (int) Math.round(price * 100);
			e.putIntArray("hist", nh);
			comp.put(c.id, e);
		}
		st.data().put("comp", comp);
		st.markDirty();

		// --- рыночное событие/новость ---
		if (RNG.nextDouble() < NEWS_CHANCE) {
			fireNewsEvent(server);
		}
	}

	private static double clampPrice(double p) {
		return Math.max(MIN_PRICE, Math.min(MAX_PRICE, p));
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
		double price = clampPrice(Nbt2.dbl(e, "price") * (1.0 + dir * pct));
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

		// чат всем онлайн
		Text msg = Text.literal("[Биржа] " + txt + " (" + (dir > 0 ? "+" : "")
				+ String.format(java.util.Locale.ROOT, "%.0f", pct * 100.0) + "%)")
				.formatted(dir > 0 ? Formatting.GREEN : Formatting.RED);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			p.sendMessage(msg, false);
		}
	}

	/** Выплата дивидендов всем держателям (включая офлайн). */
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
			for (Company c : Company.values()) {
				int n = rec.getInt(c.id, 0);
				if (n <= 0) continue;
				double price = Nbt2.dbl(comps(st.data()).getCompound(c.id).orElseGet(NbtCompound::new), "price");
				long pay = Math.round(price * n * c.divYield);
				if (pay <= 0) continue;
				MoneyManager.add(server, uuid, pay, "дивиденды " + c.id);
				total += pay;
			}
			if (total > 0) {
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
				if (p != null) {
					p.sendMessage(Text.translatable("craftnet.stocks.dividend", total), false);
					p.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
				}
			}
		}
	}

	public static double price(MinecraftServer server, String id) {
		return Nbt2.dbl(comps(get(server).data()).getCompound(id.toUpperCase()).orElseGet(NbtCompound::new), "price");
	}

	public static double prevPrice(MinecraftServer server, String id) {
		int[] hist = history(server, id);
		if (hist.length < 2) return price(server, id);
		return hist[hist.length - 2] / 100.0;
	}

	public static int[] history(MinecraftServer server, String id) {
		return comps(get(server).data()).getCompound(id.toUpperCase()).orElseGet(NbtCompound::new)
				.getIntArray("hist").orElse(new int[0]);
	}

	public static int owned(MinecraftServer server, UUID player, String id) {
		return Nbt2.sub(get(server).data(), "hold")
				.getCompound(player.toString()).orElseGet(NbtCompound::new)
				.getInt(id.toUpperCase(), 0);
	}

	private static void setOwned(MinecraftServer server, UUID player, String id, int n) {
		StocksState st = get(server);
		NbtCompound hold = Nbt2.sub(st.data(), "hold");
		NbtCompound rec = hold.getCompound(player.toString()).orElseGet(NbtCompound::new);
		rec.putInt(id.toUpperCase(), Math.max(0, n));
		hold.put(player.toString(), rec);
		st.data().put("hold", hold);
		st.markDirty();
	}

	/** Покупка со спредом. @return true при успехе. */
	public static boolean buy(MinecraftServer server, UUID player, String id, int n) {
		Company c = Company.byId(id);
		if (c == null || n <= 0 || n > 1000) return false;
		double price = price(server, c.id);
		if (price <= 0) return false;
		long cost = Math.max(1, Math.round(price * n * (1 + SPREAD)));
		if (!MoneyManager.tryCharge(server, player, cost, "акции " + c.id)) return false;
		setOwned(server, player, c.id, owned(server, player, c.id) + n);
		return true;
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
