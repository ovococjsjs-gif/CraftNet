package net.craftnet.mixin;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к внутренностям пула структур. В 1.21.11 декодированный
 * elementWeights иммутабелен — поэтому список ЗАМЕНЯЕМ целиком
 * (@Mutable-сеттер), а не мутируем на месте.
 */
@Mixin(StructurePool.class)
public interface StructurePoolAccessor {

	@Accessor("elements")
	ObjectArrayList<StructurePoolElement> craftnet$getElements();

	@Accessor("elementWeights")
	List<Pair<StructurePoolElement, Integer>> craftnet$getElementWeights();

	@Mutable
	@Accessor("elementWeights")
	void craftnet$setElementWeights(List<Pair<StructurePoolElement, Integer>> list);
}
