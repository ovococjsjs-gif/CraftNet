package net.craftnet.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.craftnet.CraftNet;
import net.craftnet.client.gui.UiKit;
import net.craftnet.client.gl.RenderPipelinesHolder;

/**
 * GPS-карта 2.0: вид сверху из загруженных клиентом чанков в динамическую
 * текстуру 160x160. Зум: 1, 2 или 4 блока на пиксель — карта «ездит»
 * за игроком и пересэмплируется по движению, а не по таймеру.
 */
@Environment(EnvType.CLIENT)
public final class GpsMapRenderer {
	public static final int SIZE = 160;

	/** M6: синглтон — иначе каждый PhoneScreen регистрировал заново одну и ту же динамическую текстуру. */
	private static GpsMapRenderer instance;

	public static GpsMapRenderer get() {
		if (instance == null) instance = new GpsMapRenderer();
		return instance;
	}

	private final NativeImageBackedTexture texture;
	private final Identifier id = CraftNet.id("gps_dynamic");
	private boolean registered;

	private long lastTick = -1;
	private int lastCX = Integer.MIN_VALUE;
	private int lastCZ = Integer.MIN_VALUE;
	private int zoom = 1;
	private int lastZoom = -1;

	private GpsMapRenderer() {
		texture = new NativeImageBackedTexture(() -> "craftnet/gps", SIZE, SIZE, true);
	}

	private void ensureRegistered() {
		if (registered) return;
		registered = true;
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
	}

	public int zoom() {
		return zoom;
	}

	/** Цикл зума 1 → 2 → 4 → 1. */
	public void cycleZoom(int dir) {
		zoom = dir >= 0 ? (zoom == 4 ? 1 : zoom * 2) : (zoom == 1 ? 4 : zoom / 2);
		lastTick = -1; // форс-пересэмпл
	}

	/** Пересэмплировать карту вокруг игрока (по движению/зуму, не чаще 10 тиков). */
	public void resampleIfNeeded(ClientPlayerEntity player, ClientWorld world) {
		ensureRegistered();
		long tick = world.getTime();
		int cx = player.getBlockPos().getX();
		int cz = player.getBlockPos().getZ();
		boolean moved = Math.abs(cx - lastCX) > zoom * 2 || Math.abs(cz - lastCZ) > zoom * 2;
		boolean zoomChanged = zoom != lastZoom;
		if (!zoomChanged && !moved && tick - lastTick < 10) return;
		lastTick = tick;
		lastCX = cx;
		lastCZ = cz;
		lastZoom = zoom;

		NativeImage img = texture.getImage();
		if (img == null) return;
		for (int py = 0; py < SIZE; py++) {
			int wz = cz + (py - SIZE / 2) * zoom;
			for (int px = 0; px < SIZE; px++) {
				int wx = cx + (px - SIZE / 2) * zoom;
				img.setColorArgb(px, py, sampleColumn(world, wx, wz));
			}
		}
		texture.upload();
	}

	private static int sampleColumn(ClientWorld world, int wx, int wz) {
		int top;
		try {
			top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
		} catch (Throwable t) {
			return 0xFF22252B;
		}
		if (top <= world.getBottomY() + 1) return 0xFF22252B; // чанк неизвестен
		BlockPos pos = new BlockPos(wx, top - 1, wz);
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) return 0xFF2A2D33;
		int rgb;
		try {
			rgb = state.getMapColor(world, pos).color & 0xFFFFFF;
		} catch (Throwable t) {
			rgb = 0x777777;
		}
		// лёгкое затенение по высоте
		double f = 0.75 + (top - 64) * 0.006;
		if (f < 0.55) f = 0.55;
		if (f > 1.25) f = 1.25;
		int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
		int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
		int b = Math.min(255, (int) ((rgb & 0xFF) * f));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Нарисовать карту в прямоугольник x,y (160x160). */
	public void draw(DrawContext ctx, int x, int y) {
		ctx.drawTexture(RenderPipelinesHolder.guiTextured(), id, x, y, 0f, 0f, SIZE, SIZE, SIZE, SIZE);
		ctx.drawHorizontalLine(x, x + SIZE - 1, y, UiKit.COL_LINE);
		ctx.drawHorizontalLine(x, x + SIZE - 1, y + SIZE - 1, UiKit.COL_LINE);
		ctx.drawVerticalLine(x, y, y + SIZE - 1, UiKit.COL_LINE);
		ctx.drawVerticalLine(x + SIZE - 1, y, y + SIZE - 1, UiKit.COL_LINE);
	}

	public Identifier textureId() {
		return id;
	}
}
