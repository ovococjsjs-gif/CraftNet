package net.craftnet.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import net.craftnet.village.VillageManager;

/**
 * Ядро вышки связи. Установка рядом с деревней включает сеть,
 * разрушение — отключает её.
 */
public class TowerCoreBlock extends Block {

	public TowerCoreBlock(Settings settings) {
		super(settings);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world instanceof ServerWorld sw) {
			VillageManager.onTowerCorePlaced(sw, pos);
		}
	}

	@Override
	protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
		super.onStateReplaced(state, world, pos, moved);
		if (!moved) {
			VillageManager.onTowerCoreBroken(world, pos);
		}
	}
}
