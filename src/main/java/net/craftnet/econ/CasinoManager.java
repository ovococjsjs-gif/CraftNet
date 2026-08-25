package net.craftnet.econ;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import net.craftnet.config.CraftNetConfig;
import net.craftnet.jobs.JobManager;
import net.craftnet.state.CasinoState;
import net.craftnet.stats.StatsManager;
import net.craftnet.util.Nbt2;
import net.craftnet.util.StackOps;

/**
 * Server-authoritative item upgrader. User items are kept as exact ItemStacks in
 * escrow; no operation is allowed to normalize components or return a fresh
 * default copy of a damaged/enchanted/container item.
 */
public final class CasinoManager {
	private CasinoManager() {}

	public static final int MAX_KINDS = 4;
	public static final int BIG_WIN_BP = 2000;

	public static final int ADD_OK = 0;
	public static final int ADD_NO_ITEM = 1;
	public static final int ADD_TOO_MANY_KINDS = 2;
	public static final int ADD_FULL = 3;
	public static final int ADD_COMPLEX_STACK = 4;

	public static final int SPIN_OK = 0;
	public static final int SPIN_EMPTY = 2;
	public static final int SPIN_BAD_TARGET = 3;
	public static final int SPIN_LOW = 4;
	public static final int SPIN_PENDING = 5;
	public static final int SPIN_OVERBET = 6;

	private static final SecureRandom RNG = new SecureRandom();

	public static int maxUnits() {
		return CraftNetConfig.get().casinoMaxStakeUnits;
	}

	public static int minChanceBp() {
		return CraftNetConfig.get().casinoMinChanceBp;
	}

	public static int maxChanceBp() {
		return CraftNetConfig.get().casinoMaxChanceBp;
	}

