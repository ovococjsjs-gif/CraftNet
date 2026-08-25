package net.craftnet.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;


import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import net.craftnet.block.ModBlocks;
import net.craftnet.econ.MoneyManager;
import net.craftnet.state.VillageState;
import net.craftnet.stats.StatsManager;
import net.craftnet.util.Nbt2;

/**
 * Реестр деревень мира: центр, имя, вышка связи, статус сети.
 * Деревни находим лениво (locateStructure), вышки ставим процедурно
 * на кольце 44–64 блоков от центра — РЯДОМ с деревней, не внутри.
 *
 * Апгрейды вышки (ур. 0..4, ПКМ по ядру, оплата с банковского счёта):
 *  - растут радиусы покрытия (×1.0 → ×2.75 по таблице RADIUS_MULT);
 *  - с ур.3 ускоряется логистика (время доставки считается тиром ниже);
 *  - с ур.4 «умная вышка»: биржа работает уже в 2G и даёт скидку 5%
 *    в онлайн-магазине всем, кого она покрывает;
 *  - уровень хранится в записи деревни и переживает слом/ремонт ядра;
 *  - мачта визуально растёт с каждым уровнем (достраивается сегментами).
 */
public final class VillageManager {
	private VillageManager() {}

	// Базовое покрытие вышки (ур.0), блоков
	public static final int R4G = 120;
	public static final int R3G = 320;
	public static final int R2G = 640;

	// -------------------- апгрейды вышки --------------------
	public static final int LVL_MAX = 4;
	/** Имена уровней 0..4. */
	public static final String[] LVL_NAMES = {
			"Базовый комплект", "Усилитель", "Фазированная решётка",
			"Дальнобойная мачта", "Умная вышка"};
	/** Цена перехода i → i+1 (i = 0..LVL_MAX-1), CR. */
	public static final int[] UPGRADE_COSTS = {500, 1500, 4000, 9000};
	/** Множитель радиусов по уровню. */
	public static final double[] RADIUS_MULT = {1.0, 1.35, 1.75, 2.25, 2.75};
	/** На сколько тиров быстрее доставка (вычитается из множителя времени). */
	public static final int[] LOGISTICS_BONUS = {0, 0, 0, 1, 2};
	/** Скидка магазина при ур.4 (доля). */
	public static final double SMART_TOWER_DISCOUNT = net.craftnet.config.CraftNetConfig.get().shopSmartTowerDiscountPct / 100.0;

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

	/** Уровень вышки деревни (0..LVL_MAX). */
	public static int tlvOf(NbtCompound v) {
		return Math.max(0, Math.min(LVL_MAX, Nbt2.i(v, "tlv")));
	}

	/** Масштабированный радиус для уровня. */
	public static int scaledRadius(int base, int tlv) {
		return (int) Math.round(base * RADIUS_MULT[Math.max(0, Math.min(LVL_MAX, tlv))]);
	}

	/** Список видовых копий деревень (поля cx,cy,cz,name,tx,ty,tz,off,tlv). */
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

	/**
	 * Итог связи для игрока. Обслуживает ЛУЧШАЯ вышка (по тиру, при равенстве —
	 * по близости) среди всех онлайн-деревень: прокачанная дальняя может
	 * обгонять базовую ближнюю. vx/vy/vz — центр обслуживающей деревни.
	 */
	public record SignalInfo(SignalLevel level, String villageName, int distance,
			boolean offlineVillage, int towerLevel, int vx, int vy, int vz) {}

