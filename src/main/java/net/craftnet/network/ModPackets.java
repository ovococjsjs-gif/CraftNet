package net.craftnet.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import net.craftnet.CraftNet;

/**
 * Три обобщённых канала:
 *  - OpenScreen  S2C: открыть экран (screen, snapshot);
 *  - ScreenSync  S2C: обновить данные открытого экрана;
 *  - ScreenAction C2S: действие игрока (screen, action, args).
 */
public final class ModPackets {
	private ModPackets() {}

	public record OpenScreenS2CPayload(String screen, NbtCompound data) implements CustomPayload {
		public static final CustomPayload.Id<OpenScreenS2CPayload> ID =
				new CustomPayload.Id<>(CraftNet.id("open_screen"));
		public static final PacketCodec<RegistryByteBuf, OpenScreenS2CPayload> CODEC =
				PacketCodec.tuple(PacketCodecs.STRING, OpenScreenS2CPayload::screen,
						PacketCodecs.NBT_COMPOUND, OpenScreenS2CPayload::data,
						OpenScreenS2CPayload::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record ScreenSyncS2CPayload(String screen, NbtCompound data) implements CustomPayload {
		public static final CustomPayload.Id<ScreenSyncS2CPayload> ID =
				new CustomPayload.Id<>(CraftNet.id("screen_sync"));
		public static final PacketCodec<RegistryByteBuf, ScreenSyncS2CPayload> CODEC =
				PacketCodec.tuple(PacketCodecs.STRING, ScreenSyncS2CPayload::screen,
						PacketCodecs.NBT_COMPOUND, ScreenSyncS2CPayload::data,
						ScreenSyncS2CPayload::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record ScreenActionC2SPayload(String screen, String action, NbtCompound args) implements CustomPayload {
		public static final CustomPayload.Id<ScreenActionC2SPayload> ID =
				new CustomPayload.Id<>(CraftNet.id("screen_action"));
		public static final PacketCodec<RegistryByteBuf, ScreenActionC2SPayload> CODEC =
				PacketCodec.tuple(PacketCodecs.STRING, ScreenActionC2SPayload::screen,
						PacketCodecs.STRING, ScreenActionC2SPayload::action,
						PacketCodecs.NBT_COMPOUND, ScreenActionC2SPayload::args,
						ScreenActionC2SPayload::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(OpenScreenS2CPayload.ID, OpenScreenS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ScreenSyncS2CPayload.ID, ScreenSyncS2CPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ScreenActionC2SPayload.ID, ScreenActionC2SPayload.CODEC);

		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				ScreenActionC2SPayload.ID, (payload, context) -> {
					context.server().execute(() ->
							ServerActions.handle(context.player(), payload));
				});
	}
}
