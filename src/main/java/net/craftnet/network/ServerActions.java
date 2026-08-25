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
import net.craftnet.stats.StatsManager;
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

	/** Анти-макрос: последний тик действия по игроку (мутации ≤ 1 раза в 0.1 с). */
	private static final Map<UUID, Integer> LAST_ACT = new java.util.concurrent.ConcurrentHashMap<>();

	// ровно столько, сколько реально рисуют экраны телефона — иначе пейджер
	// «перепрыгивал» невидимые позиции (было 16 при 6 видимых строках)
	private static final int SHOP_PAGE_SIZE = 6;

	// ============================== открытие / синхронизация ==============================

	public static void openScreen(ServerPlayerEntity player, String screen, @Nullable BlockPos station) {
		// H3: телефон открывается только при наличии смартфона в инвентаре (анти-пакет)
		if ("phone".equals(screen) && !hasPhone(player)) {
			player.sendMessage(Text.translatable("craftnet.phone.missing"), true);
			return;
		}
		OPEN.put(player.getUuid(), new OpenCtx(screen, station == null ? 0L : station.asLong(), "", 0, 0, "", 0));
		ServerPlayNetworking.send(player, new ModPackets.OpenScreenS2CPayload(screen, buildSync(player, screen)));
	}

	/** H3: есть ли смартфон CraftNet в инвентаре. */
	private static boolean hasPhone(ServerPlayerEntity player) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(ModItems.PHONE)) return true;
		}
		return false;
	}

	/**
	 * H2: анти-чит — действия стоек разрешены только рядом с той стойкой,
	 * через которую экран был открыт (ПКМ по NPC/блоку ставит ctx.station).
	 */
	private static boolean nearStation(ServerPlayerEntity player, double maxDist) {
		OpenCtx ctx = OPEN.get(player.getUuid());
		if (ctx == null || ctx.station() == 0L) return false;
		return BlockPos.fromLong(ctx.station()).getSquaredDistance(player.getBlockPos()) <= maxDist * maxDist;
	}

	/** Периодическая пересинхронизация открытых экранов (интервал — из конфига). */
	public static void tickOpenScreens(MinecraftServer server, long tick) {
		if (tick % net.craftnet.config.CraftNetConfig.get().screenSyncTicks != 0) return;
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
				case "close" -> {
					OPEN.remove(player.getUuid());
					LAST_ACT.remove(player.getUuid());
				}
				case "open" -> {
					// H2: whitelist — кастомным пакетом разрешено открывать только телефон;
					// стоечные экраны (pvz/bank/tower/job) открываются лишь ПКМ по станции
					String target = args.getString("screen", screen);
					if (!"phone".equals(target)) {
						net.craftnet.CraftNet.LOGGER.debug("[CraftNet] Отклонён open '{}' от {}",
								target, player.getName().getString());
						return;
					}
					openScreen(player, "phone", null);
				}
				default -> {
					// анти-макрос: не чаще одного действия за 2 тика (0.1 с) на игрока;
					// после рестарта сервера (отрицательная разница) — пропускаем
					MinecraftServer srv = player.getEntityWorld().getServer();
					if (srv != null) {
						int tnow = srv.getTicks();
						Integer last = LAST_ACT.put(player.getUuid(), tnow);
						if (last != null && tnow >= last && tnow - last < 2) return;
					}
					switch (screen) {
						case "phone" -> handlePhone(player, action, args);
						case "pvz" -> handlePvz(player, action, args);
						case "bank" -> handleBank(player, action, args);
						case "tower" -> handleTower(player, action, args);
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
		// H3: действия телефона без смартфона в инвентаре — только от пакет-ботов
		if (!hasPhone(player)) return;
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
			case "casino_stake_multi" -> {
				// пакетный добор ставки (чипы x2/30%…): одно действие — много позиций
				int worst = 0;
				for (var el : args.getListOrEmpty("items")) {
					if (!(el instanceof NbtCompound it)) continue;
					int rc3 = CasinoManager.addStake(server, player,
							it.getString("id", ""), it.getInt("count", 1));
					if (rc3 > worst) worst = rc3;
				}
				switch (worst) {
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
					StatsManager.bump(server, player.getUuid(), StatsManager.TRANSFERS_SENT, amount);
					StatsManager.bump(server, target.getUuid(), StatsManager.TRANSFERS_GOT, amount);
					StatsManager.addXp(server, player.getUuid(), 2);
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
		// «умная вышка» ур.4: −5% всем покрытым абонентам
		long price = Math.round(PriceManager.buyPrice(id) * count * VillageManager.shopPriceFactor(sig));
		if (!MoneyManager.tryCharge(server, player.getUuid(), price, "магазин")) {
			player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
			return;
		}
		// обслуживающая деревня — точка доставки; логистика ур.3-4 ускоряет путь
		int dist = sig.distance();
		long ready = server.getOverworld().getTime()
				+ (long) OrderManager.BASE_TRAVEL_TICKS * VillageManager.travelMultiplier(sig)
				+ dist;
		ItemStack stack = new ItemStack(item, count);
		OrderManager.newDelivery(server, player.getUuid(), stack,
				sig.vx(), sig.vy(), sig.vz(), sig.villageName(), ready);
		StatsManager.bump(server, player.getUuid(), StatsManager.ORDERS_BOUGHT, 1);
		StatsManager.addXp(server, player.getUuid(), 3);
		player.sendMessage(Text.translatable("craftnet.shop.ordered",
				count, stack.getName().getString(), sig.villageName(),
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
		int dist = sig.distance();
		long ready = server.getOverworld().getTime()
				+ (long) OrderManager.BASE_TRAVEL_TICKS * VillageManager.travelMultiplier(sig) + dist;
		int code = MarketManager.buy(server, player, lid,
				sig.vx(), sig.vy(), sig.vz(), sig.villageName(), ready);
		switch (code) {
			case MarketManager.BUY_OK -> player.sendMessage(Text.translatable("craftnet.market.bought",
					sig.villageName()), false);
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
		// торговля: 3G+, либо 2G в покрытии «умной вышки» ур.4
		if (!VillageManager.stocksAllowed(VillageManager.signalFor(player))) {
			player.sendMessage(Text.translatable("craftnet.stocks.need_3g"), false);
			return;
		}
		if (buy) {
			int rc = StocksManager.buy(server, player.getUuid(), id, n);
			if (rc == StocksManager.BUY_LIMIT) {
				player.sendMessage(Text.translatable("craftnet.stocks.limit",
						net.craftnet.config.CraftNetConfig.get().stocksMaxExposure), false);
			} else if (rc != StocksManager.BUY_OK) {
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
		// H2: экран ПВЗ открывается ПКМ по стойке; действия — только рядом с ней (24 м)
		if (!nearStation(player, 24.0)) {
			player.sendMessage(Text.translatable("craftnet.too_far"), true);
			return;
		}
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
				// sellPrice 0 = предмет не принимается (хлам нижней ступени) — и выплата
				// не создаётся, и в дельту баланса не попадает
				if (item == null || count <= 0 || PriceManager.sellPrice(id) <= 0) return;
				// H1: рабочее имущество (◆ материалы цеха/кафе, грузы) продаже не подлежит
				count = Math.min(count, JobManager.countSellable(player, item));
				if (count <= 0) return;
				JobManager.removeSellable(player, item, count);
				long value = (long) PriceManager.sellPrice(id) * count;
				VillageManager.SignalInfo sig = VillageManager.signalFor(player);
				int mult = VillageManager.travelMultiplier(sig);
				if (mult <= 0) mult = 3;
			long now0 = server.getOverworld().getTime();
			long ready = now0 + (long) OrderManager.BASE_TRAVEL_TICKS * mult;
			String name = new ItemStack(item).getName().getString();
			OrderManager.newPayout(server, player.getUuid(), value, name + " ×" + count, ready);
			StatsManager.bump(server, player.getUuid(), StatsManager.ITEMS_SOLD, count);
			StatsManager.addXp(server, player.getUuid(), Math.max(1, Math.min(10, value / 100)));
			long etaSec = (ready - now0) / 20;
			player.sendMessage(Text.translatable("craftnet.pvz.sold", count, name, value, etaSec), false);
			}
			case "market_list" -> {
				String id = args.getString("id", "");
				int price = args.getInt("price", 0);
				int lotCount = Math.max(1, Math.min(64, args.getInt("count", 64)));
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
				int rc = MarketManager.create(server, player, id, lotCount, price, vx, vy, vz, vname);
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
			case "job_cancel" -> {
				// M2: отмена задания кнопкой из ПВЗ (штраф стандартный)
				if (!JobManager.hasJob(server, player.getUuid())) return;
				JobManager.cancel(server, player.getUuid(), false);
			}
			case "loader_start" -> {
				if (JobManager.hasJob(server, player.getUuid())) {
					player.sendMessage(Text.translatable("craftnet.job.have_job"), false);
					return;
				}
				if (!JobManager.accept(player, JobManager.T_LOADER, args.getLong("win", -1L))
						&& !JobManager.onWindowCooldown(server, player.getUuid(), JobManager.T_LOADER)) {
					// при кулдауне accept() уже отправил внятное сообщение
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
		// H2: обналичивание/депозит — только рядом со стойкой банка (24 м)
		if (!nearStation(player, 24.0)) {
			player.sendMessage(Text.translatable("craftnet.too_far"), true);
			return;
		}
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
				// M4: выдаём банкноты стаками до 64, а не по одной в стак
				int rest = amount;
				for (int d : DENOMS) {
					int notes = rest / d;
					rest -= notes * d;
					while (notes > 0) {
						int n = Math.min(notes, 64);
						player.getInventory().offerOrDrop(BanknoteItem.ofValue(d, n));
						notes -= n;
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

	// ============================== вышка ==============================

	private static void handleTower(ServerPlayerEntity player, String action, NbtCompound args) {
		if (!"upgrade".equals(action)) return;
		OpenCtx ctx = OPEN.get(player.getUuid());
		if (ctx == null || ctx.station() == 0L) return;
		BlockPos pos = BlockPos.fromLong(ctx.station());
		// анти-чит: апгрейд только «в упор» — экран открывается ПКМ по ядру
		if (pos.getSquaredDistance(player.getBlockPos()) > 16 * 16) return;
		VillageManager.upgradeTower(player, pos);
	}

	// ============================== работы ==============================

	private static void handleJob(ServerPlayerEntity player, String type, String action, NbtCompound args) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		// H2: экран работ открывается ПКМ по NPC; действия — только рядом (24 м).
		// (отмена издалека — командой /craftnet job cancel)
		if (!nearStation(player, 24.0)) {
			player.sendMessage(Text.translatable("craftnet.too_far"), true);
			return;
		}
		switch (action) {
			case "accept" -> {
				if (JobManager.hasJob(server, player.getUuid())) {
					player.sendMessage(Text.translatable("craftnet.job.have_job"), false);
					return;
				}
				String real = args.getString("type", type);
				// оффер детерминированно пересоздаётся в accept → подделка невозможна;
				// win — окно показанного слепка (M7), принимаем текущее/предыдущее
				if (!JobManager.accept(player, real, args.getLong("win", -1L))
						&& !JobManager.onWindowCooldown(server, player.getUuid(), real)) {
					// при кулдауне accept() уже отправил внятное сообщение
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
			d.putInt("tlv", sig.towerLevel());
			int y = p.getBlockPos().getY();
			boolean sky = p.getEntityWorld().isSkyVisible(p.getBlockPos());
			d.putInt("gps", (y >= 55 || sky) ? 1 : 0);
			long bal = MoneyManager.balance(server, p.getUuid());
			d.putLong("bal", bal);
			// «Миллионер» и будущие balance-driven ачивки живут off-HUD-пульса
			StatsManager.set(server, p.getUuid(), StatsManager.BALANCE_NOW, bal);
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
			case "tower" -> fillTowerSync(player, server, d);
			case "job:factory" -> fillJobSync(player, server, d, "factory");
			case "job:cafe" -> fillJobSync(player, server, d, "cafe");
			default -> {
			}
		}
		return d;
	}

	private static void fillTowerSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d) {
		OpenCtx ctx = OPEN.get(player.getUuid());
		BlockPos pos = ctx != null && ctx.station() != 0L
				? BlockPos.fromLong(ctx.station()) : player.getBlockPos();
		d.put("tower", VillageManager.towerView(player, pos));
	}

	private static void fillPhoneSync(ServerPlayerEntity player, MinecraftServer server, NbtCompound d) {
		VillageManager.SignalInfo sig = VillageManager.signalFor(player);
		d.putInt("signal", sig.level().tier);
		d.putString("village", sig.villageName());
		d.putInt("dist", sig.distance());
		d.putInt("offVillage", sig.offlineVillage() ? 1 : 0);
		d.putInt("tlv", sig.towerLevel());
		// скидка «умной вышки» для отображения в магазине (сервер считает точно так же)
		d.putInt("shopDisc", sig.towerLevel() >= VillageManager.LVL_MAX
				&& sig.level().tier >= SignalLevel.G2.tier
				? (int) Math.round(VillageManager.SMART_TOWER_DISCOUNT * 100) : 0);

		// GPS: под землёй без прямого неба спутники не ловят
		int y = player.getBlockPos().getY();
		boolean sky = player.getEntityWorld().isSkyVisible(player.getBlockPos());
		boolean gpsOk = y >= 55 || sky;
		d.putInt("gpsOk", gpsOk ? 1 : 0);
		d.putInt("seaY", player.getEntityWorld().getSeaLevel());

		NbtList villages = new NbtList();
		for (NbtCompound v : VillageManager.villagesForGps(player)) villages.add(v);
		d.put("villages", villages);

		// маркер активной доставки для GPS-вкладки
		NbtCompound nav = JobManager.navTarget(server, player.getUuid());
		if (!nav.isEmpty()) d.put("jobNav", nav);

		// входящие (баннер на домашнем экране): посылки в пути + ожидаемые выплаты
		long now = server.getOverworld().getTime();
		int parcels = 0;
		long parcelEta = Long.MAX_VALUE;
		for (NbtCompound o2 : OrderManager.deliveriesOf(server, player.getUuid(), now)) {
			parcels++;
			parcelEta = Math.min(parcelEta,
					o2.getInt("readyNow", 0) == 1 ? 0 : o2.getLong("etaSec", 0L));
		}
		long payN = 0, payS = 0, payEta = Long.MAX_VALUE;
		for (NbtCompound p2 : OrderManager.payoutsOf(server, player.getUuid(), now)) {
			payN++;
			payS += p2.getLong("payout", 0L);
			payEta = Math.min(payEta, p2.getLong("etaSec", 0L));
		}
		NbtCompound inb = new NbtCompound();
		inb.putInt("parcels", parcels);
		inb.putLong("parcelEta", parcelEta == Long.MAX_VALUE ? -1 : parcelEta);
		inb.putLong("payN", payN);
		inb.putLong("paySum", payS);
		inb.putLong("payEta", payEta == Long.MAX_VALUE ? -1 : payEta);
		d.put("inbound", inb);

		NbtList stocks = new NbtList();
		for (NbtCompound row : StocksManager.clientRows(server, player.getUuid())) stocks.add(row);
		d.put("stocks", stocks);
		d.put("stNews", StocksManager.clientNews(server, 3));
		d.putLong("stMaxExp", net.craftnet.config.CraftNetConfig.get().stocksMaxExposure);

		StringBuilder tx = new StringBuilder();
		for (String s : MoneyManager.txLog(server, player.getUuid())) {
			if (tx.length() > 0) tx.append('\n');
			tx.append(s);
		}
		d.putString("tx", tx.toString());

		// UX: имена онлайн-игроков для подсказок получателя перевода (себя не шлём)
		StringBuilder on = new StringBuilder();
		for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
			if (pl.getUuid().equals(player.getUuid())) continue;
			if (on.length() > 0) on.append('\n');
			on.append(pl.getName().getString());
		}
		d.putString("online", on.toString());

		// магазин — с учётом текущего запроса игрока
		OpenCtx ctx = OPEN.get(player.getUuid());
		String q = ctx == null ? "" : ctx.shopQ();
		int page = ctx == null ? 0 : ctx.shopPage();
		d.put("shop", buildShopPage(q, page));
		d.put("market", buildMarketPage(server, ctx == null ? 0 : ctx.marketPage()));
		// казино-апгрейдер: пул ставки, источник из инвентаря, каталог целей, последний спин
		d.put("casino", buildCasinoSync(player, server,
				ctx == null ? "" : ctx.casinoQ(), ctx == null ? 0 : ctx.casinoPage()));
		// профиль: статистика, опыт, достижения
		d.put("profile", StatsManager.profileView(server, player));
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

	/** Пресеты популярных целей апгрейдера (быстрый выбор без каталога). */
	private static final String[] CASINO_PRESETS = {
			"minecraft:diamond", "minecraft:netherite_ingot", "minecraft:diamond_sword"};

	private static NbtCompound buildCasinoSync(ServerPlayerEntity player, MinecraftServer server,
			String casinoQ, int casinoPage) {
		NbtCompound cz = new NbtCompound();
		// пресеты целей (только реально торгуемые)
		NbtList presets = new NbtList();
		for (String pid : CASINO_PRESETS) {
			int bp = PriceManager.buyPrice(pid);
			if (!PriceManager.tradeable(pid) || bp <= 0) continue;
			Item pi = Registries.ITEM.get(Identifier.tryParse(pid));
			if (pi == null) continue;
			NbtCompound c = new NbtCompound();
			c.putString("id", pid);
			c.putString("name", new ItemStack(pi).getName().getString());
			c.putInt("buy", bp);
			presets.add(c);
		}
		cz.put("presets", presets);
		NbtList staked = new NbtList();
		for (NbtCompound row : CasinoManager.poolRows(server, player.getUuid())) staked.add(row);
		cz.put("staked", staked);
		cz.putLong("stakeVal", CasinoManager.poolValue(server, player.getUuid()));
		// параметры шанса — клиент считает «живой» шанс той же формулой, что и спин
		cz.putInt("bpMin", CasinoManager.BP_MIN);
		cz.putInt("bpMax", CasinoManager.BP_MAX);
		cz.putLong("rtpPromille", Math.round(
				net.craftnet.config.CraftNetConfig.get().casinoRtpPct * 10));
		// источник ставок: агрегированный инвентарь (только то, что можно оценить), до 12 видов
		Map<String, int[]> agg = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.HashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s2 = inv.getStack(i);
			if (s2.isEmpty() || JobManager.isJobTagged(s2)) continue;
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

		// входящие выплаты за проданное (с ETA и прогрессом)
		NbtList pays = new NbtList();
		long sum = 0;
		for (NbtCompound p : OrderManager.payoutsOf(server, player.getUuid(), now)) {
			pays.add(p);
			sum += p.getLong("payout", 0L);
		}
		d.put("payouts", pays);
		d.putLong("payoutSum", sum);

		// продажа: агрегация инвентаря по предмету (без ◆-рабочего имущества)
		Map<String, int[]> agg = new java.util.LinkedHashMap<>();
		Map<String, String> names = new java.util.HashMap<>();
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || JobManager.isJobTagged(s)) continue;
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
		boolean hasJob = JobManager.hasJob(server, player.getUuid());
		d.putInt("hasJob", hasJob ? 1 : 0);
		if (hasJob) {
			// M2: какая смена висит — для строки статуса и кнопки отмены
			d.putString("jobType", net.craftnet.util.Nbt2.str(
					JobManager.jobView(server, player.getUuid()), "type"));
		} else {
			NbtCompound offer = JobManager.buildOffer(player, JobManager.T_LOADER);
			if (offer != null) {
				if (JobManager.onWindowCooldown(server, player.getUuid(), JobManager.T_LOADER)) {
					offer.putInt("cool", 1);
				}
				d.put("loaderOffer", offer);
			}
		}
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
			putOffer(player, server, d, "offerFactory", JobManager.T_FACTORY);
			putOffer(player, server, d, "offerFactoryOrder", JobManager.T_FACTORY_ORDER);
		} else {
			putOffer(player, server, d, "offerCook", JobManager.T_COOK);
			putOffer(player, server, d, "offerCourier", JobManager.T_COURIER);
		}
	}

	/** Оффер в синк; cool=1 — тип уже отработан в этом окне, клиент гасит кнопку. */
	private static void putOffer(ServerPlayerEntity player, MinecraftServer server,
			NbtCompound d, String key, String type) {
		NbtCompound o = JobManager.buildOffer(player, type);
		if (o == null) return;
		if (JobManager.onWindowCooldown(server, player.getUuid(), type)) o.putInt("cool", 1);
		d.put(key, o);
	}
}
