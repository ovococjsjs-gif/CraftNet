package net.craftnet;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.craftnet.client.NetHooks;

public class CraftNetClient implements ClientModInitializer {

	private static boolean phoneKeyWasDown;

	@Override
	public void onInitializeClient() {
		NetHooks.register();

		// Клавиша P = телефон. Опрашиваем GLFW напрямую, чтобы не зависеть
		// от переезда KeyBinding.Category в 1.21.9+.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.getWindow() == null) return;
			boolean down = GLFW.glfwGetKey(client.getWindow().getHandle(), GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
			if (down && !phoneKeyWasDown && client.currentScreen == null && client.player != null) {
				NetHooks.requestOpenPhone();
			}
			phoneKeyWasDown = down;
		});
	}
}
