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
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import net.craftnet.block.ModBlocks;
import net.craftnet.econ.MoneyManager;
import net.craftnet.econ.PriceManager;
import net.craftnet.state.JobsState;
import net.craftnet.util.Nbt2;
import net.craftnet.village.VillageManager;

/**
 * Рабочие места. Лестница оплаты: завод ×1.6 > грузчик ×1.35 > повар ×1.15 > курьер ×1.0.
 * Одновременно — одно задание. Срок — 10 минут с момента принятия.
 */
public final class JobManager {
	private JobManager() {}

	public static final String T_FACTORY = "factory";
	public static final String T_LOADER = "loader";
	public static final String T_COOK = "cook";
	public static final String T_COURIER = "courier";

	private static final long JOB_TTL_TICKS = 12000; // 10 минут

	private static final Item[][] FACTORY_POOL = {
			{Items.REDSTONE, Items.REPEATER, Items.COMPARATOR},
			{Items.REDSTONE_TORCH, Items.PISTON, Items.STICKY_PISTON},
			{Items.OBSERVER, Items.DISPENSER, Items.DROPPER},
			{Items.HOPPER, Items.TARGET, Items.REDSTONE_LAMP},
	};

	private static final Item[][] COOK_POOL = {
			{Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP},
			{Items.COOKED_CHICKEN, Items.BAKED_POTATO, Items.PUMPKIN_PIE},
			{Items.MUSHROOM_STEW, Items.RABBIT_STEW, Items.COOKED_SALMON},
	};

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
		// rec() уже возвращает отсоединённую копию (поведение NBT 1.21.5+)
		return rec(state(server), player);
	}

	/** Отмена текущего задания. */
	public static void cancel(MinecraftServer server, UUID player, boolean silent) {
		unglow(server, state(server), player);
		saveRec(state(server), player, new NbtCompound());
		if (!silent) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(player);
			if (p != null) p.sendMessage(Text.translatable("craftnet.job.cancelled"), false);
		}
	}

	/**
	 * Принять задание. Оффер детерминированно пересоздаётся сервером
	 * (то же зерно, что и в показанном игроку), подделать параметры нельзя.
	 */
	public static boolean accept(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getServer();
		if (server == null || hasJob(server, player.getUuid())) return false;
		NbtCompound offer = buildOffer(player, type);
		if (offer == null) return false;
		JobsState st = state(server);
		NbtCompound rec = new NbtCompound();
		rec.putString("type", type);
		rec.put("data", offer);
		rec.putLong("since", server.getOverworld().getTime());

		if (T_LOADER.equals(type)) {
			player.getInventory().offerOrDrop(new ItemStack(ModBlocks.CARGO_CRATE.asItem(), 1));
			rec.putInt("carryNeed", 1);
		} else if (T_COURIER.equals(type)) {
			int portions = offer.getInt("portions", 4);
			player.getInventory().offerOrDrop(new ItemStack(net.craftnet.item.ModItems.FOOD_BOX, portions));
			rec.putInt("carryNeed", portions);
		}
		// подсветить целевого жителя
		String uuid = Nbt2.sub(offer, "target").getString("uuid", "");
		if (!uuid.isEmpty()) {
			try {
				Entity e = server.getOverworld().getEntityAnyDimension(UUID.fromString(uuid));
				if (e != null) e.setGlowing(true);
			} catch (IllegalArgumentException ignored) {
			}
		}
		saveRec(st, player.getUuid(), rec);
		player.sendMessage(Text.translatable("craftnet.job.accepted"), false);
		return true;
	}

	// -------------------- генерация офферов --------------------

	/** Построить оффер для экрана (null, если доступного нет). */
	public static NbtCompound buildOffer(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getServer();
		if (server == null) return null;
		java.util.Random rng = new java.util.Random(player.getUuid().hashCode() ^ (server.getOverworld().getTime() / 12000));

		NbtCompound offer = new NbtCompound();
		switch (type) {
			case T_FACTORY -> {
				Item[] picks = FACTORY_POOL[rng.nextInt(FACTORY_POOL.length)];
				NbtCompound items = new NbtCompound();
				long sum = 0;
				for (Item it : picks) {
					int n = 2 + rng.nextInt(4); // 2..5
					String id = Registries.ITEM.getId(it).toString();
					items.putInt(id, n);
					sum += (long) PriceManager.buyPrice(id) * n;
				}
				long pay = Math.max(40, Math.round(sum * 1.6) + rng.nextInt(15));
				offer.put("items", items);
				offer.putLong("pay", pay);
				offer.putString("desc", "Производственный заказ: собрать компоненты");
			}
			case T_COOK -> {
				Item[] picks = COOK_POOL[rng.nextInt(COOK_POOL.length)];
				NbtCompound items = new NbtCompound();
				long sum = 0;
				for (Item it : picks) {
					int n = 2 + rng.nextInt(3);
					String id = Registries.ITEM.getId(it).toString();
					items.putInt(id, n);
					sum += (long) PriceManager.buyPrice(id) * n;
				}
				long pay = Math.max(25, Math.round(sum * 1.15) + 5);
				offer.put("items", items);
				offer.putLong("pay", pay);
				offer.putString("desc", "Заказ кафе: приготовить блюда");
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
					offer.putLong("pay", portions * 4L);
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

	/** Случайный житель текущей деревни (не наш NPC-персонал). Выбор детерминирован зерном оффера. */
	private static NbtCompound pickTarget(ServerPlayerEntity player, java.util.Random rng) {
		MinecraftServer server = player.getServer();
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
		List<VillagerEntity> found = player.getWorld().getEntitiesByClass(VillagerEntity.class, box,
				v -> v.isAlive()
						&& !v.getCommandTags().contains("craftnet:pvz")
						&& !v.getCommandTags().contains("craftnet:bank")
						&& !v.getCommandTags().contains("craftnet:foreman")
						&& !v.getCommandTags().contains("craftnet:barista"));
		if (found.isEmpty()) return null;
		VillagerEntity pick = found.get(rng.nextInt(found.size()));
		// подсветка включается только при принятии задания (accept), не при показе оффера
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

	private static void unglow(MinecraftServer server, JobsState st, UUID playerId) {
		NbtCompound rec0 = rec(st, playerId);
		NbtCompound target = Nbt2.sub(Nbt2.sub(rec0, "data"), "target");
		String uuid = target.getString("uuid", "");
		if (uuid.isEmpty()) return;
		try {
			Entity e = server.getOverworld().getEntityAnyDimension(UUID.fromString(uuid));
			if (e != null) e.setGlowing(false);
		} catch (IllegalArgumentException ignored) {
		}
	}

	/** ПКМ по жителю с активным заданием — доставка. */
	public static boolean tryDeliver(ServerPlayerEntity player, Entity entity) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty()) return false;
		String type = Nbt2.str(rec, "type");
		if (!T_LOADER.equals(type) && !T_COURIER.equals(type)) return false;
		NbtCompound target = Nbt2.sub(Nbt2.sub(rec, "data"), "target");
		if (!entity.getUuidAsString().equals(target.getString("uuid", ""))) return false;

		Item need = T_LOADER.equals(type) ? ModBlocks.CARGO_CRATE.asItem() : net.craftnet.item.ModItems.FOOD_BOX;
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
		saveRec(st, player.getUuid(), new NbtCompound());
		return true;
	}

	/** Кнопка «сдать заказ» на экране завода/кафе. */
	public static boolean completeStation(ServerPlayerEntity player, String type) {
		MinecraftServer server = player.getServer();
		if (server == null) return false;
		JobsState st = state(server);
		NbtCompound rec = rec(st, player.getUuid());
		if (rec.isEmpty() || !type.equals(Nbt2.str(rec, "type"))) return false;
		NbtCompound data = Nbt2.sub(rec, "data");
		NbtCompound items = Nbt2.sub(data, "items");
		for (String id : items.getKeys()) {
			Item it = Registries.ITEM.get(Identifier.tryParse(id));
			int n = items.getInt(id, 0);
			if (it == null || countInInventory(player, it) < n) {
				player.sendMessage(Text.translatable("craftnet.job.missing_items"), true);
				return false;
			}
		}
		for (String id : items.getKeys()) {
			Item it = Registries.ITEM.get(Identifier.tryParse(id));
			if (it != null) removeFromInventory(player, it, items.getInt(id, 0));
		}
		long pay = Nbt2.lng(data, "pay");
		MoneyManager.add(server, player.getUuid(), pay, "работа: " + type);
		player.sendMessage(Text.translatable("craftnet.job.paid", pay, type), false);
		saveRec(st, player.getUuid(), new NbtCompound());
		return true;
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
			if (now - rec.getLong("since", 0L) > JOB_TTL_TICKS) expired.add(k);
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
