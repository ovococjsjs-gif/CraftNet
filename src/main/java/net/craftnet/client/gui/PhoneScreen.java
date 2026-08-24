package net.craftnet.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import net.craftnet.client.GpsMapRenderer;

/**
 * Телефон CraftNet 2.0: статус-бар (время/сеть/батарея/баланс), домашний
 * экран приложений, магазин с поиском, биржа с тикером новостей, GPS, банк
 * с переводами. Все данные приходят с сервера через синхронизацию.
 */
public class PhoneScreen extends CraftNetScreen {

	private enum Tab {HOME, SHOP, MARKET, CASINO, STOCKS, GPS, BANK}

	private static final int PW = 272;
	private static final int PH = 224;

	private Tab tab = Tab.HOME;
	private int buyCount = 1;
	private String query = "";
	private GpsMapRenderer map;
	private UiKit.TextInput searchInput;
	private UiKit.TextInput casinoSearchInput;
	private String targetQuery = "";
	// выбранная цель апгрейда
	private String casinoTarget;
	private int casinoTargetPrice;
	private String casinoTargetName = "";
	// рулетка
	private long seenSpinId = -1;
	private long animStartTick = -1;
	private long animSeed;
	private boolean animWin;
	private int animBp;
	private String animTargetId = "";
	private String animTargetName = "";
	private String animStakeIconId = "";
	private boolean animEndPlayed;
	private UiKit.TextInput payeeInput;
	private UiKit.TextInput amountInput;

