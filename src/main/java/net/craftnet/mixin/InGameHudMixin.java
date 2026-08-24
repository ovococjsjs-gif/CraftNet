package net.craftnet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;

import net.craftnet.client.hud.CraftNetHud;

/** Дорисовывает HUD сигнала/баланса поверх ванильного интерфейса. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void craftnet$renderSignalHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
		CraftNetHud.render(context);
	}
}
