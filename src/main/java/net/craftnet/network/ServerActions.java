package net.craftnet.network;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.jetbrains.annotations.Nullable;

import net.craftnet.econ.CasinoManager;
import net.craftnet.econ.MarketManager;
import net.craftnet.econ.MoneyManager;
import net.craftnet.econ.PriceManager;
import net.craftnet.econ.StocksManager;
import net.craftnet.item.BanknoteItem;
import net.craftnet.item.ModItems;
import net.craftnet.jobs.JobManager;
import net.craftnet.orders.OrderManager;
import net.craftnet.village.SignalLevel;
import net.craftnet.village.VillageManager;

/**
 * Серверная логика всех экранов CraftNet. Всё валидируется на сервере.
 */
public final class ServerActions {
	private ServerActions() {}

	private record OpenCtx(String screen, long station, String shopQ, int shopPage, int marketPage,
			String casinoQ, int casinoPage) {}

	private static final Map<UUID, OpenCtx> OPEN = new java.util.concurrent.ConcurrentHashMap<>();

	private static final int SHOP_PAGE_SIZE = 16;

	// ============================== открытие / синхронизация ==============================

	public static void openScreen(ServerPlayerEntity player, String screen, @Nullable BlockPos station) {
		OPEN.put(player.getUuid(), new OpenCtx(screen, station == null ? 0L : station.asLong(), "", 0, 0, "", 0));
		ServerPlayNetworking.send(player, new ModPackets.OpenScreenS2CPayload(screen, buildSync(player, screen)));
	}

