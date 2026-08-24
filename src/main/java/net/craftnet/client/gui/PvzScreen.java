package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Экран пункта выдачи заказов 2.0: посылки с прогрессом доставки и прокруткой,
 * продажа по страницам, приём на работу грузчиком. Макет без наложений:
 * заголовок (18) → две колонки (126) → полоса работы (30).
 */
public class PvzScreen extends CraftNetScreen {

	private static final int PW = 300;
	private static final int PH = 200;
	private static final int COLS_TOP = 24;
	private static final int COLS_H = 126;
	private static final int ROW_STEP = 28;
	private static final int ROWS = 3;

	private int claimsPage;
	private int sellPage;
	private UiKit.TextInput priceInput;

	public PvzScreen(NbtCompound data) {
		super("pvz", Text.translatable("craftnet.pvz.title"), data);
	}

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		priceInput = new UiKit.TextInput(px() + PW / 2 + 2 + (PW / 2 - 12) - 60, py() + COLS_TOP + 3,
				54, "цена/шт", true);
		priceInput.maxLen = 7;
		inputs.add(priceInput);
	}

	private int px() {
		return (width - PW) / 2;
	}

	private int py() {
		return (height - PH) / 2;
	}

	@Override
	protected void onSync(NbtCompound d) {
		claimsPage = Math.max(0, Math.min(claimsPage, pagesOf(rows(d, "claims").size()) - 1));
		sellPage = Math.max(0, Math.min(sellPage, pagesOf(rows(d, "sell").size()) - 1));
	}

	private static int pagesOf(int size) {
		return Math.max(1, (size + ROWS - 1) / ROWS);
	}

	private static java.util.List<NbtCompound> page(java.util.List<NbtCompound> all, int page) {
		int from = Math.min(page * ROWS, all.size());
		return all.subList(from, Math.min(from + ROWS, all.size()));
	}

	@Override
	protected void renderContent(DrawContext ctx, int mx, int my, float delta) {
		int x = px(), y = py();
		UiKit.card(ctx, x, y, PW, PH, UiKit.COL_PANEL);

		// заголовок
		UiKit.label(ctx, textRenderer, x + 10, y + 6, "ПВЗ · пункт выдачи", UiKit.COL_ACCENT);
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 10 - textRenderer.getWidth(bal), y + 6,
				UiKit.COL_YELLOW, false);
		ctx.drawHorizontalLine(x, x + PW - 1, y + 16, UiKit.COL_LINE);

		renderClaims(ctx, x + 8, y + COLS_TOP, mx, my);
		renderSell(ctx, x + PW / 2 + 2, y + COLS_TOP, mx, my);
		renderLoader(ctx, x + 8, y + PH - 34, mx, my);
	}

	// ------------------------------ посылки ------------------------------

	private void renderClaims(DrawContext ctx, int x, int y, double mx, double my) {
		int w = PW / 2 - 12;
		UiKit.card(ctx, x, y, w, COLS_H, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 6, y + 5, "Посылки", UiKit.COL_ACCENT);

		var claims = rows(data, "claims");
		// «забрать всё» — в заголовке колонки
		boolean anyReady = false;
		for (NbtCompound c : claims) if (i(c, "readyNow") == 1) { anyReady = true; break; }
		if (anyReady) {
			UiKit.button(ctx, textRenderer, x + w - 62, y + 3, 56, 12, "забрать всё", mx, my, true);
			clickable(x + w - 62, y + 3, 56, 12, () -> send("claim_all", new NbtCompound()));
		}

		if (claims.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 8, y + 40, "Пока пусто —", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 8, y + 52, "закажи в телефоне!", UiKit.COL_TEXT_DIM);
		}
		var vis = page(claims, claimsPage);
		int ry = y + 17;
		for (NbtCompound c : vis) {
			UiKit.card(ctx, x + 4, ry, w - 8, ROW_STEP - 3, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(c, "itemId")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 8, ry + 4);
			UiKit.label(ctx, textRenderer, x + 28, ry + 3, trim(str(c, "name"), 15) + " ×" + i(c, "count"),
					UiKit.COL_TEXT);
			boolean ready = i(c, "readyNow") == 1;
			if (ready) {
				UiKit.label(ctx, textRenderer, x + 28, ry + 14, "готово · " + trim(str(c, "vname"), 10),
						UiKit.COL_GREEN);
				UiKit.button(ctx, textRenderer, x + w - 46, ry + 6, 38, 13, "взять", mx, my, true);
				final long oid = lng(c, "id");
				clickable(x + w - 46, ry + 6, 38, 13, () -> {
					NbtCompound a = new NbtCompound();
					a.putLong("id", oid);
					send("claim", a);
				});
			} else {
				UiKit.progress(ctx, x + 28, ry + 17, 72, 3, i(c, "frac"), UiKit.COL_ACCENT);
				UiKit.label(ctx, textRenderer, x + 104, ry + 14,
						String.format("%d:%02d", lng(c, "etaSec") / 60, lng(c, "etaSec") % 60),
						UiKit.COL_TEXT_DIM);
			}
			ry += ROW_STEP;
		}
		// пейджер
		int pages = pagesOf(claims.size());
		if (pages > 1) {
			UiKit.button(ctx, textRenderer, x + 4, y + COLS_H - 13, 12, 11, "<", mx, my, claimsPage > 0);
			clickable(x + 4, y + COLS_H - 13, 12, 11, () -> claimsPage--);
			UiKit.label(ctx, textRenderer, x + 20, y + COLS_H - 11,
					(claimsPage + 1) + "/" + pages, UiKit.COL_TEXT_DIM);
			UiKit.button(ctx, textRenderer, x + 44, y + COLS_H - 13, 12, 11, ">", mx, my,
					claimsPage + 1 < pages);
			clickable(x + 44, y + COLS_H - 13, 12, 11, () -> claimsPage++);
		}
	}

	// ------------------------------ продажа ------------------------------

	private void renderSell(DrawContext ctx, int x, int y, double mx, double my) {
		int w = PW / 2 - 12;
		UiKit.card(ctx, x, y, w, COLS_H, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 6, y + 5, "Продать из инвентаря", UiKit.COL_ACCENT);
		UiKit.label(ctx, textRenderer, x + 6, y + 14, "выплата с задержкой", UiKit.COL_TEXT_DIM);
		var sell = rows(data, "sell");
		if (sell.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 8, y + 40, "Нечего продавать —", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 8, y + 52, "инвентарь пуст.", UiKit.COL_TEXT_DIM);
		}
		var vis = page(sell, sellPage);
		int ry = y + 25;
		for (NbtCompound c : vis) {
			UiKit.card(ctx, x + 4, ry, w - 8, ROW_STEP - 5, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(c, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 8, ry + 3);
			UiKit.label(ctx, textRenderer, x + 28, ry + 2, trim(str(c, "name"), 10) + " ×" + i(c, "count"),
					UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 28, ry + 12, i(c, "price") + " CR/шт", UiKit.COL_YELLOW);
			final String fid = str(c, "id");
			final int have = i(c, "count");
			UiKit.button(ctx, textRenderer, x + w - 64, ry + 5, 30, 13, "всё", mx, my, have >= 1);
			clickable(x + w - 64, ry + 5, 30, 13, () -> sellAction(fid, have));
			UiKit.button(ctx, textRenderer, x + w - 30, ry + 5, 18, 13, "₽", mx, my,
					have >= 1 && price() > 0);
			clickable(x + w - 30, ry + 5, 18, 13, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("id", fid);
				a.putInt("price", price());
				send("market_list", a);
			});
			ry += ROW_STEP - 3; // sell: шаг 25
		}
		int pages = pagesOf(sell.size());
		if (pages > 1) {
			UiKit.button(ctx, textRenderer, x + 4, y + COLS_H - 13, 12, 11, "<", mx, my, sellPage > 0);
			clickable(x + 4, y + COLS_H - 13, 12, 11, () -> sellPage--);
			UiKit.label(ctx, textRenderer, x + 20, y + COLS_H - 11,
					(sellPage + 1) + "/" + pages, UiKit.COL_TEXT_DIM);
			UiKit.button(ctx, textRenderer, x + 44, y + COLS_H - 13, 12, 11, ">", mx, my,
					sellPage + 1 < pages);
			clickable(x + 44, y + COLS_H - 13, 12, 11, () -> sellPage++);
		}
		int lots = i(data, "myLots");
		if (lots > 0) {
			UiKit.button(ctx, textRenderer, x + w - 64, y + COLS_H - 13, 60, 11,
					"лоты: " + lots + " ✕", mx, my, true);
			clickable(x + w - 64, y + COLS_H - 13, 60, 11, () -> send("market_cancel", new NbtCompound()));
		}
	}

	private int price() {
		try {
			return priceInput == null || priceInput.value.isBlank() ? 0
					: Integer.parseInt(priceInput.value.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void sellAction(String id, int n) {
		NbtCompound a = new NbtCompound();
		a.putString("id", id);
		a.putInt("count", n);
		send("sell", a);
	}

	// ------------------------------ работа грузчиком ------------------------------

	private void renderLoader(DrawContext ctx, int x, int y, double mx, double my) {
		UiKit.card(ctx, x, y, PW - 16, 28, UiKit.COL_PANEL_HI);
		if (i(data, "hasJob") == 1) {
			UiKit.label(ctx, textRenderer, x + 8, y + 9,
					"У вас активное задание — /craftnet job cancel для отмены", UiKit.COL_YELLOW);
			return;
		}
		NbtCompound offer = sub(data, "loaderOffer");
		if (offer.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 8, y + 9, "Работа грузчиком недоступна здесь",
					UiKit.COL_TEXT_DIM);
			return;
		}
		NbtCompound target = sub(offer, "target");
		String nm = str(target, "name").isEmpty() ? "жителю" : str(target, "name");
		UiKit.label(ctx, textRenderer, x + 8, y + 4,
				"Грузчик: ящик → " + nm + " (±" + (int) lng(target, "dist") + " м)", UiKit.COL_TEXT);
		UiKit.label(ctx, textRenderer, x + 8, y + 15, "оплата " + lng(offer, "pay") + " CR",
				UiKit.COL_GREEN);
		UiKit.button(ctx, textRenderer, x + PW - 92, y + 6, 60, 16, "Принять", mx, my, true);
		clickable(x + PW - 92, y + 6, 60, 16, () -> send("loader_start", new NbtCompound()));
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