	public PhoneScreen(NbtCompound data) {
		super("phone", Text.translatable("craftnet.phone.title"), data);
	}

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		searchInput = null;
		if (tab == Tab.SHOP) {
			searchInput = new UiKit.TextInput(px() + 8, py() + 37, PW - 100, "поиск предмета…", false);
			searchInput.value = query;
			inputs.add(searchInput);
		} else if (tab == Tab.CASINO) {
			casinoSearchInput = new UiKit.TextInput(px() + 136, py() + 73, 128, "цель…", false);
			casinoSearchInput.value = targetQuery;
			casinoSearchInput.maxLen = 20;
			inputs.add(casinoSearchInput);
		} else if (tab == Tab.BANK) {
			payeeInput = new UiKit.TextInput(px() + 16, py() + 94, 96, "имя игрока", false);
			payeeInput.maxLen = 16;
			amountInput = new UiKit.TextInput(px() + 118, py() + 94, 52, "сумма", true);
			amountInput.maxLen = 9;
			if (payeeInputPrev != null) payeeInput.value = payeeInputPrev;
			inputs.add(payeeInput);
			inputs.add(amountInput);
		}
	}

	private String payeeInputPrev;

	private void switchTab(Tab t) {
		if (payeeInput != null && tab == Tab.BANK) payeeInputPrev = payeeInput.value;
		payeeInput = null;
		amountInput = null;
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
		UiKit.card(ctx, x - 8, y - 8, PW + 16, PH + 16, 0xFF0B0D10);
		ctx.fill(x, y, x + PW, y + PH, UiKit.COL_BG);

		if (data.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Синхронизация с CraftNet…"),
					x + PW / 2, y + PH / 2 - 4, UiKit.COL_TEXT_DIM);
			return;
		}

		renderStatusBar(ctx, x, y, mx, my);

		// панель вкладок — иконки приложений
		int ty = y + 16;
		drawTabIcon(ctx, x + 8, ty, Items.COMPASS, Tab.HOME, mx, my);
		drawTabIcon(ctx, x + 44, ty, Items.EMERALD, Tab.SHOP, mx, my);
		drawTabIcon(ctx, x + 80, ty, Items.CHEST, Tab.MARKET, mx, my);
		drawTabIcon(ctx, x + 116, ty, Items.TARGET, Tab.CASINO, mx, my);
		drawTabIcon(ctx, x + 152, ty, Items.PAPER, Tab.STOCKS, mx, my);
		drawTabIcon(ctx, x + 188, ty, Items.FILLED_MAP, Tab.GPS, mx, my);
		drawTabIcon(ctx, x + 224, ty, Items.GOLD_INGOT, Tab.BANK, mx, my);
		ctx.drawHorizontalLine(x, x + PW - 1, y + 36, UiKit.COL_LINE);

		int cy = y + 40;
		switch (tab) {
			case HOME -> renderHome(ctx, x, cy, mx, my);
			case SHOP -> renderShop(ctx, x, cy, mx, my);
			case MARKET -> renderMarket(ctx, x, cy, mx, my);
			case CASINO -> renderCasino(ctx, x, cy, mx, my);
			case STOCKS -> renderStocks(ctx, x, cy, mx, my);
			case GPS -> renderGps(ctx, x, cy, mx, my);
			case BANK -> renderBank(ctx, x, cy, mx, my);
		}
	}

	private void drawTabIcon(DrawContext ctx, int x, int y, Item icon, Tab t, double mx, double my) {
		boolean active = tab == t;
		boolean hover = mx >= x && mx < x + 32 && my >= y && my < y + 18;
		ctx.fill(x, y, x + 32, y + 18, active ? UiKit.COL_PANEL_HI : hover ? UiKit.COL_PANEL : UiKit.COL_BG);
		ctx.drawItem(icon.getDefaultStack(), x + 8, y + 1);
		if (active) ctx.fill(x + 3, y + 17, x + 29, y + 19, UiKit.COL_ACCENT);
		clickable(x, y, 32, 19, () -> switchTab(t));
	}

	private void renderStatusBar(DrawContext ctx, int x, int y, double mx, double my) {
		// время мира
		String time = "--:--";
		if (mc() != null && mc().world != null) {
			long t = mc().world.getTimeOfDay() % 24000;
			int h = (int) ((t / 1000 + 6) % 24);
			int m = (int) ((t % 1000) * 60 / 1000);
			time = String.format("%02d:%02d", h, m);
		}
		UiKit.label(ctx, textRenderer, x + 8, y + 5, time, UiKit.COL_TEXT);

		// палочки сигнала — 3, по tier (G2=1..G4=3)
		int sig = i(data, "signal");
		for (int b = 0; b < 3; b++) {
			int bh = 4 + b * 3;
			int col = b < sig ? 0xFF3FB950 : 0xFF3A3F4B;
			ctx.fill(x + 52 + b * 6, y + 13 - bh, x + 56 + b * 6, y + 13, col);
		}
		UiKit.label(ctx, textRenderer, x + 72, y + 5, signalLabel(),
				sig > 0 ? UiKit.COL_GREEN : UiKit.COL_RED);

		// батарейка (вечная зарядка :-))
		int batX = x + 112;
		ctx.drawHorizontalLine(batX, batX + 11, y + 5, UiKit.COL_TEXT_DIM);
		ctx.drawHorizontalLine(batX, batX + 11, y + 11, UiKit.COL_TEXT_DIM);
		ctx.drawVerticalLine(batX, y + 5, y + 11, UiKit.COL_TEXT_DIM);
		ctx.drawVerticalLine(batX + 11, y + 5, y + 11, UiKit.COL_TEXT_DIM);
		ctx.fill(batX + 12, y + 7, batX + 14, y + 9, UiKit.COL_TEXT_DIM); // «носик»
		ctx.fill(batX + 2, y + 7, batX + 10, y + 10, 0xFF3FB950); // полный заряд

		// баланс справа
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 8 - textRenderer.getWidth(bal), y + 5,
				UiKit.COL_YELLOW, false);
		// тонкая разделительная линия статус-бара
		ctx.drawHorizontalLine(x, x + PW - 1, y + 15, UiKit.COL_LINE);
	}

	private String luckSub() {
		long v = lng(sub(data, "casino"), "stakeVal");
		return v > 0 ? v + " CR на кону" : "испытай удачу";
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
				new App("Барахолка", Items.CHEST.getDefaultStack(), Tab.MARKET),
				new App("Биржа", Items.PAPER.getDefaultStack(), Tab.STOCKS),
				new App("GPS", Items.FILLED_MAP.getDefaultStack(), Tab.GPS),
				new App("Банк", Items.GOLD_INGOT.getDefaultStack(), Tab.BANK),
				new App("Апгрейд", Items.TARGET.getDefaultStack(), Tab.CASINO),
		};
		int idx = 0;
		for (App app : apps) {
			int ax = x + 14 + (idx % 3) * 84;
			int ay = y + 4 + (idx / 3) * 56;
			UiKit.card(ctx, ax, ay, 78, 50, UiKit.COL_PANEL);
			ctx.drawItem(app.icon(), ax + 8, ay + 17);
			UiKit.label(ctx, textRenderer, ax + 28, ay + 13, app.name(), UiKit.COL_TEXT);
			String sub = switch (app.tab()) {
				case SHOP -> i(data, "signal") >= 1 ? "витрина" : "нужен 2G";
				case MARKET -> i(data, "signal") >= 1 ? "лотов: " + i(sub(data, "market"), "total") : "нужен 2G";
				case STOCKS -> i(data, "signal") >= 2 ? "5 компаний" : "нужен 3G";
				case GPS -> i(data, "gpsOk") == 1 ? "онлайн" : "офлайн";
				case BANK -> lng(data, "balance") + " CR";
				case CASINO -> luckSub();
				default -> "";
			};
			UiKit.label(ctx, textRenderer, ax + 28, ay + 27, sub,
					(app.tab() == Tab.GPS && i(data, "gpsOk") != 1)
							|| (app.tab() == Tab.SHOP && i(data, "signal") < 1)
							|| (app.tab() == Tab.MARKET && i(data, "signal") < 1)
							|| (app.tab() == Tab.STOCKS && i(data, "signal") < 2)
							? UiKit.COL_RED : UiKit.COL_TEXT_DIM);
			clickable(ax, ay, 78, 50, () -> switchTab(app.tab()));
			idx++;
		}
		// карточка сети
		UiKit.card(ctx, x + 14, y + 118, PW - 28, 40, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 22, y + 127, "Текущая сеть: " + signalLabel(),
				i(data, "signal") > 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
		String vill = str(data, "village");
		if (!vill.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 22, y + 141,
					"Точка: " + vill + " (" + i(data, "dist") + " м)", UiKit.COL_TEXT_DIM);
		} else if (i(data, "offVillage") == 1) {
			UiKit.label(ctx, textRenderer, x + 22, y + 141, "Деревня офлайн: сломана вышка!", UiKit.COL_RED);
		} else {
			UiKit.label(ctx, textRenderer, x + 22, y + 141, "Вышки не обнаружены поблизости", UiKit.COL_TEXT_DIM);
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

		// счётчик количества (правый верхний угол)
		UiKit.label(ctx, textRenderer, x + PW - 84, y + 6, "×" + buyCount, UiKit.COL_TEXT);
		UiKit.button(ctx, textRenderer, x + PW - 58, y + 3, 13, 13, "-", mx, my, buyCount > 1);
		clickable(x + PW - 58, y + 3, 13, 13, () -> buyCount = Math.max(1, buyCount / 2));
		UiKit.button(ctx, textRenderer, x + PW - 42, y + 3, 13, 13, "+", mx, my, buyCount < 64);
		clickable(x + PW - 42, y + 3, 13, 13, () -> buyCount = Math.min(64, buyCount * 2));

		// строки каталога: 6 штук по 19px — ровно до пейджера
		int listY = y + 22;
		int visible = Math.min(entries.size(), 6);
		for (int idx = 0; idx < visible; idx++) {
			NbtCompound e = entries.get(idx);
			int ry = listY + idx * 19;
			UiKit.card(ctx, x + 8, ry, PW - 16, 17, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry);
			UiKit.label(ctx, textRenderer, x + 32, ry + 1, trim(str(e, "name"), 20), UiKit.COL_TEXT);
			long total = (long) i(e, "buy") * buyCount;
			UiKit.label(ctx, textRenderer, x + 32, ry + 9,
					i(e, "buy") + " CR/шт · продажа " + i(e, "sell"), UiKit.COL_TEXT_DIM);
			UiKit.button(ctx, textRenderer, x + PW - 64, ry + 2, 56, 13, total + " CR", mx, my,
					lng(data, "balance") >= total);
			final String fid = str(e, "id");
			clickable(x + PW - 64, ry + 2, 56, 13, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("id", fid);
				a.putInt("count", buyCount);
				send("buy", a);
			});
		}
		// пейджер (центрированный)
		int page = i(shop, "page");
		int pages = i(shop, "pages");
		int py2 = y + 142;
		UiKit.button(ctx, textRenderer, x + 8, py2, 18, 13, "<", mx, my, page > 0);
		clickable(x + 8, py2, 18, 13, () -> sendQuery(query, Math.max(0, page - 1)));
		UiKit.label(ctx, textRenderer, x + 32, py2 + 3, (page + 1) + " / " + pages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 88, py2, 18, 13, ">", mx, my, page + 1 < pages);
		clickable(x + 88, py2, 18, 13, () -> sendQuery(query, page + 1));
		UiKit.label(ctx, textRenderer, x + PW - 120, py2 + 3, "доставка в ПВЗ", UiKit.COL_TEXT_DIM);
	}

	// ------------------------------ Казино-апгрейд ------------------------------

	private static final int STRIP_CELLS = 48;
	private static final int STRIP_CELL = 26;
	private static final int STRIP_LANDING = 32;
	private static final int ANIM_DUR = 64; // тиков ≈ 3.2 сек

	private void renderCasino(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 1) {
			lockOverlay(ctx, x, y, "Нужен хотя бы 2G для апгрейда");
			return;
		}
		NbtCompound cz = sub(data, "casino");
		var staked = rows(cz, "staked");
		long stakeVal = lng(cz, "stakeVal");
		NbtCompound last = sub(cz, "last");
		long lastId = lng(last, "id");

		// детектор нового спина
		if (lastId > 0) {
			if (seenSpinId < 0) {
				seenSpinId = lastId; // первичная синхронизация — без анимации
			} else if (lastId > seenSpinId) {
				seenSpinId = lastId;
				animStartTick = mc() != null && mc().world != null ? mc().world.getTime() : 0;
				animSeed = lastId;
				animWin = i(last, "win") == 1;
				animBp = i(last, "bp");
				animTargetId = str(last, "target");
				animTargetName = str(last, "tname");
				animStakeIconId = str(last, "sicon");
				animEndPlayed = false;
			}
		}

		// --- пул ставки ---
		UiKit.card(ctx, x + 8, y + 2, PW - 16, 22, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 14, y + 7, "Ставка:", UiKit.COL_TEXT_DIM);
		int ix = x + 54;
		for (NbtCompound e : staked) {
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), ix, y + 3);
			UiKit.label(ctx, textRenderer, ix + 3, y + 19 - 6, "", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, ix + 9, y + 3, "×" + i(e, "count"), UiKit.COL_TEXT_DIM);
			ix += 30;
		}
		String sv = stakeVal + " CR";
		ctx.drawText(textRenderer, Text.literal(sv), x + PW - 46 - textRenderer.getWidth(sv), y + 7,
				UiKit.COL_YELLOW, false);
		UiKit.button(ctx, textRenderer, x + PW - 36, y + 5, 24, 14, "✕", mx, my, !staked.isEmpty());
		clickable(x + PW - 36, y + 5, 24, 14, () -> send("casino_clear", new NbtCompound()));

		// --- инвентарь (источник ставок) ---
		var src = rows(cz, "src");
		UiKit.label(ctx, textRenderer, x + 8, y + 30,
				"инвентарь:" + (src.size() > 4 ? " +" + (src.size() - 4) : ""), UiKit.COL_TEXT_DIM);
		int ry = y + 40;
		int shown = 0;
		for (NbtCompound e : src) {
			if (shown >= 4) break;
			shown++;
			UiKit.card(ctx, x + 8, ry, 120, 16, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 11, ry);
			UiKit.label(ctx, textRenderer, x + 31, ry + 1, trim(str(e, "name"), 7) + " ×" + i(e, "count"),
					UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 31, ry + 9, i(e, "price") + " CR/шт", UiKit.COL_TEXT_DIM);
			final String fid = str(e, "id");
			UiKit.button(ctx, textRenderer, x + 82, ry + 2, 20, 12, "+1", mx, my, true);
			clickable(x + 82, ry + 2, 20, 12, () -> stakeAction(fid, 1));
			UiKit.button(ctx, textRenderer, x + 105, ry + 2, 22, 12, "64", mx, my, true);
			clickable(x + 105, ry + 2, 22, 12, () -> stakeAction(fid, 64));
			ry += 18;
		}

		// --- цель (каталог) ---
		UiKit.label(ctx, textRenderer, x + 136, y + 30, "цель (поиск + Enter):", UiKit.COL_TEXT_DIM);
		NbtCompound targets = sub(cz, "targets");
		var tents = rows(targets, "entries");
		ry = y + 50;
		int tShown = 0;
		for (NbtCompound e : tents) {
			if (tShown >= 3) break;
			tShown++;
			boolean sel = str(e, "id").equals(casinoTarget);
			UiKit.card(ctx, x + 136, ry, 128, 16, sel ? 0xFF2A3A20 : UiKit.COL_PANEL);
			if (sel) {
				ctx.drawHorizontalLine(x + 136, x + 263, ry, UiKit.COL_YELLOW);
				ctx.drawHorizontalLine(x + 136, x + 263, ry + 15, UiKit.COL_YELLOW);
			}
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 139, ry);
			UiKit.label(ctx, textRenderer, x + 159, ry + 1, trim(str(e, "name"), 13), UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 159, ry + 9, i(e, "buy") + " CR", UiKit.COL_YELLOW);
			final String tid = str(e, "id");
			final int tp = i(e, "buy");
			final String tn = str(e, "name");
			clickable(x + 136, ry, 128, 16, () -> {
				casinoTarget = tid;
				casinoTargetPrice = tp;
				casinoTargetName = tn;
			});
			ry += 18;
		}
		// пейджер целей
		int tpage = i(targets, "page");
		int tpages = i(targets, "pages");
		UiKit.button(ctx, textRenderer, x + 196, y + 104, 12, 11, "<", mx, my, tpage > 0);
		clickable(x + 196, y + 104, 12, 11, () -> casinoQuery(targetQuery, Math.max(0, tpage - 1)));
		UiKit.label(ctx, textRenderer, x + 212, y + 106, (tpage + 1) + "/" + tpages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 250, y + 104, 12, 11, ">", mx, my, tpage + 1 < tpages);
		clickable(x + 250, y + 104, 12, 11, () -> casinoQuery(targetQuery, tpage + 1));

		// --- шанс + спин ---
		long bp = 0;
		if (stakeVal > 0 && casinoTargetPrice > 0) {
			bp = Math.min(9500, stakeVal * 10000 / casinoTargetPrice);
		}
		String chanceTxt = casinoTarget == null ? "выбери цель →"
				: "шанс: " + (bp < 100 ? "<1" : String.format(java.util.Locale.ROOT, "%.1f", bp / 100.0)) + "%";
		int chCol = bp >= 5000 ? UiKit.COL_GREEN : bp >= 2000 ? UiKit.COL_YELLOW : UiKit.COL_RED;
		UiKit.label(ctx, textRenderer, x + 12, y + 116, chanceTxt, chCol);
		if (casinoTarget != null) {
			UiKit.label(ctx, textRenderer, x + 12, y + 126,
					"ставка " + stakeVal + " → приз " + trim(casinoTargetName, 12), UiKit.COL_TEXT_DIM);
		}
		boolean canSpin = !staked.isEmpty() && casinoTarget != null && bp >= 100;
		UiKit.button(ctx, textRenderer, x + PW - 104, y + 112, 96, 18, "КРУТИТЬ!", mx, my, canSpin);
		if (canSpin) {
			clickable(x + PW - 104, y + 112, 96, 18, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("target", casinoTarget);
				send("casino_spin", a);
			});
		}

		// --- рулетка ---
		drawRoulette(ctx, x + 8, y + 136, PW - 16, 30, lastId, last);

		// --- итог ---
		boolean animActive = animStartTick >= 0 && mc() != null && mc().world != null
				&& mc().world.getTime() - animStartTick < ANIM_DUR;
		if (!animActive && lastId > 0) {
			boolean win = i(last, "win") == 1;
			String res = win
					? "ВЫИГРЫШ: " + str(last, "tname")
					: "проигрыш — ставка " + lng(last, "sv") + " CR сгорела";
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(res), x + PW / 2, y + 172,
					win ? UiKit.COL_GREEN : UiKit.COL_RED);
		}
	}

	private void stakeAction(String id, int count) {
		NbtCompound a = new NbtCompound();
		a.putString("id", id);
		a.putInt("count", count);
		send("casino_stake", a);
	}

	private void casinoQuery(String q, int page) {
		NbtCompound a = new NbtCompound();
		a.putString("q", q);
		a.putInt("page", page);
		send("casino_query", a);
	}

	/** Лента рулетки: детерминирована от id спина, остановка строго на решении сервера. */
	private void drawRoulette(DrawContext ctx, int sx, int sy, int sw, int sh, long lastId, NbtCompound last) {
		UiKit.card(ctx, sx, sy, sw, sh, UiKit.COL_TRACK);
		long now = mc() != null && mc().world != null ? mc().world.getTime() : 0;
		boolean animating = animStartTick >= 0 && now - animStartTick < ANIM_DUR;
		if (!animating && lastId <= 0) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("собери ставку, выбери цель — и лента покажет, кому улыбнётся удача"),
					sx + sw / 2, sy + sh / 2 - 4, UiKit.COL_TEXT_DIM);
			return;
		}
		long seed = animating ? animSeed : lastId;
		boolean win = animating ? animWin : i(last, "win") == 1;
		int bp = animating ? animBp : i(last, "bp");
		String tid = animating ? animTargetId : str(last, "target");
		String stakeIcon = animating ? animStakeIconId : str(last, "sicon");
		if (stakeIcon.isEmpty()) stakeIcon = "minecraft:barrier";

		// сетка ячеек по seed
		java.util.Random rnd = new java.util.Random(seed);
		int greenCount = Math.max(1, Math.min(STRIP_CELLS - 2, (int) Math.round(bp / 10000.0 * (STRIP_CELLS - 2))));
		boolean[] green = new boolean[STRIP_CELLS];
		int placed = 0;
		while (placed < greenCount) {
			int idx = rnd.nextInt(STRIP_CELLS);
			if (idx == STRIP_LANDING || green[idx]) continue;
			green[idx] = true;
			placed++;
		}
		int jitter = rnd.nextInt(STRIP_CELL - 8) - (STRIP_CELL - 8) / 2;

		// прокрутка
		int totalScroll = STRIP_LANDING * STRIP_CELL + STRIP_CELL / 2 - sw / 2 + jitter;
		int scroll = totalScroll;
		if (animating) {
			double t = Math.min(1.0, (now - animStartTick) / (double) ANIM_DUR);
			double ease = 1.0 - Math.pow(1.0 - t, 5);
			scroll = (int) (totalScroll * ease);
		} else if (animStartTick >= 0 && now - animStartTick >= ANIM_DUR) {
			scroll = totalScroll; // приземлились
		}

		Item targetItem = Registries.ITEM.get(Identifier.tryParse(tid));
		Item loseItem = Registries.ITEM.get(Identifier.tryParse(stakeIcon));
		ctx.enableScissor(sx + 1, sy + 1, sx + sw - 1, sy + sh - 1);
		for (int ci = 0; ci < STRIP_CELLS; ci++) {
			int cx = sx + ci * STRIP_CELL - scroll;
			if (cx + STRIP_CELL < sx || cx > sx + sw) continue;
			boolean isLanding = ci == STRIP_LANDING;
			int bg = isLanding ? 0xFF6B5416 : green[ci] ? UiKit.COL_GREEN_DIM : 0xFF3B1518;
			ctx.fill(cx + 1, sy + 4, cx + STRIP_CELL - 1, sy + sh - 4, bg);
			Item icon = (isLanding ? win : green[ci]) ? targetItem : loseItem;
			if (icon != null) ctx.drawItem(icon.getDefaultStack(), cx + (STRIP_CELL - 16) / 2, sy + 7);
		}
		ctx.disableScissor();
		// указатель
		int pc = sx + sw / 2;
		ctx.fill(pc, sy, pc + 1, sy + sh, 0xFFFFD75E);
		ctx.fill(pc - 3, sy, pc + 4, sy + 2, 0xFFFFD75E);

		// звук остановки
		if (animStartTick >= 0 && now - animStartTick >= ANIM_DUR && !animEndPlayed) {
			animEndPlayed = true;
			net.minecraft.client.MinecraftClient mc2 = mc();
			if (mc2 != null && mc2.getSoundManager() != null) {
				net.minecraft.sound.SoundEvent ev = animWin
						? net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP
						: net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK.value();
				mc2.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(ev, 1.0F));
			}
		}
	}

	// ------------------------------ Барахолка ------------------------------

	private void renderMarket(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 1) {
			lockOverlay(ctx, x, y, "Нужен хотя бы 2G для барахолки");
			return;
		}
		NbtCompound market = sub(data, "market");
		var entries = rows(market, "entries");
		UiKit.label(ctx, textRenderer, x + 10, y + 6,
				"лотов: " + i(market, "total") + " · комиссия 5% у продавца", UiKit.COL_TEXT_DIM);
		if (entries.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 10, y + 40, "Пока пусто. Продавай своё из ПВЗ —", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 10, y + 52, "кнопка ₽ рядом с предметом.", UiKit.COL_TEXT_DIM);
		}
		int listY = y + 22;
		int visible = Math.min(entries.size(), 6);
		for (int idx = 0; idx < visible; idx++) {
			NbtCompound e = entries.get(idx);
			int ry = listY + idx * 19;
			UiKit.card(ctx, x + 8, ry, PW - 16, 17, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "itemId")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry);
			UiKit.label(ctx, textRenderer, x + 32, ry + 1, trim(str(e, "name"), 18) + " ×" + i(e, "count"),
					UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 32, ry + 9,
					"от " + trim(str(e, "seller"), 12) + " · " + i(e, "price") + " CR/шт", UiKit.COL_TEXT_DIM);
			long total = (long) i(e, "price") * i(e, "count");
			UiKit.button(ctx, textRenderer, x + PW - 64, ry + 2, 56, 13, total + " CR", mx, my,
					lng(data, "balance") >= total);
			final long lid = lng(e, "lid");
			clickable(x + PW - 64, ry + 2, 56, 13, () -> {
				NbtCompound a = new NbtCompound();
				a.putLong("lid", lid);
				send("market_buy", a);
			});
		}
		int page = i(market, "page");
		int pages = i(market, "pages");
		int py2 = y + 142;
		UiKit.button(ctx, textRenderer, x + 8, py2, 18, 13, "<", mx, my, page > 0);
		clickable(x + 8, py2, 18, 13, () -> marketQuery(Math.max(0, page - 1)));
		UiKit.label(ctx, textRenderer, x + 32, py2 + 3, (page + 1) + " / " + pages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 88, py2, 18, 13, ">", mx, my, page + 1 < pages);
		clickable(x + 88, py2, 18, 13, () -> marketQuery(page + 1));
		UiKit.label(ctx, textRenderer, x + PW - 130, py2 + 3, "листится из ПВЗ (кнопка ₽)", UiKit.COL_TEXT_DIM);
	}

	private void marketQuery(int page) {
		NbtCompound a = new NbtCompound();
		a.putInt("page", page);
		send("market_query", a);
	}

	private void sendQuery(String q, int page) {
		NbtCompound a = new NbtCompound();
		a.putString("q", q);
		a.putInt("page", page);
		send("query_shop", a);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int keyCode = input.key();
		if (tab == Tab.SHOP && searchInput != null && searchInput.focused
				&& (keyCode == 257 || keyCode == 335)) { // enter — поиск
			query = searchInput.value;
			searchInput.focused = false;
			sendQuery(query, 0);
			return true;
		}
		if (tab == Tab.CASINO && casinoSearchInput != null && casinoSearchInput.focused
				&& (keyCode == 257 || keyCode == 335)) {
			targetQuery = casinoSearchInput.value;
			casinoSearchInput.focused = false;
			casinoQuery(targetQuery, 0);
			return true;
		}
		if (tab == Tab.BANK && amountInput != null && amountInput.focused
				&& (keyCode == 257 || keyCode == 335)) { // enter — отправить перевод
			sendTransfer();
			return true;
		}
		return super.keyPressed(input);
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
		var news = rows(data, "stNews");
		int ry = y + 2;
		if (!news.isEmpty()) {
			NbtCompound n0 = news.get(0);
			int dir = i(n0, "dir");
			String pc = (dir >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.0f", dbl(n0, "pct")) + "%";
			UiKit.label(ctx, textRenderer, x + 8, ry,
					"Новости: " + trim(str(n0, "txt") + " (" + pc + ")", 42),
					dir >= 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
			ry += 11;
		}
		var stocks = rows(data, "stocks");
		for (NbtCompound s : stocks) {
			UiKit.card(ctx, x + 8, ry, PW - 16, 30, UiKit.COL_PANEL);
			String id = str(s, "id");
			UiKit.label(ctx, textRenderer, x + 14, ry + 3, id + " · " + trim(str(s, "name"), 12), UiKit.COL_TEXT);
			double price = dbl(s, "price");
			double delta = dbl(s, "delta");
			String ds = (delta >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", delta) + "%";
			ctx.drawText(textRenderer, Text.literal(ds), x + 202 - textRenderer.getWidth(ds), ry + 3,
					delta >= 0 ? UiKit.COL_GREEN : UiKit.COL_RED, false);
			UiKit.label(ctx, textRenderer, x + 14, ry + 13,
					String.format(java.util.Locale.ROOT, "%.2f", price) + " CR", UiKit.COL_YELLOW);
			UiKit.label(ctx, textRenderer, x + 14, ry + 21,
					"у вас: " + i(s, "owned") + " · див " + String.format(java.util.Locale.ROOT, "%.1f", dbl(s, "div")) + "%/д",
					UiKit.COL_TEXT_DIM);
			drawSparkline(ctx, x + 146, ry + 11, 56, 14, s.getIntArray("hist").orElse(new int[0]));
			// кнопки +1/-1/+10/-10
			UiKit.button(ctx, textRenderer, x + PW - 64, ry + 3, 26, 11, "+1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 64, ry + 16, 26, 11, "-1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 36, ry + 3, 28, 11, "+10", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 36, ry + 16, 28, 11, "-10", mx, my, true);
			clickable(x + PW - 64, ry + 3, 26, 11, () -> stock(id, true, 1));
			clickable(x + PW - 64, ry + 16, 26, 11, () -> stock(id, false, 1));
			clickable(x + PW - 36, ry + 3, 28, 11, () -> stock(id, true, 10));
			clickable(x + PW - 36, ry + 16, 28, 11, () -> stock(id, false, 10));
			ry += 32;
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
			int px2 = x + k;
			int py2 = y + h - 2 - (int) ((v - min) * (h - 4.0) / (max - min));
			ctx.fill(px2, py2, px2 + 1, py2 + 1, UiKit.COL_ACCENT);
			if (prevX >= 0) {
				int y1 = Math.min(prevY, py2), y2 = Math.max(prevY, py2);
				if (y2 > y1) ctx.fill(px2, y1, px2 + 1, y2, UiKit.COL_ACCENT);
			}
			prevX = px2;
			prevY = py2;
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
		int pY = mc().player.getBlockPos().getY();
		UiKit.label(ctx, textRenderer, mxp, myp + GpsMapRenderer.SIZE + 4,
				"XYZ: " + pX + " " + pY + " " + pZ + " · 1 пикс = 1 блок", UiKit.COL_TEXT_DIM);
		// компас
		UiKit.label(ctx, textRenderer, mxp + GpsMapRenderer.SIZE - 10, myp + 3, "N", UiKit.COL_TEXT_DIM);
	}

	// ------------------------------ Банк ------------------------------

	private void renderBank(DrawContext ctx, int x, int y, int mx, int my) {
		// баланс
		UiKit.card(ctx, x + 8, y + 2, PW - 16, 26, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 8, "Баланс", UiKit.COL_TEXT_DIM);
		String bal = lng(data, "balance") + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 16 - textRenderer.getWidth(bal), y + 8,
				UiKit.COL_YELLOW, false);
		UiKit.label(ctx, textRenderer, x + 60, y + 8, "процент 0.15%/день", UiKit.COL_TEXT_DIM);

		// перевод игроку
		UiKit.card(ctx, x + 8, y + 34, PW - 16, 40, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + 16, y + 40, "Перевод игроку онлайн (нужен 2G):", UiKit.COL_ACCENT);
		UiKit.button(ctx, textRenderer, x + 174, y + 58, 90, 15, "Отправить", mx, my,
				canTransfer());
		clickable(x + 174, y + 58, 90, 15, this::sendTransfer);

		// история
		UiKit.card(ctx, x + 8, y + 80, PW - 16, 104, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + 16, y + 86, "История операций", UiKit.COL_ACCENT);
		String[] lines = str(data, "tx").isEmpty() ? new String[0] : str(data, "tx").split("\\n");
		if (lines.length == 0) {
			UiKit.label(ctx, textRenderer, x + 16, y + 100, "пока пусто", UiKit.COL_TEXT_DIM);
		}
		int ly = y + 98;
		for (int k = Math.max(0, lines.length - 8); k < lines.length; k++) {
			String l = lines[k];
			int col = l.startsWith("+") ? UiKit.COL_GREEN : UiKit.COL_TEXT;
			UiKit.label(ctx, textRenderer, x + 16, ly, trim(l, 50), col);
			ly += 10;
		}
	}

	private boolean canTransfer() {
		if (payeeInput == null || amountInput == null) return false;
		if (payeeInput.value.isBlank() || amountInput.value.isBlank()) return false;
		try {
			return Long.parseLong(amountInput.value) > 0 && i(data, "signal") >= 1;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private void sendTransfer() {
		if (!canTransfer()) return;
		NbtCompound a = new NbtCompound();
		a.putString("name", payeeInput.value.trim());
		a.putLong("amount", Long.parseLong(amountInput.value.trim()));
		send("transfer", a);
		amountInput.value = "";
	}

	// ----------------------------------------------------------------

	private void lockOverlay(DrawContext ctx, int x, int y, String msg) {
		int w = PW - 32;
		UiKit.card(ctx, x + 16, y + 60, w, 40, UiKit.COL_PANEL_HI);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), x + PW / 2, y + 76, UiKit.COL_RED);
		String hint = "связь дают вышки рядом с деревнями";
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), x + PW / 2, y + 88, UiKit.COL_TEXT_DIM);
	}
}
