package net.craftnet.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import net.craftnet.block.ModBlocks;
import net.craftnet.econ.MoneyManager;
import net.craftnet.item.ModItems;
import net.craftnet.state.JobsState;
import net.craftnet.util.Nbt2;
import net.craftnet.village.VillageManager;

/**
 * Рабочие места. Лестница оплаты: завод > грузчик > повар > курьер.
 * Одновременно — одно задание, срок ограничен.
 *
 * Завод и повар — мини-игра «сборка по схеме»: сервер задаёт последовательность
 * компонентов, игрок повторяет её кликами на палитре. Третья ошибка = брак
 * (схема перегенерируется, собранные детали/порции не сгорают). Повар после
 * сборки всех порций разносит готовую еду жителям (как курьер, но на 2-3 цели).
 */
public final class JobManager {
	private JobManager() {}

	public static final String T_FACTORY = "factory";
	public static final String T_LOADER = "loader";
	public static final String T_COOK = "cook";
	public static final String T_COURIER = "courier";

	private static final long TTL_SHORT = 12000; // 10 мин
	private static final long TTL_LONG = 18000;  // 15 мин (повару ещё бегать)

	/** Палитра компонентов завода (редстоун-детали). */
	private static final String[] FACTORY_CATS = {
			"minecraft:redstone", "minecraft:repeater", "minecraft:comparator",
			"minecraft:piston", "minecraft:observer", "minecraft:redstone_torch"};

	/** Палитра ингредиентов кафе. */
	private static final String[] COOK_CATS = {
			"minecraft:bread", "minecraft:cooked_beef", "minecraft:baked_potato",
			"minecraft:cooked_chicken", "minecraft:pumpkin_pie", "minecraft:mushroom_stew"};

	public static String[] catsFor(String type) {
		return T_COOK.equals(type) ? COOK_CATS : FACTORY_CATS;
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

	public static NbtCompound jobView(MinecraftServer server, UUID player) {
		return rec(state(server), player);
	}

	/** Отмена текущего задания (с погашением подсветок). */
	public static void cancel(MinecraftServer server, UUID player, boolean silent) {
		unglowAll(server, player);
		saveRec(state(server), player, new NbtCompound());
		if (!silent) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
			if (p != null) p.sendMessage(Text.translatable("craftnet.job.cancelled"), false);
		}
	}

	// -------------------- генерация офферов --------------------

	/** Построить оффер для экрана (null, если доступного нет). */
	public static NbtCompound buildOffer(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return null;
		java.util.Random rng = new java.util.Random(player.getUuid().hashCode() ^ (server.getOverworld().getTime() / 12000));

		NbtCompound offer = new NbtCompound();
		switch (type) {
			case T_FACTORY -> {
				int parts = 3 + rng.nextInt(3); // 3..5 деталей
				offer.putInt("partsNeed", parts);
				offer.putLong("pay", 90 + parts * 35L + rng.nextInt(20));
				offer.putString("desc", "Сборка редстоун-деталей по схеме");
				offer.putString("cats", String.join(",", FACTORY_CATS));
			}
			case T_COOK -> {
				int parts = 3 + rng.nextInt(3); // 3..5 порций
				offer.putInt("partsNeed", parts);
				offer.putLong("pay", 60 + parts * 22L + rng.nextInt(10));
				offer.putString("desc", "Приготовить порции по рецепту и разнести");
				offer.putString("cats", String.join(",", COOK_CATS));
			}
			case T_LOADER, T_COURIER -> {
				NbtCompound target = pickTarget(player, rng);
				if (target == null) return null;
				if (T_LOADER.equals(type)) {
					long pay = target.getLong("dist", 10) * 2;
					pay = Math.max(30, Math.min(400, Math.round(pay * 1.35)));
					offer.putLong("pay", pay);
					offer.putString("desc", "Отнести грузовой ящик жителю");
				} else {
					int portions = 3 + rng.nextInt(6); // 3..8
					offer.putInt("portions", portions);
					offer.putLong("pay", 10 + portions * 5L);
					offer.putString("desc", "Доставить пакет еды жителю");
				}
				offer.put("target", target);
			}
			default -> {
				return null;
			}
		}
		offer.putString("type", type);
		return offer;
	}

	/**
	 * Принять задание. Оффер пересоздаётся на сервере тем же зерном —
	 * клиент не может подделать параметры.
	 */
	public static boolean accept(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null || hasJob(server, player.getUuid())) return false;
		NbtCompound offer = buildOffer(player, type);
		if (offer == null) return false;
		JobsState st = state(server);
		NbtCompound rec = new NbtCompound();
		rec.putString("type", type);
		rec.put("data", offer);
		rec.putLong("since", server.getOverworld().getTime());

		if (T_FACTORY.equals(type) || T_COOK.equals(type)) {
			NbtCompound data = Nbt2.sub(rec, "data");
			data.putInt("parts", 0);
			data.putInt("errors", 0);
			data.putString("stage", "build");
			data.putString("seqNeed", newSeq(type, server, player.getUuid(), 0));
			data.putString("seqHave", "");
			rec.put("data", data);
		}

