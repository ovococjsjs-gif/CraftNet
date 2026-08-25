package net.craftnet.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Экран пункта выдачи заказов 2.1: посылки с прогрессом доставки и прокруткой,
 * продажа по страницам, полоса ОЖИДАЕМЫХ ВЫПЛАТ (видно, когда придут деньги),
 * приём на работу грузчиком. Макет без наложений:
 * заголовок (22) → две колонки (116) → выплаты (32) → полоса работы (28).
 */
public class PvzScreen extends CraftNetScreen {

	private static final int PW = 300;
	private static final int PH = 212;
	private static final int COLS_TOP = 22;
	private static final int COLS_H = 116;
	/** Верх полосы ожидаемых выплат (относительно карточки). */
	private static final int STRIP_TOP = COLS_TOP + COLS_H + 4;
	private static final int ROW_STEP = 28;
	private static final int ROWS = 3;

	private int claimsPage;
	private int sellPage;
	private UiKit.TextInput priceInput;
	private UiKit.TextInput countInput;

	public PvzScreen(NbtCompound data) {
		super("pvz", Text.translatable("craftnet.pvz.title"), data);
	}

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		// [шт][цена/шт] — пара полей лота барахолки: количество и цена за штуку
		countInput = new UiKit.TextInput(px() + PW / 2 + 2 + (PW / 2 - 12) - 94, py() + COLS_TOP + 3,
				32, "шт", true);
		countInput.maxLen = 2;
		priceInput = new UiKit.TextInput(px() + PW / 2 + 2 + (PW / 2 - 12) - 60, py() + COLS_TOP + 3,
				54, "цена/шт", true);
		priceInput.maxLen = 7;
		inputs.add(countInput);
		inputs.add(priceInput);
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
		String bal = UiKit.fmt(lng(data, "balance")) + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 10 - textRenderer.getWidth(bal), y + 6,
				UiKit.COL_YELLOW, false);
		ctx.drawHorizontalLine(x, x + PW - 1, y + 16, UiKit.COL_LINE);

		renderClaims(ctx, x + 8, y + COLS_TOP, mx, my);
		renderSell(ctx, x + PW / 2 + 2, y + COLS_TOP, mx, my);
		renderPayouts(ctx, x + 8, y + STRIP_TOP);
		renderLoader(ctx, x + 8, y + PH - 34, mx, my);
	}

	// ------------------------------ ожидаемые выплаты ------------------------------

	private void renderPayouts(DrawContext ctx, int x, int y) {
		UiKit.card(ctx, x, y, PW - 16, 32, UiKit.COL_PANEL_HI);
		var pays = rows(data, "payouts");
		long sum = lng(data, "payoutSum");
		if (pays.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 6, y + 3, "Ожидаемые выплаты", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 6, y + 14, "нет — продай что-нибудь, деньги придут сюда",
					UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 6, y + 23, "(обычно 2–6 минут — зависит от сети деревни)", UiKit.COL_TEXT_DIM);
			return;
		}
		// L8: «и ещё N» — правым краем; заголовок обрезаем, чтобы не налезал
		String more = pays.size() > 2 ? "и ещё " + (pays.size() - 2) : null;
		int moreW = more == null ? 0 : textRenderer.getWidth(more) + 8;
		String head = fit("Ожидаемые выплаты · всего " + sum + " CR", PW - 16 - 12 - moreW);
		UiKit.label(ctx, textRenderer, x + 6, y + 3, head, UiKit.COL_YELLOW);
		if (more != null) {
			ctx.drawText(textRenderer, Text.literal(more),
					x + PW - 16 - 6 - textRenderer.getWidth(more), y + 3, UiKit.COL_TEXT_DIM, false);
		}
		int ry = y + 13;
		int shown = 0;
		for (NbtCompound p : pays) {
			if (shown >= 2) break;
			long eta = lng(p, "etaSec");
			String line = trim(str(p, "comment"), 16) + " → +" + lng(p, "payout") + " CR · "
					+ String.format("%d:%02d", eta / 60, eta % 60);
			UiKit.label(ctx, textRenderer, x + 6, ry, line, UiKit.COL_TEXT);
			UiKit.progress(ctx, x + 6, ry + 8, PW - 28 - 60, 2, i(p, "frac"), UiKit.COL_GREEN);
			ry += 10;
			shown++;
		}
	}

	/** Обрезать строку под ширину в пикселях (с «…»). */
	private String fit(String s, int maxW) {
		return UiKit.fit(textRenderer, s, maxW);
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
		UiKit.label(ctx, textRenderer, x + 6, y + 5, "Продажа", UiKit.COL_ACCENT);
		// UX-5: подписи полей и кнопок; «шт» делят продажа и лот (пусто = всё)
		UiKit.label(ctx, textRenderer, x + 6, y + 14, "шт — кол-во (пусто = всё), ₽ — лот по цене ↑",
				UiKit.COL_TEXT_DIM);
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
			final int slot = i(c, "slot");
			final int have = i(c, "count");
			final NbtCompound expected = sub(c, "stack");
			boolean plain = i(c, "plain") == 1;
			// Server sale destroys the item and therefore accepts only default-component
			// stacks. The flea market preserves exact components and accepts either.
			int sellN = lotCount() > 0 ? Math.min(lotCount(), have) : have;
			UiKit.button(ctx, textRenderer, x + w - 64, ry + 5, 30, 13,
					plain ? (lotCount() > 0 ? "×" + sellN : "всё") : "—", mx, my,
					plain && sellN >= 1);
			if (plain && sellN >= 1) {
				clickable(x + w - 64, ry + 5, 30, 13, () -> sellAction(slot, expected, sellN));
			}
			UiKit.button(ctx, textRenderer, x + w - 30, ry + 5, 18, 13, "₽", mx, my,
					have >= 1 && price() > 0);
			clickable(x + w - 30, ry + 5, 18, 13, () -> {
				NbtCompound a = new NbtCompound();
				a.putInt("slot", slot);
				a.put("stack", expected.copy());
				a.putInt("price", price());
				a.putInt("count", lotCount() > 0 ? Math.min(lotCount(), have) : have);
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

	/** Количество лота (0 = поле пустое = «всё»). */
	private int lotCount() {
		try {
			return countInput == null || countInput.value.isBlank() ? 0
					: Integer.parseInt(countInput.value.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** UX: колесо мыши листает колонку под курсором (слева посылки, справа продажа). */
	@Override
	protected boolean onScroll(double mx, double my, double dir) {
		int step = dir < 0 ? 1 : -1;
		if (mx < px() + PW / 2) {
			pageClaimsBy(step);
		} else {
			pageSellBy(step);
		}
		return true;
	}

	/** PgUp/PgDn — посылки; с зажатым Shift — список продажи. */
	@Override
	protected boolean onPageKey(int step) {
		if (shiftDown()) {
			pageSellBy(step);
		} else {
			pageClaimsBy(step);
		}
		return true;
	}

	private void pageClaimsBy(int step) {
		int pages = pagesOf(rows(data, "claims").size());
		claimsPage = Math.max(0, Math.min(pages - 1, claimsPage + step));
	}

	private void pageSellBy(int step) {
		int pages = pagesOf(rows(data, "sell").size());
		sellPage = Math.max(0, Math.min(pages - 1, sellPage + step));
	}

	/** Нажат ли Shift (левый/правый) — модификатор PgUp/PgDn. */
	private static boolean shiftDown() {
		var mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.getWindow() == null) return false;
		return net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(),
				net.minecraft.client.util.InputUtil.GLFW_KEY_LEFT_SHIFT)
				|| net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(),
						net.minecraft.client.util.InputUtil.GLFW_KEY_RIGHT_SHIFT);
	}

	private void sellAction(int slot, NbtCompound expected, int n) {
		if (n <= 0) return;
		NbtCompound a = new NbtCompound();
		a.putInt("slot", slot);
		a.put("stack", expected.copy());
		a.putInt("count", n);
		send("sell", a);
	}

	// ------------------------------ работа грузчиком ------------------------------

	private void renderLoader(DrawContext ctx, int x, int y, double mx, double my) {
		UiKit.card(ctx, x, y, PW - 16, 28, UiKit.COL_PANEL_HI);
		if (i(data, "hasJob") == 1) {
			UiKit.label(ctx, textRenderer, x + 8, y + 4, "Активное задание: " + jobTitle(str(data, "jobType")),
					UiKit.COL_YELLOW);
			UiKit.label(ctx, textRenderer, x + 8, y + 15, "завершите его или отмените (штраф)",
					UiKit.COL_TEXT_DIM);
			// M2: отмена смены прямо с ПВЗ (иначе грузчик «застревал» на 10 минут)
			UiKit.button(ctx, textRenderer, x + PW - 92, y + 6, 60, 16, "Отменить", mx, my, true);
			clickable(x + PW - 92, y + 6, 60, 16, () -> send("job_cancel", new NbtCompound()));
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
		if (i(offer, "cool") == 1) {
			// смена грузчика в этом окне уже отработана — кнопки нет
			UiKit.label(ctx, textRenderer, x + 118, y + 15,
					fit("отработано · жди окна", PW - 16 - 118), UiKit.COL_TEXT_DIM);
			return;
		}
		UiKit.button(ctx, textRenderer, x + PW - 92, y + 6, 60, 16, "Принять", mx, my, true);
		clickable(x + PW - 92, y + 6, 60, 16, () -> {
			NbtCompound a = new NbtCompound();
			a.putLong("win", lng(offer, "win")); // M7: принять ровно показанный слепок
			send("loader_start", a);
		});
	}

	private static String jobTitle(String type) {
		return switch (type) {
			case "factory" -> "сборщик деталей";
			case "factory_order" -> "цеховой заказ";
			case "cook" -> "повар";
			case "loader" -> "грузчик";
			case "courier" -> "курьер";
			default -> "работа";
		};
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
