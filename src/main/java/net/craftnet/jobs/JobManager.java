package net.craftnet.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import net.craftnet.block.ModBlocks;
import net.craftnet.component.ModComponents;
import net.craftnet.config.CraftNetConfig;
import net.craftnet.econ.MoneyManager;
import net.craftnet.econ.PriceManager;
import net.craftnet.item.ModItems;
import net.craftnet.state.JobsState;
import net.craftnet.stats.StatsManager;
import net.craftnet.util.Nbt2;
import net.craftnet.village.VillageManager;

/**
 * Рабочие места 2.0. Лестница оплаты: завод > грузчик > повар > курьер
 * (окна строго не пересекаются: 195-285 / 150-190 / 110-145 / 25-50 —
 * все цифры лестниц вынесены в {@code config/craftnet.json}, блок job*).
 *
 * Темп фиксируется ОКНОМ ОФФЕРОВ: каждый тип смены отрабатывается не чаще
 * одного раза за {@code jobWindowCooldown} окон (дефолт 1 = одна смена типа
 * на 10-минутное окно; завершение, отмена и таймаут — всё считается сменой).
 * Именно этим экономика держит якорь «~1700 CR/ч активной игры» (ECONOMY.md):
 * даже играя ВСЕ пять работ подряд, игрок получает ≤ ~800 CR за два окна.
 *
 * Завод: на выбор — мини-игра «сборка по схеме» ИЛИ цеховой заказ (крафт).
 * Кафе: повар = крафт-заказ блюд (складываем баристе), курьер = доставка
 * пакетов жителям. Повар и курьер — полностью разные работы.
 *
 * Крафт-заказ: сервер выдаёт ПОМЕЧЕННЫЕ (компонент job_tag + имя «◆»)
 * материалы ровно по рецептам; игрок крафтит на верстаке и сдаёт целевые
 * предметы кнопкой «Сдать заказ». Оплата = стоимость материалов ×
 * {@code matsPct/100} + бонус, зажатая в окно лестницы — множители
 * подобраны так, что медианный заказ платит середину окна (см.
 * tools/simulate_jobs.py). Потерянные/проданные материалы при отмене
 * или таймауте конвертируются в штраф по их стоимости, плюс неустойка
 * за срыв смены. У грузчика/курьера груз тоже помечен — срыв смены =
 * конфискация груза + штраф {@code jobTimeoutFeePct}% от оплаты.
 */
public final class JobManager {
	private JobManager() {}

	public static final String T_FACTORY = "factory";        // мини-игра сборки
	public static final String T_FACTORY_ORDER = "factory_order"; // крафт-заказ цеха
	public static final String T_LOADER = "loader";
	public static final String T_COOK = "cook";              // крафт-заказ кафе
	public static final String T_COURIER = "courier";

	private static final long TTL_SHORT = 12000; // 10 мин
	private static final long TTL_LONG = 18000;  // 15 мин (крафт-заказы)

	/** Палитра мини-игры завода. */
	private static final String[] FACTORY_CATS = {
			"minecraft:redstone", "minecraft:repeater", "minecraft:comparator",
			"minecraft:piston", "minecraft:observer", "minecraft:redstone_torch"};

	/** Цели крафта цеха: {цель, материалы на 1 шт}. Рецепты = ваниль. */
	private static final String[][] FACTORY_RECIPES = {
			{"minecraft:repeater", "minecraft:stone:3", "minecraft:redstone:1", "minecraft:redstone_torch:2"},
			{"minecraft:comparator", "minecraft:stone:3", "minecraft:quartz:1", "minecraft:redstone_torch:3"},
			{"minecraft:piston", "minecraft:oak_planks:3", "minecraft:cobblestone:4", "minecraft:iron_ingot:1", "minecraft:redstone:1"},
			{"minecraft:observer", "minecraft:cobblestone:6", "minecraft:redstone:2", "minecraft:quartz:1"},
			{"minecraft:redstone_lamp", "minecraft:redstone:4", "minecraft:glowstone:1"},
			{"minecraft:dispenser", "minecraft:cobblestone:7", "minecraft:bow:1", "minecraft:redstone:1"},
			{"minecraft:target", "minecraft:redstone:4", "minecraft:hay_block:1"},
			{"minecraft:note_block", "minecraft:oak_planks:8", "minecraft:redstone:1"},
			{"minecraft:dropper", "minecraft:cobblestone:7", "minecraft:redstone:1"},
			{"minecraft:tnt", "minecraft:gunpowder:5", "minecraft:sand:4"},
	};

