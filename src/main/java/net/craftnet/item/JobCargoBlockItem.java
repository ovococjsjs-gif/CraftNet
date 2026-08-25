package net.craftnet.item;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;

import net.craftnet.jobs.JobManager;

/** A normal crate can be placed; a job-owned crate cannot be laundered via block loot. */
public class JobCargoBlockItem extends BlockItem {
	public JobCargoBlockItem(Block block, Settings settings) {
		super(block, settings);
	}

	@Override
	public ActionResult place(ItemPlacementContext context) {
		ItemStack stack = context.getStack();
		if (JobManager.isJobTagged(stack)) return ActionResult.FAIL;
		return super.place(context);
	}
}
