package net.craftnet.mixin;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructurePool.class)
public interface StructurePoolAccessor {

	@Accessor("elements")
	ObjectArrayList<StructurePoolElement> craftnet$getElements();

	@Accessor("elementWeights")
	List<Pair<StructurePoolElement, Integer>> craftnet$getElementWeights();
}
