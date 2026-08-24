package net.craftnet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import net.craftnet.block.ModBlocks;
import net.craftnet.command.AdminCommands;
import net.craftnet.component.ModComponents;
import net.craftnet.econ.MoneyManager;
import net.craftnet.econ.StocksManager;
import net.craftnet.item.ModItems;
import net.craftnet.jobs.JobManager;
import net.craftnet.network.ModPackets;
import net.craftnet.network.ServerActions;
import net.craftnet.orders.OrderManager;
import net.craftnet.village.VillageManager;
import net.craftnet.village.VillageStructureInjector;

public class CraftNet implements ModInitializer {
	public static final String MOD_ID = "craftnet";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static MinecraftServer server;

	public static MinecraftServer getServer() {
		return server;
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("[CraftNet] Инициализация...");

		ModComponents.register();
		ModItems.register();
		ModBlocks.register();
		ModPackets.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				AdminCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			VillageStructureInjector.inject(s);
			StocksManager.ensureDefaults(s);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);

		// Выдать телефон новичку со стартовым капиталом
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayerEntity player = handler.getPlayer();
			if (MoneyManager.consumeFirstJoinFlag(s, player.getUuid())) {
				player.getInventory().insertStack(new ItemStack(ModItems.PHONE));
				player.sendMessage(Text.translatable("craftnet.welcome_phone"), false);
			}
		});

		// ПКМ по NPC персонала / целевым жителям
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient) return ActionResult.PASS;
			if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
			return ServerActions.handleEntityInteract(sp, entity);
		});

		// Серверный тик: доставка, биржа, деревни/вышки, задания, GUI-синхронизация
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			long tick = s.getTicks();
			OrderManager.tick(s);
			if (tick % 600 == 0) StocksManager.tick(s);
			VillageManager.tick(s, tick);
			JobManager.tick(s, tick);
			ServerActions.tickOpenScreens(s, tick);
		});

		LOGGER.info("[CraftNet] Готово. Стабильного 4G!");
	}
}
