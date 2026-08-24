package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Экран нанимателя: завод (бригадир) и кафе (бариста).
 * Активная смена заводчика/повара — мини-игра «сборка по схеме»:
 * сервер задаёт последовательность компонентов, игрок повторяет её кликами
 * по палитре. Три ошибки = брак (схема новая, прогресс деталей не сгорает).
 */
public class JobScreen extends CraftNetScreen {

	private static final int PW = 250;
	private static final int PH = 190;

	public JobScreen(String screenId, NbtCompound data) {
		super(screenId, Text.translatable(screenId.equals("job:cafe") ? "craftnet.job.cafe_title" : "craftnet.job.factory_title"), data);
	}

	private boolean isCafe() {
		return screenId.equals("job:cafe");
	}

	private int px() {
		return (width - PW) / 2;
	}

	private int py() {
		return (height - PH) / 2;
	}

	@Override
	protected void renderContent(DrawContext ctx, int mx, int my, float delta) {
		int x = px(), y = py();
		UiKit.panel(ctx, x, y, PW, PH, UiKit.COL_PANEL);
		ctx.drawCenteredTextWithShadow(textRenderer, title, x + PW / 2, y - 12, UiKit.COL_ACCENT);

		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 10 - textRenderer.getWidth(bal), y + 6, UiKit.COL_YELLOW, false);

