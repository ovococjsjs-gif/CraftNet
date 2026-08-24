package net.craftnet.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;

import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import net.craftnet.econ.MoneyManager;
import net.craftnet.jobs.JobManager;
import net.craftnet.village.VillageManager;

public final class AdminCommands {
	/** Аналог старого hasPermissionLevel(2) из 1.21.9+: права гейм-мастера. */
	private static final Permission OP = new Permission.Level(PermissionLevel.GAMEMASTERS);

	private AdminCommands() {}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("craftnet")
				// /craftnet signal — доступна всем
				.then(CommandManager.literal("signal").executes(ctx -> {
					ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
					VillageManager.SignalInfo sig = VillageManager.signalFor(p);
					if (sig.level() == net.craftnet.village.SignalLevel.NONE) {
						if (sig.offlineVillage()) {
							ctx.getSource().sendFeedback(() -> Text.translatable("craftnet.cmd.signal.offline", sig.villageName()), false);
						} else {
							ctx.getSource().sendFeedback(() -> Text.translatable("craftnet.cmd.signal.none"), false);
						}
					} else {
						ctx.getSource().sendFeedback(() -> Text.translatable("craftnet.cmd.signal.level",
								sig.level().label, sig.villageName(), sig.distance()), false);
					}
					return 1;
				}))

				.then(CommandManager.literal("money").requires(s -> s.getPermissions().hasPermission(OP))
						.then(CommandManager.literal("add")
								.then(CommandManager.argument("player", EntityArgumentType.player())
										.then(CommandManager.argument("amount", LongArgumentType.longArg(0))
												.executes(ctx -> {
													ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
													long amount = LongArgumentType.getLong(ctx, "amount");
													long bal = MoneyManager.add(ctx.getSource().getServer(), t.getUuid(), amount, "админ");
													ctx.getSource().sendFeedback(() -> Text.literal("Баланс " + t.getName().getString() + " = " + bal + " CR"), false);
													return 1;
												}))))
						.then(CommandManager.literal("set")
								.then(CommandManager.argument("player", EntityArgumentType.player())
										.then(CommandManager.argument("amount", LongArgumentType.longArg(0))
												.executes(ctx -> {
													ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
													long amount = LongArgumentType.getLong(ctx, "amount");
													long cur = MoneyManager.balance(ctx.getSource().getServer(), t.getUuid());
													MoneyManager.add(ctx.getSource().getServer(), t.getUuid(), amount - cur, "админ set");
													ctx.getSource().sendFeedback(() -> Text.literal("Баланс " + t.getName().getString() + " = " + amount + " CR"), false);
													return 1;
												})))))

				.then(CommandManager.literal("village").requires(s -> s.getPermissions().hasPermission(OP))
						.then(CommandManager.literal("rescan").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
							VillageManager.scanAround(p);
							long n = VillageManager.villages(ctx.getSource().getServer()).size();
							ctx.getSource().sendFeedback(() -> Text.literal("Сканирование завершено, известных деревень: " + n), false);
							return 1;
						}))
						.then(CommandManager.literal("list").executes(ctx -> {
							for (var v : VillageManager.villages(ctx.getSource().getServer())) {
								ctx.getSource().sendFeedback(() -> Text.literal(
										v.getString("name", "?") + " @ " + v.getInt("cx", 0) + "," + v.getInt("cz", 0)
												+ (v.getInt("off", 0) == 1 ? " [OFFLINE]" : " [ONLINE]")), false);
							}
							return 1;
						})))

				.then(CommandManager.literal("tower").requires(s -> s.getPermissions().hasPermission(OP))
						.then(CommandManager.literal("spawn").executes(ctx -> {
							ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
							var v = VillageManager.registerVillage(ctx.getSource().getServer(), p.getBlockPos());
							ctx.getSource().sendFeedback(() -> Text.literal("Метка «деревни» создана, вышка: "
									+ (v.getInt("ty", -1) >= 0 ? "да" : "нет места")), false);
							return 1;
						})))

				.then(CommandManager.literal("job").requires(s -> s.getPermissions().hasPermission(OP))
						.then(CommandManager.literal("cancel")
								.then(CommandManager.argument("player", EntityArgumentType.player())
										.executes(ctx -> {
											ServerPlayerEntity t = EntityArgumentType.getPlayer(ctx, "player");
											JobManager.cancel(ctx.getSource().getServer(), t.getUuid(), false);
											ctx.getSource().sendFeedback(() -> Text.literal("Задание отменено"), false);
											return 1;
										}))))

				.then(CommandManager.literal("stocktick").requires(s -> s.getPermissions().hasPermission(OP))
						.executes(ctx -> {
							net.craftnet.econ.StocksManager.tick(ctx.getSource().getServer());
							ctx.getSource().sendFeedback(() -> Text.literal("Биржа: новый тик цен"), false);
							return 1;
						})));
	}
}
