package net.craftnet.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.craftnet.client.gui.UiKit;
import net.craftnet.village.SignalLevel;

/**
 * HUD CraftNet: палочки сигнала (2G/3G/4G), ближайшая деревня и дистанция,
 * статус GPS и баланс + НАВИГАТОР активной доставки: стрелка-указатель
 * к целевому жителю (8 направлений) с дистанцией — чтобы цель всегда
 * можно было найти даже без свечения.
 * Данные приходят компактным S2C-пушем раз в 2 секунды см. HudSyncS2CPayload.
 */
@Environment(EnvType.CLIENT)
public final class CraftNetHud {
	private CraftNetHud() {}

	private static volatile int tier = 0;
	private static volatile int towerLevel = 0;
	private static volatile String village = "";
	private static volatile int dist = -1;
	private static volatile boolean offline = false;
	private static volatile boolean gpsOk = false;
	private static volatile long balance = 0;
	private static volatile long updatedTick = -1;
	// всплывающая дельта баланса («+350 CR» / «−120 CR») — живой фидбек с HUD-пульса
	private static volatile long prevBal = -1;
	private static volatile String flashText = "";
	private static volatile boolean flashGain;
	private static volatile long flashStart = -1;
	// навигатор доставки
	private static volatile boolean navOn = false;
	private static volatile int navX, navY, navZ;
	private static volatile String navName = "";
	private static volatile String navJob = "";
	// HUD-виджет активной смены
	private static volatile boolean jobOn = false;
	private static volatile String jobType = "";
	private static volatile long jobRemainSec = 0;
	private static volatile long jobPay = 0;
	private static volatile int jobGot = 0, jobNeed = 1;
	private static volatile boolean coolFlag = false;

	public static void update(NbtCompound d) {
		tier = d.getInt("sig", 0);
		towerLevel = d.getInt("tlv", 0);
		village = d.getString("village", "");
		dist = d.getInt("dist", -1);
		offline = d.getInt("off", 0) == 1;
		gpsOk = d.getInt("gps", 0) == 1;
		MinecraftClient mc = MinecraftClient.getInstance();
		long newBal = d.getLong("bal", 0L);
		if (prevBal >= 0 && newBal != prevBal) {
			long delta = newBal - prevBal;
			flashText = (delta > 0 ? "+" : "−") + UiKit.fmt(Math.abs(delta)) + " CR";
			flashGain = delta > 0;
			flashStart = mc.world == null ? -1 : mc.world.getTime();
		}
		prevBal = newBal;
		balance = newBal;
		NbtCompound nav = d.getCompound("jobNav").orElseGet(NbtCompound::new);
		navOn = !nav.isEmpty();
		if (navOn) {
			navX = nav.getInt("x", 0);
			navY = nav.getInt("y", 0);
			navZ = nav.getInt("z", 0);
			navName = nav.getString("name", "");
			navJob = nav.getString("job", "");
		}
		NbtCompound job = d.getCompound("job").orElseGet(NbtCompound::new);
		jobOn = !job.isEmpty();
		if (jobOn) {
			jobType = job.getString("type", "");
			jobRemainSec = job.getLong("remain", 0L) / 20;
			jobPay = job.getLong("pay", 0L);
			jobGot = job.getInt("got", 0);
			jobNeed = Math.max(1, job.getInt("need", 1));
		}
		coolFlag = d.getInt("cool", 0) == 1;
		updatedTick = mc.world == null ? -1 : mc.world.getTime();
	}

