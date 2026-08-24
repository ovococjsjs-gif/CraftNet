package net.craftnet.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Минимальный «дизайн-система» CraftNet: панели, кнопки, инпуты.
 * Всё рисуется заливками + текстом, без внешних текстур-GUI.
 */
public final class UiKit {
	private UiKit() {}

	public static final int COL_BG = 0xEE15171C;          // тёмный фон телефона
	public static final int COL_PANEL = 0xFF23262E;       // карточка
	public static final int COL_PANEL_HI = 0xFF2D313B;    // карточка светлее
	public static final int COL_ACCENT = 0xFF58A6FF;      // голубой акцент
	public static final int COL_GREEN = 0xFF3FB950;
	public static final int COL_RED = 0xFFF85149;
	public static final int COL_YELLOW = 0xFFD29922;
	public static final int COL_TEXT = 0xFFE6EDF3;
	public static final int COL_TEXT_DIM = 0xFF8B949E;
	public static final int COL_LINE = 0xFF3A3F4B;
	public static final int COL_GREEN_DIM = 0xFF1E4620;
	public static final int COL_TRACK = 0xFF181B21;

	/** Звук клика кнопки (как у ванильных виджетов). */
	public static void clickSound() {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc != null && mc.getSoundManager() != null) {
			mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
					net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
		}
	}

	/** Прогресс-бар: frac 0..100. */
	public static void progress(DrawContext ctx, int x, int y, int w, int h, int frac, int fillCol) {
		ctx.fill(x, y, x + w, y + h, COL_TRACK);
		int f = Math.max(0, Math.min(100, frac)) * w / 100;
		if (f > 0) ctx.fill(x, y, x + f, y + h, fillCol);
	}

	/** Панель со «скруглёнными» углами (уголки не закрашены) — современный вид. */
	public static void card(DrawContext ctx, int x, int y, int w, int h, int bg) {
		ctx.fill(x + 1, y, x + w - 1, y + h, bg);
		ctx.fill(x, y + 1, x + w, y + h - 1, bg);
		ctx.drawHorizontalLine(x + 1, x + w - 2, y, COL_LINE);
		ctx.drawHorizontalLine(x + 1, x + w - 2, y + h - 1, COL_LINE);
		ctx.drawVerticalLine(x, y + 1, y + h - 2, COL_LINE);
		ctx.drawVerticalLine(x + w - 1, y + 1, y + h - 2, COL_LINE);
	}

	public static void panel(DrawContext ctx, int x, int y, int w, int h, int bg) {
		ctx.fill(x, y, x + w, y + h, bg);
		ctx.drawHorizontalLine(x, x + w - 1, y, COL_LINE);
		ctx.drawHorizontalLine(x, x + w - 1, y + h - 1, COL_LINE);
		ctx.drawVerticalLine(x, y, y + h - 1, COL_LINE);
		ctx.drawVerticalLine(x + w - 1, y, y + h - 1, COL_LINE);
	}

	/** @return наведена ли мышь. */
	public static boolean button(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
			String text, double mx, double my, boolean enabled) {
		boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
		int bg = !enabled ? 0xFF3A3F4B : hover ? 0xFF1F6FEB : 0xFF264F9E;
		ctx.fill(x, y, x + w, y + h, bg);
		ctx.drawHorizontalLine(x, x + w - 1, y, 0x44FFFFFF);
		ctx.drawHorizontalLine(x, x + w - 1, y + h - 1, 0x44000000);
		int tw = tr.getWidth(text);
		ctx.drawText(tr, Text.literal(text), x + (w - tw) / 2, y + (h - 8) / 2, enabled ? COL_TEXT : COL_TEXT_DIM, false);
		return hover;
	}

	public static void label(DrawContext ctx, TextRenderer tr, int x, int y, String s, int color) {
		ctx.drawText(tr, Text.literal(s), x, y, color, false);
	}

	/** Лёгкое текстовое поле без виджетов. */
	public static final class TextInput {
		public int x, y, w;
		public String value = "";
		public String hint = "";
		public boolean focused;
		public boolean digitsOnly;
		public int maxLen = 24;
		private int caretBlink;

		public TextInput(int x, int y, int w, String hint, boolean digitsOnly) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.hint = hint;
			this.digitsOnly = digitsOnly;
		}

		public void render(DrawContext ctx, TextRenderer tr) {
			int bg = focused ? 0xFF2D313B : 0xFF1E2128;
			ctx.fill(x, y, x + w, y + 14, bg);
			ctx.drawHorizontalLine(x, x + w - 1, y + 13, focused ? COL_ACCENT : COL_LINE);
			String shown = value;
			int color = COL_TEXT;
			if (shown.isEmpty()) {
				shown = hint;
				color = COL_TEXT_DIM;
			}
			ctx.enableScissor(x + 2, y, x + w - 2, y + 14);
			ctx.drawText(tr, Text.literal(shown), x + 3, y + 3, color, false);
			if (focused && (caretBlink++ / 10) % 2 == 0) {
				int cx = x + 3 + tr.getWidth(value);
				ctx.fill(cx, y + 3, cx + 1, y + 11, COL_ACCENT);
			}
			ctx.disableScissor();
		}

		public boolean mouseDown(double mx, double my) {
			boolean hit = mx >= x && mx < x + w && my >= y && my < y + 14;
			focused = hit;
			return hit;
		}

		public boolean type(char c) {
			if (!focused) return false;
			if (digitsOnly && (c < '0' || c > '9')) return true;
			if (value.length() >= maxLen) return true;
			value += c;
			return true;
		}

		/** @return true если клавиша обработана. */
		public boolean key(int keyCode) {
			if (!focused) return false;
			if (keyCode == 259 && !value.isEmpty()) { // backspace
				value = value.substring(0, value.length() - 1);
				return true;
			}
			if (keyCode == 257 || keyCode == 256) { // enter/esc — снять фокус
				focused = false;
				return true;
			}
			return true; // всё остальное глотаем, пока в фокусе
		}
	}
}
