package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/**
 * Банкомат: обналичивание баланса в банкноты и обратно.
 *
 * <p>Единственное текстовое поле — сумма с чипами быстрого ввода
 * (+10/+100/+1000/макс); номинал банкнот подбирает сервер (стаками ≤64).
 */
public class BankScreen extends CraftNetScreen {

	private static final int PW = 220;
	private static final int PH = 166;

	public BankScreen(NbtCompound data) {
		super("bank", Text.translatable("craftnet.bank.title"), data);
	}

	private UiKit.TextInput amount;

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		amount = new UiKit.TextInput((width - PW) / 2 + 12, (height - PH) / 2 + 72, 92, "сумма", true);
		amount.maxLen = 9;
		inputs.add(amount);
	}

	@Override protected int designWidth() { return PW + 16; }
	@Override protected int designHeight() { return PH + 16; }

	private int px() {
		return (canvasWidth() - PW) / 2;
	}

	private int py() {
		return (canvasHeight() - PH) / 2;
	}

	@Override
	protected void renderContent(DrawContext ctx, int mx, int my, float delta) {
		int x = px(), y = py();
		UiKit.card(ctx, x, y, PW, PH, UiKit.COL_PANEL);

		// заголовок
		UiKit.label(ctx, textRenderer, x + 10, y + 6, "Банк · банкомат", UiKit.COL_ACCENT);
		ctx.drawHorizontalLine(x, x + PW - 1, y + 16, UiKit.COL_LINE);

		// балансы
		UiKit.card(ctx, x + 8, y + 22, PW - 16, 30, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 28, "На счёте", UiKit.COL_TEXT_DIM);
		String bal = UiKit.fmt(lng(data, "balance")) + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 16 - textRenderer.getWidth(bal), y + 28,
				UiKit.COL_YELLOW, false);
		UiKit.label(ctx, textRenderer, x + 16, y + 40, "Банкнотами: " + lng(data, "banknotes") + " CR",
				UiKit.COL_TEXT_DIM);

		// обналичить
		UiKit.label(ctx, textRenderer, x + 12, y + 60, "Снять со счёта:", UiKit.COL_TEXT);
		// чипы быстрого ввода
		String[] chips = {"+10", "+100", "+1000", "макс"};
		for (int ci = 0; ci < chips.length; ci++) {
			int bx = x + 110 + ci * 27;
			UiKit.button(ctx, textRenderer, bx, y + 70, 25, 13, chips[ci], mx, my, true);
			final int ci2 = ci;
			clickable(bx, y + 70, 25, 13, () -> {
				long cur = parse(amount.value);
				// L13: не вылезаем за maxLen=9 цифр (сервер всё равно клампит 100 000)
				long lim = (long) Math.pow(10, amount.maxLen) - 1;
				if (ci2 == 3) amount.value = String.valueOf(Math.min(lng(data, "balance"), lim));
				else amount.value = String.valueOf(Math.min(cur + new long[]{10, 100, 1000}[ci2], lim));
			});
		}

		UiKit.button(ctx, textRenderer, x + 12, y + 92, 92, 16, "Снять", mx, my, parse(amount.value) > 0);
		clickable(x + 12, y + 92, 92, 16, () -> {
			long v = parse(amount.value);
			if (v <= 0) return;
			NbtCompound a = new NbtCompound();
			a.putLong("amount", v);
			send("cashout", a);
			amount.value = "";
		});

		UiKit.button(ctx, textRenderer, x + 110, y + 92, PW - 122, 16, "Внести все банкноты", mx, my,
				lng(data, "banknotes") > 0);
		clickable(x + 110, y + 92, PW - 122, 16, () -> send("deposit_all", new NbtCompound()));

		UiKit.label(ctx, textRenderer, x + 12, y + 120, "Комиссия 0. Банкноты можно отдавать", UiKit.COL_TEXT_DIM);
		UiKit.label(ctx, textRenderer, x + 12, y + 131, "другим игрокам как наличные.", UiKit.COL_TEXT_DIM);
		UiKit.label(ctx, textRenderer, x + 12, y + 148, "Номиналы: 1, 5, 10, 50, 100, 500, 1000 CR", UiKit.COL_TEXT_DIM);
	}

	private static long parse(String s) {
		try {
			return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
