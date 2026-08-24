package net.craftnet.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import net.craftnet.block.ModBlocks;
import net.craftnet.state.VillageState;
import net.craftnet.util.Nbt2;

/**
 * Реестр деревень мира: центр, имя, вышка связи, статус сети.
 * Деревни находим лениво (locateStructure), вышки ставим процедурно
 * на кольце 44–64 блоков от центра — РЯДОМ с деревней, не внутри.
 */
public final class VillageManager {
	private VillageManager() {}

	public static final int R4G = 60;
	public static final int R3G = 160;
	public static final int R2G = 320;

	/** Радиус поиска деревень (в чанках, по уже сгенерированным — быстро). */
	private static final int LOCATE_RADIUS = 64;

	private static final String[] ADJ = {"Старые", "Верхние", "Новые", "Тихие", "Дальние", "Красные", "Зелёные", "Речные"};
	private static final String[] NOUN = {"Озёры", "Ручьи", "Полянки", "Ключи", "Пески", "Холмы", "Берёзы", "Дубравы"};

	public static VillageState state(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(VillageState.TYPE);
	}

	private static String keyOf(int x, int z) {
		return (x >> 5) + ":" + (z >> 5);
	}

	public static String villageNameFor(int x, int z) {
		int h = Math.abs((x * 73471) ^ (z * 19391) ^ 0x5f3759df);
		String base = ADJ[h % ADJ.length] + " " + NOUN[(h / 8) % NOUN.length];
		return base + " #" + Integer.toHexString(h % 0xFFF).toUpperCase();
	}

	/** Список видовых копий деревень (поля cx,cy,cz,name,tx,ty,tz,off). */
	public static List<NbtCompound> villages(MinecraftServer server) {
		List<NbtCompound> out = new ArrayList<>();
		NbtCompound map = Nbt2.sub(state(server).data(), "villages");
		for (String k : map.getKeys()) {
			map.getCompound(k).ifPresent(out::add);
		}
		return out;
	}

	/** Ближайшая известная деревня (любая). */
	public static Optional<NbtCompound> nearest(MinecraftServer server, BlockPos pos, boolean onlineOnly) {
		return villages(server).stream()
				.filter(v -> !onlineOnly || Nbt2.i(v, "off") == 0)
				.min(Comparator.comparingDouble(v -> dist2(v, pos)));
	}

	private static double dist2(NbtCompound v, BlockPos pos) {
		double dx = Nbt2.i(v, "cx") - pos.getX();
		double dz = Nbt2.i(v, "cz") - pos.getZ();
		return dx * dx + dz * dz;
	}

	public record SignalInfo(SignalLevel level, String villageName, int distance, boolean offlineVillage) {}

