package net.craftnet.econ;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;

import net.craftnet.state.StocksState;
import net.craftnet.util.Nbt2;

/**
 * Биржа CraftNet: 5 компаний, случайное блуждание цены, спред 2%.
 * История цен хранится в санти-кредитах (int = CR * 100).
 */
public final class StocksManager {
	private StocksManager() {}

	public static final double SPREAD = 0.02;
	private static final int HISTORY_MAX = 48;
	private static final double MIN_PRICE = 5.0;
	private static final double MAX_PRICE = 5000.0;

	public enum Company {
		REDR("REDR", "РедстоунКорп", 120.0, 0.030),
		ENDT("ENDT", "ЭндерТех", 260.0, 0.045),
		CRPR("CRPR", "КриперЭнерджи", 75.0, 0.055),
		VLBK("VLBK", "ЖительБанк", 180.0, 0.020),
		NFSH("NFSH", "НезерСталь", 340.0, 0.035);

		public final String id;
		public final String ruName;
		public final double basePrice;
		public final double volatility;

		Company(String id, String ruName, double basePrice, double volatility) {
			this.id = id;
			this.ruName = ruName;
			this.basePrice = basePrice;
			this.volatility = volatility;
		}

		public static Company byId(String id) {
			if (id == null) return null;
			for (Company c : values()) if (c.id.equalsIgnoreCase(id)) return c;
			return null;
		}
	}

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
		if (dirty) {
			st.data().put("comp", comp);
			st.markDirty();
		}
	}

	/** Тик генерации цены (вызывается раз в 600 тиков = 30 секунд). */
	public static void tick(MinecraftServer server) {
		ensureDefaults(server);
		StocksState st = get(server);
		NbtCompound comp = comps(st.data());
		for (Company c : Company.values()) {
			NbtCompound e = comp.getCompound(c.id).orElseGet(NbtCompound::new);
			double price = Nbt2.dbl(e, "price");
			double drift = RNG.nextGaussian() * 0.004;
			double shock = RNG.nextGaussian() * c.volatility;
			if (RNG.nextDouble() < 0.01) {
				shock += (RNG.nextBoolean() ? 1 : -1) * (0.10 + RNG.nextDouble() * 0.15);
			}
			price = Math.max(MIN_PRICE, Math.min(MAX_PRICE, price * (1.0 + drift + shock)));
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
			out.add(row);
		}
		return out;
	}
}
