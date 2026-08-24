package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

/** Банкомат: обналичивание баланса в банкноты и обратно. */
public class BankScreen extends CraftNetScreen {

	private static final int PW = 220;
	private static final int PH = 150;

	public BankScreen(NbtCompound data) {
		super("bank", Text.translatable("craftnet.bank.title"), data);
	}

	private UiKit.TextInput amount;

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		amount = new UiKit.TextInput((width - PW) / 2 + 12, (height - PH) / 2 + 64, 90, "сумма", true);
		inputs.add(amount);
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

		UiKit.label(ctx, textRenderer, x + 12, y + 10, "Баланс счёта", UiKit.COL_TEXT_DIM);
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 12 - textRenderer.getWidth(bal), y + 10, UiKit.COL_YELLOW, false);

		UiKit.label(ctx, textRenderer, x + 12, y + 28, "Банкноты в инвентаре: " + lng(data, "banknotes") + " CR", UiKit.COL_TEXT_DIM);

		UiKit.label(ctx, textRenderer, x + 12, y + 48, "Обналичить:", UiKit.COL_TEXT);

		UiKit.button(ctx, textRenderer, x + 108, y + 62, 46, 16, "Снять", mx, my, !amount.value.isEmpty());
		clickable(x + 108, y + 62, 46, 16, () -> {
			if (amount.value.isEmpty()) return;
			long v;
			try {
				v = Long.parseLong(amount.value);
			} catch (NumberFormatException e) {
				return;
			}
			NbtCompound a = new NbtCompound();
			a.putLong("amount", v);
			send("cashout", a);
			amount.value = "";
		});

		UiKit.button(ctx, textRenderer, x + 12, y + 90, PW - 24, 18, "Внести все банкноты на счёт", mx, my, lng(data, "banknotes") > 0);
		clickable(x + 12, y + 90, PW - 24, 18, () -> send("deposit_all", new NbtCompound()));

		UiKit.label(ctx, textRenderer, x + 12, y + 120, "Номиналы: 1, 5, 10, 50, 100, 500, 1000 CR", UiKit.COL_TEXT_DIM);
	}
}
