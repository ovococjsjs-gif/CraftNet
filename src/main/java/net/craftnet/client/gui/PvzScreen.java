package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Экран пункта выдачи заказов: получение, продажа, работа грузчиком. */
public class PvzScreen extends CraftNetScreen {

	private static final int PW = 300;
	private static final int PH = 200;

	public PvzScreen(NbtCompound data) {
		super("pvz", Text.translatable("craftnet.pvz.title"), data);
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

		renderClaims(ctx, x + 8, y + 20, mx, my);
		renderSell(ctx, x + PW / 2 + 2, y + 20, mx, my);
		renderLoader(ctx, x + 8, y + PH - 34, mx, my);
	}

	private void renderClaims(DrawContext ctx, int x, int y, double mx, double my) {
		int w = PW / 2 - 12;
		UiKit.panel(ctx, x, y, w, PH - 60, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 6, y + 5, "Входящие посылки", UiKit.COL_ACCENT);
		var claims = rows(data, "claims");
		if (claims.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 6, y + 20, "пусто — закажите что-нибудь", UiKit.COL_TEXT_DIM);
		}
		int ry = y + 16;
		for (NbtCompound c : claims.subList(0, Math.min(6, claims.size()))) {
			UiKit.panel(ctx, x + 4, ry, w - 8, 20, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(c, "itemId")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 8, ry + 2);
			UiKit.label(ctx, textRenderer, x + 28, ry + 2, trim(str(c, "name"), 16) + " ×" + i(c, "count"), UiKit.COL_TEXT);
			boolean ready = i(c, "readyNow") == 1;
			UiKit.label(ctx, textRenderer, x + 28, ry + 11,
					ready ? str(c, "vname") : "в пути: " + lng(c, "etaSec") + " сек",
					ready ? UiKit.COL_GREEN : UiKit.COL_TEXT_DIM);
			UiKit.button(ctx, textRenderer, x + w - 58, ry + 3, 50, 13, "Забрать", mx, my, ready);
			final long oid = lng(c, "id");
			clickable(x + w - 58, ry + 3, 50, 13, () -> {
				NbtCompound a = new NbtCompound();
				a.putLong("id", oid);
				send("claim", a);
			});
			ry += 24;
		}
		UiKit.button(ctx, textRenderer, x + 6, y + PH - 78, w - 12, 14, "Забрать всё", mx, my, !claims.isEmpty());
		clickable(x + 6, y + PH - 78, w - 12, 14, () -> send("claim_all", new NbtCompound()));
	}

	private void renderSell(DrawContext ctx, int x, int y, double mx, double my) {
		int w = PW / 2 - 12;
		UiKit.panel(ctx, x, y, w, PH - 60, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 6, y + 5, "Продать (выплата с задержкой)", UiKit.COL_ACCENT);
		var sell = rows(data, "sell");
		if (sell.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 6, y + 20, "в инвентаре нечего продать", UiKit.COL_TEXT_DIM);
		}
		int ry = y + 16;
		for (NbtCompound c : sell.subList(0, Math.min(6, sell.size()))) {
			UiKit.panel(ctx, x + 4, ry, w - 8, 20, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(c, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 8, ry + 2);
			UiKit.label(ctx, textRenderer, x + 28, ry + 2, trim(str(c, "name"), 14) + " ×" + i(c, "count"), UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 28, ry + 11, i(c, "price") + " CR/шт", UiKit.COL_YELLOW);
			final String fid = str(c, "id");
			final int have = i(c, "count");
			UiKit.button(ctx, textRenderer, x + w - 92, ry + 3, 40, 13, "1 шт", mx, my, have >= 1);
			clickable(x + w - 92, ry + 3, 40, 13, () -> sellAction(fid, 1));
			UiKit.button(ctx, textRenderer, x + w - 48, ry + 3, 44, 13, "всё", mx, my, have >= 1);
			clickable(x + w - 48, ry + 3, 44, 13, () -> sellAction(fid, have));
			ry += 24;
		}
	}

	private void sellAction(String id, int n) {
		NbtCompound a = new NbtCompound();
		a.putString("id", id);
		a.putInt("count", n);
		send("sell", a);
	}

	private void renderLoader(DrawContext ctx, int x, int y, double mx, double my) {
		UiKit.panel(ctx, x, y, PW - 16, 28, UiKit.COL_PANEL_HI);
		if (i(data, "hasJob") == 1) {
			UiKit.label(ctx, textRenderer, x + 8, y + 9,
					"У вас активное задание — /craftnet job cancel для отмены", UiKit.COL_YELLOW);
			return;
		}
		NbtCompound offer = sub(data, "loaderOffer");
		if (offer.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 8, y + 9, "Работа грузчиком недоступна здесь", UiKit.COL_TEXT_DIM);
			return;
		}
		NbtCompound target = sub(offer, "target");
		String nm = str(target, "name").isEmpty() ? "жителю" : str(target, "name");
		UiKit.label(ctx, textRenderer, x + 8, y + 4,
				"Грузчик: ящик → " + nm + " (" + target.getInt("x", 0) + "," + target.getInt("z", 0) + ")",
				UiKit.COL_TEXT);
		UiKit.label(ctx, textRenderer, x + 8, y + 15, "оплата " + lng(offer, "pay") + " CR", UiKit.COL_GREEN);
		UiKit.button(ctx, textRenderer, x + PW - 92, y + 6, 60, 16, "Принять", mx, my, true);
		clickable(x + PW - 92, y + 6, 60, 16, () -> send("loader_start", new NbtCompound()));
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