	/** Уровень связи для игрока с учётом уровней вышек. */
	public static SignalInfo signalFor(ServerPlayerEntity player) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return new SignalInfo(SignalLevel.NONE, "", -1, false, 0, 0, 0, 0);
		BlockPos pos = player.getBlockPos();
		SignalLevel best = SignalLevel.NONE;
		NbtCompound bestV = null;
		double bestD = -1;
		for (NbtCompound v : villages(server)) {
			if (Nbt2.i(v, "off") != 0 || Nbt2.i(v, "ty") < 0) continue;
			int tlv = tlvOf(v);
			double d = Math.sqrt(dist2(v, pos));
			SignalLevel lvl = d <= scaledRadius(R4G, tlv) ? SignalLevel.G4
					: d <= scaledRadius(R3G, tlv) ? SignalLevel.G3
					: d <= scaledRadius(R2G, tlv) ? SignalLevel.G2 : SignalLevel.NONE;
			if (lvl.tier > best.tier || (lvl == best && lvl != SignalLevel.NONE && (bestD < 0 || d < bestD))) {
				best = lvl;
				bestV = v;
				bestD = d;
			}
		}
		if (bestV != null) {
			return new SignalInfo(best, Nbt2.str(bestV, "name"), (int) Math.round(bestD), false,
					tlvOf(bestV), Nbt2.i(bestV, "cx"), Nbt2.i(bestV, "cy"), Nbt2.i(bestV, "cz"));
		}
		// онлайн нет: может, рядом деревня со сломанной вышкой?
		for (NbtCompound v : villages(server)) {
			if (Nbt2.i(v, "off") == 0) continue;
			int tlv = tlvOf(v);
			double d = Math.sqrt(dist2(v, pos));
			if (d <= scaledRadius(R2G, tlv)) {
				return new SignalInfo(SignalLevel.NONE, Nbt2.str(v, "name"), (int) Math.round(d), true,
						tlv, Nbt2.i(v, "cx"), Nbt2.i(v, "cy"), Nbt2.i(v, "cz"));
			}
		}
		return new SignalInfo(SignalLevel.NONE, "", -1, false, 0, 0, 0, 0);
	}

	/** Множитель времени доставки с учётом логистики ур.3-4 (0 = нет сети). */
	public static int travelMultiplier(SignalInfo sig) {
		int mult = sig.level().travelMultiplier();
		if (mult <= 0) return 0;
		return Math.max(1, mult - LOGISTICS_BONUS[sig.towerLevel()]);
	}

	/** Ценовой множитель магазина («умная вышка» ур.4 даёт −5%). */
	public static double shopPriceFactor(SignalInfo sig) {
		return sig.towerLevel() >= LVL_MAX ? 1.0 - SMART_TOWER_DISCOUNT : 1.0;
	}

	/** Торговать на бирже можно с 3G, а в покрытии «умной вышки» ур.4 — уже с 2G. */
	public static boolean stocksAllowed(SignalInfo sig) {
		return sig.level().tier >= SignalLevel.G3.tier
				|| (sig.towerLevel() >= LVL_MAX && sig.level().tier >= SignalLevel.G2.tier);
	}

	// ------------------- Обнаружение и регистрация -------------------

	public static void tick(MinecraftServer server, long tick) {
		if (tick % 20 == 0) drainPending(server);
		if (tick % 120 == 60) retryUnsettled(server);
		if (tick % 160 != 0) return;
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		int idx = (int) ((tick / 160) % Math.max(1, players.size()));
		if (players.isEmpty()) return;
		ServerPlayerEntity p = players.get(idx % players.size());
		scanAround(p);
	}

	/**
	 * Чанк только что загружен: если в нём началась деревня — отложить
	 * регистрацию (мир правим только в главном тике, не из потока генерации).
	 * Благодаря этому вышка и здания появляются сразу, а не после скана.
	 */
	public static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
		MinecraftServer server = world.getServer();
		if (server == null) return;
		var starts = chunk.getStructureStarts();
		if (starts.isEmpty()) return;
		Registry<net.minecraft.world.gen.structure.Structure> strReg =
				server.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
		for (var e : starts.entrySet()) {
			Identifier id = strReg.getId(e.getKey());
			if (id == null || !id.getPath().startsWith("village_")) continue;
			if (e.getValue().getChildren().isEmpty()) continue; // пустой старт — не деревня
			enqueuePending(server, chunk.getPos().getCenterX(), chunk.getPos().getCenterZ());
			return;
		}
	}

	private static void enqueuePending(MinecraftServer server, int x, int z) {
		VillageState st = state(server);
		NbtList pending = st.data().getListOrEmpty("pendingV");
		for (int i = 0; i < pending.size(); i++) {
			if (pending.get(i) instanceof NbtCompound c
					&& Nbt2.i(c, "x") == x && Nbt2.i(c, "z") == z) return;
		}
		NbtCompound p = new NbtCompound();
		p.putInt("x", x);
		p.putInt("z", z);
		pending.add(p);
		st.data().put("pendingV", pending);
		st.markDirty();
	}

	/** Разбираем очередь загруженных деревенских чанков (до 2 за прогон). */
	private static void drainPending(MinecraftServer server) {
		VillageState st = state(server);
		NbtList pending = st.data().getListOrEmpty("pendingV");
		if (pending.isEmpty()) return;
		int done = 0;
		while (!pending.isEmpty() && done < 2) {
			if (pending.get(0) instanceof NbtCompound c) {
				registerVillage(server, new BlockPos(Nbt2.i(c, "x"), 64, Nbt2.i(c, "z")));
			}
			pending.remove(0);
			done++;
		}
		st.data().put("pendingV", pending);
		st.markDirty();
	}

	/**
	 * Достройка: деревни, где вышка ещё не встала (чанки не были готовы)
	 * или здания размещены не все — пробуем снова, но не вечно (30 попыток).
	 */
	private static void retryUnsettled(MinecraftServer server) {
		VillageState st = state(server);
		NbtCompound map = Nbt2.sub(st.data(), "villages");
		boolean dirty = false;
		for (String k : map.getKeys()) {
			NbtCompound v = map.getCompound(k).orElseGet(NbtCompound::new);
			boolean needTower = Nbt2.i(v, "ty") < 0;
			boolean needBld = Nbt2.i(v, "bld") == 0 && Nbt2.i(v, "btry") < 30;
			if (!needTower && !needBld) continue;
			if (needTower) {
				ensureTower(server, v);
				dirty = true;
			}
			if (needBld && Nbt2.i(v, "manual") == 0) {
				v.putInt("btry", Nbt2.i(v, "btry") + 1);
				placeVillageBuildings(server, v);
				if (Nbt2.i(v, "btry") >= 30) v.putInt("bld", 2); // сдались: что встало, то встало
				dirty = true;
			}
			map.put(k, v);
		}
		if (dirty) {
			st.data().put("villages", map);
			st.markDirty();
		}
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
				if (Nbt2.i(v, "manual") == 0) placeVillageBuildings(server, v);
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
		placeVillageBuildings(server, v);
		map.put(keyOf(center.getX(), center.getZ()), v);
		st.data().put("villages", map);
		st.markDirty();
		return v;
	}

	// ------------------- Программное размещение зданий -------------------

	private static final String[] BUILDING_IDS = {
			"craftnet:village/pvz", "craftnet:village/bank",
			"craftnet:village/factory", "craftnet:village/cafe"};

	/**
	 * Гарантированное размещение наших зданий (ПВЗ/банк/завод/кафе) рядом
	 * с деревней через StructureTemplate.place: спавним по кольцу 16–30 м
	 * от центра на ровных площадках, со случайным поворотом. NBT шаблоны
	 * содержат и NPC (теги craftnet:pvz/bank/foreman/barista) — размещаются
	 * вместе с блоками. Флаг bld: 0 = ждём, 1 = всё встало, 2 = сдалиcь.
	 */
	private static void placeVillageBuildings(MinecraftServer server, NbtCompound v) {
		if (Nbt2.i(v, "bld") != 0) return;
		ServerWorld world = server.getOverworld();
		if (world == null) return;
		int cx = Nbt2.i(v, "cx");
		int cz = Nbt2.i(v, "cz");
		int doneMask = Nbt2.i(v, "bmask"); // какие здания уже стоят
		java.util.Random rng = new java.util.Random((cx * 73856093L) ^ (cz * 19349663L));
		double baseAng = rng.nextDouble() * Math.PI * 2;
		boolean anyProgress = false;

		for (int i = 0; i < BUILDING_IDS.length; i++) {
			if ((doneMask & (1 << i)) != 0) continue;
			Optional<StructureTemplate> tplOpt = world.getStructureTemplateManager()
					.getTemplate(Identifier.of(BUILDING_IDS[i]));
			if (tplOpt.isEmpty()) {
				net.craftnet.CraftNet.LOGGER.warn("[CraftNet] Шаблон не найден: {}", BUILDING_IDS[i]);
				continue;
			}
			StructureTemplate tpl = tplOpt.get();
			boolean placedHere = false;
			for (int attempt = 0; attempt < 10 && !placedHere; attempt++) {
				double ang = baseAng + Math.PI / 2 * i + attempt * 0.35;
				int r = 16 + (i % 2) * 7 + rng.nextInt(6); // 16..29
				int x = cx + (int) Math.round(Math.cos(ang) * r);
				int z = cz + (int) Math.round(Math.sin(ang) * r);
				int y = flatSpotY(world, x, z);
				if (y < 0) continue;

				BlockRotation rot = BlockRotation.values()[rng.nextInt(4)];
				StructurePlacementData data = new StructurePlacementData().setRotation(rot);
				var size = tpl.getSize();
				BlockPos corner = computeCorner(size.getX(), size.getZ(), x, y, z, rot);
				try {
					tpl.place(world, corner, corner, data, world.getRandom(), Block.NOTIFY_LISTENERS);
					doneMask |= 1 << i;
					placedHere = true;
					anyProgress = true;
				} catch (Throwable t) {
					net.craftnet.CraftNet.LOGGER.warn("[CraftNet] Не встало здание {}: {}",
							BUILDING_IDS[i], t.toString());
				}
			}
		}
		v.putInt("bmask", doneMask);
		if (doneMask == 0b1111) {
			v.putInt("bld", 1);
			net.craftnet.CraftNet.LOGGER.info("[CraftNet] Здания размещены: {} (ПВЗ/банк/завод/кафе)",
					Nbt2.str(v, "name"));
		} else if (anyProgress) {
			net.craftnet.CraftNet.LOGGER.info("[CraftNet] Здания частично: {} (маска {})",
					Nbt2.str(v, "name"), doneMask);
		}
	}

	/**
	 * Угол размещения так, чтобы шаблон (после поворота) оказался рядом
	 * с якорной точкой (якорь примерно на углу здания).
	 */
	private static net.minecraft.util.math.BlockPos computeCorner(int sx, int sz, int x, int y, int z,
			BlockRotation rot) {
		return switch (rot) {
			case NONE -> new BlockPos(x, y, z);
			case CLOCKWISE_180 -> new BlockPos(x + sx - 1, y, z + sz - 1);
			case CLOCKWISE_90 -> new BlockPos(x, y, z + sx - 1);
			case COUNTERCLOCKWISE_90 -> new BlockPos(x + sz - 1, y, z);
		};
	}

	/**
	 * Плоская площадка под здание: замеры высот в 9 точках (±4 м),
	 * все чанки сгенерированы, разброс высот ≤ 2 блоков.
	 * @return Y поверхности или -1, если место неподходящее.
	 */
	private static int flatSpotY(ServerWorld world, int x, int z) {
		if (!world.isChunkLoaded(x >> 4, z >> 4)) return -1;
		if (!world.isChunkLoaded((x + 5) >> 4, (z + 5) >> 4)) return -1;
		if (!world.isChunkLoaded((x - 5) >> 4, (z - 5) >> 4)) return -1;
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int cy = -1;
		for (int dx = -4; dx <= 4; dx += 4) {
			for (int dz = -4; dz <= 4; dz += 4) {
				int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
				if (y <= world.getBottomY() + 1) return -1;
				if (dx == 0 && dz == 0) cy = y;
				min = Math.min(min, y);
				max = Math.max(max, y);
			}
		}
		if (max - min > 2) return -1;
		return cy;
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
			// M1: свежепостроенная вышка = деревня онлайн (иначе «вечный офлайн»,
			// если off=1 успел встать до появления вышки)
			v.putInt("off", 0);
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
			if (!world.isChunkLoaded(x >> 4, z >> 4)) continue; // чанк ещё не готов — достроим позже
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
			// M1: офлайн ставим только деревне, чьё ПРИКРЕПЛЁННОЕ ядро сломали.
			// Соседство с безвышковой деревней (ty<0) её не трогает — иначе она
			// уходила в офлайн, не имея вышки вовсе.
			if (towerHere) {
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

	// ------------------- Апгрейды вышки: действие -------------------

	/**
	 * Прокачка вышки ПКМ по ядру. Уровень копится в записи деревни (переживает
	 * слом ядра), оплата — с банковского счёта, эффект мгновенный и общий.
	 */
	public static void upgradeTower(ServerPlayerEntity player, BlockPos corePos) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return;
		VillageState st = state(server);
		NbtCompound map = Nbt2.sub(st.data(), "villages");
		String foundKey = null;
		NbtCompound found = null;
		for (String k : map.getKeys()) {
			NbtCompound v = map.getCompound(k).orElseGet(NbtCompound::new);
			if (Nbt2.i(v, "ty") == corePos.getY()
					&& Nbt2.i(v, "tx") == corePos.getX()
					&& Nbt2.i(v, "tz") == corePos.getZ()) {
				foundKey = k;
				found = v;
				break;
			}
		}
		if (found == null) {
			player.sendMessage(Text.translatable("craftnet.tower.unbound"), false);
			return;
		}
		if (Nbt2.i(found, "off") != 0) {
			player.sendMessage(Text.translatable("craftnet.tower.offline"), false);
			return;
		}
		int tlv = tlvOf(found);
		if (tlv >= LVL_MAX) {
			player.sendMessage(Text.translatable("craftnet.tower.max"), false);
			return;
		}
		int cost = UPGRADE_COSTS[tlv];
		if (!MoneyManager.tryCharge(server, player.getUuid(), cost, "апгрейд вышки")) {
			player.sendMessage(Text.translatable("craftnet.bank.no_money"), false);
			return;
		}
		found.putInt("tlv", tlv + 1);
		map.put(foundKey, found);
		st.data().put("villages", map);
		st.markDirty();

		growTower(server, found, tlv + 1);

		StatsManager.bump(server, player.getUuid(), StatsManager.TOWER_UPGRADES, 1);
		StatsManager.bump(server, player.getUuid(), StatsManager.TOWER_INVESTED, cost);
		StatsManager.addXp(server, player.getUuid(), 50);

		String name = Nbt2.str(found, "name");
		net.craftnet.CraftNet.LOGGER.info("[CraftNet] Вышка {}: ур.{} (оплатил {}, {} CR)",
				name, tlv + 1, player.getName().getString(), cost);
		Text msg = Text.translatable("craftnet.tower.upgraded",
				name, tlv + 1, LVL_NAMES[tlv + 1], player.getName().getString(), cost);
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			p.sendMessage(msg, false);
		}
		player.playSound(net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 0.9f, 1.1f);
	}

	/**
	 * Достроить мачту до уровня newTlv: кладём все сегменты ур.1..newTlv
	 * (идемпотентно — повторная перезапись не страшна, зато надёжно чинит
	 * башни, прокачанные до введения роста).
	 */
	private static void growTower(MinecraftServer server, NbtCompound v, int newTlv) {
		ServerWorld world = server.getOverworld();
		int tx = Nbt2.i(v, "tx");
		int ty = Nbt2.i(v, "ty");
		int tz = Nbt2.i(v, "tz");
		for (int l = 1; l <= newTlv; l++) {
			applyTowerLevel(world, tx, ty, tz, l);
		}
	}

	/**
	 * Один сегмент роста мачты. Ядро на (tx,ty,tz); базовая мачта занимает
	 * ty+1(огонёк)..ty+4. Уровень l=1: огонёк поднимается с ty+3 на ty+5,
	 * его место зашивается в мачту (перезапись поверх — ок).
	 * Уровень l≥2: ещё +2. С l=3 — крестовина антенн, на l=4 — лампы.
	 */
	private static void applyTowerLevel(ServerWorld world, int tx, int ty, int tz, int l) {
		var bars = Blocks.IRON_BARS.getDefaultState();
		int prev = 4 + 2 * (l - 1);
		int tip = 4 + 2 * l;
		// секцию от старого «огонька» до нового верха переписываем целиком:
		// надёжно ремонтирует мачту даже если её частично ломали
		for (int dy = prev; dy < tip; dy++) {
			world.setBlockState(new BlockPos(tx, ty + dy, tz), bars, Block.NOTIFY_LISTENERS);
		}
		world.setBlockState(new BlockPos(tx, ty + tip, tz),
				Blocks.SEA_LANTERN.getDefaultState(), Block.NOTIFY_LISTENERS);
		if (l >= 3) {
			int y = ty + tip - 1; // крестовина на верхнем решётчатом сегменте
			world.setBlockState(new BlockPos(tx + 1, y, tz), bars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx - 1, y, tz), bars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx, y, tz + 1), bars, Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx, y, tz - 1), bars, Block.NOTIFY_LISTENERS);
		}
		if (l >= LVL_MAX) {
			int y = ty + tip - 2; // лампы кольцом под крестовиной
			world.setBlockState(new BlockPos(tx + 1, y, tz),
					Blocks.REDSTONE_LAMP.getDefaultState(), Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx - 1, y, tz),
					Blocks.REDSTONE_LAMP.getDefaultState(), Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx, y, tz + 1),
					Blocks.REDSTONE_LAMP.getDefaultState(), Block.NOTIFY_LISTENERS);
			world.setBlockState(new BlockPos(tx, y, tz - 1),
					Blocks.REDSTONE_LAMP.getDefaultState(), Block.NOTIFY_LISTENERS);
		}
	}

	// ------------------- виды для клиентов -------------------

	/** Список деревень для GPS (12 ближайших, онлайн-статус, уровень и радиус 4G). */
	public static List<NbtCompound> villagesForGps(ServerPlayerEntity player) {
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return List.of();
		BlockPos pos = player.getBlockPos();
		List<NbtCompound> all = villages(server);
		all.sort(Comparator.comparingDouble(v -> dist2(v, pos)));
		List<NbtCompound> out = new ArrayList<>();
		for (int i = 0; i < Math.min(12, all.size()); i++) {
			NbtCompound v = all.get(i);
			int tlv = tlvOf(v);
			NbtCompound view = new NbtCompound();
			view.putString("name", Nbt2.str(v, "name"));
			view.putInt("x", Nbt2.i(v, "cx"));
			view.putInt("y", Nbt2.i(v, "cy"));
			view.putInt("z", Nbt2.i(v, "cz"));
			view.putInt("d", (int) Math.round(Math.sqrt(dist2(v, pos))));
			view.putInt("off", Nbt2.i(v, "off"));
			view.putInt("tlv", tlv);
			view.putInt("r4", scaledRadius(R4G, tlv));
			out.add(view);
		}
		return out;
	}

	/**
	 * Полный снапшот вышки для экрана апгрейда (всё посчитано на сервере —
	 * клиент только рисует): статус, уровни с радиусами/ценами/перками.
	 */
	public static NbtCompound towerView(ServerPlayerEntity player, BlockPos corePos) {
		NbtCompound out = new NbtCompound();
		MinecraftServer server = player.getEntityWorld().getServer();
		if (server == null) return out;
		NbtCompound found = null;
		for (NbtCompound v : villages(server)) {
			if (Nbt2.i(v, "ty") == corePos.getY()
					&& Nbt2.i(v, "tx") == corePos.getX()
					&& Nbt2.i(v, "tz") == corePos.getZ()) {
				found = v;
				break;
			}
		}
		if (found == null) {
			out.putInt("found", 0);
			return out;
		}
		out.putInt("found", 1);
		out.putString("name", Nbt2.str(found, "name"));
		out.putInt("off", Nbt2.i(found, "off"));
		out.putInt("manual", Nbt2.i(found, "manual"));
		int tlv = tlvOf(found);
		out.putInt("tlv", tlv);
		out.putInt("dist", (int) Math.round(Math.sqrt(corePos.getSquaredDistance(player.getBlockPos()))));
		out.putLong("invested", StatsManager.get(server, player.getUuid(), StatsManager.TOWER_INVESTED));

		NbtList levels = new NbtList();
		for (int l = 0; l <= LVL_MAX; l++) {
			NbtCompound row = new NbtCompound();
			row.putInt("lv", l);
			row.putString("lname", LVL_NAMES[l]);
			row.putInt("r4", scaledRadius(R4G, l));
			row.putInt("r3", scaledRadius(R3G, l));
			row.putInt("r2", scaledRadius(R2G, l));
			row.putInt("cost", l == 0 ? 0 : UPGRADE_COSTS[l - 1]);
			row.putInt("logi", LOGISTICS_BONUS[l]);
			String perk = switch (l) {
				case 0 -> "базовое покрытие";
				case 1 -> "покрытие ×1.35";
				case 2 -> "покрытие ×1.75";
				case 3 -> "покрытие ×2.25 · доставка быстрее";
				default -> "×2.75 · доставка максимум · биржа в 2G · скидка магазина −5%";
			};
			row.putString("perk", perk);
			levels.add(row);
		}
		out.put("levels", levels);
		return out;
	}
}
