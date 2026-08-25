package net.craftnet.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.craftnet.client.gui.BankScreen;
import net.craftnet.client.gui.CraftNetScreen;
import net.craftnet.client.gui.JobScreen;
import net.craftnet.client.gui.PhoneScreen;
import net.craftnet.client.gui.PvzScreen;
import net.craftnet.client.gui.TowerScreen;
import net.craftnet.network.ModPackets;

/** Клиентские приёмники пакетов и фабрика экранов. */
public final class NetHooks {
	private NetHooks() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ModPackets.OpenScreenS2CPayload.ID, (payload, context) ->
				context.client().execute(() -> openScreen(payload.screen(), payload.data())));

		ClientPlayNetworking.registerGlobalReceiver(ModPackets.ScreenSyncS2CPayload.ID, (payload, context) ->
				context.client().execute(() -> onSync(payload)));

		ClientPlayNetworking.registerGlobalReceiver(ModPackets.HudSyncS2CPayload.ID, (payload, context) ->
				context.client().execute(() ->
						net.craftnet.client.hud.CraftNetHud.update(payload.data())));
	}

	private static void openScreen(String screenId, net.minecraft.nbt.NbtCompound data) {
		MinecraftClient client = MinecraftClient.getInstance();
		CraftNetScreen screen = switch (screenId) {
			case "phone" -> new PhoneScreen(data);
			case "pvz" -> new PvzScreen(data);
			case "bank" -> new BankScreen(data);
			case "tower" -> new TowerScreen(data);
			case "job:factory" -> new JobScreen("job:factory", data);
			case "job:cafe" -> new JobScreen("job:cafe", data);
			default -> null;
		};
		if (screen != null) {
			client.setScreen(screen);
		}
	}

	private static void onSync(ModPackets.ScreenSyncS2CPayload payload) {
		Screen current = MinecraftClient.getInstance().currentScreen;
		if (current instanceof CraftNetScreen cns && cns.screenId.equals(payload.screen())) {
			cns.sync(payload.data());
		}
	}

	public static void sendAction(String screen, String action, net.minecraft.nbt.NbtCompound args) {
		ClientPlayNetworking.send(new ModPackets.ScreenActionC2SPayload(screen, action, args));
	}

	/** Открыть телефон (клавиша P — опрос GLFW, без регистрации KeyBinding). */
	public static void requestOpenPhone() {
		net.minecraft.nbt.NbtCompound args = new net.minecraft.nbt.NbtCompound();
		args.putString("screen", "phone");
		sendAction("any", "open", args);
	}
}