	/** Периодическая пересинхронизация открытых экранов. */
	public static void tickOpenScreens(MinecraftServer server, long tick) {
		if (tick % 10 != 0) return;
		for (Map.Entry<UUID, OpenCtx> e : OPEN.entrySet()) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
			if (p == null) {
				OPEN.remove(e.getKey());
				continue;
			}
			OpenCtx ctx = e.getValue();
			ServerPlayNetworking.send(p, new ModPackets.ScreenSyncS2CPayload(ctx.screen(), buildSync(p, ctx.screen())));
		}
	}

	// ============================== взаимодействия с NPC ==============================

	/** ПКМ по сущности. Возвращает ActionResult для UseEntityCallback. */
	public static ActionResult handleEntityInteract(ServerPlayerEntity player, Entity entity) {
		// 1) доставка по работе (грузчик/курьер)
		if (JobManager.tryDeliver(player, entity)) return ActionResult.SUCCESS;

		var tags = entity.getCommandTags();
		if (tags.contains("craftnet:pvz")) {
			openScreen(player, "pvz", entity.getBlockPos());
			return ActionResult.SUCCESS;
		}
		if (tags.contains("craftnet:bank")) {
			openScreen(player, "bank", entity.getBlockPos());
			return ActionResult.SUCCESS;
		}
		if (tags.contains("craftnet:foreman")) {
			openScreen(player, "job:factory", entity.getBlockPos());
			return ActionResult.SUCCESS;
		}
		if (tags.contains("craftnet:barista")) {
			openScreen(player, "job:cafe", entity.getBlockPos());
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}

	// ============================== вход C2S ==============================

	public static void handle(ServerPlayerEntity player, ModPackets.ScreenActionC2SPayload payload) {
		String screen = payload.screen();
		String action = payload.action();
		NbtCompound args = payload.args() == null ? new NbtCompound() : payload.args();
		try {
			switch (action) {
				case "close" -> OPEN.remove(player.getUuid());
				case "open" -> openScreen(player, args.getString("screen", screen), null);
				default -> {
					switch (screen) {
						case "phone" -> handlePhone(player, action, args);
						case "pvz" -> handlePvz(player, action, args);
						case "bank" -> handleBank(player, action, args);
						case "job:factory" -> handleJob(player, "factory", action, args);
						case "job:cafe" -> handleJob(player, "cafe", action, args);
						default -> net.craftnet.CraftNet.LOGGER.debug("[CraftNet] Неизвестный экран: {}", screen);
					}
					// мгновенная пересинхронизация после действия
					OpenCtx ctx = OPEN.get(player.getUuid());
					if (ctx != null) {
						ServerPlayNetworking.send(player,
								new ModPackets.ScreenSyncS2CPayload(ctx.screen(), buildSync(player, ctx.screen())));
					}
				}
			}
		} catch (Throwable t) {
			net.craftnet.CraftNet.LOGGER.error("[CraftNet] Ошибка действия {}:{}: {}", screen, action, t.toString());
		}
	}

	// ============================== телефон ==============================

	private static void handlePhone(ServerPlayerEntity player, String action, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		OpenCtx ctx = OPEN.get(player.getUuid());
		switch (action) {
			case "query_shop" -> {
				if (ctx != null) {
					OPEN.put(player.getUuid(), new OpenCtx(ctx.screen(), ctx.station(),
							args.getString("q", ""), Math.max(0, args.getInt("page", 0)), ctx.marketPage(), ctx.casinoQ(), ctx.casinoPage()));
				}
			}
			case "buy" -> phoneBuy(player, args);
			case "market_query" -> {
				if (ctx != null) {
					OPEN.put(player.getUuid(), new OpenCtx(ctx.screen(), ctx.station(), ctx.shopQ(),
							ctx.shopPage(), Math.max(0, args.getInt("page", 0)), ctx.casinoQ(), ctx.casinoPage()));
				}
			}
			case "market_buy" -> marketBuy(player, args);
			case "casino_query" -> {
				if (ctx != null) {
					OPEN.put(player.getUuid(), new OpenCtx(ctx.screen(), ctx.station(), ctx.shopQ(),
							ctx.shopPage(), ctx.marketPage(),
							args.getString("q", ""), Math.max(0, args.getInt("page", 0))));
				}
			}
			case "casino_stake" -> {
				int rc2 = CasinoManager.addStake(server, player, args.getString("id", ""),
						args.getInt("count", 1));
				switch (rc2) {
					case 2 -> player.sendMessage(Text.translatable("craftnet.casino.kinds"), true);
					case 3 -> player.sendMessage(Text.translatable("craftnet.casino.full"), true);
					default -> { }
				}
			}
			case "casino_clear" -> CasinoManager.clearPool(server, player);
			case "casino_spin" -> {
				int rc = CasinoManager.spin(server, player, args.getString("target", ""));
				switch (rc) {
					case CasinoManager.SPIN_EMPTY -> player.sendMessage(
							Text.translatable("craftnet.casino.no_stake"), true);
					case CasinoManager.SPIN_BAD_TARGET -> player.sendMessage(
							Text.translatable("craftnet.casino.bad_target"), true);
					case CasinoManager.SPIN_LOW -> player.sendMessage(
							Text.translatable("craftnet.casino.low_chance"), true);
					default -> { }
				}
			}
			case "stock_buy" -> stockOp(player, args, true);
			case "stock_sell" -> stockOp(player, args, false);
			case "transfer" -> {
				if (ctx == null) return;
				if (VillageManager.signalFor(player).level().tier < SignalLevel.G2.tier) {
					player.sendMessage(Text.translatable("craftnet.need_signal"), false);
					return;
				}
				String to = args.getString("name", "");
				long amount = args.getLong("amount", 0L);
				if (amount <= 0) return;
				ServerPlayerEntity target = server.getPlayerManager().getPlayer(to);
				if (target == null) {
					player.sendMessage(Text.translatable("craftnet.bank.transfer.offline"), false);
					return;
				}
				if (MoneyManager.transfer(server, player.getUuid(), target.getUuid(), amount)) {
					player.sendMessage(Text.translatable("craftnet.bank.transfer.ok", amount, to), false);
					target.sendMessage(Text.translatable("craftnet.bank.transfer.got", amount, player.getName().getString()), false);
				} else {
					player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
				}
			}
			default -> {
			}
		}
	}

	private static void phoneBuy(ServerPlayerEntity player, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		String id = args.getString("id", "");
		int count = args.getInt("count", 1);
		if (count <= 0 || count > 640) return;
		Item item = Registries.ITEM.get(Identifier.tryParse(id));
		if (item == null || !PriceManager.tradeable(id)) {
			player.sendMessage(Text.translatable("craftnet.shop.unavailable"), false);
			return;
		}
		VillageManager.SignalInfo sig = VillageManager.signalFor(player);
		if (sig.level().tier < SignalLevel.G2.tier) {
			player.sendMessage(Text.translatable("craftnet.need_signal"), false);
			return;
		}
		var near = VillageManager.nearest(server, player.getBlockPos(), true);
		if (near.isEmpty()) {
			player.sendMessage(Text.translatable("craftnet.shop.no_village"), false);
			return;
		}
		long price = (long) PriceManager.buyPrice(id) * count;
		if (!MoneyManager.tryCharge(server, player.getUuid(), price, "магазин")) {
			player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
			return;
		}
		NbtCompound v = near.get();
		int dist = (int) Math.round(Math.sqrt(player.getBlockPos().getSquaredDistance(new BlockPos(
				net.craftnet.util.Nbt2.i(v, "cx"), net.craftnet.util.Nbt2.i(v, "cy"),
				net.craftnet.util.Nbt2.i(v, "cz")))));
		long ready = server.getOverworld().getTime()
				+ (long) OrderManager.BASE_TRAVEL_TICKS * sig.level().travelMultiplier()
				+ dist;
		ItemStack stack = new ItemStack(item, count);
		OrderManager.newDelivery(server, player.getUuid(), stack,
				net.craftnet.util.Nbt2.i(v, "cx"), net.craftnet.util.Nbt2.i(v, "cy"),
				net.craftnet.util.Nbt2.i(v, "cz"), net.craftnet.util.Nbt2.str(v, "name"), ready);
		player.sendMessage(Text.translatable("craftnet.shop.ordered",
				count, stack.getName().getString(), net.craftnet.util.Nbt2.str(v, "name"),
				Math.max(1, (ready - server.getOverworld().getTime()) / 20)), false);
	}

	private static void marketBuy(ServerPlayerEntity player, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		long lid = args.getLong("lid", -1L);
		if (lid < 0) return;
		VillageManager.SignalInfo sig = VillageManager.signalFor(player);
		if (sig.level().tier < SignalLevel.G2.tier) {
			player.sendMessage(Text.translatable("craftnet.need_signal"), false);
			return;
		}
		var near = VillageManager.nearest(server, player.getBlockPos(), true);
		if (near.isEmpty()) {
			player.sendMessage(Text.translatable("craftnet.shop.no_village"), false);
			return;
		}
		NbtCompound v = near.get();
		int dist = (int) Math.round(Math.sqrt(player.getBlockPos().getSquaredDistance(new BlockPos(
				net.craftnet.util.Nbt2.i(v, "cx"), net.craftnet.util.Nbt2.i(v, "cy"),
				net.craftnet.util.Nbt2.i(v, "cz")))));
		long ready = server.getOverworld().getTime()
				+ (long) OrderManager.BASE_TRAVEL_TICKS * sig.level().travelMultiplier() + dist;
		int code = MarketManager.buy(server, player, lid,
				net.craftnet.util.Nbt2.i(v, "cx"), net.craftnet.util.Nbt2.i(v, "cy"),
				net.craftnet.util.Nbt2.i(v, "cz"), net.craftnet.util.Nbt2.str(v, "name"), ready);
		switch (code) {
			case MarketManager.BUY_OK -> player.sendMessage(Text.translatable("craftnet.market.bought",
					net.craftnet.util.Nbt2.str(v, "name")), false);
			case MarketManager.BUY_GONE -> player.sendMessage(Text.translatable("craftnet.market.gone"), false);
			case MarketManager.BUY_OWN -> player.sendMessage(Text.translatable("craftnet.market.own"), false);
			default -> player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
		}
	}

	private static void stockOp(ServerPlayerEntity player, NbtCompound args, boolean buy) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		String id = args.getString("id", "");
		int n = args.getInt("n", 1);
		if (VillageManager.signalFor(player).level().tier < SignalLevel.G3.tier) {
			player.sendMessage(Text.translatable("craftnet.stocks.need_3g"), false);
			return;
		}
		if (buy) {
			if (!StocksManager.buy(server, player.getUuid(), id, n)) {
				player.sendMessage(Text.translatable("craftnet.stocks.fail_buy"), false);
			}
		} else {
			long gain = StocksManager.sell(server, player.getUuid(), id, n);
			if (gain < 0) {
				player.sendMessage(Text.translatable("craftnet.stocks.fail_sell"), false);
			} else {
				player.sendMessage(Text.translatable("craftnet.stocks.sold", n, id, gain), false);
			}
		}
	}

	// ============================== ПВЗ ==============================

	private static void handlePvz(ServerPlayerEntity player, String action, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		switch (action) {
			case "claim" -> {
				long id = args.getLong("id", -1L);
				if (id >= 0 && OrderManager.claim(server, player, id)) {
					player.sendMessage(Text.translatable("craftnet.pvz.claimed"), false);
				} else {
					player.sendMessage(Text.translatable("craftnet.pvz.claim_fail"), false);
				}
			}
			case "claim_all" -> {
				long now = server.getOverworld().getTime();
				int got = 0;
				for (NbtCompound order : OrderManager.deliveriesOf(server, player.getUuid(), now)) {
					if (order.getInt("readyNow", 0) == 1
							&& OrderManager.claim(server, player, order.getLong("id", -1L))) {
						got++;
					}
				}
				player.sendMessage(Text.translatable("craftnet.pvz.claimed_n", got), false);
			}
			case "sell" -> {
				String id = args.getString("id", "");
				int count = args.getInt("count", 1);
				Item item = Registries.ITEM.get(Identifier.tryParse(id));
				if (item == null || count <= 0 || !PriceManager.tradeable(id)) return;
				count = Math.min(count, JobManager.countInInventory(player, item));
				if (count <= 0) return;
				JobManager.removeFromInventory(player, item, count);
				long value = (long) PriceManager.sellPrice(id) * count;
				VillageManager.SignalInfo sig = VillageManager.signalFor(player);
				int mult = sig.level().travelMultiplier();
				if (mult <= 0) mult = 3;
				long ready = server.getOverworld().getTime() + (long) OrderManager.BASE_TRAVEL_TICKS * mult;
				String name = new ItemStack(item).getName().getString();
				OrderManager.newPayout(server, player.getUuid(), value, name + " ×" + count, ready);
				player.sendMessage(Text.translatable("craftnet.pvz.sold", count, name, value), false);
			}
			case "market_list" -> {
				String id = args.getString("id", "");
				int price = args.getInt("price", 0);
				var near2 = VillageManager.nearest(server, player.getBlockPos(), true);
				int vx = player.getBlockPos().getX(), vy = 64, vz = player.getBlockPos().getZ();
				String vname = "ПВЗ";
				if (near2.isPresent()) {
					NbtCompound v2 = near2.get();
					vx = net.craftnet.util.Nbt2.i(v2, "cx");
					vy = net.craftnet.util.Nbt2.i(v2, "cy");
					vz = net.craftnet.util.Nbt2.i(v2, "cz");
					vname = net.craftnet.util.Nbt2.str(v2, "name");
				}
				int rc = MarketManager.create(server, player, id, 64, price, vx, vy, vz, vname);
				switch (rc) {
					case 0 -> player.sendMessage(Text.translatable("craftnet.market.listed"), false);
					case 1 -> player.sendMessage(
							Text.translatable("craftnet.market.limit", MarketManager.MAX_PER_PLAYER), false);
					case 3 -> player.sendMessage(Text.translatable("craftnet.market.bad_price"), false);
					default -> player.sendMessage(Text.translatable("craftnet.market.no_item"), false);
				}
			}
			case "market_cancel" -> {
				int n = MarketManager.cancelMine(server, player);
				player.sendMessage(Text.translatable("craftnet.market.canceled", n), true);
			}
			case "loader_start" -> {
				if (JobManager.hasJob(server, player.getUuid())) {
					player.sendMessage(Text.translatable("craftnet.job.have_job"), false);
					return;
				}
				if (!JobManager.accept(player, JobManager.T_LOADER)) {
					player.sendMessage(Text.translatable("craftnet.job.no_offer"), false);
				}
			}
			default -> {
			}
		}
	}

	// ============================== банк ==============================

	private static final int[] DENOMS = {1000, 500, 100, 50, 10, 5, 1};

	private static void handleBank(ServerPlayerEntity player, String action, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		switch (action) {
			case "cashout" -> {
				int amount = args.getInt("amount", 0);
				if (amount <= 0) return;
				long bal = MoneyManager.balance(server, player.getUuid());
				amount = (int) Math.min(amount, Math.min(bal, 100000));
				if (amount <= 0) {
					player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
					return;
				}
				if (!MoneyManager.tryCharge(server, player.getUuid(), amount, "обналичивание")) return;
				int rest = amount;
				for (int d : DENOMS) {
					while (rest >= d) {
						player.getInventory().offerOrDrop(BanknoteItem.ofValue(d));
						rest -= d;
					}
				}
				player.sendMessage(Text.translatable("craftnet.bank.cashout", amount), false);
			}
			case "deposit_all" -> {
				long total = 0;
				var inv = player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					ItemStack s = inv.getStack(i);
					if (!s.isOf(ModItems.BANKNOTE)) continue;
					total += (long) BanknoteItem.valueOf(s) * s.getCount();
					s.setCount(0);
				}
				if (total > 0) {
					MoneyManager.add(server, player.getUuid(), total, "депозит");
					player.sendMessage(Text.translatable("craftnet.bank.deposit", total), false);
				} else {
					player.sendMessage(Text.translatable("craftnet.bank.no_banknotes"), true);
				}
			}
			default -> {
			}
		}
	}

	// ============================== работы ==============================

	private static void handleJob(ServerPlayerEntity player, String type, String action, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		switch (action) {
			case "accept" -> {
				if (JobManager.hasJob(server, player.getUuid())) {
					player.sendMessage(Text.translatable("craftnet.job.have_job"), false);
					return;
				}
				String real = args.getString("type", type);
				// оффер детерминированно пересоздаётся в accept → подделка невозможна
				if (!JobManager.accept(player, real)) {
					player.sendMessage(Text.translatable("craftnet.job.no_offer"), false);
				}
			}
			case "assem_click" -> JobManager.assemClick(player, type, args.getString("id", ""));
			case "handin" -> {
				int rc = JobManager.handin(player);
				if (rc == 2) {
					player.sendMessage(Text.translatable("craftnet.job.missing_items"), true);
				}
			}
			case "cancel" -> JobManager.cancel(server, player.getUuid(), false);
			default -> {
			}
		}
	}

	/** Компактный HUD-пуш всем онлайн-игрокам (сигнал, деревня-ish, gps, баланс). Раз в 40 тиков. */
	public static void pushHudSync(MinecraftServer server) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			VillageManager.SignalInfo sig = VillageManager.signalFor(p);
			NbtCompound d = new NbtCompound();
			d.putInt("sig", sig.level().tier);
			d.putString("village", sig.villageName());
			d.putInt("dist", sig.distance());
			d.putInt("off", sig.offlineVillage() ? 1 : 0);
			int y = p.getBlockPos().getY();
			boolean sky = p.getEntityWorld().isSkyVisible(p.getBlockPos());
			d.putInt("gps", (y >= 55 || sky) ? 1 : 0);
			d.putLong("bal", MoneyManager.balance(server, p.getUuid()));
			NbtCompound nav = JobManager.navTarget(server, p.getUuid());
			if (!nav.isEmpty()) d.put("jobNav", nav);
			ServerPlayNetworking.send(p, new ModPackets.HudSyncS2CPayload(d));
		}
	}

	// ============================== сборка синхронизации ==============================

	private static NbtCompound buildSync(ServerPlayerEntity player, String screen) {
		MinecraftServer server = player.getEntityWorld().getServer();
		NbtCompound d = new NbtCompound();
		if (server == null) return d;
		d.putLong("balance", MoneyManager.balance(server, player.getUuid()));
		switch (screen) {
			case "phone" -> fillPhoneSync(player, server, d);
			case "pvz" -> fillPvzSync(player, server, d);
			case "bank" -> fillBankSync(player, server, d);
			case "job:factory" -> fillJobSync(player, server, d, "factory");
			case "job:cafe" -> fillJobSync(player, server, d, "cafe");
			default -> {
			}
		}
		return d;
	}

	private static void fillPhoneSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d) {
		VillageManager.SignalInfo sig = VillageManager.signalFor(player);
		d.putInt("signal", sig.level().tier);
		d.putString("village", sig.villageName());
		d.putInt("dist", sig.distance());
		d.putInt("offVillage", sig.offlineVillage() ? 1 : 0);

		// GPS: под землёй без прямого неба спутники не ловят
		int y = player.getBlockPos().getY();
		boolean sky = player.getEntityWorld().isSkyVisible(player.getBlockPos());
		boolean gpsOk = y >= 55 || sky;
		d.putInt("gpsOk", gpsOk ? 1 : 0);
		d.putInt("seaY", player.getEntityWorld().getSeaLevel());

		NbtList villages = new NbtList();
		for (NbtCompound v : VillageManager.villagesForGps(player)) villages.add(v);
		d.put("villages", villages);

		NbtList stocks = new NbtList();
		for (NbtCompound row : StocksManager.clientRows(server, player.getUuid())) stocks.add(row);
		d.put("stocks", stocks);
		d.put("stNews", StocksManager.clientNews(server, 3));

		StringBuilder tx = new StringBuilder();
		for (String s : MoneyManager.txLog(server, player.getUuid())) {
			if (tx.length() > 0) tx.append('\n');
			tx.append(s);
		}
		d.putString("tx", tx.toString());

		// магазин — с учётом текущего запроса игрока
		OpenCtx ctx = OPEN.get(player.getUuid());
		String q = ctx == null ? "" : ctx.shopQ();
		int page = ctx == null ? 0 : ctx.shopPage();
		d.put("shop", buildShopPage(q, page));
		d.put("market", buildMarketPage(server, ctx == null ? 0 : ctx.marketPage()));
		// казино-апгрейдер: пул ставки, источник из инвентаря, каталог целей, последний спин
		d.put("casino", buildCasinoSync(player, server,
				ctx == null ? "" : ctx.casinoQ(), ctx == null ? 0 : ctx.casinoPage()));
	}

	private record ShopEntry(String id, String name, int buy, int sell, int max) {}

	/** Полный каталог строится один раз (реестр статичен), фильтрация — только по строке. */
	private static volatile List<ShopEntry> SHOP_CACHE;

	private static List<ShopEntry> shopCatalog() {
		List<ShopEntry> c = SHOP_CACHE;
		if (c == null) {
			synchronized (ServerActions.class) {
				c = SHOP_CACHE;
				if (c == null) {
					List<ShopEntry> all = new ArrayList<>();
					for (Identifier id : Registries.ITEM.getIds()) {
						String sid = id.toString();
						if (!PriceManager.tradeable(sid)) continue;
						Item item = Registries.ITEM.get(id);
						if (item == null) continue;
						all.add(new ShopEntry(sid, new ItemStack(item).getName().getString(),
								PriceManager.buyPrice(sid), PriceManager.sellPrice(sid),
								item.getDefaultStack().getMaxCount()));
					}
					all.sort(Comparator.comparing(ShopEntry::name));
					c = List.copyOf(all);
					SHOP_CACHE = c;
				}
			}
		}
		return c;
	}

	private static NbtCompound buildShopPage(String q, int page) {
		String needle = q == null ? "" : q.toLowerCase(java.util.Locale.ROOT);
		List<ShopEntry> all = new ArrayList<>();
		for (ShopEntry e : shopCatalog()) {
			if (!needle.isEmpty()
					&& !e.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
					&& !e.id().contains(needle)) {
				continue;
			}
			all.add(e);
		}
		int pages = Math.max(1, (int) Math.ceil(all.size() / (double) SHOP_PAGE_SIZE));
		page = Math.max(0, Math.min(page, pages - 1));
		NbtCompound shop = new NbtCompound();
		shop.putInt("page", page);
		shop.putInt("pages", pages);
		shop.putString("q", q == null ? "" : q);
		NbtList entries = new NbtList();
		int from = page * SHOP_PAGE_SIZE;
		for (int i = from; i < Math.min(from + SHOP_PAGE_SIZE, all.size()); i++) {
			ShopEntry e = all.get(i);
			NbtCompound c = new NbtCompound();
			c.putString("id", e.id());
			c.putString("name", e.name());
			c.putInt("buy", e.buy());
			c.putInt("sell", e.sell());
			c.putInt("max", e.max());
			entries.add(c);
		}
		shop.put("entries", entries);
		return shop;
	}

	private static NbtCompound buildCasinoSync(ServerPlayerEntity player, MinecraftServer server,
			String casinoQ, int casinoPage) {
		NbtCompound cz = new NbtCompound();
		NbtList staked = new NbtList();
		for (NbtCompound row : CasinoManager.poolRows(server, player.getUuid())) staked.add(row);
		cz.put("staked", staked);
		cz.putLong("stakeVal", CasinoManager.poolValue(server, player.getUuid()));
		// источник ставок: агрегированный инвентарь (только то, что можно оценить), до 12 видов
		Map<String, int[]> agg = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.HashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s2 = inv.getStack(i);
			if (s2.isEmpty()) continue;
			String id = Registries.ITEM.getId(s2.getItem()).toString();
			if (!PriceManager.tradeable(id)) continue;
			int price = PriceManager.sellPrice(id);
			if (price <= 0) continue;
			agg.computeIfAbsent(id, k -> new int[2]);
			agg.get(id)[0] += s2.getCount();
			agg.get(id)[1] = price;
			names.putIfAbsent(id, s2.getName().getString());
		}
		NbtList src = new NbtList();
		int rows = 0;
		for (Map.Entry<String, int[]> e : agg.entrySet()) {
			if (rows++ >= 12) break;
			NbtCompound c = new NbtCompound();
			c.putString("id", e.getKey());
			c.putString("name", names.get(e.getKey()));
			c.putInt("count", e.getValue()[0]);
			c.putInt("price", e.getValue()[1]);
			src.add(c);
		}
		cz.put("src", src);
		cz.put("targets", buildShopPage(casinoQ, casinoPage));
		NbtCompound last = CasinoManager.lastSpinView(server, player.getUuid());
		if (!last.isEmpty()) cz.put("last", last);
		return cz;
	}

	private static final int MARKET_PAGE_SIZE = 6;

	private static NbtCompound buildMarketPage(MinecraftServer server, int page) {
		NbtList rows = MarketManager.clientRows(server); // уже декодировано, свежие первыми
		NbtCompound d = new NbtCompound();
		int total = rows.size();
		int pages = Math.max(1, (int) Math.ceil(total / (double) MARKET_PAGE_SIZE));
		page = Math.max(0, Math.min(page, pages - 1));
		d.putInt("page", page);
		d.putInt("pages", pages);
		d.putInt("total", total);
		NbtList entries = new NbtList();
		int from = page * MARKET_PAGE_SIZE;
		for (int k = from; k < Math.min(from + MARKET_PAGE_SIZE, total); k++) {
			if (rows.get(k) instanceof NbtCompound c) entries.add(c);
		}
		d.put("entries", entries);
		return d;
	}

	private static void fillPvzSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d) {
		long now = server.getOverworld().getTime();
		NbtList claims = new NbtList();
		for (NbtCompound o : OrderManager.deliveriesOf(server, player.getUuid(), now)) claims.add(o);
		d.put("claims", claims);

		// продажа: агрегация инвентаря по предмету
		Map<String, int[]> agg = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.HashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			String id = Registries.ITEM.getId(s.getItem()).toString();
			if (!PriceManager.tradeable(id)) continue;
			int price = PriceManager.sellPrice(id);
			if (price <= 0) continue;
			agg.computeIfAbsent(id, k -> new int[2]);
			agg.get(id)[0] += s.getCount();
			agg.get(id)[1] = price;
			names.putIfAbsent(id, s.getName().getString());
		}
		NbtList sell = new NbtList();
		int rows = 0;
		for (Map.Entry<String, int[]> e : agg.entrySet()) {
			if (rows++ >= 24) break;
			NbtCompound c = new NbtCompound();
			c.putString("id", e.getKey());
			c.putString("name", names.get(e.getKey()));
			c.putInt("count", e.getValue()[0]);
			c.putInt("price", e.getValue()[1]);
			sell.add(c);
		}
		d.put("sell", sell);

		// работа грузчиком прямо с ПВЗ
		d.putInt("myLots", MarketManager.listingsOf(server, player.getUuid()));
		d.putInt("hasJob", JobManager.hasJob(server, player.getUuid()) ? 1 : 0);
		NbtCompound offer = JobManager.hasJob(server, player.getUuid()) ? null : JobManager.buildOffer(player, JobManager.T_LOADER);
		if (offer != null) d.put("loaderOffer", offer);
	}

	private static void fillBankSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d) {
		long notes = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(ModItems.BANKNOTE)) notes += (long) BanknoteItem.valueOf(s) * s.getCount();
		}
		d.putLong("banknotes", notes);
	}

	private static void fillJobSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d, String group) {
		d.putInt("hasJob", JobManager.hasJob(server, player.getUuid()) ? 1 : 0);
		NbtCompound job = JobManager.jobView(server, player.getUuid());
		if (!job.isEmpty()) {
			d.put("active", job);
			d.putLong("now", server.getOverworld().getTime());
			return;
		}
		if ("factory".equals(group)) {
			NbtCompound o = JobManager.buildOffer(player, JobManager.T_FACTORY);
			if (o != null) d.put("offerFactory", o);
			NbtCompound oo = JobManager.buildOffer(player, JobManager.T_FACTORY_ORDER);
			if (oo != null) d.put("offerFactoryOrder", oo);
		} else {
			NbtCompound oc = JobManager.buildOffer(player, JobManager.T_COOK);
			if (oc != null) d.put("offerCook", oc);
			NbtCompound od = JobManager.buildOffer(player, JobManager.T_COURIER);
			if (od != null) d.put("offerCourier", od);
		}
	}
}
