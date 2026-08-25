package net.craftnet.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import net.craftnet.network.ServerActions;
import net.craftnet.village.VillageManager;

/**
 * Ядро вышки связи. Установка рядом с деревней включает сеть,
 * разрушение — отключает её. ПКМ — панель апгрейдов вышки.
 */
public class TowerCoreBlock extends Block {

	public TowerCoreBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
			ServerActions.openScreen(sp, "tower", pos);
		}
		return ActionResult.SUCCESS;
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
