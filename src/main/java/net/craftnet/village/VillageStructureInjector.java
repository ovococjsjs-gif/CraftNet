package net.craftnet.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.datafixers.util.Pair;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.Identifier;

import net.craftnet.CraftNet;
import net.craftnet.mixin.StructurePoolAccessor;

/**
 * Инъекция наших NBT-зданий в пулы домов всех типов деревень.
 * Запускаем на SERVER_STARTED — пулы к этому моменту собраны из датапаков.
 *
 * В 1.21.11 список elementWeights после декодирования иммутабелен, поэтому
 * тихий add() в него (как было раньше) просто падал в catch — здания не
 * генерировались вовсе. Теперь: заменяем elementWeights новым списком
 * (ваниль + мы) И добавляем развёрнутую копию в elements — так покрыты
 * оба пути семплинга пула.
 */
public final class VillageStructureInjector {
	private VillageStructureInjector() {}

	/** Наша структура → вес в пуле (из ~100+ суммарного веса ванилы). */
	private static final Map<String, Integer> ENTRIES = Map.of(
			"craftnet:village/pvz", 8,
			"craftnet:village/bank", 8,
			"craftnet:village/factory", 5,
			"craftnet:village/cafe", 5);

	private static final String[] TYPES = {"plains", "desert", "savanna", "snowy", "taiga"};

	public static void inject(MinecraftServer server) {
		Registry<StructurePool> pools = server.getRegistryManager().getOrThrow(RegistryKeys.TEMPLATE_POOL);
		int touched = 0;
		int added = 0;
		for (String type : TYPES) {
			for (boolean zombie : new boolean[]{false, true}) {
				Identifier poolId = Identifier.of("minecraft",
						"village/" + type + (zombie ? "/zombie" : "") + "/houses");
				StructurePool pool = pools.get(poolId);
				if (pool == null) continue;
				added += addToPool(pool);
				touched++;
			}
		}
		CraftNet.LOGGER.info("[CraftNet] Здания добавлены в {} пулов деревень ({} элементов)", touched, added);
	}

	private static int addToPool(StructurePool pool) {
		StructurePoolAccessor acc = (StructurePoolAccessor) pool;
		List<Pair<StructurePoolElement, Integer>> weights =
				new ArrayList<>(acc.craftnet$getElementWeights());
		int added = 0;
		for (Map.Entry<String, Integer> e : ENTRIES.entrySet()) {
			StructurePoolElement element = StructurePoolElement
					.ofLegacySingle(e.getKey())
					.apply(StructurePool.Projection.RIGID);
			// 1) взвешенный список (заменяем целиком — исходный иммутабелен)
			weights.add(Pair.of(element, e.getValue()));
			// 2) развёрнутый список, из которого тоже умеют сэмплить
			try {
				for (int i = 0; i < e.getValue(); i++) {
					acc.craftnet$getElements().add(element);
				}
			} catch (UnsupportedOperationException ex) {
				CraftNet.LOGGER.warn("[CraftNet] Развёрнутый список пула неизменяем: {}", ex.toString());
			}
			added++;
		}
		acc.craftnet$setElementWeights(weights);
		return added;
	}
}