	public static void render(DrawContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
		// L2/L14: без пушей больше 10 с (обрыв, лаг сервера) — гасим HUD,
		// не показываем устаревшие баланс/сеть
		if (updatedTick < 0 || mc.world.getTime() - updatedTick > 200) return;

		SignalLevel lvl = SignalLevel.byTier(tier);
		int w = ctx.getScaledWindowWidth();
		int h = ctx.getScaledWindowHeight();
		int pw = 96, ph = 34;
		int x = w - pw - 6, y = h - ph - 6;

		// панель
		ctx.fill(x + 1, y, x + pw - 1, y + ph, 0x99101014);
		ctx.fill(x, y + 1, x + pw, y + ph - 1, 0x99101014);
		frame(ctx, x, y, pw, ph, UiKit.COL_LINE);

		// палочки сигнала
		int bars = tier; // G2=1..G4=3
		for (int i = 0; i < 3; i++) {
			int bh = 5 + i * 4;
			int bx = x + 6 + i * 6;
			int by = y + ph - 6 - bh;
			int col = i < bars ? argb(lvl.colorRgb) : 0xFF3A3F4B;
			ctx.fill(bx, by, bx + 4, by + bh, col);
		}

		ctx.drawText(mc.textRenderer, Text.literal(lvl.label), x + 6 + 3 * 6 + 2, y + ph - 16,
				bars > 0 ? argb(lvl.colorRgb) : UiKit.COL_TEXT_DIM, false);
		if (bars > 0 && towerLevel > 0) {
			String tlv = "у" + towerLevel;
			ctx.drawText(mc.textRenderer, Text.literal(tlv),
					x + 6 + 3 * 6 + 4 + mc.textRenderer.getWidth(lvl.label), y + ph - 16,
					UiKit.COL_YELLOW, false);
		}

		String line1 = offline || village.isEmpty() ? "нет сети" : trim(village, 14);
		ctx.drawText(mc.textRenderer, Text.literal(line1), x + 6, y + 5,
				offline ? UiKit.COL_TEXT_DIM : UiKit.COL_TEXT, false);
		String line2 = offline || dist < 0 ? "" : (dist + " м");
		if (!line2.isEmpty()) {
			ctx.drawText(mc.textRenderer, Text.literal(line2), x + 6, y + 14, UiKit.COL_TEXT_DIM, false);
		}

		int gpsCol = gpsOk ? 0xFF55FF55 : 0xFFFF5555;
		ctx.drawText(mc.textRenderer, Text.literal("GPS"), x + pw - 26, y + 5, gpsCol, false);
		String bal = UiKit.fmt(balance) + " CR";
		ctx.drawText(mc.textRenderer, Text.literal(bal), x + pw - 6 - mc.textRenderer.getWidth(bal), y + 14,
				UiKit.COL_YELLOW, false);

		// всплывашка дельты баланса: ~3.5 с, всплывает вверх и тает
		// (при активном виджете смены — стартует над ним, не под ним)
		if (flashStart >= 0) {
			long age = mc.world.getTime() - flashStart;
			if (age < 70) {
				int alpha = age < 50 ? 255 : Math.max(20, (int) (255 * (70 - age) / 20.0));
				int col = (alpha << 24) | (flashGain ? 0x55FF55 : 0xFF5555);
				int fw = mc.textRenderer.getWidth(flashText);
				ctx.drawText(mc.textRenderer, Text.literal(flashText),
						x + pw - 6 - fw, y - (jobOn ? 44 : 10) - (int) (age / 10), col, true);
			}
		}

		if (navOn) renderNav(ctx, mc, w, h);

		// виджет активной смены над основной панелью; без смены — строка
		// «следующее окно работ» (только если в этом окне уже работали)
		if (jobOn) {
			renderJob(ctx, mc, x, y - 34, pw);
		} else if (coolFlag) {
			long t = mc.world.getTime();
			long left = Math.max(0, (12000 - (t % 12000)) / 20);
			String s = "окно работ через " + (left / 60) + ":" + String.format("%02d", left % 60);
			ctx.drawText(mc.textRenderer, Text.literal(s),
					x + pw - mc.textRenderer.getWidth(s), y - 12, UiKit.COL_TEXT_DIM, false);
		}
	}

