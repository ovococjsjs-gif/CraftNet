package net.craftnet.village;

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
 */
public final class VillageStructureInjector {
	private VillageStructureInjector() {}

	/** Наша структура → вес в пуле (из ~80 суммарного веса ванилы). */
	private static final Map<String, Integer> ENTRIES = Map.of(
			"craftnet:village/pvz", 6,
			"craftnet:village/bank", 6,
			"craftnet:village/factory", 4,
			"craftnet:village/cafe", 4);

	private static final String[] TYPES = {"plains", "desert", "savanna", "snowy", "taiga"};

	public static void inject(MinecraftServer server) {
		Registry<StructurePool> pools = server.getRegistryManager().getOrThrow(RegistryKeys.TEMPLATE_POOL);
		int touched = 0;
		for (String type : TYPES) {
			for (boolean zombie : new boolean[]{false, true}) {
				Identifier poolId = Identifier.of("minecraft",
						"village/" + type + (zombie ? "/zombie" : "") + "/houses");
				StructurePool pool = pools.get(poolId);
				if (pool == null) continue;
				addToPool(pool);
				touched++;
			}
		}
		CraftNet.LOGGER.info("[CraftNet] Здания добавлены в {} пулов деревень", touched);
	}

	private static void addToPool(StructurePool pool) {
		StructurePoolAccessor acc = (StructurePoolAccessor) pool;
		for (Map.Entry<String, Integer> e : ENTRIES.entrySet()) {
			StructurePoolElement element = StructurePoolElement
					.ofLegacySingle(e.getKey())
					.apply(StructurePool.Projection.RIGID);
			try {
				for (int i = 0; i < e.getValue(); i++) {
					acc.craftnet$getElements().add(element);
				}
			} catch (UnsupportedOperationException ex) {
				CraftNet.LOGGER.warn("[CraftNet] Пул неизменяем ({}): {}", e.getKey(), ex.toString());
			}
			try {
				acc.craftnet$getElementWeights().add(Pair.of(element, e.getValue()));
			} catch (UnsupportedOperationException ignored) {
				// ваниле хватает spec-списка
			}
		}
	}
}
