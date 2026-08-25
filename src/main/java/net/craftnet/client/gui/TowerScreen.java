package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/**
 * Панель вышки связи (ПКМ по ядру): статус, лесенка уровней ур.0..4
 * с радиусами и перками, кнопка прокачки за банковские CR.
 * Все числа готовит сервер (VillageManager.towerView) — экран только рисует.
 */
public class TowerScreen extends CraftNetScreen {

	private static final int PW2 = 300;
	private static final int PH2 = 200;

	public TowerScreen(NbtCompound data) {
		super("tower", Text.translatable("craftnet.tower.title"), data);
	}

	@Override
	protected void renderContent(DrawContext ctx, int mx, int my, float delta) {
		int x = (width - PW2) / 2;
		int y = (height - PH2) / 2;
		UiKit.card(ctx, x - 6, y - 6, PW2 + 12, PH2 + 12, 0xFF0B0D10);
		ctx.fill(x, y, x + PW2, y + PH2, UiKit.COL_BG);

		if (data.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Синхронизация с CraftNet…"),
					x + PW2 / 2, y + PH2 / 2 - 4, UiKit.COL_TEXT_DIM);
			return;
		}
		NbtCompound tw = sub(data, "tower");
		if (i(tw, "found") != 1) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Ядро не привязано к сети"),
					x + PW2 / 2, y + 70, UiKit.COL_RED);
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("ядро связывается с деревней в радиусе 96 м —"),
					x + PW2 / 2, y + 88, UiKit.COL_TEXT_DIM);
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("переставь его поближе к домам"),
					x + PW2 / 2, y + 99, UiKit.COL_TEXT_DIM);
			return;
		}

		String name = str(tw, "name");
		int off = i(tw, "off");
		int tlv = i(tw, "tlv");
		boolean manual = i(tw, "manual") == 1;

		// ================= заголовок =================
		UiKit.card(ctx, x + 8, y + 6, PW2 - 16, 30, UiKit.COL_PANEL_HI);
		ctx.drawItem(Items.REDSTONE_TORCH.getDefaultStack(), x + 14, y + 12);
		UiKit.label(ctx, textRenderer, x + 34, y + 11,
				trim2("Вышка «" + name + "»", 36), UiKit.COL_TEXT);
		String statusTxt = off == 1 ? "ОФЛАЙН — ядро сломано" : "онлайн · " + i(tw, "dist") + " м до ядра";
		UiKit.label(ctx, textRenderer, x + 34, y + 21, statusTxt,
				off == 1 ? UiKit.COL_RED : UiKit.COL_GREEN);
		String lvBadge = "ур." + tlv;
		int bw = textRenderer.getWidth(lvBadge);
		ctx.fill(x + PW2 - 24 - bw, y + 12, x + PW2 - 12, y + 26, 0xFF1F6FEB);
		ctx.drawText(textRenderer, Text.literal(lvBadge), x + PW2 - 18 - bw, y + 16, UiKit.COL_TEXT, false);

		// ================= лесенка уровней =================
		var levels = rows(tw, "levels");
		int ly = y + 42;
		for (NbtCompound row : levels) {
			int lv = i(row, "lv");
			boolean done = lv <= tlv;
			boolean next = lv == tlv + 1;
			int bg = done ? 0xFF1E3020 : next ? 0xFF33291A : UiKit.COL_PANEL;
			UiKit.card(ctx, x + 8, ly, PW2 - 16, 24, bg);
			// лампочка статуса
			int dot = done ? UiKit.COL_GREEN : next ? UiKit.COL_YELLOW : 0xFF3A3F4B;
			ctx.fill(x + 14, ly + 5, x + 20, ly + 11, dot);
			UiKit.label(ctx, textRenderer, x + 26, ly + 3,
					"ур." + lv + " " + str(row, "lname"),
					done ? UiKit.COL_TEXT : next ? UiKit.COL_YELLOW : UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 26, ly + 13,
					i(row, "r4") + "/" + i(row, "r3") + "/" + i(row, "r2") + " м · " + str(row, "perk"),
					UiKit.COL_TEXT_DIM);
			String right = done ? "✓"
					: i(row, "cost") > 0 ? i(row, "cost") + " CR" : "—";
			int rw = textRenderer.getWidth(right);
			ctx.drawText(textRenderer, Text.literal(right), x + PW2 - 20 - rw, ly + 8,
					done ? UiKit.COL_GREEN : UiKit.COL_YELLOW, false);
			ly += 26;
		}

		// ================= подвал: состояние + кнопка =================
		long invested = lng(tw, "invested");
		if (invested > 0) {
			UiKit.label(ctx, textRenderer, x + 10, y + PH2 - 28,
					"ваших вложений в сеть: " + invested + " CR", UiKit.COL_TEXT_DIM);
		}
		if (off == 1) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("Вышка офлайн: сначала восстановите ядро"),
					x + PW2 / 2, y + PH2 - 16, UiKit.COL_RED);
		} else if (tlv >= 4) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("МАКСИМАЛЬНЫЙ УРОВЕНЬ — умная вышка"),
					x + PW2 / 2, y + PH2 - 16, UiKit.COL_GREEN);
		} else {
			int nextCost = -1;
			for (NbtCompound row : rows(tw, "levels")) {
				if (i(row, "lv") == tlv + 1) nextCost = i(row, "cost");
			}
			boolean can = nextCost >= 0 && lng(data, "balance") >= nextCost;
			String label = "Улучшить до ур." + (tlv + 1) + " · " + nextCost + " CR";
			UiKit.button(ctx, textRenderer, x + 8, y + PH2 - 22, PW2 - 16, 16, label, mx, my, can);
			if (can) {
				clickable(x + 8, y + PH2 - 22, PW2 - 16, 16,
						() -> send("upgrade", new NbtCompound()));
			}
		}
	}

	private static String trim2(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
