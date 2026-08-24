package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/**
 * Экран нанимателя: завод (бригадир) и кафе (бариста).
 * Показывает офферы, активное задание, кнопки принять/сдать/отменить.
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
			renderOffer(ctx, x, y + 16, sub(data, "offerCook"), "Повар (×1.15)", "cook", mx, my);
			renderOffer(ctx, x, y + 100, sub(data, "offerCourier"), "Курьер (×1.0)", "courier", mx, my);
		} else {
			renderOffer(ctx, x, y + 16, sub(data, "offerFactory"), "Сборщик (×1.6)", "factory", mx, my);
		}
	}

	private void renderActive(DrawContext ctx, int x, int y, double mx, double my) {
		NbtCompound job = sub(data, "active");
		NbtCompound jd = sub(job, "data");
		String type = str(job, "type");
		UiKit.panel(ctx, x + 8, y, PW - 16, 130, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 6, "Активное задание: " + type, UiKit.COL_GREEN);
		long since = lng(job, "since");
		long now = lng(data, "now");
		long remainSec = Math.max(0, (12000 - (now - since)) / 20);
		UiKit.label(ctx, textRenderer, x + 16, y + 17, "осталось: " + (remainSec / 60) + ":" + String.format("%02d", remainSec % 60), UiKit.COL_TEXT_DIM);

		if ("factory".equals(type) || "cook".equals(type)) {
			int ly = y + 30;
			NbtCompound items = sub(jd, "items");
			for (String id : items.getKeys()) {
				UiKit.label(ctx, textRenderer, x + 16, ly, shortName(id) + " ×" + items.getInt(id, 0), UiKit.COL_TEXT);
				ly += 11;
			}
			UiKit.label(ctx, textRenderer, x + 16, ly + 2, "награда: " + lng(jd, "pay") + " CR", UiKit.COL_YELLOW);
			UiKit.button(ctx, textRenderer, x + 16, y + 106, 90, 16, "Сдать заказ", mx, my, true);
			clickable(x + 16, y + 106, 90, 16, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("type", type);
				send("complete", a);
			});
		} else {
			NbtCompound t = sub(jd, "target");
			String nm = str(t, "name").isEmpty() ? "житель" : str(t, "name");
			UiKit.label(ctx, textRenderer, x + 16, y + 30, "доставка: " + nm, UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 16, y + 42,
					"координаты: " + t.getInt("x", 0) + ", " + t.getInt("y", 0) + ", " + t.getInt("z", 0), UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 16, y + 54, "цель подсвечена белым свечением", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 16, y + 66, "награда: " + lng(jd, "pay") + " CR", UiKit.COL_YELLOW);
		}

		UiKit.button(ctx, textRenderer, x + PW - 96, y + 106, 80, 16, "Отменить", mx, my, true);
		clickable(x + PW - 96, y + 106, 80, 16, () -> send("cancel", new NbtCompound()));
	}

	private void renderOffer(DrawContext ctx, int x, int y, NbtCompound offer, String label, String type, double mx, double my) {
		if (offer.isEmpty()) {
			UiKit.panel(ctx, x + 8, y, PW - 16, 72, UiKit.COL_PANEL_HI);
			UiKit.label(ctx, textRenderer, x + 16, y + 8, label + ": оффер недоступен", UiKit.COL_TEXT_DIM);
			return;
		}
		UiKit.panel(ctx, x + 8, y, PW - 16, 72, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 6, label, UiKit.COL_ACCENT);
		UiKit.label(ctx, textRenderer, x + 16, y + 17, str(offer, "desc"), UiKit.COL_TEXT_DIM);
		int ly = y + 28;
		var readable = rows(offer, "readable");
		if (readable.isEmpty() && i(offer, "portions") > 0) {
			UiKit.label(ctx, textRenderer, x + 16, ly, "порций: " + i(offer, "portions"), UiKit.COL_TEXT);
			ly += 11;
		}
		for (NbtCompound r : readable) {
			UiKit.label(ctx, textRenderer, x + 16, ly, str(r, "name") + " ×" + i(r, "count"), UiKit.COL_TEXT);
			ly += 11;
		}
		if ("courier".equals(type) || "loader".equals(type)) {
			NbtCompound t = sub(offer, "target");
			String nm = str(t, "name").isEmpty() ? "жителю" : str(t, "name");
			UiKit.label(ctx, textRenderer, x + 16, ly, "кому: " + nm + " (" + t.getInt("x", 0) + "," + t.getInt("z", 0) + ")", UiKit.COL_TEXT_DIM);
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

	private static String shortName(String id) {
		int k = id.indexOf(':');
		return k > 0 ? id.substring(k + 1) : id;
	}
}