	/** Компактная карточка смены: тип + таймер, прогресс-бар, got/need и награда. */
	private static void renderJob(DrawContext ctx, MinecraftClient mc, int x, int y, int pw) {
		int ph = 28;
		ctx.fill(x + 1, y, x + pw - 1, y + ph, 0x99101014);
		ctx.fill(x, y + 1, x + pw, y + ph - 1, 0x99101014);
		frame(ctx, x, y, pw, ph, UiKit.COL_LINE);

		String left = jobShort(jobType);
		ctx.drawText(mc.textRenderer, Text.literal(left), x + 6, y + 4, UiKit.COL_ACCENT, false);
		String time = (jobRemainSec / 60) + ":" + String.format("%02d", jobRemainSec % 60);
		ctx.drawText(mc.textRenderer, Text.literal(time),
				x + pw - 6 - mc.textRenderer.getWidth(time), y + 4,
				jobRemainSec < 120 ? UiKit.COL_RED : UiKit.COL_TEXT_DIM, false);

		int frac = (int) (Math.min(jobGot, jobNeed) * 100L / jobNeed);
		UiKit.progress(ctx, x + 6, y + 15, pw - 12, 4, frac,
				jobGot >= jobNeed ? UiKit.COL_GREEN : UiKit.COL_YELLOW);
		String pr = jobGot + "/" + jobNeed + " · " + jobPay + " CR";
		ctx.drawText(mc.textRenderer, Text.literal(pr), x + 6, y + 21, UiKit.COL_TEXT_DIM, false);
	}

	private static String jobShort(String type) {
		return switch (type) {
			case "factory" -> "завод";
			case "factory_order" -> "цеховой заказ";
			case "cook" -> "повар";
			case "loader" -> "грузчик";
			case "courier" -> "курьер";
			default -> "смена";
		};
	}

	/** Стрелка-навигатор к цели доставки (по центру верха экрана). */
	private static void renderNav(DrawContext ctx, MinecraftClient mc, int w, int h) {
		double dx = navX - mc.player.getX();
		double dz = navZ - mc.player.getZ();
		double distM = Math.sqrt(dx * dx + dz * dz);
		// направление на цель в терминах yaw игрока (0 = вперёд, + = вправо)
		double heading = Math.toDegrees(Math.atan2(-dx, dz)); // совместимо с mc-yaw
		double rel = norm(heading - mc.player.getYaw());

		int cx = w / 2;
		int cy = 26;
		int col = "courier".equals(navJob) ? 0xFFFFA63D : 0xFFFFD75E;

		// подложка
		String label = (navName.isEmpty() ? "цель" : trim(navName, 16)) + " · " + (int) distM + " м";
		int lw = mc.textRenderer.getWidth(label);
		ctx.fill(cx - lw / 2 - 10, cy - 12, cx + lw / 2 + 10, cy + 12, 0x99101014);
		frame(ctx, cx - lw / 2 - 10, cy - 12, lw + 20, 24, UiKit.COL_LINE);
		ctx.drawText(mc.textRenderer, Text.literal(label), cx - lw / 2, cy + 3, UiKit.COL_TEXT, false);

		// стрелка: 8 направлений пиксельной маской; rel 0 → вверх
		double rad = Math.toRadians(rel);
		int vx = (int) Math.round(Math.sin(rad));
		int vy = (int) Math.round(-Math.cos(rad));
		// квантование в 8 секторов
		int ax = cx + vx * 2, ay = cy - 5 + vy * 2; // тело
		drawArrowPixel(ctx, ax, ay, col);
		drawArrowPixel(ctx, cx + vx * 3, cy - 5 + vy * 3, col);
		int px = vy == 0 ? 0 : 1; // перпендикуляр для «крыльев»
		int py = vx == 0 ? 0 : 1;
		int tx = cx + vx * 4, ty = cy - 5 + vy * 4; // остриё
		drawArrowPixel(ctx, tx, ty, col);
		drawArrowPixel(ctx, tx - vx + px, ty - vy + py, col);
		drawArrowPixel(ctx, tx - vx - px, ty - vy - py, col);
	}

	private static void drawArrowPixel(DrawContext ctx, int x, int y, int col) {
		ctx.fill(x, y, x + 1, y + 1, col);
	}

	private static double norm(double a) {
		while (a > 180) a -= 360;
		while (a < -180) a += 360;
		return a;
	}

	private static int argb(int rgb) {
		return 0xFF000000 | rgb;
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static void frame(DrawContext ctx, int x, int y, int w, int h, int color) {
		ctx.drawHorizontalLine(x, x + w - 1, y, color);
		ctx.drawHorizontalLine(x, x + w - 1, y + h - 1, color);
		ctx.drawVerticalLine(x, y, y + h - 1, color);
		ctx.drawVerticalLine(x + w - 1, y, y + h - 1, color);
	}
}