	/** Уровень связи для игрока: по ближайшей ОНЛАЙН деревне; офлайн-деревня рядом → пометка. */
	public static SignalInfo signalFor(ServerPlayerEntity player) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return new SignalInfo(SignalLevel.NONE, "", -1, false);
		BlockPos pos = player.getBlockPos();
		Optional<NbtCompound> online = nearest(server, pos, true);
		if (online.isPresent()) {
			int d = (int) Math.round(Math.sqrt(dist2(online.get(), pos)));
			SignalLevel lvl = d <= R4G ? SignalLevel.G4 : d <= R3G ? SignalLevel.G3 : d <= R2G ? SignalLevel.G2 : SignalLevel.NONE;
			return new SignalInfo(lvl, Nbt2.str(online.get(), "name"), d, false);
		}
		// онлайн нет: может, рядом деревня со сломанной вышкой?
		Optional<NbtCompound> any = nearest(server, pos, false);
		if (any.isPresent()) {
			int d = (int) Math.round(Math.sqrt(dist2(any.get(), pos)));
			if (d <= R2G && Nbt2.i(any.get(), "off") != 0) {
				return new SignalInfo(SignalLevel.NONE, Nbt2.str(any.get(), "name"), d, true);
			}
		}
		return new SignalInfo(SignalLevel.NONE, "", -1, false);
	}

	// ------------------- Обнаружение и регистрация -------------------

	public static void tick(MinecraftServer server, long tick) {
		if (tick % 160 != 0) return;
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		int idx = (int) ((tick / 160) % Math.max(1, players.size()));
		if (players.isEmpty()) return;
		ServerPlayerEntity p = players.get(idx % players.size());
		scanAround(p);
	}

	/** Найти ближайшую деревню и зарегистрировать (+ вышка). Вызывается редко. */
	public static void scanAround(ServerPlayerEntity player) {
		if (!(player.getEntityWorld() instanceof ServerWorld world)) return;
		MinecraftServer server = world.getServer();
		try {
			// skipReferencedStructures=true: ищем только по сгенерированным чанкам —
			// никаких тормозов от генерации, деревня всё равно найдётся,
			// как только игрок к ней подойдёт (чанки сгенерируются сами).
			BlockPos found = world.locateStructure(StructureTags.VILLAGE, player.getBlockPos(), LOCATE_RADIUS, true);
			if (found != null) {
				registerVillage(server, found);
			}
		} catch (Throwable t) {
			net.craftnet.CraftNet.LOGGER.warn("[CraftNet] Ошибка поиска деревни: {}", t.toString());
		}
	}

	/** Регистрация/слияние: если рядом (≤128 м) уже есть запись — обновляем центр. */
	public static NbtCompound registerVillage(MinecraftServer server, BlockPos center) {
		VillageState st = state(server);
		NbtCompound map = Nbt2.sub(st.data(), "villages");
		for (String k : map.getKeys()) {
			NbtCompound v = map.getCompound(k).orElseGet(NbtCompound::new);
			double dx = Nbt2.i(v, "cx") - center.getX();
			double dz = Nbt2.i(v, "cz") - center.getZ();
			if (dx * dx + dz * dz < 128 * 128) {
				ensureTower(server, v);
				map.put(k, v);
				st.data().put("villages", map);
				st.markDirty();
				return v;
			}
		}
		NbtCompound v = new NbtCompound();
		v.putInt("cx", center.getX());
		v.putInt("cy", center.getY());
		v.putInt("cz", center.getZ());
		v.putString("name", villageNameFor(center.getX(), center.getZ()));
		v.putInt("ty", -1); // нет вышки
		v.putInt("off", 0);
		ensureTower(server, v);
		map.put(keyOf(center.getX(), center.getZ()), v);
		st.data().put("villages", map);
		st.markDirty();
		return v;
	}

	/** Построить вышку на кольце от центра, если её нет. */
	private static void ensureTower(MinecraftServer server, NbtCompound v) {
		if (Nbt2.i(v, "ty") >= 0) return;
		ServerWorld world = server.getOverworld();
		BlockPos center = new BlockPos(Nbt2.i(v, "cx"), 64, Nbt2.i(v, "cz"));
		BlockPos towerPos = buildTower(world, center);
		if (towerPos != null) {
			v.putInt("tx", towerPos.getX());
			v.putInt("ty", towerPos.getY());
			v.putInt("tz", towerPos.getZ());
			net.craftnet.CraftNet.LOGGER.info("[CraftNet] Вышка связи построена: {} @ {}",
					Nbt2.str(v, "name"), towerPos.toShortString());
		}
	}

	/**
	 * Процедурная постройка вышки. 12 кандидатов по кольцу; берём первый
	 * с приемлемым рельефом. @return позиция ядра или null.
	 */
	private static BlockPos buildTower(ServerWorld world, BlockPos center) {
		int centerY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
		for (int i = 0; i < 12; i++) {
			double ang = Math.toRadians(i * 30 + 15);
			int r = 44 + (i % 3) * 6; // 44–56 блоков: рядом, но не внутри деревни
			int x = center.getX() + (int) Math.round(Math.cos(ang) * r);
			int z = center.getZ() + (int) Math.round(Math.sin(ang) * r);
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (y <= world.getBottomY() + 1) continue;
			if (Math.abs(y - centerY) > 8) continue;
			placeTowerAt(world, x, y, z);
			return new BlockPos(x, y + 12, z);
		}
		return null;
	}

	private static void placeTowerAt(ServerWorld world, int x, int y, int z) {
		var stoneBricks = Blocks.STONE_BRICKS.getDefaultState();
		var ironBars = Blocks.IRON_BARS.getDefaultState();
		// платформа 3x3 под ногами
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				world.setBlockState(new BlockPos(x + dx, y - 1, z + dz), stoneBricks, Block.NOTIFY_LISTENERS);
		// опоры по углам
		for (int dy = 0; dy <= 10; dy++) {
			world.setBlockState(new BlockPos(x - 1, y + dy, z - 1), ironBars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(x + 1, y + dy, z - 1), ironBars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(x - 1, y + dy, z + 1), ironBars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(x + 1, y + dy, z + 1), ironBars, Block.NOTIFY_LISTENERS);
		}
		// центральная мачта
		for (int dy = 0; dy <= 12; dy++)
			world.setBlockState(new BlockPos(x, y + dy, z), ironBars, Block.NOTIFY_LISTENERS);
		// площадка
		var slab = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				if (!(dx == 0 && dz == 0))
					world.setBlockState(new BlockPos(x + dx, y + 11, z + dz), slab, Block.NOTIFY_LISTENERS);
		// ядро и огонёк
		world.setBlockState(new BlockPos(x, y + 12, z), ModBlocks.TOWER_CORE.getDefaultState(), Block.NOTIFY_LISTENERS);
		world.setBlockState(new BlockPos(x, y + 13, z), Blocks.SEA_LANTERN.getDefaultState(), Block.NOTIFY_LISTENERS);
		for (int dy = 14; dy <= 16; dy++)
			world.setBlockState(new BlockPos(x, y + dy, z), ironBars, Block.NOTIFY_LISTENERS);
	}

	// ------------------- События ядра вышки -------------------

	public static void onTowerCorePlaced(ServerWorld world, BlockPos pos) {
		MinecraftServer server = world.getServer();
		// рядом с известной деревней → подключить её
		Optional<NbtCompound> near = nearest(server, pos, false);
		if (near.isPresent() && dist2(near.get(), pos) < 96 * 96) {
			VillageState st = state(server);
			NbtCompound map = Nbt2.sub(st.data(), "villages");
			for (String k : map.getKeys()) {
				NbtCompound v = map.getCompound(k).orElseGet(NbtCompound::new);
				if (Math.abs(Nbt2.i(v, "cx") - Nbt2.i(near.get(), "cx")) < 2
						&& Math.abs(Nbt2.i(v, "cz") - Nbt2.i(near.get(), "cz")) < 2) {
					v.putInt("tx", pos.getX());
					v.putInt("ty", pos.getY());
					v.putInt("tz", pos.getZ());
					v.putInt("off", 0);
					map.put(k, v);
					break;
				}
			}
			st.data().put("villages", map);
			st.markDirty();
			return;
		}
		// иначе — «базовая станция» игрока: отдельная запись
		registerManualSite(server, pos);
	}

	private static void registerManualSite(MinecraftServer server, BlockPos pos) {
		VillageState st = state(server);
		NbtCompound map = Nbt2.sub(st.data(), "villages");
		NbtCompound v = new NbtCompound();
		v.putInt("cx", pos.getX());
		v.putInt("cy", pos.getY());
		v.putInt("cz", pos.getZ());
		v.putString("name", "Антенна #" + Integer.toHexString(Math.abs(pos.hashCode()) % 0xFFF).toUpperCase());
		v.putInt("tx", pos.getX());
		v.putInt("ty", pos.getY());
		v.putInt("tz", pos.getZ());
		v.putInt("off", 0);
		v.putInt("manual", 1);
		map.put(keyOf(pos.getX(), pos.getZ()) + ":t", v);
		st.data().put("villages", map);
		st.markDirty();
	}

	public static void onTowerCoreBroken(ServerWorld world, BlockPos pos) {
		MinecraftServer server = world.getServer();
		VillageState st = state(server);
		NbtCompound map = Nbt2.sub(st.data(), "villages");
		boolean dirty = false;
		for (String k : map.getKeys()) {
			NbtCompound v = map.getCompound(k).orElseGet(NbtCompound::new);
			boolean towerHere = Nbt2.i(v, "ty") == pos.getY()
					&& Nbt2.i(v, "tx") == pos.getX()
					&& Nbt2.i(v, "tz") == pos.getZ();
			boolean nearTower = Nbt2.i(v, "ty") < 0
					&& dist2(v, pos) < 96 * 96;
			if (towerHere || nearTower) {
				v.putInt("off", 1);
				map.put(k, v);
				dirty = true;
				net.craftnet.CraftNet.LOGGER.info("[CraftNet] Вышка уничтожена — {} офлайн", Nbt2.str(v, "name"));
			}
		}
		if (dirty) {
			st.data().put("villages", map);
			st.markDirty();
		}
	}

	/** Список деревень для GPS (12 ближайших, онлайн-статус). */
	public static List<NbtCompound> villagesForGps(ServerPlayerEntity player) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return List.of();
		BlockPos pos = player.getBlockPos();
		List<NbtCompound> all = villages(server);
		all.sort(Comparator.comparingDouble(v -> dist2(v, pos)));
		List<NbtCompound> out = new ArrayList<>();
		for (int i = 0; i < Math.min(12, all.size()); i++) {
			NbtCompound v = all.get(i);
			NbtCompound view = new NbtCompound();
			view.putString("name", Nbt2.str(v, "name"));
			view.putInt("x", Nbt2.i(v, "cx"));
			view.putInt("y", Nbt2.i(v, "cy"));
			view.putInt("z", Nbt2.i(v, "cz"));
			view.putInt("d", (int) Math.round(Math.sqrt(dist2(v, pos))));
			view.putInt("off", Nbt2.i(v, "off"));
			out.add(view);
		}
		return out;
	}
}
