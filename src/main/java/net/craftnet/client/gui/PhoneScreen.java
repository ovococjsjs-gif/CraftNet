package net.craftnet.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import net.craftnet.client.GpsMapRenderer;

/**
 * Телефон CraftNet. Вкладки: Главная, Магазин, Биржа, GPS, Банк.
 * Все данные приходят с сервера через синхронизацию.
 */
public class PhoneScreen extends CraftNetScreen {

	private enum Tab {HOME, SHOP, STOCKS, GPS, BANK}

	private static final int PW = 272;
	private static final int PH = 224;

	private Tab tab = Tab.HOME;
	private int buyCount = 1;
	private String query = "";
	private GpsMapRenderer map;
	private UiKit.TextInput searchInput;

	public PhoneScreen(NbtCompound data) {
		super("phone", Text.translatable("craftnet.phone.title"), data);
	}

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		if (tab == Tab.SHOP) {
			searchInput = new UiKit.TextInput(px() + 8, py() + 34, PW - 60, "поиск предмета…", false);
			searchInput.value = query;
			inputs.add(searchInput);
		} else {
			searchInput = null;
		}
	}

	private void switchTab(Tab t) {
		tab = t;
		query = searchInput == null ? query : searchInput.value;
		clearAndInit();
	}

	private int px() {
		return (width - PW) / 2;
	}

	private int py() {
		return (height - PH) / 2;
	}

	private MinecraftClient mc() {
		return client;
	}

	// ----------------------------------------------------------------

	@Override
	protected void renderContent(DrawContext ctx, int mx, int my, float delta) {
		int x = px(), y = py();
		// корпус телефона
		ctx.fill(x - 6, y - 6, x + PW + 6, y + PH + 6, 0xFF0B0D10);
		ctx.fill(x, y, x + PW, y + PH, UiKit.COL_BG);

		renderStatusBar(ctx, x, y);

		// панель вкладок
		int ty = y + 18;
		drawTabButton(ctx, x + 6, ty, "Дом", Tab.HOME, mx, my);
		drawTabButton(ctx, x + 50, ty, "Магазин", Tab.SHOP, mx, my);
		drawTabButton(ctx, x + 110, ty, "Биржа", Tab.STOCKS, mx, my);
		drawTabButton(ctx, x + 166, ty, "GPS", Tab.GPS, mx, my);
		drawTabButton(ctx, x + 210, ty, "Банк", Tab.BANK, mx, my);

		int cy = y + 34;
		switch (tab) {
			case HOME -> renderHome(ctx, x, cy, mx, my);
			case SHOP -> renderShop(ctx, x, cy, mx, my);
			case STOCKS -> renderStocks(ctx, x, cy, mx, my);
			case GPS -> renderGps(ctx, x, cy, mx, my);
			case BANK -> renderBank(ctx, x, cy, mx, my);
		}
	}

	private void drawTabButton(DrawContext ctx, int x, int y, String name, Tab t, double mx, double my) {
		boolean active = tab == t;
		if (UiKit.button(ctx, textRenderer, x, y, t == Tab.GPS ? 40 : t == Tab.HOME ? 40 : 56, 14, name, mx, my, true) || active) {
			if (active) {
				ctx.fill(x, y + 13, x + (t == Tab.GPS ? 40 : t == Tab.HOME ? 40 : 56), y + 15, UiKit.COL_ACCENT);
			}
		}
		clickable(x, y, t == Tab.GPS ? 40 : t == Tab.HOME ? 40 : 56, 14, () -> switchTab(t));
	}

	private void renderStatusBar(DrawContext ctx, int x, int y) {
		// время мира
		String time = "--:--";
		if (mc() != null && mc().world != null) {
			long t = mc().world.getTimeOfDay() % 24000;
			int h = (int) ((t / 1000 + 6) % 24);
			int m = (int) ((t % 1000) * 60 / 1000);
			time = String.format("%02d:%02d", h, m);
		}
		UiKit.label(ctx, textRenderer, x + 8, y + 5, time, UiKit.COL_TEXT);

		// баланс
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 8 - textRenderer.getWidth(bal), y + 5, UiKit.COL_YELLOW, false);

		// полоски сигнала (справа от центра)
		int sig = i(data, "signal");
		for (int i = 0; i < 4; i++) {
			int bh = 3 + i * 2;
			int col = i <= sig ? 0xFF3FB950 : 0xFF3A3F4B;
			ctx.fill(x + 70 + i * 6, y + 13 - bh, x + 74 + i * 6, y + 13, col);
		}
		String[] lbl = {"нет", "2G", "3G", "4G"};
		UiKit.label(ctx, textRenderer, x + 96, y + 5, lbl[Math.max(0, Math.min(3, sig - 1 + 1))].replace("нет", "нет сети"), sig > 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
	}

	private String signalLabel() {
		return switch (i(data, "signal")) {
			case 1 -> "2G";
			case 2 -> "3G";
			case 3 -> "4G";
			default -> "нет сети";
		};
	}

	// ------------------------------ Дом ------------------------------

	private record App(String name, ItemStack icon, Tab tab) {}

	private void renderHome(DrawContext ctx, int x, int y, int mx, int my) {
		App[] apps = {
				new App("Магазин", Items.EMERALD.getDefaultStack(), Tab.SHOP),
				new App("Биржа", Items.PAPER.getDefaultStack(), Tab.STOCKS),
				new App("GPS", Items.FILLED_MAP.getDefaultStack(), Tab.GPS),
				new App("Банк", Items.GOLD_INGOT.getDefaultStack(), Tab.BANK),
		};
		int idx = 0;
		for (App app : apps) {
			int ax = x + 14 + (idx % 2) * ((PW - 28) / 2);
			int ay = y + 6 + (idx / 2) * 52;
			UiKit.panel(ctx, ax, ay, 116, 46, UiKit.COL_PANEL);
			ctx.drawItem(app.icon(), ax + 8, ay + 8);
			UiKit.label(ctx, textRenderer, ax + 30, ay + 12, app.name(), UiKit.COL_TEXT);
			String sub = switch (app.tab()) {
				case SHOP -> "покупка/продажа";
				case STOCKS -> "5 компаний";
				case GPS -> i(data, "gpsOk") == 1 ? "онлайн" : "нет сигнала";
				case BANK -> lng(data, "balance") + " CR";
				default -> "";
			};
			UiKit.label(ctx, textRenderer, ax + 30, ay + 26, sub, UiKit.COL_TEXT_DIM);
			clickable(ax, ay, 116, 46, () -> switchTab(app.tab()));
			idx++;
		}
		// статус сети внизу
		UiKit.panel(ctx, x + 14, y + 118, PW - 28, 42, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 22, y + 128, "Текущая сеть: " + signalLabel(),
				i(data, "signal") > 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
		String vill = str(data, "village");
		if (!vill.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 22, y + 142,
					"Точка: " + vill + " (" + i(data, "dist") + " м)", UiKit.COL_TEXT_DIM);
		} else if (i(data, "offVillage") == 1) {
			UiKit.label(ctx, textRenderer, x + 22, y + 142, "Деревня офлайн: сломана вышка!", UiKit.COL_RED);
		} else {
			UiKit.label(ctx, textRenderer, x + 22, y + 142, "Вышки не обнаружены поблизости", UiKit.COL_TEXT_DIM);
		}
	}

	// ------------------------------ Магазин ------------------------------

	private void renderShop(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 1) {
			lockOverlay(ctx, x, y, "Нужен хотя бы 2G для магазина");
			return;
		}
		NbtCompound shop = sub(data, "shop");
		var entries = rows(shop, "entries");

		int count = buyCount;
		UiKit.label(ctx, textRenderer, x + PW - 44, y + 5, "×" + count, UiKit.COL_TEXT);
		UiKit.button(ctx, textRenderer, x + PW - 26, y + 18, 12, 12, "-", mx, my, count > 1);
		clickable(x + PW - 26, y + 18, 12, 12, () -> buyCount = Math.max(1, buyCount / 2));
		UiKit.button(ctx, textRenderer, x + PW - 14, y + 18, 12, 12, "+", mx, my, count < 64);
		clickable(x + PW - 14, y + 18, 12, 12, () -> buyCount = Math.min(64, buyCount * 2));

		int listY = y + 36;
		int rowH = 9 + 10;
		int visible = Math.min(entries.size(), 8);
		for (int idx = 0; idx < visible; idx++) {
			NbtCompound e = entries.get(idx);
			int ry = listY + idx * (rowH + 2);
			UiKit.panel(ctx, x + 8, ry, PW - 16, rowH, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry + 1);
			UiKit.label(ctx, textRenderer, x + 32, ry + 2, trim(str(e, "name"), 28), UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 32, ry + 10, i(e, "buy") + " CR", UiKit.COL_YELLOW);
			UiKit.button(ctx, textRenderer, x + PW - 60, ry + 2, 52, 14, "Купить", mx, my, true);
			final String fid = str(e, "id");
			clickable(x + PW - 60, ry + 2, 52, 14, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("id", fid);
				a.putInt("count", buyCount);
				send("buy", a);
			});
		}
		// пейджер
		int page = i(shop, "page");
		int pages = i(shop, "pages");
		UiKit.button(ctx, textRenderer, x + 8, y + PH - 34, 18, 13, "<", mx, my, page > 0);
		clickable(x + 8, y + PH - 34, 18, 13, () -> sendQuery(query, Math.max(0, page - 1)));
		UiKit.label(ctx, textRenderer, x + 34, y + PH - 31, (page + 1) + "/" + pages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 80, y + PH - 34, 18, 13, ">", mx, my, page + 1 < pages);
		clickable(x + 80, y + PH - 34, 18, 13, () -> sendQuery(query, page + 1));
	}

	private void sendQuery(String q, int page) {
		NbtCompound a = new NbtCompound();
		a.putString("q", q);
		a.putInt("page", page);
		send("query_shop", a);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (tab == Tab.SHOP && searchInput != null && searchInput.focused
				&& (keyCode == 257 || keyCode == 335)) { // enter — поиск
			query = searchInput.value;
			searchInput.focused = false;
			sendQuery(query, 0);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	// ------------------------------ Биржа ------------------------------

	private void renderStocks(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 2) {
			lockOverlay(ctx, x, y, "Нужен 3G+ для биржи");
			return;
		}
		var stocks = rows(data, "stocks");
		int ry = y + 2;
		for (NbtCompound s : stocks) {
			UiKit.panel(ctx, x + 8, ry, PW - 16, 34, UiKit.COL_PANEL);
			String id = str(s, "id");
			UiKit.label(ctx, textRenderer, x + 14, ry + 4, id + " · " + str(s, "name"), UiKit.COL_TEXT);
			double price = dbl(s, "price");
			double delta = dbl(s, "delta");
			String ds = (delta >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", delta) + "%";
			UiKit.label(ctx, textRenderer, x + 14, ry + 14,
					String.format(java.util.Locale.ROOT, "%.2f", price) + " CR", UiKit.COL_YELLOW);
			UiKit.label(ctx, textRenderer, x + 90, ry + 14, ds, delta >= 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
			UiKit.label(ctx, textRenderer, x + 14, ry + 24, "у вас: " + i(s, "owned"), UiKit.COL_TEXT_DIM);
			drawSparkline(ctx, x + 130, ry + 5, 70, 22, s.getIntArray("hist").orElse(new int[0]));
			// кнопки
			UiKit.button(ctx, textRenderer, x + PW - 68, ry + 3, 28, 12, "+1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 68, ry + 19, 28, 12, "-1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 36, ry + 3, 28, 12, "+10", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 36, ry + 19, 28, 12, "-10", mx, my, true);
			clickable(x + PW - 68, ry + 3, 28, 12, () -> stock(id, true, 1));
			clickable(x + PW - 68, ry + 19, 28, 12, () -> stock(id, false, 1));
			clickable(x + PW - 36, ry + 3, 28, 12, () -> stock(id, true, 10));
			clickable(x + PW - 36, ry + 19, 28, 12, () -> stock(id, false, 10));
			ry += 38;
		}
	}

	private void stock(String id, boolean buy, int n) {
		NbtCompound a = new NbtCompound();
		a.putString("id", id);
		a.putInt("n", n);
		send(buy ? "stock_buy" : "stock_sell", a);
	}

	private void drawSparkline(DrawContext ctx, int x, int y, int w, int h, int[] hist) {
		ctx.drawHorizontalLine(x, x + w, y + h - 1, UiKit.COL_LINE);
		if (hist == null || hist.length < 2) return;
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for (int v : hist) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		if (min == max) {
			min = max - 1;
		}
		int n = Math.min(hist.length, w);
		int start = hist.length - n;
		int prevX = -1, prevY = -1;
		for (int k = 0; k < n; k++) {
			int v = hist[start + k];
			int px = x + k;
			int py = y + h - 2 - (int) ((v - min) * (h - 4.0) / (max - min));
			ctx.fill(px, py, px + 1, py + 1, UiKit.COL_ACCENT);
			if (prevX >= 0) {
				// соединяем вертикаль перемычкой
				int y1 = Math.min(prevY, py), y2 = Math.max(prevY, py);
				if (y2 > y1) ctx.fill(px, y1, px + 1, y2, UiKit.COL_ACCENT);
			}
			prevX = px;
			prevY = py;
		}
	}

	// ------------------------------ GPS ------------------------------

	private void renderGps(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "gpsOk") != 1) {
			lockOverlay(ctx, x, y, "Нет сигнала GPS (глубоко под землёй)");
			return;
		}
		if (mc() == null || mc().player == null || mc().world == null) return;
		if (map == null) map = new GpsMapRenderer();
		map.resampleIfNeeded(mc().player, mc().world);

		int mxp = x + (PW - GpsMapRenderer.SIZE) / 2;
		int myp = y + 2;
		map.draw(ctx, mxp, myp);

		// игрок — центр
		int cx = mxp + GpsMapRenderer.SIZE / 2;
		int cy = myp + GpsMapRenderer.SIZE / 2;
		ctx.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);

		// метки деревень
		int pX = mc().player.getBlockPos().getX();
		int pZ = mc().player.getBlockPos().getZ();
		for (NbtCompound v : rows(data, "villages")) {
			int dx = i(v, "x") - pX;
			int dz = i(v, "z") - pZ;
			int vx = cx + Math.max(-60, Math.min(60, dx));
			int vz = cy + Math.max(-60, Math.min(60, dz));
			int col = i(v, "off") == 1 ? 0xFFF85149 : 0xFF3FB950;
			ctx.fill(vx - 2, vz - 2, vx + 2, vz + 2, col);
			String nm = str(v, "name");
			int nw = Math.min(textRenderer.getWidth(nm), 80);
			ctx.drawText(textRenderer, Text.literal(nm), vx - nw / 2, vz + 4, UiKit.COL_TEXT, true);
			String dlab = i(v, "d") + " м";
			ctx.drawText(textRenderer, Text.literal(dlab), vx - textRenderer.getWidth(dlab) / 2, vz + 13, UiKit.COL_TEXT_DIM, true);
		}
		UiKit.label(ctx, textRenderer, mxp, myp + GpsMapRenderer.SIZE + 4,
				"X: " + pX + "  Z: " + pZ, UiKit.COL_TEXT_DIM);
	}

	// ------------------------------ Банк ------------------------------

	private void renderBank(DrawContext ctx, int x, int y, int mx, int my) {
		UiKit.panel(ctx, x + 8, y + 2, PW - 16, 30, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 9, "Баланс", UiKit.COL_TEXT_DIM);
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 16 - textRenderer.getWidth(bal), y + 9, UiKit.COL_YELLOW, false);
		UiKit.label(ctx, textRenderer, x + 16, y + 20, "Обналичивание и депозит — через банкомат в деревне", UiKit.COL_TEXT_DIM);

		// лог транзакций
		UiKit.panel(ctx, x + 8, y + 38, PW - 16, 128, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + 16, y + 44, "История операций", UiKit.COL_ACCENT);
		String[] lines = str(data, "tx").isEmpty() ? new String[0] : str(data, "tx").split("\n");
		int ly = y + 58;
		for (int k = Math.max(0, lines.length - 10); k < lines.length; k++) {
			String l = lines[k];
			int col = l.startsWith("+") ? UiKit.COL_GREEN : UiKit.COL_TEXT;
			UiKit.label(ctx, textRenderer, x + 16, ly, trim(l, 54), col);
			ly += 10;
		}
	}

	// ----------------------------------------------------------------

	private void lockOverlay(DrawContext ctx, int x, int y, String msg) {
		int w = PW - 32;
		UiKit.panel(ctx, x + 16, y + 60, w, 40, UiKit.COL_PANEL_HI);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), x + PW / 2, y + 76, UiKit.COL_RED);
		String hint = "связь дают вышки рядом с деревнями";
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), x + PW / 2, y + 88, UiKit.COL_TEXT_DIM);
	}
}