		if (T_LOADER.equals(type)) {
			player.getInventory().offerOrDrop(new ItemStack(ModBlocks.CARGO_CRATE.asItem(), 1));
			rec.putInt("carryNeed", 1);
		} else if (T_COURIER.equals(type)) {
			int portions = offer.getInt("portions", 4);
			player.getInventory().offerOrDrop(new ItemStack(ModItems.FOOD_BOX, portions));
			rec.putInt("carryNeed", portions);
		}

		// подсветка целей (одиночный target у грузчика/курьера)
		NbtCompound target = Nbt2.sub(offer, "target");
		String uuid = target.getString("uuid", "");
		if (!uuid.isEmpty()) glow(server, uuid, true);

		saveRec(st, player.getUuid(), rec);
		player.sendMessage(Text.translatable("craftnet.job.accepted"), false);
		return true;
	}

	// -------------------- мини-игра «сборка» --------------------

	/** Новая схема: длина растёт с прогрессом (4..7 шагов), зерно — игрок+тик+parts. */
	private static String newSeq(String type, MinecraftServer server, UUID player, int parts) {
		String[] cats = catsFor(type);
		int len = 4 + Math.min(3, parts);
		java.util.Random rng = new java.util.Random(
				server.getOverworld().getTime() ^ (player.hashCode() * 31L) ^ parts * 997L);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len; i++) {
			if (i > 0) sb.append(',');
			sb.append(cats[rng.nextInt(cats.length)]);
		}
		return sb.toString();
	}

	/**
	 * Клик по компоненту палитры. Только для factory/cook на стадии build,
	 * экран должен соответствовать типу задания (завод ↔ factory, кафе ↔ cook).
	 */
	public static boolean assemClick(ServerPlayerEntity player, String group, String itemId) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return false;
		String expectedType = "factory".equals(group) ? T_FACTORY : T_COOK;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty() || !expectedType.equals(Nbt2.str(rec, "type"))) return false;
		NbtCompound data = Nbt2.sub(rec, "data");
		if (!"build".equals(Nbt2.str(data, "stage"))) return false;

		// кликабельны только компоненты из палитры
		boolean inPalette = false;
		for (String c : catsFor(expectedType)) if (c.equals(itemId)) { inPalette = true; break; }
		if (!inPalette) return false;

		String[] seq = Nbt2.str(data, "seqNeed").split(",");
		String have = Nbt2.str(data, "seqHave");
		int progress = have.isEmpty() ? 0 : have.split(",").length;
		if (progress >= seq.length) return false;

		int parts = data.getInt("parts", 0);
		int partsNeed = data.getInt("partsNeed", 3);

		if (seq[progress].equals(itemId)) {
			// верный шаг
			String newHave = have.isEmpty() ? itemId : have + "," + itemId;
			data.putString("seqHave", newHave);
			if (progress + 1 >= seq.length) {
				parts++;
				data.putInt("parts", parts);
				data.putInt("errors", 0);
				data.putString("seqHave", "");
				if (parts >= partsNeed) {
					finishBuildPhase(player, st, rec, data, expectedType);
					return true;
				}
				data.putString("seqNeed", newSeq(expectedType, server, player.getUuid(), parts));
				player.sendMessage(Text.translatable("craftnet.job.part_ok", parts, partsNeed), true);
				player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.4f);
			}
		} else {
			int errors = data.getInt("errors", 0) + 1;
			data.putInt("errors", errors);
			player.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 0.7f, 0.9f);
			if (errors >= 3) {
				// брак: схема новая, собранные детали НЕ сгорают
				data.putInt("errors", 0);
				data.putString("seqHave", "");
				data.putString("seqNeed", newSeq(expectedType, server, player.getUuid(), parts));
				player.sendMessage(Text.translatable("craftnet.job.scrap"), true);
			}
		}
		rec.put("data", data);
		saveRec(st, player.getUuid(), rec);
		return true;
	}

	/** Завершает стадию сборки: factory — сразу расчёт; cook — фаза разноса. */
	private static void finishBuildPhase(ServerPlayerEntity player, JobsState st, NbtCompound rec,
			NbtCompound data, String type) {
		MinecraftServer server = player.getEntityWorld().getServer();
		long pay = Nbt2.lng(data, "pay");
		if (T_FACTORY.equals(type)) {
			MoneyManager.add(server, player.getUuid(), pay, "работа: завод");
			player.sendMessage(Text.translatable("craftnet.job.paid", pay, type), false);
			player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
			saveRec(st, player.getUuid(), new NbtCompound());
			return;
		}
		// повар: переход к разносу
		int partsNeed = data.getInt("partsNeed", 3);
		data.putString("stage", "deliver");
		player.getInventory().offerOrDrop(new ItemStack(ModItems.FOOD_BOX, partsNeed));

		int serves = Math.min(partsNeed, 3); // 2-3 точки доставки
		NbtList targets = new NbtList();
		java.util.Random rng = new java.util.Random(
				server.getOverworld().getTime() ^ player.getUuid().hashCode());
		List<UUID> used = new ArrayList<>();
		for (int i = 0; i < serves; i++) {
			NbtCompound t = pickTargetExcluding(player, rng, used);
			if (t == null) break;
			used.add(UUID.fromString(t.getString("uuid", "")));
			targets.add(t);
			glow(server, t.getString("uuid", ""), true);
		}
		if (targets.isEmpty()) {
			// жителей нет — просто расчёт сейчас
			MoneyManager.add(server, player.getUuid(), pay, "работа: повар");
			player.sendMessage(Text.translatable("craftnet.job.paid", pay, type), false);
			saveRec(st, player.getUuid(), new NbtCompound());
			return;
		}
		data.put("targets", targets);
		// цена одной подачи (последняя добирает остаток)
		data.putLong("servePay", Math.max(1, pay / targets.size()));
		rec.put("data", data);
		saveRec(st, player.getUuid(), rec);
		player.sendMessage(Text.translatable("craftnet.job.deliver_phase", targets.size()), false);
		player.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_CELEBRATE, 0.7f, 1.1f);
	}

	// -------------------- доставка жителям --------------------

	/** ПКМ по жителю с активным заданием — доставка. */
	public static boolean tryDeliver(ServerPlayerEntity player, Entity entity) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return false;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty()) return false;
		String type = Nbt2.str(rec, "type");
		String uuid = entity.getUuidAsString();

		// грузчик/курьер — одиночная цель
		if (T_LOADER.equals(type) || T_COURIER.equals(type)) {
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
			MoneyManager.add(server, player.getUuid(), pay, "работа: " + type);
			player.sendMessage(Text.translatable("craftnet.job.paid", pay, type), false);
			player.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
			saveRec(st, player.getUuid(), new NbtCompound());
			return true;
		}

		// повар — фаза разноса по списку целей
		if (T_COOK.equals(type)) {
			NbtCompound data = Nbt2.sub(rec, "data");
			if (!"deliver".equals(Nbt2.str(data, "stage"))) return false;
			NbtList targets = data.getListOrEmpty("targets");
			int hit = -1;
			for (int i = 0; i < targets.size(); i++) {
				if (targets.get(i) instanceof NbtCompound c
						&& uuid.equals(c.getString("uuid", ""))) { hit = i; break; }
			}
			if (hit < 0) return false;
			if (countInInventory(player, ModItems.FOOD_BOX) < 1) {
				player.sendMessage(Text.translatable("craftnet.job.no_cargo"), true);
				return false;
			}
			removeFromInventory(player, ModItems.FOOD_BOX, 1);
			entity.setGlowing(false);
			long servePay = Nbt2.lng(data, "servePay");
			targets.remove(hit);
			long payTotal = Nbt2.lng(data, "pay");
			int servedBefore = data.getInt("served", 0);
			// последняя подача добирает остаток от общей суммы заказа
			long thisPay = targets.isEmpty() ? payTotal - servePay * servedBefore : servePay;
			data.putInt("served", servedBefore + 1);
			MoneyManager.add(server, player.getUuid(), thisPay, "работа: повар(подача)");
			player.sendMessage(Text.translatable("craftnet.job.paid", thisPay, type), false);
			player.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_YES, 0.7f, 1.0f);
			if (targets.isEmpty()) {
				saveRec(st, player.getUuid(), new NbtCompound());
				return true;
			}
			data.put("targets", targets);
			rec.put("data", data);
			saveRec(st, player.getUuid(), rec);
			return true;
		}
		return false;
	}

	// -------------------- состояние/тики --------------------

	/** Случайный житель текущей деревни (не наш NPC-персонал). Выбор детерминирован зерном. */
	private static NbtCompound pickTarget(ServerPlayerEntity player, java.util.Random rng) {
		return pickTargetExcluding(player, rng, List.of());
	}

	private static NbtCompound pickTargetExcluding(ServerPlayerEntity player, java.util.Random rng, List<UUID> exclude) {
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
						&& !v.getCommandTags().contains("craftnet:barista")
						&& !exclude.contains(v.getUuid()));
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

	private static long ttlOf(String type) {
		return T_COOK.equals(type) ? TTL_LONG : TTL_SHORT;
	}

	/** Истечение сроков + эффект тяжести у грузчиков. */
	public static void tick(MinecraftServer server, long tick) {
		if (tick % 20 == 0) tickCarryWeight(server);
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
				cancel(server, UUID.fromString(k), true);
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(UUID.fromString(k));
				if (p != null) p.sendMessage(Text.translatable("craftnet.job.expired"), false);
			} catch (IllegalArgumentException ignored) {
			}
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
				p.removeStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOWNESS);
			}
		}
	}

	// -------------------- инвентарь --------------------

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
}