	/** Цели крафта кафе: {блюдо, материалы на 1 шт}. cookie даёт 8 за крафт. */
	private static final String[][] CAFE_RECIPES = {
			{"minecraft:bread:1", "minecraft:wheat:3"},
			{"minecraft:pumpkin_pie:1", "minecraft:pumpkin:1", "minecraft:sugar:1", "minecraft:egg:1"},
			{"minecraft:mushroom_stew:1", "minecraft:red_mushroom:1", "minecraft:brown_mushroom:1", "minecraft:bowl:1"},
			{"minecraft:beetroot_soup:1", "minecraft:beetroot:6", "minecraft:bowl:1"},
			{"minecraft:cookie:8", "minecraft:wheat:2", "minecraft:cocoa_beans:1"},
			{"minecraft:rabbit_stew:1", "minecraft:cooked_rabbit:1", "minecraft:carrot:1",
					"minecraft:baked_potato:1", "minecraft:red_mushroom:1", "minecraft:bowl:1"},
	};

	public static boolean isCraftOrder(String type) {
		return T_FACTORY_ORDER.equals(type) || T_COOK.equals(type);
	}

	public static JobsState state(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(JobsState.TYPE);
	}

	private static NbtCompound rec(JobsState st, UUID id) {
		return Nbt2.sub(Nbt2.sub(st.data(), "players"), id.toString());
	}

	private static void saveRec(JobsState st, UUID id, NbtCompound rec) {
		NbtCompound players = Nbt2.sub(st.data(), "players");
		if (rec.isEmpty()) players.remove(id.toString());
		else players.put(id.toString(), rec);
		st.data().put("players", players);
		st.markDirty();
	}

	public static boolean hasJob(MinecraftServer server, UUID player) {
		return !rec(state(server), player).isEmpty();
	}

	// -------------------- кулдаун окон --------------------

	private static double feeCancel() {
		return CraftNetConfig.get().jobCancelFeePct / 100.0;
	}

	private static double feeTimeout() {
		return CraftNetConfig.get().jobTimeoutFeePct / 100.0;
	}

	/**
	 * Смена этого типа уже отработана в текущем окне (лимит jobWindowCooldown)?
	 * Сменой считается ЛЮБОЕ завершение: оплата, отмена, таймаут — иначе
	 * отмена→перепринятие была бы бесплатным рероллом состава оффера.
	 */
	public static boolean onWindowCooldown(MinecraftServer server, UUID player, String type) {
		int k = CraftNetConfig.get().jobWindowCooldown;
		if (k <= 0) return false;
		long cur = offerWindow(server);
		long w = Nbt2.sub(Nbt2.sub(state(server).data(), "cool"), player.toString())
				.getLong(type, Long.MIN_VALUE);
		return w > cur - k;
	}

	/** Отметить смену отработанной; мёртвые записи (старше лимита окон) подчищаются. */
	private static void markCooldown(MinecraftServer server, UUID player, String type) {
		int k = CraftNetConfig.get().jobWindowCooldown;
		if (k <= 0) return;
		JobsState st = state(server);
		long cur = offerWindow(server);
		NbtCompound coolAll = Nbt2.sub(st.data(), "cool");
		NbtCompound c = Nbt2.sub(coolAll, player.toString());
		c.putLong(type, cur);
		List<String> dead = new ArrayList<>();
		for (String key : c.getKeys()) {
			if (c.getLong(key, Long.MIN_VALUE) <= cur - k) dead.add(key);
		}
		for (String key : dead) c.remove(key);
		if (c.isEmpty()) coolAll.remove(player.toString());
		else coolAll.put(player.toString(), c);
		st.data().put("cool", coolAll);
		st.markDirty();
	}

	public static NbtCompound jobView(MinecraftServer server, UUID player) {
		return rec(state(server), player);
	}

	// -------------------- генерация офферов --------------------

	/** Текущее 10-минутное окно офферов. */
	public static long offerWindow(MinecraftServer server) {
		return server.getOverworld().getTime() / 12000;
	}

	/** Построить оффер для экрана (null, если доступного нет). */
	public static NbtCompound buildOffer(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return null;
		return buildOffer(player, type, offerWindow(server));
	}

	/**
	 * Оффер для конкретного окна (M7): клиент показывает слепок окна N и при
	 * принятии присылает его номер — сервер восстанавливает ровно тот же оффер,
	 * даже если между показом и кликом окно перевалило.
	 */
	public static NbtCompound buildOffer(ServerPlayerEntity player, String type, long win) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return null;
		java.util.Random rng = new java.util.Random(player.getUuid().hashCode() ^ win);