		if (i(data, "hasJob") == 1) {
			renderActive(ctx, x, y + 16, mx, my);
			return;
		}
		if (isCafe()) {
			renderOffer(ctx, x, y + 16, sub(data, "offerCook"), "Повар (мини-игра + разнос)", "cook", mx, my);
			renderOffer(ctx, x, y + 100, sub(data, "offerCourier"), "Курьер (доставка еды)", "courier", mx, my);
		} else {
			renderOffer(ctx, x, y + 16, sub(data, "offerFactory"), "Сборщик деталей (мини-игра)", "factory", mx, my);
		}
	}

	// ------------------------------ активная смена ------------------------------

	private void renderActive(DrawContext ctx, int x, int y, double mx, double my) {
		NbtCompound job = sub(data, "active");
		NbtCompound jd = sub(job, "data");
		String type = str(job, "type");
		UiKit.panel(ctx, x + 8, y, PW - 16, 152, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 6, "Смена: " + jobTitle(type), UiKit.COL_GREEN);
		long since = lng(job, "since");
		long now = lng(data, "now");
		long ttl = "cook".equals(type) ? 18000 : 12000;
		long remainSec = Math.max(0, (ttl - (now - since)) / 20);
		UiKit.label(ctx, textRenderer, x + 16, y + 17, "осталось: " + (remainSec / 60) + ":"
				+ String.format("%02d", remainSec % 60) + "   награда: " + lng(jd, "pay") + " CR", UiKit.COL_TEXT_DIM);

		if (("factory".equals(type) || "cook".equals(type))) {
			if ("deliver".equals(str(jd, "stage"))) {
				renderDeliverPhase(ctx, x, y, jd, mx, my);
			} else if (str(jd, "seqNeed").isEmpty()) {
				// легаси-формат смены (из старой версии) — просто предлагаем отменить
				UiKit.label(ctx, textRenderer, x + 16, y + 34, "Формат задания устарел.", UiKit.COL_TEXT_DIM);
				UiKit.label(ctx, textRenderer, x + 16, y + 45, "Отмени смену и возьми новую.", UiKit.COL_TEXT_DIM);
			} else {
				renderAssembly(ctx, x, y, jd, mx, my);
			}
		} else {
			renderDeliveryTarget(ctx, x, y, jd);
		}

		UiKit.button(ctx, textRenderer, x + PW - 96, y + 130, 80, 16, "Отменить", mx, my, true);
		clickable(x + PW - 96, y + 130, 80, 16, () -> send("cancel", new NbtCompound()));
	}

	private String jobTitle(String type) {
		return switch (type) {
			case "factory" -> "сборщик деталей";
			case "cook" -> "повар";
			case "loader" -> "грузчик";
			case "courier" -> "курьер";
			default -> type;
		};
	}

	/** Мини-игра сборки: схема сверху, палитра компонентов снизу. */
	private void renderAssembly(DrawContext ctx, int x, int y, NbtCompound jd, double mx, double my) {
		int parts = i(jd, "parts");
		int partsNeed = i(jd, "partsNeed");
		UiKit.label(ctx, textRenderer, x + 16, y + 30,
				(isCafe() ? "Готово порций: " : "Собрано деталей: ") + parts + " / " + partsNeed, UiKit.COL_TEXT);

		// полоса прогресса
		int barX = x + 16, barY = y + 41, barW = PW - 32, barH = 5;
		ctx.fill(barX, barY, barX + barW, barY + barH, UiKit.COL_PANEL);
		int fill = partsNeed <= 0 ? 0 : (int) (barW * (parts / (double) partsNeed));
		if (fill > 0) ctx.fill(barX, barY, barX + fill, barY + barH, UiKit.COL_GREEN);

		// схема
		String[] seq = str(jd, "seqNeed").split(",");
		String haveStr = str(jd, "seqHave");
		int have = haveStr.isEmpty() ? 0 : haveStr.split(",").length;
		UiKit.label(ctx, textRenderer, x + 16, y + 52, "Схема:", UiKit.COL_TEXT_DIM);
		int sx = x + 56, sy = y + 50;
		for (int i = 0; i < seq.length; i++) {
			int slotX = sx + i * 20;
			int bg = i < have ? 0xFF1E4620 : (i == have ? UiKit.COL_PANEL : UiKit.COL_PANEL);
			ctx.fill(slotX, sy, slotX + 17, sy + 17, bg);
			if (i == have) drawFrame(ctx, slotX, sy, 17, UiKit.COL_YELLOW);
			else if (i < have) drawFrame(ctx, slotX, sy, 17, UiKit.COL_GREEN);
			else drawFrame(ctx, slotX, sy, 17, UiKit.COL_LINE);
			Item it = itemOf(seq[i]);
			if (it != null) ctx.drawItem(it.getDefaultStack(), slotX, sy);
		}

		int errors = i(jd, "errors");
		UiKit.label(ctx, textRenderer, x + 16, y + 70,
				"ошибки: " + errors + "/3" + (errors == 2 ? "  — аккуратно!" : ""), UiKit.COL_TEXT_DIM);

		// палитра
		UiKit.label(ctx, textRenderer, x + 16, y + 86, "Компоненты (кликай по схеме):", UiKit.COL_TEXT_DIM);
		String[] cats = str(jd, "cats").split(",");
		int px0 = x + 16, py0 = y + 98;
		for (int ci = 0; ci < cats.length; ci++) {
			int bx = px0 + ci * 26;
			ctx.fill(bx, py0, bx + 22, py0 + 22, UiKit.COL_PANEL);
			boolean hover = mx >= bx && mx < bx + 22 && my >= py0 && my < py0 + 22;
			drawFrame(ctx, bx, py0, 22, hover ? UiKit.COL_YELLOW : UiKit.COL_LINE);
			Item it = itemOf(cats[ci]);
			if (it != null) ctx.drawItem(new ItemStack(it), bx + 3, py0 + 3);
			final String id = cats[ci];
			clickable(bx + 1, py0 + 1, 20, 20, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("id", id);
				send("assem_click", a);
			});
		}

		UiKit.label(ctx, textRenderer, x + 16, y + 128, "схема растёт: каждая следующая на шаг длиннее", UiKit.COL_TEXT_DIM);
	}

	/** Фаза разноса (повар): список клиентов. */
	private void renderDeliverPhase(DrawContext ctx, int x, int y, NbtCompound jd, double mx, double my) {
		UiKit.label(ctx, textRenderer, x + 16, y + 30, "Готово! Разнеси порции:", UiKit.COL_YELLOW);
		int ly = y + 44;
		for (NbtCompound t : rows(jd, "targets")) {
			String nm = str(t, "name").isEmpty() ? "Житель" : str(t, "name");
			UiKit.label(ctx, textRenderer, x + 16, ly,
					"• " + nm + "  [" + i(t, "x") + ", " + i(t, "z") + "] ~" + (int) lng(t, "dist") + "м",
					UiKit.COL_TEXT);
			ly += 11;
		}
		UiKit.label(ctx, textRenderer, x + 16, ly + 4, "цели подсвечены свечением; подай ПКМ с пакетом еды", UiKit.COL_TEXT_DIM);
	}

	/** Цель доставки (грузчик/курьер). */
	private void renderDeliveryTarget(DrawContext ctx, int x, int y, NbtCompound jd) {
		NbtCompound t = sub(jd, "target");
		String nm = str(t, "name").isEmpty() ? "житель" : str(t, "name");
		UiKit.label(ctx, textRenderer, x + 16, y + 30, "доставка: " + nm, UiKit.COL_TEXT);
		UiKit.label(ctx, textRenderer, x + 16, y + 42,
				"координаты: " + i(t, "x") + ", " + i(t, "y") + ", " + i(t, "z"), UiKit.COL_TEXT_DIM);
		UiKit.label(ctx, textRenderer, x + 16, y + 54, "цель подсвечена белым свечением", UiKit.COL_TEXT_DIM);
		UiKit.label(ctx, textRenderer, x + 16, y + 66, "награда: " + lng(jd, "pay") + " CR", UiKit.COL_YELLOW);
	}

	// ------------------------------ офферы ------------------------------

	private void renderOffer(DrawContext ctx, int x, int y, NbtCompound offer, String label, String type, double mx, double my) {
		UiKit.panel(ctx, x + 8, y, PW - 16, 72, UiKit.COL_PANEL_HI);
		if (offer.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 16, y + 8, label + ": смен сейчас нет", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 16, y + 20, "загляни позже", UiKit.COL_TEXT_DIM);
			return;
		}
		UiKit.label(ctx, textRenderer, x + 16, y + 6, label, UiKit.COL_ACCENT);
		UiKit.label(ctx, textRenderer, x + 16, y + 17, str(offer, "desc"), UiKit.COL_TEXT_DIM);
		int ly = y + 30;
		if (i(offer, "partsNeed") > 0) {
			UiKit.label(ctx, textRenderer, x + 16, ly,
					("factory".equals(type) ? "деталей собрать: " : "порций приготовить и разнести: ")
							+ i(offer, "partsNeed"), UiKit.COL_TEXT);
			ly += 11;
		}
		if (i(offer, "portions") > 0) {
			UiKit.label(ctx, textRenderer, x + 16, ly, "порций: " + i(offer, "portions"), UiKit.COL_TEXT);
			ly += 11;
		}
		if ("courier".equals(type) || "loader".equals(type)) {
			NbtCompound t = sub(offer, "target");
			String nm = str(t, "name").isEmpty() ? "жителю" : str(t, "name");
			UiKit.label(ctx, textRenderer, x + 16, ly, "кому: " + nm + " (" + i(t, "x") + "," + i(t, "z") + ")", UiKit.COL_TEXT_DIM);
			ly += 11;
		}
		UiKit.label(ctx, textRenderer, x + 16, y + 58, "награда: " + lng(offer, "pay") + " CR", UiKit.COL_GREEN);
		UiKit.button(ctx, textRenderer, x + PW - 92, y + 52, 76, 15, "Принять", mx, my, true);
		clickable(x + PW - 92, y + 52, 76, 15, () -> {
			NbtCompound a = new NbtCompound();
			a.putString("type", type);
			send("accept", a);
		});
	}

	// ------------------------------ утилиты ------------------------------

	private static Item itemOf(String id) {
		return Registries.ITEM.get(Identifier.tryParse(id));
	}

	private static void drawFrame(DrawContext ctx, int x, int y, int size, int color) {
		ctx.drawHorizontalLine(x, x + size - 1, y, color);
		ctx.drawHorizontalLine(x, x + size - 1, y + size - 1, color);
		ctx.drawVerticalLine(x, y, y + size - 1, color);
		ctx.drawVerticalLine(x + size - 1, y, y + size - 1, color);
	}
}
