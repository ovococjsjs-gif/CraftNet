package net.craftnet;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.craftnet.client.NetHooks;

public class CraftNetClient implements ClientModInitializer {

	private static KeyBinding phoneKey;

	@Override
	public void onInitializeClient() {
		NetHooks.register();

		// Клавиша P = телефон. Полноценный KeyBinding (категория CraftNet),
		// переназначается в настройках управления.
		phoneKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.craftnet.phone",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_P,
				KeyBinding.Category.create(CraftNet.id("keys"))));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (phoneKey.wasPressed()) {
				if (client.currentScreen == null && client.player != null) {
					NetHooks.requestOpenPhone();
				}
			}
		});
	}
}
