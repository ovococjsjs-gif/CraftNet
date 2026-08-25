package net.craftnet.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Экран нанимателя 2.0.
 *
 * ЗАВОД (бригадир): мини-игра «сборка по схеме» + цеховой заказ (крафт).
 * КАФЕ (бариста): повар (крафт-заказ блюд) и курьер (доставка) — раздельно.
 *
 * Активный крафт-заказ: список целей с прогрессом по инвентарю игрока,
 * список выданных материалов, кнопка «Сдать заказ», предупреждение о штрафе.
 */
public class JobScreen extends CraftNetScreen {

	private static final int PW = 288;
	private static final int PH = 212;

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
		UiKit.card(ctx, x, y, PW, PH, UiKit.COL_PANEL);
		ctx.drawCenteredTextWithShadow(textRenderer, title, x + PW / 2, y - 12, UiKit.COL_ACCENT);

		String bal = UiKit.fmt(lng(data, "balance")) + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 10 - textRenderer.getWidth(bal), y + 6, UiKit.COL_YELLOW, false);

		if (i(data, "hasJob") == 1) {
			renderActive(ctx, x + 8, y + 18, mx, my);
			return;
		}
		if (isCafe()) {
			renderOffer(ctx, x + 8, y + 18, PW - 16, 92, sub(data, "offerCook"),
					"Повар · крафт-заказ кухни", "cook", mx, my);
			renderOffer(ctx, x + 8, y + 116, PW - 16, 92, sub(data, "offerCourier"),
					"Курьер · доставка пакетов", "courier", mx, my);
		} else {
			renderOffer(ctx, x + 8, y + 18, PW - 16, 92, sub(data, "offerFactoryOrder"),
					"Цеховой заказ · крафт из выданных материалов", "factory_order", mx, my);
			renderOffer(ctx, x + 8, y + 116, PW - 16, 92, sub(data, "offerFactory"),
					"Сборка по схеме · мини-игра", "factory", mx, my);
		}
	}

	// ------------------------------ активная смена ------------------------------

	private void renderActive(DrawContext ctx, int x, int y, double mx, double my) {
		NbtCompound job = sub(data, "active");
		NbtCompound jd = sub(job, "data");
		String type = str(job, "type");
		UiKit.card(ctx, x, y, PW - 16, 184, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 10, y + 8, "Смена: " + jobTitle(type), UiKit.COL_GREEN);
		long since = lng(job, "since");
		long now = lng(data, "now");
		long ttl = lng(jd, "ttl");
		if (ttl <= 0) ttl = 12000;
		long remainSec = Math.max(0, (ttl - (now - since)) / 20);
		UiKit.label(ctx, textRenderer, x + 10, y + 20, "осталось: " + (remainSec / 60) + ":"
				+ String.format("%02d", remainSec % 60) + "   награда: " + lng(jd, "pay") + " CR", UiKit.COL_TEXT_DIM);

		if ("factory".equals(type)) {
			if (str(jd, "seqNeed").isEmpty()) {
				UiKit.label(ctx, textRenderer, x + 10, y + 40, "Формат задания устарел — отмени смену.", UiKit.COL_TEXT_DIM);
			} else {
				renderAssembly(ctx, x, y + 6, jd, mx, my);
			}
		} else if ("factory_order".equals(type) || "cook".equals(type)) {
			renderCraftActive(ctx, x, y, jd, mx, my);
		} else {
			renderDeliveryTarget(ctx, x, y, jd);
		}

		UiKit.button(ctx, textRenderer, x + PW - 114, y + 160, 96, 16, "Отменить", mx, my, true);
		clickable(x + PW - 114, y + 160, 96, 16, () -> send("cancel", new NbtCompound()));
		UiKit.label(ctx, textRenderer, x + 10, y + 162, "отмена или таймаут: штраф", UiKit.COL_RED);
	}

	private String jobTitle(String type) {
		return switch (type) {
			case "factory" -> "сборщик деталей (мини-игра)";
			case "factory_order" -> "цеховой заказ (крафт)";
			case "cook" -> "повар (крафт-заказ)";
			case "loader" -> "грузчик";
			case "courier" -> "курьер";
			default -> type;
		};
	}

	/** Активный крафт-заказ: цели с прогрессом + выданные материалы. */
	private void renderCraftActive(DrawContext ctx, int x, int y, NbtCompound jd, double mx, double my) {
		if (rows(jd, "targets").isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 10, y + 40, "Формат задания устарел — отмени смену.", UiKit.COL_TEXT_DIM);
			return;
		}
		UiKit.label(ctx, textRenderer, x + 10, y + 34, "Сдать (крафт из выданных ◆материалов):", UiKit.COL_ACCENT);
		int ry = y + 46;
		boolean allDone = true;
		for (NbtCompound t : rows(jd, "targets")) {
			Item item = itemOf(str(t, "id"));
			int have = countClient(item);
			int need = i(t, "need");
			boolean ok = have >= need;
			if (!ok) allDone = false;
			UiKit.card(ctx, x + 8, ry, PW - 32, 18, UiKit.COL_PANEL);
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 11, ry + 1);
			UiKit.label(ctx, textRenderer, x + 32, ry + 5, trim(str(t, "name"), 18), UiKit.COL_TEXT);
			String pr = have + " / " + need;
			ctx.drawText(textRenderer, Text.literal(pr), x + PW - 42 - textRenderer.getWidth(pr), ry + 5,
					ok ? UiKit.COL_GREEN : UiKit.COL_YELLOW, false);
			ry += 21;
		}
		// выданные материалы (справочно)
		UiKit.label(ctx, textRenderer, x + 10, ry + 2, "выдано со склада:", UiKit.COL_TEXT_DIM);
		ry += 12;
		int ix = x + 10;
		for (NbtCompound m : rows(jd, "mats")) {
			Item item = itemOf(str(m, "id"));
			if (item != null) ctx.drawItem(item.getDefaultStack(), ix, ry);
			UiKit.label(ctx, textRenderer, ix + 2, ry + 17, "×" + i(m, "count"), UiKit.COL_TEXT_DIM);
			ix += 24;
		}
		UiKit.button(ctx, textRenderer, x + 10, y + 160, 110, 16, "Сдать заказ", mx, my, allDone);
		if (allDone) {
			clickable(x + 10, y + 160, 110, 16, () -> send("handin", new NbtCompound()));
		}
	}

	/** Мини-игра сборки: схема сверху, палитра компонентов снизу. */
	private void renderAssembly(DrawContext ctx, int x, int y, NbtCompound jd, double mx, double my) {
		int parts = i(jd, "parts");
		int partsNeed = i(jd, "partsNeed");
		UiKit.label(ctx, textRenderer, x + 10, y + 30,
				"Собрано деталей: " + parts + " / " + partsNeed, UiKit.COL_TEXT);

		int barX = x + 10, barY = y + 41, barW = PW - 36, barH = 5;
		ctx.fill(barX, barY, barX + barW, barY + barH, UiKit.COL_PANEL);
		int fill = partsNeed <= 0 ? 0 : (int) (barW * (parts / (double) partsNeed));
		if (fill > 0) ctx.fill(barX, barY, barX + fill, barY + barH, UiKit.COL_GREEN);

		String[] seq = str(jd, "seqNeed").split(",");
		String haveStr = str(jd, "seqHave");
		int have = haveStr.isEmpty() ? 0 : haveStr.split(",").length;
		UiKit.label(ctx, textRenderer, x + 10, y + 52, "Схема:", UiKit.COL_TEXT_DIM);
		int sx = x + 50, sy = y + 50;
		for (int i = 0; i < seq.length; i++) {
			int slotX = sx + i * 20;
			ctx.fill(slotX, sy, slotX + 17, sy + 17, UiKit.COL_PANEL);
			if (i == have) drawFrame(ctx, slotX, sy, 17, UiKit.COL_YELLOW);
			else if (i < have) drawFrame(ctx, slotX, sy, 17, UiKit.COL_GREEN);
			else drawFrame(ctx, slotX, sy, 17, UiKit.COL_LINE);
			Item it = itemOf(seq[i]);
			if (it != null) ctx.drawItem(it.getDefaultStack(), slotX, sy);
		}

		int errors = i(jd, "errors");
		UiKit.label(ctx, textRenderer, x + 10, y + 72,
				"ошибки: " + errors + "/3" + (errors == 2 ? "  — аккуратно!" : ""), UiKit.COL_TEXT_DIM);

		UiKit.label(ctx, textRenderer, x + 10, y + 88, "Компоненты (кликай по схеме):", UiKit.COL_TEXT_DIM);
		String[] cats = str(jd, "cats").split(",");
		int px0 = x + 10, py0 = y + 100;
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

		UiKit.label(ctx, textRenderer, x + 10, y + 132, "схема растёт: каждая следующая на шаг длиннее", UiKit.COL_TEXT_DIM);
	}

	/** Цель доставки (грузчик/курьер). */
	private void renderDeliveryTarget(DrawContext ctx, int x, int y, NbtCompound jd) {
		NbtCompound t = sub(jd, "target");
		String nm = str(t, "name").isEmpty() ? "житель" : str(t, "name");
		UiKit.label(ctx, textRenderer, x + 10, y + 34, "доставка: " + nm, UiKit.COL_TEXT);
		UiKit.label(ctx, textRenderer, x + 10, y + 46,
				"координаты: " + i(t, "x") + ", " + i(t, "y") + ", " + i(t, "z"), UiKit.COL_TEXT_DIM);
		UiKit.label(ctx, textRenderer, x + 10, y + 58, "цель светится + стрелка-навигатор в HUD", UiKit.COL_ACCENT);
		UiKit.label(ctx, textRenderer, x + 10, y + 70, "награда: " + lng(jd, "pay") + " CR", UiKit.COL_YELLOW);
	}

	// ------------------------------ офферы ------------------------------

	private void renderOffer(DrawContext ctx, int x, int y, int w, int h, NbtCompound offer,
			String label, String type, double mx, double my) {
		UiKit.card(ctx, x, y, w, h, UiKit.COL_PANEL_HI);
		if (offer.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 10, y + 8, label, UiKit.COL_ACCENT);
			UiKit.label(ctx, textRenderer, x + 10, y + 22, "смен сейчас нет — загляни позже", UiKit.COL_TEXT_DIM);
			return;
		}
		UiKit.label(ctx, textRenderer, x + 10, y + 6, label, UiKit.COL_ACCENT);
		UiKit.label(ctx, textRenderer, x + 10, y + 17, str(offer, "desc"), UiKit.COL_TEXT_DIM);
		int ly = y + 29;

		if ("factory_order".equals(type) || "cook".equals(type)) {
			// цели заказа: иконки + названия + количество
			StringBuilder sb = new StringBuilder();
			for (NbtCompound t : rows(offer, "targets")) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(trim(str(t, "name"), 10)).append(" ×").append(i(t, "need"));
			}
			UiKit.label(ctx, textRenderer, x + 10, ly, "сдать: " + trim(sb.toString(), 42), UiKit.COL_TEXT);
			ly += 11;
			UiKit.label(ctx, textRenderer, x + 10, ly, "материалы выдаст склад (◆ помечены)", UiKit.COL_TEXT_DIM);
			ly += 11;
		}
		if (i(offer, "partsNeed") > 0) {
			UiKit.label(ctx, textRenderer, x + 10, ly, "деталей собрать: " + i(offer, "partsNeed"), UiKit.COL_TEXT);
			ly += 11;
		}
		if (i(offer, "portions") > 0) {
			UiKit.label(ctx, textRenderer, x + 10, ly, "пакетов: " + i(offer, "portions"), UiKit.COL_TEXT);
			ly += 11;
		}
		if ("courier".equals(type) || "loader".equals(type)) {
			NbtCompound t = sub(offer, "target");
			String nm = str(t, "name").isEmpty() ? "жителю" : str(t, "name");
			UiKit.label(ctx, textRenderer, x + 10, ly, "кому: " + nm + " (" + i(t, "x") + "," + i(t, "z") + ")", UiKit.COL_TEXT_DIM);
			ly += 11;
		}
		long ttlSec = lng(offer, "ttl") / 20;
		UiKit.label(ctx, textRenderer, x + 10, ly, "срок: " + (ttlSec / 60) + " мин", UiKit.COL_TEXT_DIM);

		// M7: показываем, когда сменится слепок оффера (окно 10 мин); при принятии
		// сервер восстановит ровно этот слепок даже после смены окна
		long winLeft = winLeftSec();
		if (winLeft >= 0) {
			UiKit.label(ctx, textRenderer, x + 10, y + h - 12,
					"новый состав через " + (winLeft / 60) + ":" + String.format("%02d", winLeft % 60),
					UiKit.COL_TEXT_DIM);
		}
		UiKit.label(ctx, textRenderer, x + 10, y + h - 24, "награда: " + lng(offer, "pay") + " CR", UiKit.COL_GREEN);
		UiKit.button(ctx, textRenderer, x + w - 86, y + h - 27, 76, 17, "Принять", mx, my, true);
		clickable(x + w - 86, y + h - 27, 76, 17, () -> {
			NbtCompound a = new NbtCompound();
			a.putString("type", type);
			a.putLong("win", lng(offer, "win"));
			send("accept", a);
		});
	}

	// ------------------------------ утилиты ------------------------------

	/** Сколько секунд осталось до смены 10-минутного окна офферов (-1 — нет мира). */
	private static long winLeftSec() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return -1;
		long t = mc.world.getTime();
		return Math.max(0, (12000 - (t % 12000)) / 20);
	}

	private static int countClient(Item item) {
		if (item == null) return 0;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null) return 0;
		int n = 0;
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	private static Item itemOf(String id) {
		return Registries.ITEM.get(Identifier.tryParse(id));
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static void drawFrame(DrawContext ctx, int x, int y, int size, int color) {
		ctx.drawHorizontalLine(x, x + size - 1, y, color);
		ctx.drawHorizontalLine(x, x + size - 1, y + size - 1, color);
		ctx.drawVerticalLine(x, y, y + size - 1, color);
		ctx.drawVerticalLine(x + size - 1, y, y + size - 1, color);
	}
}
