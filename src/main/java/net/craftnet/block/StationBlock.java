package net.craftnet.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import net.craftnet.network.ServerActions;

/**
 * Рабочая станция: ПВЗ, банк, завод, кафе. ПКМ открывает соответствующий экран.
 */
public class StationBlock extends Block {

	public enum Kind {
		PVZ("pvz"), BANK("bank"), FACTORY("job:factory"), CAFE("job:cafe");

		public final String screen;

		Kind(String screen) {
			this.screen = screen;
		}
	}

	private final Kind kind;

	public StationBlock(Settings settings, Kind kind) {
		super(settings);
		this.kind = kind;
	}

	public Kind getKind() {
		return kind;
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient() && player instanceof ServerPlayerEntity sp) {
			ServerActions.openScreen(sp, kind.screen, pos);
		}
		return ActionResult.SUCCESS;
	}
}
