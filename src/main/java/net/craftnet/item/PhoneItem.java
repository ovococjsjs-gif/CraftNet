package net.craftnet.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import net.craftnet.network.ServerActions;

public class PhoneItem extends Item {
	public PhoneItem(Settings settings) {
		super(settings);
	}

	@Override
	public ActionResult use(World world, PlayerEntity user, Hand hand) {
		if (!world.isClient() && user instanceof ServerPlayerEntity sp) {
			ServerActions.openScreen(sp, "phone", null);
		}
		return ActionResult.SUCCESS;
	}
}