	public static CasinoState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(CasinoState.TYPE);
	}

	private static NbtCompound poolRec(CasinoState st, UUID uuid) {
		return Nbt2.sub(Nbt2.sub(st.data(), "p"), uuid.toString());
	}

	private static void savePoolRec(CasinoState st, UUID uuid, NbtCompound rec) {
		NbtCompound pools = Nbt2.sub(st.data(), "p");
		if (rec.isEmpty()) pools.remove(uuid.toString());
		else pools.put(uuid.toString(), rec);
		st.data().put("p", pools);
		st.markDirty();
	}

	/** Decode exact escrow stacks. Legacy {id,count} rows are recovered as defaults. */
	private static List<ItemStack> poolStacks(MinecraftServer server, CasinoState st, UUID uuid) {
		List<ItemStack> out = new ArrayList<>();
		NbtList items = poolRec(st, uuid).getListOrEmpty("items");
		for (int i = 0; i < items.size(); i++) {
			if (!(items.get(i) instanceof NbtCompound row)) continue;
			ItemStack stack = StackOps.decode(server, Nbt2.sub(row, "stack"));
			if (stack.isEmpty()) {
				// Migration from v1.1: components were irretrievably absent, but the
				// item itself must still be returned rather than silently deleted.
				String id = Nbt2.str(row, "id");
				Item item = Registries.ITEM.get(Identifier.tryParse(id));
				if (item != null) stack = new ItemStack(item, Math.max(1, Nbt2.i(row, "count")));
			}
			if (!stack.isEmpty()) out.add(stack);
		}
		return out;
	}

	private static void saveStacks(MinecraftServer server, CasinoState st, UUID uuid, List<ItemStack> stacks) {
		if (stacks.isEmpty()) {
			savePoolRec(st, uuid, new NbtCompound());
			return;
		}
		NbtCompound rec = new NbtCompound();
		NbtList list = new NbtList();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) continue;
			NbtCompound row = new NbtCompound();
			row.put("stack", StackOps.encode(server, stack));
			list.add(row);
		}
		rec.put("items", list);
		savePoolRec(st, uuid, rec);
	}

	/** Stake value uses the configured server sell value of the base item. */
	public static long poolValue(MinecraftServer server, UUID uuid) {
		long value = 0;
		for (ItemStack stack : poolStacks(server, get(server), uuid)) {
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			value += (long) PriceManager.sellPrice(id) * stack.getCount();
		}
		return value;
	}

	public static long chanceBp(long stakeSellValue, long targetBuyPrice) {
		if (stakeSellValue <= 0 || targetBuyPrice <= 0) return 0;
		long rtpPromille = Math.round(CraftNetConfig.get().casinoRtpPct * 10);
		return stakeSellValue * 10000L * rtpPromille / 1000L / targetBuyPrice;
	}

	/** Exact escrow rows for the phone. */
	public static List<NbtCompound> poolRows(MinecraftServer server, UUID uuid) {
		List<NbtCompound> out = new ArrayList<>();
		for (ItemStack stack : poolStacks(server, get(server), uuid)) {
			String id = Registries.ITEM.getId(stack.getItem()).toString();
			NbtCompound row = new NbtCompound();
			row.putString("id", id);
			row.putString("name", stack.getName().getString());
			row.putInt("count", stack.getCount());
			row.putInt("val", PriceManager.sellPrice(id) * stack.getCount());
			row.put("stack", StackOps.identity(server, stack));
			out.add(row);
		}
		return out;
	}

	/**
	 * Move an exact stack from a concrete inventory slot into escrow.
	 * Component-bearing stacks are deliberately rejected until a component-aware
	 * valuation policy exists; silently valuing an enchanted/filled item as its
	 * empty base item is not acceptable.
	 */
	public static int addStake(MinecraftServer server, ServerPlayerEntity player,
			int slot, NbtCompound expected, int count) {
		if (count <= 0 || slot < 0 || slot >= player.getInventory().size()) return ADD_NO_ITEM;
		ItemStack current = player.getInventory().getStack(slot);
		if (current.isEmpty() || JobManager.isJobTagged(current)
				|| !StackOps.sameIdentity(server, current, expected)) return ADD_NO_ITEM;
		if (!StackOps.isPlain(server, current)) return ADD_COMPLEX_STACK;
		String itemId = Registries.ITEM.getId(current.getItem()).toString();
		if (!PriceManager.tradeable(itemId) || PriceManager.sellPrice(itemId) <= 0) return ADD_NO_ITEM;

		CasinoState st = get(server);
		List<ItemStack> items = poolStacks(server, st, player.getUuid());
		ItemStack target = null;
		for (ItemStack stack : items) {
			if (StackOps.identity(server, stack).equals(expected)) {
				target = stack;
				break;
			}
		}
		if (target == null && items.size() >= MAX_KINDS) return ADD_TOO_MANY_KINDS;
		int already = target == null ? 0 : target.getCount();
		if (already >= maxUnits()) return ADD_FULL;
		count = Math.min(count, Math.min(current.getCount(), maxUnits() - already));
		if (count <= 0) return ADD_FULL;

		ItemStack taken = StackOps.takeExact(player, server, slot, expected, count);
		if (taken.isEmpty()) return ADD_NO_ITEM;
		if (target == null) items.add(taken);
		else target.increment(taken.getCount());
		saveStacks(server, st, player.getUuid(), items);
		return ADD_OK;
	}

	/** Return one exact escrow variant (used by clicking a stake icon). */
	public static boolean removeStake(MinecraftServer server, ServerPlayerEntity player,
			NbtCompound expected, int count) {
		if (count <= 0 || expected == null || expected.isEmpty()) return false;
		CasinoState st = get(server);
		List<ItemStack> items = poolStacks(server, st, player.getUuid());
		for (int index = 0; index < items.size(); index++) {
			ItemStack stack = items.get(index);
			if (!StackOps.identity(server, stack).equals(expected)) continue;
			ItemStack returned = stack.split(Math.min(count, stack.getCount()));
			if (stack.isEmpty()) items.remove(index);
			saveStacks(server, st, player.getUuid(), items);
			StackOps.offerOrDropSplit(player, returned);
			return true;
		}
		return false;
	}

	/** Return the exact escrowed stacks, split to legal stack sizes. */
	public static boolean clearPool(MinecraftServer server, ServerPlayerEntity player) {
		CasinoState st = get(server);
		List<ItemStack> items = poolStacks(server, st, player.getUuid());
		if (items.isEmpty()) return false;
		// Clear durable state before delivery: a crash cannot duplicate escrow.
		savePoolRec(st, player.getUuid(), new NbtCompound());
		for (ItemStack stack : items) StackOps.offerOrDropSplit(player, stack.copy());
		return true;
	}

	public static int spin(MinecraftServer server, ServerPlayerEntity player, String targetId) {
		CasinoState st = get(server);
		NbtCompound pendingAll = Nbt2.sub(st.data(), "pending");
		if (!Nbt2.sub(pendingAll, player.getUuidAsString()).isEmpty()) return SPIN_PENDING;
		List<ItemStack> items = poolStacks(server, st, player.getUuid());
		if (items.isEmpty()) return SPIN_EMPTY;
		Item target = Registries.ITEM.get(Identifier.tryParse(targetId));
		if (target == null || !PriceManager.buyable(targetId) || PriceManager.buyPrice(targetId) <= 0) {
			return SPIN_BAD_TARGET;
		}
		long stake = poolValue(server, player.getUuid());
		long targetVal = PriceManager.buyPrice(targetId);
		if (stake <= 0) return SPIN_EMPTY;
		long bp = chanceBp(stake, targetVal);
		if (bp < minChanceBp()) return SPIN_LOW;
		if (bp > maxChanceBp()) return SPIN_OVERBET;

		// Commit: consume escrow and persist an unresolved spin. No prize, chat or
		// outcome sound is emitted until the client has had time to animate.
		savePoolRec(st, player.getUuid(), new NbtCompound());
		long spinId = Nbt2.lng(st.data(), "spinSeq") + 1;
		st.data().putLong("spinSeq", spinId);
		NbtCompound pending = new NbtCompound();
		pending.putLong("id", spinId);
		pending.putInt("win", RNG.nextInt(10000) < bp ? 1 : 0);
		pending.putInt("bp", (int) bp);
		pending.putLong("sv", stake);
		pending.putLong("tv", targetVal);
		pending.putString("target", targetId);
		pending.putString("tname", new ItemStack(target).getName().getString());
		pending.putString("sicon", items.isEmpty() ? ""
				: Registries.ITEM.getId(items.get(0).getItem()).toString());
		pending.putLong("due", server.getTicks() + 55L);
		pendingAll.put(player.getUuidAsString(), pending);
		st.data().put("pending", pendingAll);
		st.markDirty();

		StatsManager.bump(server, player.getUuid(), StatsManager.SPINS, 1);
		StatsManager.bump(server, player.getUuid(), StatsManager.CASINO_WAGERED, stake);
		StatsManager.addXp(server, player.getUuid(), 1);
		return SPIN_OK;
	}

	/** Resolve committed spins after the anticipation window. Offline winners remain pending. */
	public static void tick(MinecraftServer server) {
		if (server.getTicks() % 5 != 0) return;
		CasinoState st = get(server);
		NbtCompound all = Nbt2.sub(st.data(), "pending");
		if (all.isEmpty()) return;
		for (String uuidString : new ArrayList<>(all.getKeys())) {
			NbtCompound pending = Nbt2.sub(all, uuidString);
			if (pending.isEmpty() || pending.getLong("due", Long.MAX_VALUE) > server.getTicks()) continue;
			UUID uuid;
			try {
				uuid = UUID.fromString(uuidString);
			} catch (IllegalArgumentException invalid) {
				all.remove(uuidString);
				continue;
			}
			ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
			if (player == null) continue;

			// Move pending -> last before side effects so repeated ticks cannot award twice.
			all.remove(uuidString);
			NbtCompound lasts = Nbt2.sub(st.data(), "last");
			NbtCompound last = pending.copy();
			last.remove("due");
			lasts.put(uuidString, last);
			st.data().put("pending", all);
			st.data().put("last", lasts);
			st.markDirty();

			boolean win = Nbt2.i(last, "win") == 1;
			long bp = Nbt2.i(last, "bp");
			long stake = Nbt2.lng(last, "sv");
			long targetVal = Nbt2.lng(last, "tv");
			String targetId = Nbt2.str(last, "target");
			String targetName = Nbt2.str(last, "tname");
			String pct = String.format(Locale.ROOT, "%.1f", bp / 100.0);
			Item target = Registries.ITEM.get(Identifier.tryParse(targetId));
			if (win && target != null) {
				StatsManager.bump(server, uuid, StatsManager.SPIN_WINS, 1);
				StatsManager.bump(server, uuid, StatsManager.CASINO_WON, targetVal);
				if (targetVal > StatsManager.get(server, uuid, StatsManager.CASINO_BEST)) {
					StatsManager.set(server, uuid, StatsManager.CASINO_BEST, targetVal);
				}
				StatsManager.addXp(server, uuid, 15);
				player.getInventory().offerOrDrop(new ItemStack(target));
				player.sendMessage(Text.translatable("craftnet.casino.win", targetName, pct), false);
				if (bp <= BIG_WIN_BP) {
					Text broadcast = Text.translatable("craftnet.casino.broadcast",
							player.getName().getString(), targetName, pct).formatted(Formatting.GOLD);
					for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
						online.sendMessage(broadcast, false);
					}
				}
			} else {
				player.sendMessage(Text.translatable("craftnet.casino.lose", stake, pct), false);
			}
			// Outcome sound is client-timed at the end of the dial animation.
			net.craftnet.network.ServerActions.syncNow(player);
		}
	}

	public static NbtCompound pendingSpinView(MinecraftServer server, UUID uuid) {
		NbtCompound pending = Nbt2.sub(Nbt2.sub(get(server).data(), "pending"), uuid.toString());
		if (pending.isEmpty()) return pending;
		NbtCompound view = pending.copy();
		view.remove("win"); // commit phase never reveals the outcome
		return view;
	}

	public static NbtCompound lastSpinView(MinecraftServer server, UUID uuid) {
		return Nbt2.sub(Nbt2.sub(get(server).data(), "last"), uuid.toString());
	}
}