		CraftNetConfig cfg = CraftNetConfig.get();
		NbtCompound offer = new NbtCompound();
		switch (type) {
			case T_FACTORY -> {
				int parts = cfg.jobFactoryMinParts + rng.nextInt(cfg.jobFactoryMaxParts - cfg.jobFactoryMinParts + 1);
				offer.putInt("partsNeed", parts);
				offer.putLong("pay", cfg.jobFactoryBase + parts * (long) cfg.jobFactoryPerPart
						+ (cfg.jobFactoryJitter > 0 ? rng.nextInt(cfg.jobFactoryJitter) : 0)); // 195..285 по дефолту
				offer.putString("desc", "Повторяй схему кликами по компонентам");
				offer.putString("cats", String.join(",", FACTORY_CATS));
			}
			case T_FACTORY_ORDER -> {
				if (!buildCraftOffer(offer, rng, FACTORY_RECIPES,
						cfg.jobOrderFactoryMatsPct, cfg.jobOrderFactoryBonus,
						cfg.jobOrderFactoryMinPay, cfg.jobOrderFactoryMaxPay, 1, 2, 2, 3)) return null;
				offer.putString("desc", "Цех выдаст материалы — собери на верстаке и сдай");
			}
			case T_COOK -> {
				if (!buildCraftOffer(offer, rng, CAFE_RECIPES,
						cfg.jobOrderCookMatsPct, cfg.jobOrderCookBonus,
						cfg.jobOrderCookMinPay, cfg.jobOrderCookMaxPay, 2, 3, 2, 4)) return null;
				offer.putString("desc", "Бариста выдаст продукты — приготовь и сдай заказ");
			}
			case T_LOADER, T_COURIER -> {
				NbtCompound target = pickTarget(player, rng);
				if (target == null) return null;
				if (T_LOADER.equals(type)) {
					long pay = target.getLong("dist", 10) * cfg.jobLoaderPerBlock;
					pay = Math.max(cfg.jobLoaderMinPay, Math.min(cfg.jobLoaderMaxPay, pay));
					offer.putLong("pay", pay);
					offer.putString("desc", "Отнести тяжёлый ящик жителю (замедляет!)");
				} else {
					int portions = cfg.jobCourierMinPortions
							+ rng.nextInt(cfg.jobCourierMaxPortions - cfg.jobCourierMinPortions + 1);
					offer.putInt("portions", portions);
					offer.putLong("pay", cfg.jobCourierBase + portions * (long) cfg.jobCourierPerPortion); // 25..50
					offer.putString("desc", "Доставить пакеты еды жителю");
				}
				offer.put("target", target);
			}
			default -> {
				return null;
			}
		}
		offer.putString("type", type);
		offer.putLong("ttl", ttlOf(type));
		offer.putLong("win", win);
		return offer;
	}

	/**
	 * Собрать крафт-заказ: distinct рецептов [minKinds..maxKinds], кол-во
	 * каждого [minCount..maxCount] единиц ЦЕЛИ (cookie: единица = 8 шт).
	 * Награда = round(стоимость материалов × matsPct/100) + bonus, зажатая
	 * в окно [payLo..payHi] лестницы. Множитель с бонусом в конфиге подобраны
	 * так, что медианный заказ платит середину окна; кламп срабатывает лишь
	 * на крайне дешёвых/дорогих составах (проверка — tools/simulate_jobs.py).
	 */
	private static boolean buildCraftOffer(NbtCompound offer, java.util.Random rng,
			String[][] recipes, int matsPct, int bonus, int payLo, int payHi,
			int minKinds, int maxKinds, int minCount, int maxCount) {
		int kinds = minKinds + rng.nextInt(maxKinds - minKinds + 1);
		List<Integer> picked = new ArrayList<>();
		NbtList targets = new NbtList();
		NbtList mats = new NbtList();
		java.util.Map<String, Integer> matAgg = new java.util.LinkedHashMap<>();
		long matsValue = 0;
		for (int k = 0; k < kinds; k++) {
			int idx;
			int guard = 0;
			do {
				idx = rng.nextInt(recipes.length);
			} while (picked.contains(idx) && ++guard < 20);
			if (picked.contains(idx)) continue;
			picked.add(idx);
			String[] row = recipes[idx];
			String goalRef = row[0];
			String goalId = goalRef.contains(":") && goalRef.split(":").length == 3
					? goalRef.substring(0, goalRef.lastIndexOf(':')) : goalRef;
			int per = goalRef.equals(goalId) ? 1 : Integer.parseInt(goalRef.substring(goalRef.lastIndexOf(':') + 1));
			int units = minCount + rng.nextInt(maxCount - minCount + 1); // сколько крафтов
			int need = per * units;

			NbtCompound t = new NbtCompound();
			t.putString("id", goalId);
			t.putString("name", itemName(goalId));
			t.putInt("need", need);
			targets.add(t);

			for (int m = 1; m < row.length; m++) {
				String[] parts = row[m].split(":");
				String mid = parts[0] + ":" + parts[1];
				int cnt = Integer.parseInt(parts[2]) * units;
				matAgg.merge(mid, cnt, Integer::sum);
			}
		}
		if (targets.isEmpty()) return false;
		for (var e : matAgg.entrySet()) {
			NbtCompound m = new NbtCompound();
			m.putString("id", e.getKey());
			m.putString("name", itemName(e.getKey()));
			m.putInt("count", e.getValue());
			mats.add(m);
			matsValue += (long) PriceManager.buyPrice(e.getKey()) * e.getValue();
		}
		offer.put("targets", targets);
		offer.put("mats", mats);
		offer.putLong("matsValue", matsValue); // для отображения «заказ материалов на N CR»
		long pay = Math.round(matsValue * matsPct / 100.0) + bonus;
		pay = Math.max(payLo, Math.min(payHi, pay));
		offer.putLong("pay", pay);
		return true;
	}

	private static String itemName(String itemId) {
		Item item = Registries.ITEM.get(Identifier.tryParse(itemId));
		return item == null ? itemId : new ItemStack(item).getName().getString();
	}

	// -------------------- принятие --------------------

	/**
	 * Принять задание. Оффер пересоздаётся на сервере тем же зерном —
	 * клиент не может подделать параметры.
	 */
	public static boolean accept(ServerPlayerEntity player, String type) {
		return accept(player, type, -1L);
	}

	/**
	 * Принять задание, указав окно оффера (M7): принимается только текущее
	 * или предыдущее 10-минутное окно — слепок, который реально видел игрок
	 * (подделать чужое окно нельзя: зерно = uuid ⊗ win).
	 */
	public static boolean accept(ServerPlayerEntity player, String type, long win) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null || hasJob(server, player.getUuid())) return false;
		if (onWindowCooldown(server, player.getUuid(), type)) {
			player.sendMessage(Text.translatable("craftnet.job.cooldown"), false);
			return false;
		}
		long cur = offerWindow(server);
		if (win < 0 || win > cur) win = cur;
		else if (win < cur - 1) win = cur; // слишком старый слепок — берём свежий
		NbtCompound offer = buildOffer(player, type, win);
		if (offer == null) return false;
		JobsState st = state(server);
		NbtCompound rec = new NbtCompound();
		rec.putString("type", type);
		rec.put("data", offer);
		rec.putLong("since", server.getOverworld().getTime());

		String tag = tagOf(player.getUuid(), type);

		if (T_FACTORY.equals(type)) {
			NbtCompound data = Nbt2.sub(rec, "data");
			data.putInt("parts", 0);
			data.putInt("errors", 0);
			data.putString("seqNeed", newSeq(server, player.getUuid(), 0));
			data.putString("seqHave", "");
			rec.put("data", data);
		}

		if (isCraftOrder(type)) {
			// выдаём помеченные материалы
			for (var el : Nbt2.sub(rec, "data").getListOrEmpty("mats")) {
				if (!(el instanceof NbtCompound m)) continue;
				Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(m, "id")));
				if (item == null) continue;
				giveTagged(player, item, Nbt2.i(m, "count"), tag, true);
			}
		}

		if (T_LOADER.equals(type)) {
			giveTagged(player, ModBlocks.CARGO_CRATE.asItem(), 1, tag, false);
			rec.putInt("carryNeed", 1);
		} else if (T_COURIER.equals(type)) {
			int portions = offer.getInt("portions", 4);
			giveTagged(player, ModItems.FOOD_BOX, portions, tag, false);
			rec.putInt("carryNeed", portions);
		}

		// подсветка цели (одиночный target у грузчика/курьера)
		NbtCompound target = Nbt2.sub(offer, "target");
		String uuid = target.getString("uuid", "");
		if (!uuid.isEmpty()) glow(server, uuid, true);

		saveRec(st, player.getUuid(), rec);
		player.sendMessage(Text.translatable("craftnet.job.accepted"), false);
		return true;
	}

	private static String tagOf(UUID owner, String type) {
		return owner + ":" + type;
	}

	/** Выдать помеченный материал (имя «◆» — рабочее имущество). */
	private static void giveTagged(ServerPlayerEntity player, Item item, int count, String tag, boolean material) {
		int left = count;
		while (left > 0) {
			int n = Math.min(left, Math.max(1, item.getDefaultStack().getMaxCount()));
			ItemStack s = new ItemStack(item, n);
			s.set(ModComponents.JOB_TAG, tag);
			if (material) {
				s.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
						Text.literal("◆ ").append(s.getName()).formatted(Formatting.GOLD)
								.styled(st -> st.withItalic(false)));
			}
			player.getInventory().offerOrDrop(s);
			left -= n;
		}
	}

	// -------------------- мини-игра «сборка» (завод) --------------------

	private static String newSeq(MinecraftServer server, UUID player, int parts) {
		int len = 4 + Math.min(3, parts);
		java.util.Random rng = new java.util.Random(
				server.getOverworld().getTime() ^ (player.hashCode() * 31L) ^ parts * 997L);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len; i++) {
			if (i > 0) sb.append(',');
			sb.append(FACTORY_CATS[rng.nextInt(FACTORY_CATS.length)]);
		}
		return sb.toString();
	}

	/** Клик по компоненту палитры (только мини-игра завода). */
	public static boolean assemClick(ServerPlayerEntity player, String group, String itemId) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null || !"factory".equals(group)) return false;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty() || !T_FACTORY.equals(Nbt2.str(rec, "type"))) return false;
		NbtCompound data = Nbt2.sub(rec, "data");

		boolean inPalette = false;
		for (String c : FACTORY_CATS) if (c.equals(itemId)) { inPalette = true; break; }
		if (!inPalette) return false;

		String[] seq = Nbt2.str(data, "seqNeed").split(",");
		String have = Nbt2.str(data, "seqHave");
		int progress = have.isEmpty() ? 0 : have.split(",").length;
		if (progress >= seq.length) return false;

		int parts = data.getInt("parts", 0);
		int partsNeed = data.getInt("partsNeed", 3);

		if (seq[progress].equals(itemId)) {
			String newHave = have.isEmpty() ? itemId : have + "," + itemId;
			data.putString("seqHave", newHave);
			if (progress + 1 >= seq.length) {
				parts++;
				data.putInt("parts", parts);
				data.putInt("errors", 0);
				data.putString("seqHave", "");
				if (parts >= partsNeed) {
					long pay = Nbt2.lng(data, "pay");
					MoneyManager.add(server, player.getUuid(), pay, "работа: завод");
					payStats(server, player.getUuid(), StatsManager.JOBS_FACTORY, pay);
					player.sendMessage(Text.translatable("craftnet.job.paid", pay, "завод"), false);
					player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
					saveRec(st, player.getUuid(), new NbtCompound());
					markCooldown(server, player.getUuid(), T_FACTORY);
					return true;
				}
				data.putString("seqNeed", newSeq(server, player.getUuid(), parts));
				player.sendMessage(Text.translatable("craftnet.job.part_ok", parts, partsNeed), true);
				player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
			}
		} else {
			int errors = data.getInt("errors", 0) + 1;
			data.putInt("errors", errors);
			player.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
			if (errors >= 3) {
				data.putInt("errors", 0);
				data.putString("seqHave", "");
				data.putString("seqNeed", newSeq(server, player.getUuid(), parts));
				player.sendMessage(Text.translatable("craftnet.job.scrap"), true);
			}
		}
		rec.put("data", data);
		saveRec(st, player.getUuid(), rec);
		return true;
	}

	// -------------------- крафт-заказ: сдача --------------------

	/**
	 * Сдать крафт-заказ: все целевые предметы должны быть в инвентаре.
	 * @return 0 ок; 1 — нет активного крафт-заказа; 2 — не хватает предметов.
	 */
	public static int handin(ServerPlayerEntity player) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return 1;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty()) return 1;
		String type = Nbt2.str(rec, "type");
		if (!isCraftOrder(type)) return 1;
		NbtCompound data = Nbt2.sub(rec, "data");
		// легаси-смена повара (из старой версии) — не оплачиваем вслепую
		if (data.getListOrEmpty("targets").isEmpty()) return 1;

		// проверка всех целей
		for (var el : data.getListOrEmpty("targets")) {
			if (!(el instanceof NbtCompound t)) continue;
			Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(t, "id")));
			if (item == null || countInInventory(player, item) < Nbt2.i(t, "need")) return 2;
		}
		// изымаем результат
		for (var el : data.getListOrEmpty("targets")) {
			if (!(el instanceof NbtCompound t)) continue;
			Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(t, "id")));
			removeFromInventory(player, item, Nbt2.i(t, "need"));
		}
		// конфискаем остатки выданных ◆материалов — смена закрыта, склад не складируется
		String tag = tagOf(player.getUuid(), type);
		for (var el : data.getListOrEmpty("mats")) {
			if (!(el instanceof NbtCompound m)) continue;
			Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(m, "id")));
			if (item == null) continue;
			removeTagged(player, item, tag, Integer.MAX_VALUE);
		}
		long pay = Nbt2.lng(data, "pay");
		MoneyManager.add(server, player.getUuid(), pay,
				T_COOK.equals(type) ? "работа: повар" : "работа: цех");
		payStats(server, player.getUuid(),
				T_COOK.equals(type) ? StatsManager.JOBS_COOK : StatsManager.JOBS_FACTORY, pay);
		player.sendMessage(Text.translatable("craftnet.job.paid", pay,
				T_COOK.equals(type) ? "повар" : "цех"), false);
		player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
		saveRec(st, player.getUuid(), new NbtCompound());
		markCooldown(server, player.getUuid(), type);
		return 0;
	}

	// -------------------- доставка жителям --------------------

	/** ПКМ по жителю с активным заданием доставки. */
	public static boolean tryDeliver(ServerPlayerEntity player, Entity entity) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return false;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty()) return false;
		String type = Nbt2.str(rec, "type");
		if (!T_LOADER.equals(type) && !T_COURIER.equals(type)) return false;
		String uuid = entity.getUuidAsString();

		NbtCompound target = Nbt2.sub(Nbt2.sub(rec, "data"), "target");
		if (!uuid.equals(target.getString("uuid", ""))) return false;
		Item need = T_LOADER.equals(type) ? ModBlocks.CARGO_CRATE.asItem() : ModItems.FOOD_BOX;
		int needCount = Nbt2.i(rec, "carryNeed");
		if (countInInventory(player, need) < needCount) {
			player.sendMessage(Text.translatable("craftnet.job.no_cargo"), true);
			return false;
		}
		removeFromInventory(player, need, needCount);
		entity.setGlowing(false);
		long pay = Nbt2.lng(Nbt2.sub(rec, "data"), "pay");
		MoneyManager.add(server, player.getUuid(), pay,
				T_LOADER.equals(type) ? "работа: грузчик" : "работа: курьер");
		payStats(server, player.getUuid(),
				T_LOADER.equals(type) ? StatsManager.JOBS_LOADER : StatsManager.JOBS_COURIER, pay);
		player.sendMessage(Text.translatable("craftnet.job.paid", pay,
				T_LOADER.equals(type) ? "грузчик" : "курьер"), false);
		player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
		saveRec(st, player.getUuid(), new NbtCompound());
		markCooldown(server, player.getUuid(), type);
		return true;
	}

	// -------------------- отмена / штрафы --------------------

	/** Отмена текущего задания (с погашением подсветок и штрафом). */
	public static void cancel(MinecraftServer server, UUID player, boolean silent) {
		cancelInternal(server, player, silent, feeCancel(), "craftnet.job.cancelled");
	}

	/** Таймаут. */
	private static void expire(MinecraftServer server, UUID player) {
		cancelInternal(server, player, true, feeTimeout(), "craftnet.job.expired");
	}

	private static void cancelInternal(MinecraftServer server, UUID player, boolean silentTimeout,
			double feeRate, String msgKey) {
		JobsState st = state(server);
		NbtCompound rec = rec(st, player);
		unglowAll(server, player);
		if (!rec.isEmpty()) {
			String type = Nbt2.str(rec, "type");
			markCooldown(server, player, type); // сорванная смена тоже «смена» — анти-реролл оффера
			NbtCompound data = Nbt2.sub(rec, "data");
			long fee = Math.round(Nbt2.lng(data, "pay") * feeRate);
			long matLoss = 0;

			ServerPlayerEntity pl = server.getPlayerManager().getPlayer(player);
			if (isCraftOrder(type)) {
				// конфискуем помеченные материалы; недостачу оцениваем в деньги
				String tag = tagOf(player, type);
				for (var el : data.getListOrEmpty("mats")) {
					if (!(el instanceof NbtCompound m)) continue;
					Item item = Registries.ITEM.get(Identifier.tryParse(Nbt2.str(m, "id")));
					if (item == null) continue;
					int given = Nbt2.i(m, "count");
					int found = pl == null ? 0 : countTagged(pl, item, tag);
					if (pl != null) removeTagged(pl, item, tag, Integer.MAX_VALUE);
					int lost = Math.max(0, given - found);
					matLoss += (long) PriceManager.sellPrice(Nbt2.str(m, "id")) * lost;
				}
			} else if (T_LOADER.equals(type)) {
				if (pl != null) {
					String tag = tagOf(player, type);
					removeTagged(pl, ModBlocks.CARGO_CRATE.asItem(), tag, Integer.MAX_VALUE);
				}
				matLoss = CraftNetConfig.get().jobCargoLossFee; // потерянный ящик цеха
			} else if (T_COURIER.equals(type) && pl != null) {
				String tag = tagOf(player, type);
				removeTagged(pl, ModItems.FOOD_BOX, tag, Integer.MAX_VALUE);
			}

			long total = fee + matLoss;
			StatsManager.bump(server, player, StatsManager.JOBS_CANCELED, 1);
			StatsManager.bump(server, player, StatsManager.FINES_PAID, total);
			if (total > 0) {
				long bal = MoneyManager.balance(server, player);
				MoneyManager.add(server, player, -Math.min(bal, total), "штраф за срыв смены");
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
				if (p != null) {
					p.sendMessage(Text.translatable("craftnet.job.fined", total)
							.formatted(Formatting.RED), true);
				}
			}
		}
		saveRec(st, player, new NbtCompound());
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
		if (p != null) p.sendMessage(Text.translatable(msgKey), false);
	}

	/** Единая точка статистики оплаченной смены: счётчики + опыт. */
	private static void payStats(MinecraftServer server, UUID player, String jobCounterKey, long pay) {
		StatsManager.bump(server, player, StatsManager.JOBS_DONE, 1);
		StatsManager.bump(server, player, jobCounterKey, 1);
		StatsManager.bump(server, player, StatsManager.EARN_JOBS, pay);
		StatsManager.addXp(server, player, Math.max(1, pay / 10));
	}

	// -------------------- цели/подсветка/навигация --------------------

	private static NbtCompound pickTarget(ServerPlayerEntity player, java.util.Random rng) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return null;
		var near = VillageManager.nearest(server, player.getBlockPos(), false);
		BlockPos center;
		if (near.isPresent()) {
			NbtCompound v = near.get();
			center = new BlockPos(Nbt2.i(v, "cx"), Nbt2.i(v, "cy"), Nbt2.i(v, "cz"));
			if (center.getSquaredDistance(player.getBlockPos()) > 96 * 96) center = player.getBlockPos();
		} else {
			center = player.getBlockPos();
		}
		Box box = Box.of(net.minecraft.util.math.Vec3d.ofCenter(center), 96, 48, 96);
		List<VillagerEntity> found = player.getEntityWorld().getEntitiesByClass(VillagerEntity.class, box,
				v -> v.isAlive()
						&& !v.getCommandTags().contains("craftnet:pvz")
						&& !v.getCommandTags().contains("craftnet:bank")
						&& !v.getCommandTags().contains("craftnet:foreman")
						&& !v.getCommandTags().contains("craftnet:barista"));
		if (found.isEmpty()) return null;
		VillagerEntity pick = found.get(rng.nextInt(found.size()));
		NbtCompound t = new NbtCompound();
		t.putString("uuid", pick.getUuidAsString());
		t.putString("name", pick.hasCustomName() && pick.getCustomName() != null
				? pick.getCustomName().getString() : "Житель");
		t.putInt("x", pick.getBlockPos().getX());
		t.putInt("y", pick.getBlockPos().getY());
		t.putInt("z", pick.getBlockPos().getZ());
		t.putLong("dist", Math.round(Math.sqrt(pick.getBlockPos().getSquaredDistance(player.getBlockPos()))));
		return t;
	}

	private static void glow(MinecraftServer server, String uuid, boolean on) {
		try {
			Entity e = server.getOverworld().getEntityAnyDimension(UUID.fromString(uuid));
			if (e != null) e.setGlowing(on);
		} catch (IllegalArgumentException ignored) {
		}
	}

	private static void unglowAll(MinecraftServer server, UUID playerId) {
		NbtCompound rec0 = rec(state(server), playerId);
		NbtCompound data = Nbt2.sub(rec0, "data");
		glow(server, Nbt2.sub(data, "target").getString("uuid", ""), false);
		for (var el : data.getListOrEmpty("targets")) {
			if (el instanceof NbtCompound c) glow(server, c.getString("uuid", ""), false);
		}
	}

	/** Цель активной доставки для HUD-навигатора (или пустой compound). */
	public static NbtCompound navTarget(MinecraftServer server, UUID player) {
		NbtCompound rec = rec(state(server), player);
		String type = Nbt2.str(rec, "type");
		if (!T_LOADER.equals(type) && !T_COURIER.equals(type)) return new NbtCompound();
		NbtCompound t = Nbt2.sub(Nbt2.sub(rec, "data"), "target");
		if (t.isEmpty()) return new NbtCompound();
		NbtCompound out = new NbtCompound();
		out.putInt("x", Nbt2.i(t, "x"));
		out.putInt("y", Nbt2.i(t, "y"));
		out.putInt("z", Nbt2.i(t, "z"));
		out.putString("name", Nbt2.str(t, "name"));
		out.putString("job", type);
		return out;
	}

	private static long ttlOf(String type) {
		return isCraftOrder(type) ? TTL_LONG : TTL_SHORT;
	}

	/** Истечение сроков + тяжесть ящика + повторная подсветка целей. */
	public static void tick(MinecraftServer server, long tick) {
		if (tick % 20 == 0) tickCarryWeight(server);
		if (tick % 100 == 50) reGlowAll(server);
		if (tick % 100 != 0) return;
		JobsState st = state(server);
		NbtCompound players = Nbt2.sub(st.data(), "players");
		long now = server.getOverworld().getTime();
		List<String> expired = new ArrayList<>();
		for (String k : players.getKeys()) {
			NbtCompound rec = players.getCompound(k).orElseGet(NbtCompound::new);
			String type = Nbt2.str(rec, "type");
			if (now - rec.getLong("since", 0L) > ttlOf(type)) expired.add(k);
		}
		for (String k : expired) {
			try {
				expire(server, UUID.fromString(k));
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	/** Подсветка живёт на сущности; перевключаем — вдруг чанк перезагружался. */
	private static void reGlowAll(MinecraftServer server) {
		JobsState st = state(server);
		NbtCompound players = Nbt2.sub(st.data(), "players");
		for (String k : players.getKeys()) {
			NbtCompound rec = players.getCompound(k).orElseGet(NbtCompound::new);
			String type = Nbt2.str(rec, "type");
			if (!T_LOADER.equals(type) && !T_COURIER.equals(type)) continue;
			String uuid = Nbt2.sub(Nbt2.sub(rec, "data"), "target").getString("uuid", "");
			if (!uuid.isEmpty()) glow(server, uuid, true);
		}
	}

	/** Тяжёлый ящик грузчика давит на спину: пока он в инвентаре — замедление. */
	private static void tickCarryWeight(MinecraftServer server) {
		JobsState st = state(server);
		NbtCompound players = Nbt2.sub(st.data(), "players");
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			NbtCompound rec = players.getCompound(p.getUuid().toString()).orElseGet(NbtCompound::new);
			if (rec.isEmpty() || !T_LOADER.equals(Nbt2.str(rec, "type"))) continue;
			boolean carrying = countInInventory(p, ModBlocks.CARGO_CRATE.asItem()) > 0;
			boolean has = p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
			if (carrying && !has) {
				p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
						net.minecraft.entity.effect.StatusEffects.SLOWNESS, 80, 1, false, false, true));
				p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
						net.minecraft.entity.effect.StatusEffects.MINING_FATIGUE, 80, 0, false, false, false));
			} else if (!carrying && has) {
				// L1: снимаем только НАШЕ замедление (amplifier 1, короткое) —
				// чужой Slowness от зелий/мобов не трогаем
				var eff = p.getStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
				if (eff != null && eff.getAmplifier() == 1 && eff.getDuration() <= 100) {
					p.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
				}
			}
		}
	}

	// -------------------- инвентарь --------------------

	/**
	 * Помечен ли стак рабочим имуществом (компонент job_tag).
	 * Такое нельзя продать в ПВЗ, выставить на барахолку или поставить в казино —
	 * иначе материалы цеха превращаются в бесконечный фарм (принять → продать → отменить).
	 */
	public static boolean isJobTagged(ItemStack stack) {
		if (stack.isEmpty()) return false;
		String t = stack.get(ModComponents.JOB_TAG);
		return t != null && !t.isEmpty();
	}

	/** Сколько НЕпомеченных предметов этого вида в инвентаре (только их можно продать). */
	public static int countSellable(ServerPlayerEntity player, Item item) {
		int n = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item) && !isJobTagged(s)) n += s.getCount();
		}
		return n;
	}

	/** Снять count штук, пропуская помеченные (рабочие) стаки. */
	public static boolean removeSellable(ServerPlayerEntity player, Item item, int count) {
		if (countSellable(player, item) < count) return false;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size() && count > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(item) || isJobTagged(s)) continue;
			int take = Math.min(s.getCount(), count);
			s.decrement(take);
			count -= take;
		}
		return count == 0;
	}

	public static int countInInventory(ServerPlayerEntity player, Item item) {
		int n = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	public static boolean removeFromInventory(ServerPlayerEntity player, Item item, int count) {
		if (countInInventory(player, item) < count) return false;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size() && count > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(item)) continue;
			int take = Math.min(s.getCount(), count);
			s.decrement(take);
			count -= take;
		}
		return count == 0;
	}

	/** Сколько помеченных нашим тегом стаков предмета лежит в инвентаре. */
	private static int countTagged(ServerPlayerEntity player, Item item, String tag) {
		int n = 0;
		var inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(item)) continue;
			String t = s.get(ModComponents.JOB_TAG);
			if (tag.equals(t)) n += s.getCount();
		}
		return n;
	}

	/** Изыять помеченные стаки (до count штук). */
	private static void removeTagged(ServerPlayerEntity player, Item item, String tag, int count) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.size() && count > 0; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(item)) continue;
			String t = s.get(ModComponents.JOB_TAG);
			if (!tag.equals(t)) continue;
			int take = Math.min(s.getCount(), count);
			s.decrement(take);
			count -= take;
		}
	}
}
