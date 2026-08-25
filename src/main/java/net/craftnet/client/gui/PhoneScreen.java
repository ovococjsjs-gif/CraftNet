package net.craftnet.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
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

	private enum Tab {HOME, PROFILE, SHOP, MARKET, CASINO, STOCKS, GPS, BANK}

	private static final int PW = 300;
	private static final int PH = 240;

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
	private boolean casinoPickMode;
	private String casinoHint = "";
	// рулетка-циферблат
	private long seenSpinId = -1;
	private long animStartTick = -1;
	private long animSeed;
	private boolean animWin;
	private int animBp;
	private String animTargetId = "";
	private String animTargetName = "";
	private String animStakeIconId = "";
	private boolean animEndPlayed;
	private double animAngleFrom = -95;
	private double animFinalAngle;
	private int animLastMark = -1;
	private int lastMouseX;
	private int lastMouseY;
	private UiKit.TextInput payeeInput;
	private UiKit.TextInput amountInput;
	// апгрейдер: анимации
	private long lastStakeVal = -1;
	private long stakePulseStart = -100;
	private float shownBp;
	private static final int COL_STEP1 = 0xFF7EE787;
	private static final int COL_STEP2 = 0xFF58A6FF;

	public PhoneScreen(NbtCompound data) {
		super("phone", Text.translatable("craftnet.phone.title"), data);
	}

	@Override
	protected void init() {
		super.init();
		inputs.clear();
		searchInput = null;
		if (tab == Tab.SHOP) {
			searchInput = new UiKit.TextInput(px() + 8, py() + 43, 150, "поиск предмета…", false);
			searchInput.value = query;
			inputs.add(searchInput);
		} else if (tab == Tab.CASINO) {
			if (casinoPickMode) {
				casinoSearchInput = new UiKit.TextInput(px() + 8, py() + 44, PW - 64, "название цели… (Enter)", false);
				casinoSearchInput.value = targetQuery;
				casinoSearchInput.maxLen = 20;
				inputs.add(casinoSearchInput);
			}
		} else if (tab == Tab.BANK) {
			payeeInput = new UiKit.TextInput(px() + 16, py() + 90, 120, "имя игрока", false);
			payeeInput.maxLen = 16;
			amountInput = new UiKit.TextInput(px() + 142, py() + 90, 60, "сумма", true);
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
		casinoPickMode = false;
		casinoHint = "";
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

		// панель вкладок — иконки приложений (8 штук, шаг 36)
		int ty = y + 15;
		int tStep = 36;
		drawTabIcon(ctx, x + 8, ty, Items.COMPASS, Tab.HOME, mx, my);
		drawTabIcon(ctx, x + 8 + tStep, ty, Items.PLAYER_HEAD, Tab.PROFILE, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 2, ty, Items.EMERALD, Tab.SHOP, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 3, ty, Items.CHEST, Tab.MARKET, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 4, ty, Items.TARGET, Tab.CASINO, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 5, ty, Items.PAPER, Tab.STOCKS, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 6, ty, Items.FILLED_MAP, Tab.GPS, mx, my);
		drawTabIcon(ctx, x + 8 + tStep * 7, ty, Items.GOLD_INGOT, Tab.BANK, mx, my);
		ctx.drawHorizontalLine(x, x + PW - 1, y + 37, UiKit.COL_LINE);

		int cy = y + 40;
		switch (tab) {
			case HOME -> renderHome(ctx, x, cy, mx, my);
			case PROFILE -> renderProfile(ctx, x, cy, mx, my);
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
		boolean hover = mx >= x && mx < x + 36 && my >= y && my < y + 20;
		ctx.fill(x, y, x + 36, y + 20, active ? UiKit.COL_PANEL_HI : hover ? UiKit.COL_PANEL : UiKit.COL_BG);
		ctx.drawItem(icon.getDefaultStack(), x + 10, y + 2);
		if (active) ctx.fill(x + 4, y + 19, x + 32, y + 21, UiKit.COL_ACCENT);
		clickable(x, y, 36, 21, () -> switchTab(t));
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

		// баланс справа (с разрядами)
		String bal = UiKit.fmt(lng(data, "balance")) + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 8 - textRenderer.getWidth(bal), y + 5,
				UiKit.COL_YELLOW, false);
		// тонкая разделительная линия статус-бара
		ctx.drawHorizontalLine(x, x + PW - 1, y + 15, UiKit.COL_LINE);
	}

	private String luckSub() {
		long v = lng(sub(data, "casino"), "stakeVal");
		return v > 0 ? UiKit.fmt(v) + " CR на кону" : "испытай удачу";
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
				new App("Профиль", Items.PLAYER_HEAD.getDefaultStack(), Tab.PROFILE),
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
				case PROFILE -> "ур." + i(sub(data, "profile"), "lvl")
						+ " · " + trim(str(sub(data, "profile"), "rank"), 9);
				case SHOP -> i(data, "signal") >= 1 ? "витрина" : "нужен 2G";
				case MARKET -> i(data, "signal") >= 1 ? "лотов: " + i(sub(data, "market"), "total") : "нужен 2G";
				case STOCKS -> stocksUtc() ? "5 компаний" : "нужен 3G";
				case GPS -> i(data, "gpsOk") == 1 ? "онлайн" : "офлайн";
				case BANK -> lng(data, "balance") + " CR";
				case CASINO -> luckSub();
				default -> "";
			};
			UiKit.label(ctx, textRenderer, ax + 28, ay + 27, sub,
					(app.tab() == Tab.GPS && i(data, "gpsOk") != 1)
							|| (app.tab() == Tab.SHOP && i(data, "signal") < 1)
							|| (app.tab() == Tab.MARKET && i(data, "signal") < 1)
							|| (app.tab() == Tab.STOCKS && !stocksUtc())
							? UiKit.COL_RED : UiKit.COL_TEXT_DIM);
			clickable(ax, ay, 78, 50, () -> switchTab(app.tab()));
			idx++;
		}
		// карточка сети (компактная, в строку под сеткой 3×3)
		UiKit.card(ctx, x + 14, y + 168, PW - 28, 24, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 22, y + 174, "Сеть: " + signalLabel(),
				i(data, "signal") > 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
		String vill = str(data, "village");
		if (!vill.isEmpty()) {
			String tlvTxt = i(data, "tlv") > 0 ? " · вышка ур." + i(data, "tlv") : "";
			UiKit.label(ctx, textRenderer, x + 22, y + 184,
					trim(vill, 22) + " (" + i(data, "dist") + " м)" + tlvTxt,
					i(data, "tlv") > 0 ? UiKit.COL_YELLOW : UiKit.COL_TEXT_DIM);
		} else if (i(data, "offVillage") == 1) {
			UiKit.label(ctx, textRenderer, x + 22, y + 184, "Деревня офлайн: сломана вышка!", UiKit.COL_RED);
		} else {
			UiKit.label(ctx, textRenderer, x + 22, y + 184, "Вышки не обнаружены поблизости", UiKit.COL_TEXT_DIM);
		}
	}

	/** Доступна ли торговля на бирже прямо сейчас (3G+, или 2G под «умной вышкой» ур.4). */
	private boolean stocksUtc() {
		return i(data, "signal") >= 2 || (i(data, "tlv") >= 4 && i(data, "signal") >= 1);
	}

	// ------------------------------ Магазин ------------------------------

	private void renderShop(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 1) {
			lockOverlay(ctx, x, y, "Нужен хотя бы 2G для магазина");
			return;
		}
		NbtCompound shop = sub(data, "shop");
		var entries = rows(shop, "entries");
		int disc = i(data, "shopDisc"); // скидка «умной вышки» ур.4, %

		// счётчик количества (верхняя строка, справа от поиска): − ×N + и «М»
		UiKit.label(ctx, textRenderer, x + PW - 92, y + 7, "×" + buyCount, UiKit.COL_TEXT);
		UiKit.button(ctx, textRenderer, x + PW - 66, y + 3, 14, 14, "-", mx, my, buyCount > 1);
		clickable(x + PW - 66, y + 3, 14, 14, () -> buyCount = Math.max(1, buyCount / 2));
		UiKit.button(ctx, textRenderer, x + PW - 50, y + 3, 14, 14, "+", mx, my, buyCount < 64);
		clickable(x + PW - 50, y + 3, 14, 14, () -> buyCount = Math.min(64, buyCount * 2));
		// L10: «Макс» — самое большое количество, которое хватит баланса купить ЛЮБОЙ
		// из видимых товаров (чтобы все кнопки строк остались доступными)
		UiKit.button(ctx, textRenderer, x + PW - 34, y + 3, 14, 14, "М", mx, my, true);
		clickable(x + PW - 34, y + 3, 14, 14, () -> {
			long bal = lng(data, "balance");
			long best = 64;
			for (NbtCompound e : rows(shop, "entries")) {
				long unit = Math.round((long) i(e, "buy") * (100 - disc) / 100.0);
				if (unit <= 0) continue;
				best = Math.min(best, bal / unit);
			}
			buyCount = (int) Math.max(1, Math.min(64, best));
		});

		// строки каталога: 6 штук по 22px с двумя строками текста
		int listY = y + 24;
		int visible = Math.min(entries.size(), 6);
		for (int idx = 0; idx < visible; idx++) {
			NbtCompound e = entries.get(idx);
			int ry = listY + idx * 22;
			UiKit.card(ctx, x + 8, ry, PW - 16, 20, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry + 2);
			UiKit.label(ctx, textRenderer, x + 34, ry + 2, trim(str(e, "name"), 26), UiKit.COL_TEXT);
			// сервер считает итог так же: round(buy*count*(1-disc/100)) — сходится буква в букву
			long total = Math.round((long) i(e, "buy") * buyCount * (100 - disc) / 100.0);
			UiKit.label(ctx, textRenderer, x + 34, ry + 11,
					i(e, "buy") + " CR/шт" + (disc > 0 ? " (−" + disc + "%)" : "")
							+ " · продажа " + i(e, "sell"), UiKit.COL_TEXT_DIM);
			UiKit.button(ctx, textRenderer, x + PW - 68, ry + 3, 60, 14, total + " CR", mx, my,
					lng(data, "balance") >= total);
			final String fid = str(e, "id");
			clickable(x + PW - 68, ry + 3, 60, 14, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("id", fid);
				a.putInt("count", buyCount);
				send("buy", a);
			});
		}
		if (visible == 0) {
			UiKit.label(ctx, textRenderer, x + 14, y + 60, "Ничего не найдено — уточни запрос.", UiKit.COL_TEXT_DIM);
		}
		// пейджер (центрированный)
		int page = i(shop, "page");
		int pages = i(shop, "pages");
		int py2 = y + 160;
		UiKit.button(ctx, textRenderer, x + 8, py2, 18, 14, "<", mx, my, page > 0);
		clickable(x + 8, py2, 18, 14, () -> sendQuery(query, Math.max(0, page - 1)));
		UiKit.label(ctx, textRenderer, x + 34, py2 + 3, (page + 1) + " / " + pages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 92, py2, 18, 14, ">", mx, my, page + 1 < pages);
		clickable(x + 92, py2, 18, 14, () -> sendQuery(query, page + 1));
		UiKit.label(ctx, textRenderer, x + PW - 128, py2 + 3,
				disc > 0 ? "−" + disc + "% за умную вышку" : "доставка в ПВЗ",
				disc > 0 ? UiKit.COL_GREEN : UiKit.COL_TEXT_DIM);
	}

	// ------------------------------ Казино-апгрейд ------------------------------

	private static final int ANIM_DUR = 62; // тиков ≈ 3.1 сек

	private void renderCasino(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "signal") < 1) {
			lockOverlay(ctx, x, y, "Нужен хотя бы 2G для апгрейда");
			return;
		}
		if (casinoPickMode) {
			renderCasinoPicker(ctx, x, y, mx, my);
			return;
		}
		NbtCompound cz = sub(data, "casino");
		var staked = rows(cz, "staked");
		long stakeVal = lng(cz, "stakeVal");
		NbtCompound last = sub(cz, "last");
		long lastId = lng(last, "id");

		// детектор нового спина
		long now = mc() != null && mc().world != null ? mc().world.getTime() : 0;
		if (lastId > 0) {
			if (seenSpinId < 0) {
				seenSpinId = lastId; // первичная синхронизация — без анимации
			} else if (lastId > seenSpinId) {
				seenSpinId = lastId;
				animStartTick = now;
				animSeed = lastId;
				animWin = i(last, "win") == 1;
				animBp = i(last, "bp");
				animTargetId = str(last, "target");
				animTargetName = str(last, "tname");
				animStakeIconId = str(last, "sicon");
				animEndPlayed = false;
				animAngleFrom = normAngle(animFinalAngle);
				animFinalAngle = finalAngleFor(lastId, animWin, animBp);
				animLastMark = -1;
				// дальнейшее отрисовывает циферблат
			}
		}

		boolean animating = animStartTick >= 0 && now - animStartTick < ANIM_DUR;
		// текущий «живой» шанс от ставки; после спина (ставка сгорела) держим дугу прошлого спина
		long liveBp = stakeVal > 0 && casinoTargetPrice > 0
				? Math.min(9500, stakeVal * 10000 / casinoTargetPrice) : 0;
		int arcBp;
		if (animating || (seenSpinId == lastId && lastId > 0 && stakeVal <= 0)) {
			arcBp = animBp;
		} else {
			arcBp = (int) liveBp;
		}

		// ================= верх: ставка | циферблат | цель =================
		int topY = y;

		// ---- карточка ставки: «1. Ставка», вспышка при изменении ----
		if (stakeVal != lastStakeVal) {
			lastStakeVal = stakeVal;
			stakePulseStart = now;
		}
		int stakeBg = UiKit.COL_PANEL;
		long pulseAge = now - stakePulseStart;
		if (pulseAge >= 0 && pulseAge < 14) {
			int k = (int) (14 - pulseAge); // 13..1
			stakeBg = blend(stakeBg, 0xFF2D5A38, k / 13.0f);
		}
		UiKit.card(ctx, x + 8, topY, 108, 60, stakeBg);
		UiKit.label(ctx, textRenderer, x + 14, topY + 4, "1. Ставка", COL_STEP1);
		int ix = x + 14;
		for (NbtCompound e : staked) {
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), ix, topY + 14);
			UiKit.label(ctx, textRenderer, ix + 1, topY + 31, "×" + i(e, "count"), UiKit.COL_TEXT_DIM);
			ix += 22;
		}
		if (staked.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 14, topY + 20, "кликай предметы", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 14, topY + 31, "в сетке ниже ↓", UiKit.COL_TEXT_DIM);
		}
		UiKit.label(ctx, textRenderer, x + 14, topY + 45,
				"Ценность: " + stakeVal, stakeVal > 0 ? UiKit.COL_YELLOW : UiKit.COL_TEXT_DIM);
		if (!staked.isEmpty()) {
			UiKit.button(ctx, textRenderer, x + 96, topY + 3, 16, 11, "✕", mx, my, true);
			clickable(x + 96, topY + 3, 16, 11, () -> send("casino_clear", new NbtCompound()));
		}

		// ---- циферблат (дуга плавно догоняет шанс) ----
		if (!animating) {
			shownBp += (arcBp - shownBp) * 0.25f;
			if (Math.abs(shownBp - arcBp) < 2f) shownBp = arcBp;
		} else {
			shownBp = animBp;
		}
		int cx = x + PW / 2;
		int cyd = topY + 30;
		int radius = 27;
		drawDial(ctx, cx, cyd, radius, Math.round(shownBp), animating, now, lastId, last);

		// ---- карточка цели: «2. Цель» (+ пресеты быстрого выбора) ----
		UiKit.card(ctx, x + PW - 116, topY, 108, 60, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + PW - 110, topY + 4, "2. Цель", COL_STEP2);
		if (casinoTarget == null) {
			var presets = rows(cz, "presets");
			int pk = 0;
			for (NbtCompound pre : presets) {
				if (pk >= 3) break;
				int px = x + PW - 106 + pk * 35;
				Item pi = Registries.ITEM.get(Identifier.tryParse(str(pre, "id")));
				if (pi != null) ctx.drawItem(pi.getDefaultStack(), px, topY + 14);
				UiKit.label(ctx, textRenderer, px - 2, topY + 33, "" + i(pre, "buy"), UiKit.COL_YELLOW);
				UiKit.label(ctx, textRenderer, px - 2, topY + 43, trim(str(pre, "name"), 5), UiKit.COL_TEXT_DIM);
				final String fid = str(pre, "id");
				final int fp = i(pre, "buy");
				final String fn = str(pre, "name");
				clickable(px - 3, topY + 12, 34, 43, () -> {
					casinoTarget = fid;
					casinoTargetPrice = fp;
					casinoTargetName = fn;
					shownBp = 0;
				});
				pk++;
			}
			UiKit.label(ctx, textRenderer, x + PW - 110, topY + 50, "или выбери свою →", UiKit.COL_ACCENT);
		} else {
			Item t = Registries.ITEM.get(Identifier.tryParse(casinoTarget));
			if (t != null) ctx.drawItem(t.getDefaultStack(), x + PW - 108, topY + 14);
			UiKit.label(ctx, textRenderer, x + PW - 88, topY + 13, trim(casinoTargetName, 12), UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + PW - 88, topY + 23, "Ценность: " + casinoTargetPrice, UiKit.COL_YELLOW);
			UiKit.label(ctx, textRenderer, x + PW - 108, topY + 45, "сменить →", UiKit.COL_TEXT_DIM);
		}
		// регионы: вся карточка — только когда цель уже выбрана (иначе съест пресеты)
		if (casinoTarget != null) {
			clickable(x + PW - 116, topY, 108, 46, () -> {
				casinoPickMode = true;
				clearAndInit();
			});
		}
		clickable(x + PW - 116, topY + 46, 108, 14, () -> {
			casinoPickMode = true;
			clearAndInit();
		});

		// ================= кнопка спина + чипы =================
		long bp = liveBp;
		boolean canSpin = !staked.isEmpty() && casinoTarget != null && bp >= 100;
		int btnY = y + 66;
		String spinText = animating ? "КРУТИМ…"
				: staked.isEmpty() ? "← шаг 1: ставка"
				: casinoTarget == null ? "← шаг 2: цель"
				: bp < 100 ? "ставки < 1% шанса"
				: "3. КРУТИТЬ!";
		UiKit.button(ctx, textRenderer, x + 8, btnY, 130, 18, spinText, mx, my, canSpin && !animating);
		// пульс рамки — зовёт нажать, когда всё готово
		if (canSpin && !animating) {
			int alpha = 0x60 + (int) (0x50 * (0.5 + 0.5 * Math.sin(now / 5.0)));
			int col = (alpha << 24) | 0x3FB950;
			ctx.drawHorizontalLine(x + 6, x + 139, btnY - 2, col);
			ctx.drawHorizontalLine(x + 6, x + 139, btnY + 19, col);
			ctx.drawVerticalLine(x + 6, btnY - 2, btnY + 19, col);
			ctx.drawVerticalLine(x + 139, btnY - 2, btnY + 19, col);
			clickable(x + 8, btnY, 130, 18, () -> {
				NbtCompound a = new NbtCompound();
				a.putString("target", casinoTarget);
				send("casino_spin", a);
			});
		}
		// чипы автодобора: x2 x4 x8 — «цена цели / N», % — доля от цены цели
		String[] chipLab = {"x2", "x4", "x8", "30%", "50%", "70%"};
		double[] chipFac = {0.5, 0.25, 0.125, 0.30, 0.50, 0.70};
		for (int c = 0; c < chipLab.length; c++) {
			int bx = x + 144 + c * 25;
			double f = chipFac[c];
			UiKit.button(ctx, textRenderer, bx, btnY, 22, 18, chipLab[c], mx, my,
					casinoTarget != null && casinoTargetPrice > 0);
			if (casinoTarget != null && casinoTargetPrice > 0) {
				clickable(bx, btnY, 22, 18, () -> autoStake(f));
			}
		}

		// ================= инвентарь (источник ставок) =================
		UiKit.label(ctx, textRenderer, x + 8, y + 92,
				"инвентарь: клик → в ставку · Shift → ×8", UiKit.COL_TEXT_DIM);
		var src = rows(cz, "src");
		if (src.isEmpty()) {
			UiKit.label(ctx, textRenderer, x + 8, y + 106,
					"в инвентаре нет оцениваемых предметов", UiKit.COL_TEXT_DIM);
		}
		int cell = 44;
		int gridX = x + (PW - cell * 6) / 2;
		int gridY = y + 104;
		for (int k = 0; k < Math.min(12, src.size()); k++) {
			NbtCompound e = src.get(k);
			int gx = gridX + (k % 6) * cell;
			int gy = gridY + (k / 6) * 30;
			UiKit.card(ctx, gx, gy, cell - 4, 28, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), gx + 3, gy + 2);
			UiKit.label(ctx, textRenderer, gx + 21, gy + 3, "×" + i(e, "count"), UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, gx + 21, gy + 12, i(e, "price") + "cr", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, gx + 3, gy + 19, trim(str(e, "name"), 6), UiKit.COL_TEXT_DIM);
			final String fid = str(e, "id");
			clickable(gx, gy, cell - 4, 28,
					() -> stakeAction(fid, shiftHeld() ? 8 : 1));
		}

		// ================= результат / подсказка =================
		if (!casinoHint.isEmpty()) {
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(casinoHint),
					x + PW / 2, y + 174, UiKit.COL_TEXT_DIM);
		} else if (!animating && lastId > 0) {
			boolean win = i(last, "win") == 1;
			String res = win
					? "ВЫИГРЫШ: " + str(last, "tname")
					: "мимо — ставка " + lng(last, "sv") + " CR сгорела";
			ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(res), x + PW / 2, y + 174,
					win ? UiKit.COL_GREEN : UiKit.COL_RED);
		}
	}

	/** Автодобор ставки до доли f от цены цели (x2 = половина цены и т.п.). */
	private void autoStake(double factor) {
		NbtCompound cz = sub(data, "casino");
		long stakeVal = lng(cz, "stakeVal");
		if (casinoTargetPrice <= 0) return;
		long desired = (long) Math.ceil(casinoTargetPrice * factor);
		long extra = desired - stakeVal;
		if (extra <= 0) {
			casinoHint = "ставки уже хватает на этот порог";
			return;
		}
		casinoHint = "";
		// жадно добираем по списку источника; сервер всё равно перепроверит
		var staked = rows(cz, "staked");
		java.util.Map<String, Integer> poolCnt = new java.util.HashMap<>();
		for (NbtCompound e : staked) poolCnt.put(str(e, "id"), i(e, "count"));
		long addedVal = 0;
		net.minecraft.nbt.NbtList batch = new net.minecraft.nbt.NbtList();
		for (NbtCompound e : rows(cz, "src")) {
			if (addedVal >= extra) break;
			String id = str(e, "id");
			int price = i(e, "price");
			if (price <= 0) continue;
			boolean newKind = !poolCnt.containsKey(id);
			if (newKind && poolCnt.size() >= 4) continue;
			int cap = 64 - poolCnt.getOrDefault(id, 0);
			int have = i(e, "count");
			int take = (int) Math.min(Math.min(have, cap),
					Math.ceil((extra - addedVal) / (double) price));
			if (take <= 0) continue;
			NbtCompound it = new NbtCompound();
			it.putString("id", id);
			it.putInt("count", take);
			batch.add(it);
			poolCnt.put(id, poolCnt.getOrDefault(id, 0) + take);
			addedVal += (long) take * price;
		}
		// один пакет на весь добор — иначе анти-макрос лимит отбросит «хвост» чипа
		if (!batch.isEmpty()) {
			NbtCompound a = new NbtCompound();
			a.put("items", batch);
			send("casino_stake_multi", a);
		}
	}

	/** Режим выбора цели: поиск + каталог на весь экран. */
	private void renderCasinoPicker(DrawContext ctx, int x, int y, int mx, int my) {
		NbtCompound cz = sub(data, "casino");
		NbtCompound targets = sub(cz, "targets");
		var tents = rows(targets, "entries");
		UiKit.label(ctx, textRenderer, x + 8, y + 22, "Выбор цели апгрейда:", UiKit.COL_ACCENT);
		UiKit.button(ctx, textRenderer, x + PW - 48, y + 4, 40, 13, "назад", mx, my, true);
		clickable(x + PW - 48, y + 4, 40, 13, () -> {
			casinoPickMode = false;
			clearAndInit();
		});
		int ry = y + 22;
		int shown = 0;
		for (NbtCompound e : tents) {
			if (shown >= 6) break;
			shown++;
			ry += 20;
			boolean sel = str(e, "id").equals(casinoTarget);
			UiKit.card(ctx, x + 8, ry, PW - 16, 18, sel ? 0xFF2A3A20 : UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "id")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry + 1);
			UiKit.label(ctx, textRenderer, x + 34, ry + 5, trim(str(e, "name"), 20), UiKit.COL_TEXT);
			String pr = i(e, "buy") + " CR";
			ctx.drawText(textRenderer, Text.literal(pr), x + PW - 16 - textRenderer.getWidth(pr),
					ry + 5, UiKit.COL_YELLOW, false);
			final String tid = str(e, "id");
			final int tp = i(e, "buy");
			final String tn = str(e, "name");
			clickable(x + 8, ry, PW - 16, 18, () -> {
				casinoTarget = tid;
				casinoTargetPrice = tp;
				casinoTargetName = tn;
				casinoPickMode = false;
				clearAndInit();
			});
		}
		int tpage = i(targets, "page");
		int tpages = i(targets, "pages");
		int py3 = y + 168;
		UiKit.button(ctx, textRenderer, x + 8, py3, 18, 13, "<", mx, my, tpage > 0);
		clickable(x + 8, py3, 18, 13, () -> casinoQuery(targetQuery, Math.max(0, tpage - 1)));
		UiKit.label(ctx, textRenderer, x + 34, py3 + 3, (tpage + 1) + " / " + tpages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 96, py3, 18, 13, ">", mx, my, tpage + 1 < tpages);
		clickable(x + 96, py3, 18, 13, () -> casinoQuery(targetQuery, tpage + 1));
	}

	/** Зажат ли Shift (в 1.21.9+ Screen.hasShiftDown удалён — проверяем через InputUtil). */
	private boolean shiftHeld() {
		if (client == null || client.getWindow() == null) return false;
		return InputUtil.isKeyPressed(client.getWindow(), InputUtil.GLFW_KEY_LEFT_SHIFT)
				|| InputUtil.isKeyPressed(client.getWindow(), InputUtil.GLFW_KEY_RIGHT_SHIFT);
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

	// ------------------------------ циферблат ------------------------------

	/** Финальный угол стрелки: детерминирован от seed, попадает в зону решения. */
	private static double finalAngleFor(long seed, boolean win, int bp) {
		java.util.Random r = new java.util.Random(seed);
		double winArc = bp / 10000.0 * 360.0;
		double winArcSafe = Math.max(4.0, winArc - 16.0);
		double loseArcSafe = Math.max(6.0, 360.0 - winArc - 20.0);
		if (win) {
			return -90 + 8 + r.nextDouble() * winArcSafe;
		}
		return -90 + winArc + 10 + r.nextDouble() * loseArcSafe;
	}

	private static double normAngle(double a) {
		double n = a % 360.0;
		if (n < -180) n += 360;
		if (n > 180) n -= 360;
		return n;
	}

	/** Разница углов в диапазоне (0..360] — куда докручивать. */
	private static double deltaAngle(double from, double to) {
		double d = normAngle(to) - normAngle(from);
		while (d <= 0) d += 360;
		return d;
	}

	/** Циферблат шанса: дуга выигрыша, стрелка, % посередине; спин — плавная раскрутка. */
	private void drawDial(DrawContext ctx, int cx, int cy, int radius, int bp,
			boolean animating, long now, long lastId, NbtCompound last) {
		fillDisc(ctx, cx, cy, radius + 2, 0xFF0E1014);
		fillDisc(ctx, cx, cy, radius, UiKit.COL_TRACK);
		// деления
		for (int m = 0; m < 12; m++) {
			double ang = Math.toRadians(m * 30.0 - 90);
			int tx1 = cx + (int) Math.round(Math.cos(ang) * (radius - 1));
			int ty1 = cy + (int) Math.round(Math.sin(ang) * (radius - 1));
			int tx2 = cx + (int) Math.round(Math.cos(ang) * (radius - 4));
			int ty2 = cy + (int) Math.round(Math.sin(ang) * (radius - 4));
			plotLine(ctx, tx1, ty1, tx2, ty2, UiKit.COL_LINE);
		}
		// дуга шанса (выигрышная зона) — от верха по часовой
		double winArc = bp / 10000.0 * 360.0;
		if (winArc > 0) {
			ringArc(ctx, cx, cy, radius - 1, 5, -90, -90 + winArc, 0xFFB07219);
		}
		// % по центру
		String pct = bp <= 0 ? "--.--%"
				: String.format(java.util.Locale.ROOT, "%.2f%%", bp / 100.0);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(pct), cx, cy - 4,
				bp <= 0 ? UiKit.COL_TEXT_DIM : UiKit.COL_YELLOW);
		UiKit.label(ctx, textRenderer, cx - textRenderer.getWidth("шанс") / 2, cy + 5,
				"шанс", UiKit.COL_TEXT_DIM);

		// стрелка
		double angle;
		if (animating) {
			double t = Math.min(1.0, (now - animStartTick) / (double) ANIM_DUR);
			double ease = 1.0 - Math.pow(1.0 - t, 5);
			double total = 6 * 360.0 + deltaAngle(animAngleFrom, animFinalAngle);
			angle = animAngleFrom + total * ease;
			// тиканье каждые ~12° раскрутки
			int mark = (int) Math.floor(angle / 12.0);
			if (mark != animLastMark && t < 1.0) {
				animLastMark = mark;
				playUiSoft(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f);
			}
		} else {
			angle = animFinalAngle != 0 ? normAngle(animFinalAngle)
					: (lastId > 0 ? finalAngleFor(lastId, i(last, "win") == 1, i(last, "bp")) : -95);
		}
		double rad = Math.toRadians(angle);
		int nx = cx + (int) Math.round(Math.cos(rad) * (radius - 6));
		int ny = cy + (int) Math.round(Math.sin(rad) * (radius - 6));
		plotLine(ctx, cx, cy, nx, ny, 0xFFFFD75E);
		int hx = cx + (int) Math.round(Math.cos(rad) * (radius - 4));
		int hy = cy + (int) Math.round(Math.sin(rad) * (radius - 4));
		ctx.fill(hx - 1, hy - 1, hx + 1, hy + 1, 0xFFFFD75E);

		// финал: вспышка + звук один раз
		if (animStartTick >= 0 && !animating && !animEndPlayed) {
			animEndPlayed = true;
			playUiSoft(animWin ? net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP
					: net.minecraft.sound.SoundEvents.ENTITY_ITEM_BREAK.value(), 1.0f);
		}
		if (animStartTick >= 0 && !animating && now - animStartTick < ANIM_DUR + 40) {
			long ph = (now - animStartTick - ANIM_DUR) / 5;
			int col = animWin ? 0xFF3FB950 : 0xFFF85149;
			if (ph % 2 == 0) {
				ringArc(ctx, cx, cy, radius + 1, 2, 0, 360, col);
			}
		}
	}

	private static void fillDisc(DrawContext ctx, int cx, int cy, int r, int col) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.sqrt(r * r - dy * dy);
			ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, col);
		}
	}

	private static void ringArc(DrawContext ctx, int cx, int cy, int rOuter, int thick,
			double fromDeg, double toDeg, int col) {
		for (double a = fromDeg; a < toDeg; a += 2.2) {
			double rad = Math.toRadians(a);
			double cs = Math.cos(rad), sn = Math.sin(rad);
			for (int t = 0; t < thick; t++) {
				int rr = rOuter - t;
				int px2 = cx + (int) Math.round(cs * rr);
				int py2 = cy + (int) Math.round(sn * rr);
				ctx.fill(px2, py2, px2 + 1, py2 + 1, col);
			}
		}
	}

	/** Тонкая линия Брезенхема (для стрелки и делений циферблата). */
	private static void plotLine(DrawContext ctx, int x0, int y0, int x1, int y1, int col) {
		int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
		int err = dx - dy;
		int x = x0, y = y0;
		for (int guard = 0; guard < 64; guard++) {
			ctx.fill(x, y, x + 1, y + 1, col);
			if (x == x1 && y == y1) break;
			int e2 = 2 * err;
			if (e2 > -dy) { err -= dy; x += sx; }
			if (e2 < dx) { err += dx; y += sy; }
		}
	}

	private static void playUiSoft(net.minecraft.sound.SoundEvent ev, float volume) {
		net.minecraft.client.MinecraftClient mc2 = net.minecraft.client.MinecraftClient.getInstance();
		if (mc2 != null && mc2.getSoundManager() != null) {
			mc2.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(ev, volume));
		}
	}

	/** Линейный бленд двух ARGB-цветов (k=0 → a, k=1 → b). */
	private static int blend(int a, int b, float k) {
		int ch = (int) (k * 255);
		int ar = (a >>> 24) & 0xFF, br = (b >>> 24) & 0xFF;
		int al = Math.min(255, Math.max(ar, br));
		int r = ((a >>> 16) & 0xFF) + (ch * (((b >>> 16) & 0xFF) - ((a >>> 16) & 0xFF))) / 255;
		int g = ((a >>> 8) & 0xFF) + (ch * (((b >>> 8) & 0xFF) - ((a >>> 8) & 0xFF))) / 255;
		int bl = (a & 0xFF) + (ch * ((b & 0xFF) - (a & 0xFF))) / 255;
		return (al << 24) | (r << 16) | (g << 8) | bl;
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
			UiKit.label(ctx, textRenderer, x + 10, y + 48, "Пока пусто. Продавай своё из ПВЗ —", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, x + 10, y + 60, "кнопка ₽ рядом с предметом.", UiKit.COL_TEXT_DIM);
		}
		int listY = y + 22;
		int visible = Math.min(entries.size(), 6);
		for (int idx = 0; idx < visible; idx++) {
			NbtCompound e = entries.get(idx);
			int ry = listY + idx * 22;
			UiKit.card(ctx, x + 8, ry, PW - 16, 20, UiKit.COL_PANEL);
			Item item = Registries.ITEM.get(Identifier.tryParse(str(e, "itemId")));
			if (item != null) ctx.drawItem(item.getDefaultStack(), x + 12, ry + 2);
			UiKit.label(ctx, textRenderer, x + 34, ry + 2, trim(str(e, "name"), 20) + " ×" + i(e, "count"),
					UiKit.COL_TEXT);
			UiKit.label(ctx, textRenderer, x + 34, ry + 11,
					"от " + trim(str(e, "seller"), 13) + " · " + i(e, "price") + " CR/шт", UiKit.COL_TEXT_DIM);
			long total = (long) i(e, "price") * i(e, "count");
			UiKit.button(ctx, textRenderer, x + PW - 68, ry + 3, 60, 14, total + " CR", mx, my,
					lng(data, "balance") >= total);
			final long lid = lng(e, "lid");
			clickable(x + PW - 68, ry + 3, 60, 14, () -> {
				NbtCompound a = new NbtCompound();
				a.putLong("lid", lid);
				send("market_buy", a);
			});
		}
		int page = i(market, "page");
		int pages = i(market, "pages");
		int py2 = y + 160;
		UiKit.button(ctx, textRenderer, x + 8, py2, 18, 14, "<", mx, my, page > 0);
		clickable(x + 8, py2, 18, 14, () -> marketQuery(Math.max(0, page - 1)));
		UiKit.label(ctx, textRenderer, x + 34, py2 + 3, (page + 1) + " / " + pages, UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, x + 92, py2, 18, 14, ">", mx, my, page + 1 < pages);
		clickable(x + 92, py2, 18, 14, () -> marketQuery(page + 1));
		UiKit.label(ctx, textRenderer, x + PW - 140, py2 + 3, "листится из ПВЗ (₽)", UiKit.COL_TEXT_DIM);
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
		if (!stocksUtc()) {
			lockOverlay(ctx, x, y, "Нужен 3G (или 2G под умной вышкой ур.4)");
			return;
		}
		var stocks = rows(data, "stocks");
		// портфельная сводка
		long portVal = 0;
		int portCnt = 0;
		for (NbtCompound s0 : stocks) {
			portCnt += i(s0, "owned");
			portVal += Math.round(i(s0, "owned") * dbl(s0, "price"));
		}
		String port = portCnt > 0 ? "Портфель: " + portVal + " CR (" + portCnt + " шт.)" : "портфель пуст";
		UiKit.label(ctx, textRenderer, x + 8, y + 3, port, portCnt > 0 ? UiKit.COL_YELLOW : UiKit.COL_TEXT_DIM);
		String hint = "пульс цен ~5 с";
		ctx.drawText(textRenderer, Text.literal(hint), x + PW - 8 - textRenderer.getWidth(hint), y + 3,
				UiKit.COL_TEXT_DIM, false);
		// новостная лента (последнее)
		var news = rows(data, "stNews");
		if (!news.isEmpty()) {
			NbtCompound n0 = news.get(0);
			int dir = i(n0, "dir");
			String pc = (dir >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.0f", dbl(n0, "pct")) + "%";
			UiKit.label(ctx, textRenderer, x + 8, y + 13,
					trim(str(n0, "txt") + " (" + pc + ")", 46),
					dir >= 0 ? UiKit.COL_GREEN : UiKit.COL_RED);
		}
		int ry = y + 25;
		for (NbtCompound s : stocks) {
			UiKit.card(ctx, x + 8, ry, PW - 16, 29, UiKit.COL_PANEL);
			String id = str(s, "id");
			UiKit.label(ctx, textRenderer, x + 14, ry + 3, id + " · " + trim(str(s, "name"), 12), UiKit.COL_TEXT);
			double price = dbl(s, "price");
			double delta = dbl(s, "delta");
			String ds = (delta >= 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.2f", delta) + "%";
			ctx.drawText(textRenderer, Text.literal(ds), x + 212 - textRenderer.getWidth(ds), ry + 3,
					delta >= 0 ? UiKit.COL_GREEN : UiKit.COL_RED, false);
			UiKit.label(ctx, textRenderer, x + 14, ry + 12,
					String.format(java.util.Locale.ROOT, "%.2f", price) + " CR", UiKit.COL_YELLOW);
			UiKit.label(ctx, textRenderer, x + 14, ry + 20,
					"у вас: " + i(s, "owned") + " · див " + String.format(java.util.Locale.ROOT, "%.1f", dbl(s, "div")) + "%/д"
							+ " · " + i(s, "lo") + "…" + i(s, "hi"), UiKit.COL_TEXT_DIM);
			drawSparkline(ctx, x + 152, ry + 10, 52, 14, s.getIntArray("hist").orElse(new int[0]));
			// кнопки +1/-1/+10/-10
			UiKit.button(ctx, textRenderer, x + PW - 72, ry + 3, 28, 11, "+1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 72, ry + 15, 28, 11, "-1", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 40, ry + 3, 32, 11, "+10", mx, my, true);
			UiKit.button(ctx, textRenderer, x + PW - 40, ry + 15, 32, 11, "-10", mx, my, true);
			clickable(x + PW - 72, ry + 3, 28, 11, () -> stock(id, true, 1));
			clickable(x + PW - 72, ry + 15, 28, 11, () -> stock(id, false, 1));
			clickable(x + PW - 40, ry + 3, 32, 11, () -> stock(id, true, 10));
			clickable(x + PW - 40, ry + 15, 32, 11, () -> stock(id, false, 10));
			ry += 31;
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

	private boolean hasWaypoint;
	private int wpX, wpZ;

	private void renderGps(DrawContext ctx, int x, int y, int mx, int my) {
		if (i(data, "gpsOk") != 1) {
			lockOverlay(ctx, x, y, "Нет сигнала GPS (глубоко под землёй)",
					"спутники ловятся на поверхности — поднимись выше");
			return;
		}
		if (mc() == null || mc().player == null || mc().world == null) return;
		if (map == null) map = GpsMapRenderer.get(); // синглтон (M6)
		map.resampleIfNeeded(mc().player, mc().world);
		lastMouseX = mx;
		lastMouseY = my;

		int pX = mc().player.getBlockPos().getX();
		int pZ = mc().player.getBlockPos().getZ();
		int zoom = map.zoom();

		int mxp = x + 8;
		int myp = y + 2;
		map.draw(ctx, mxp, myp);
		int cxm = mxp + GpsMapRenderer.SIZE / 2;
		int cym = myp + GpsMapRenderer.SIZE / 2;
		final int fmxp = mxp, fmyp = myp;

		// кольца покрытия 4G вышек (4G-зона растёт с прокачкой вышки)
		for (NbtCompound v : rows(data, "villages")) {
			int rr = i(v, "r4") / zoom;
			if (rr < 2 || rr > 300) continue;
			int ddx = (i(v, "x") - pX) / zoom;
			int ddz = (i(v, "z") - pZ) / zoom;
			if (Math.abs(ddx) > GpsMapRenderer.SIZE / 2 + rr
					|| Math.abs(ddz) > GpsMapRenderer.SIZE / 2 + rr) continue;
			drawRing(ctx, cxm + ddx, cym + ddz, rr, mxp, myp, ringColor(i(v, "off"), i(v, "tlv")));
		}

		// клик по карте — точка маршрута (метка хранится, пока открыт экран)
		clickable(mxp, myp, GpsMapRenderer.SIZE, GpsMapRenderer.SIZE, () -> {
			hasWaypoint = true;
			wpX = pX + (int) Math.round(((lastMouseX - fmxp) - GpsMapRenderer.SIZE / 2.0) * zoom);
			wpZ = pZ + (int) Math.round(((lastMouseY - fmyp) - GpsMapRenderer.SIZE / 2.0) * zoom);
		});

		// метки деревень
		for (NbtCompound v : rows(data, "villages")) {
			int dx = (i(v, "x") - pX) / zoom;
			int dz = (i(v, "z") - pZ) / zoom;
			int vx = cxm + Math.max(-78, Math.min(78, dx));
			int vz = cym + Math.max(-78, Math.min(78, dz));
			int col = i(v, "off") == 1 ? 0xFFF85149 : 0xFF3FB950;
			ctx.fill(vx - 2, vz - 2, vx + 2, vz + 2, col);
		}
		// метка маршрута (ромб)
		if (hasWaypoint) {
			int dx = (wpX - pX) / zoom;
			int dz = (wpZ - pZ) / zoom;
			int wx = Math.max(mxp + 2, Math.min(mxp + GpsMapRenderer.SIZE - 2, cxm + dx));
			int wz = Math.max(myp + 2, Math.min(myp + GpsMapRenderer.SIZE - 2, cym + dz));
			diamond(ctx, wx, wz, 0xFF58A6FF);
		}
		// цель активной работы (оранжевый ромб + буква Р)
		NbtCompound nav = sub(data, "jobNav");
		if (!nav.isEmpty()) {
			int dx = (i(nav, "x") - pX) / zoom;
			int dz = (i(nav, "z") - pZ) / zoom;
			int tx = Math.max(mxp + 2, Math.min(mxp + GpsMapRenderer.SIZE - 2, cxm + dx));
			int tz = Math.max(myp + 2, Math.min(myp + GpsMapRenderer.SIZE - 2, cym + dz));
			diamond(ctx, tx, tz, 0xFFFFA63D);
			UiKit.label(ctx, textRenderer, tx + 4, tz - 3, "Р", 0xFFFFA63D);
		}
		// игрок — стрелка по направлению взгляда
		double rad = Math.toRadians(mc().player.getYaw());
		int vx = (int) Math.round(Math.sin(rad));       // смотрим «вверх» на карте при yaw, повёрнутом на север
		int vy = (int) Math.round(-Math.cos(rad));
		ctx.fill(cxm - 2, cym - 2, cxm + 2, cym + 2, 0xFFFFFFFF);
		ctx.fill(cxm + vx * 2, cym + vy * 2, cxm + vx * 2 + 2, cym + vy * 2 + 2, 0xFF58A6FF);
		ctx.fill(cxm + vx * 4, cym + vy * 4, cxm + vx * 4 + 1, cym + vy * 4 + 1, 0xFF58A6FF);

		// ================= правая колонка =================
		int rx = x + 176;
		int rw = PW - 176 - 8;
		UiKit.card(ctx, rx, y + 2, rw, 30, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, rx + 6, y + 7, "Масштаб", UiKit.COL_TEXT_DIM);
		UiKit.button(ctx, textRenderer, rx + 6, y + 17, 16, 12, "-", mx, my, zoom > 1);
		clickable(rx + 6, y + 17, 16, 12, () -> map.cycleZoom(-1));
		UiKit.label(ctx, textRenderer, rx + 26, y + 19, zoom + ":1", UiKit.COL_YELLOW);
		UiKit.button(ctx, textRenderer, rx + rw - 22, y + 17, 16, 12, "+", mx, my, zoom < 4);
		clickable(rx + rw - 22, y + 17, 16, 12, () -> map.cycleZoom(1));

		UiKit.card(ctx, rx, y + 36, rw, 30, UiKit.COL_PANEL);
		if (hasWaypoint) {
			UiKit.label(ctx, textRenderer, rx + 6, y + 41, "Метка маршрута", UiKit.COL_ACCENT);
			int d = (int) Math.round(Math.sqrt((wpX - pX) * (long)(wpX - pX) + (wpZ - pZ) * (long)(wpZ - pZ)));
			UiKit.label(ctx, textRenderer, rx + 6, y + 52, wpX + ", " + wpZ + " · " + d + " м", UiKit.COL_TEXT);
			UiKit.button(ctx, textRenderer, rx + rw - 18, y + 39, 12, 11, "✕", mx, my, true);
			clickable(rx + rw - 18, y + 39, 12, 11, () -> hasWaypoint = false);
		} else {
			UiKit.label(ctx, textRenderer, rx + 6, y + 41, "Метки нет", UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, rx + 6, y + 52, "клик по карте — метка", UiKit.COL_TEXT_DIM);
		}

		UiKit.card(ctx, rx, y + 70, rw, 92, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, rx + 6, y + 75, "Деревни рядом", UiKit.COL_ACCENT);
		int vy2 = y + 87;
		int rowsShown = 0;
		for (NbtCompound v : rows(data, "villages")) {
			if (rowsShown++ >= 5) break;
			int col = i(v, "off") == 1 ? 0xFFF85149 : 0xFF3FB950;
			ctx.fill(rx + 7, vy2 + 3, rx + 10, vy2 + 6, col);
			String vname = trim(str(v, "name"), 12) + (i(v, "tlv") > 0 ? " у" + i(v, "tlv") : "");
			UiKit.label(ctx, textRenderer, rx + 14, vy2,
					vname, i(v, "off") == 1 ? UiKit.COL_TEXT_DIM : UiKit.COL_TEXT);
			String dl = i(v, "d") + "м";
			ctx.drawText(textRenderer, Text.literal(dl), rx + rw - 6 - textRenderer.getWidth(dl), vy2,
					UiKit.COL_TEXT_DIM, false);
			vy2 += 14;
		}
		if (rowsShown == 0) {
			UiKit.label(ctx, textRenderer, rx + 6, vy2, "нет данных", UiKit.COL_TEXT_DIM);
		}

		// футер: координаты
		int pY = mc().player.getBlockPos().getY();
		UiKit.label(ctx, textRenderer, x + 8, y + 168,
				"XYZ: " + pX + " " + pY + " " + pZ, UiKit.COL_TEXT);
		UiKit.label(ctx, textRenderer, x + 8, y + 179,
				"1 пикс = " + zoom + (zoom == 1 ? " блок" : " блока") + " · клик — метка маршрута",
				UiKit.COL_TEXT_DIM);
	}

	private static void diamond(DrawContext ctx, int cx, int cy, int col) {
		ctx.fill(cx - 1, cy - 3, cx + 1, cy - 2, col);
		ctx.fill(cx - 2, cy - 2, cx + 2, cy + 2, col);
		ctx.fill(cx - 1, cy + 2, cx + 1, cy + 3, col);
	}

	/** Цвет кольца зоны 4G: офлайн — красное, зелёное → золотое по уровню вышки. */
	private static int ringColor(int off, int tlv) {
		if (off == 1) return 0x44F85149;
		return switch (Math.min(4, tlv)) {
			case 0, 1 -> 0x443FB950;
			case 2, 3 -> 0x4458A6FF;
			default -> 0x44FFD75E;
		};
	}

	/** Окружность с клиппингом по рамке карты (чтобы не замусоривать колонки). */
	private static void drawRing(DrawContext ctx, int cx, int cy, int r, int minX, int minY, int col) {
		double step = Math.max(1.2, 160.0 / r); // чем больше круг, тем чаще точки
		for (double a = 0; a < 360; a += step) {
			double rad = Math.toRadians(a);
			int px3 = cx + (int) Math.round(Math.cos(rad) * r);
			int py3 = cy + (int) Math.round(Math.sin(rad) * r);
			if (px3 < minX || px3 > minX + GpsMapRenderer.SIZE - 1) continue;
			if (py3 < minY || py3 > minY + GpsMapRenderer.SIZE - 1) continue;
			ctx.fill(px3, py3, px3 + 1, py3 + 1, col);
		}
	}

	// ------------------------------ Банк ------------------------------

	private void renderBank(DrawContext ctx, int x, int y, int mx, int my) {
		// баланс
		UiKit.card(ctx, x + 8, y + 2, PW - 16, 24, UiKit.COL_PANEL_HI);
		UiKit.label(ctx, textRenderer, x + 16, y + 8, "Баланс", UiKit.COL_TEXT_DIM);
		String bal = UiKit.fmt(lng(data, "balance")) + " CR";
		ctx.drawText(textRenderer, Text.literal(bal), x + PW - 16 - textRenderer.getWidth(bal), y + 8,
				UiKit.COL_YELLOW, false);
		UiKit.label(ctx, textRenderer, x + 70, y + 8, "процент 0.15%/день от 50 CR", UiKit.COL_TEXT_DIM);

		// перевод игроку (поля ввода живут внутри карточки)
		UiKit.card(ctx, x + 8, y + 30, PW - 16, 80, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + 16, y + 36, "Перевод игроку онлайн (нужен 2G):", UiKit.COL_ACCENT);
		UiKit.button(ctx, textRenderer, x + PW - 104, y + 50, 96, 16, "Отправить", mx, my,
				canTransfer());
		clickable(x + PW - 104, y + 50, 96, 16, this::sendTransfer);

		// UX-2: подсказки получателя — онлайн-игроки, фильтр по префиксу, клик подставляет имя
		UiKit.label(ctx, textRenderer, x + 16, y + 68, "кому:", UiKit.COL_TEXT_DIM);
		String on = str(data, "online");
		if (!on.isEmpty() && payeeInput != null) {
			String typed = payeeInput.value.trim().toLowerCase(java.util.Locale.ROOT);
			int sx = x + 44;
			int shown = 0;
			for (String nm : on.split("\n")) {
				if (nm.isBlank()) continue;
				if (!typed.isEmpty() && !nm.toLowerCase(java.util.Locale.ROOT).startsWith(typed)) continue;
				int cw = textRenderer.getWidth(nm) + 10;
				if (sx + cw > x + PW - 12 || shown >= 4) break;
				UiKit.button(ctx, textRenderer, sx, y + 64, cw, 13, nm, mx, my, true);
				final String fname = nm;
				clickable(sx, y + 64, cw, 13, () -> payeeInput.value = fname);
				sx += cw + 4;
				shown++;
			}
			if (shown == 0 && !typed.isEmpty()) {
				UiKit.label(ctx, textRenderer, x + 44, y + 68, "онлайн никого нет с таким именем",
						UiKit.COL_TEXT_DIM);
			}
		} else {
			UiKit.label(ctx, textRenderer, x + 44, y + 68, "кроме вас сейчас никого", UiKit.COL_TEXT_DIM);
		}

		// история
		UiKit.card(ctx, x + 8, y + 114, PW - 16, 74, UiKit.COL_PANEL);
		UiKit.label(ctx, textRenderer, x + 16, y + 121, "История операций", UiKit.COL_ACCENT);
		String[] lines = str(data, "tx").isEmpty() ? new String[0] : str(data, "tx").split("\n");
		if (lines.length == 0) {
			UiKit.label(ctx, textRenderer, x + 16, y + 136, "пока пусто", UiKit.COL_TEXT_DIM);
		}
		int ly = y + 133;
		int rowIdx = 0;
		for (int k = Math.max(0, lines.length - 5); k < lines.length; k++) {
			String l = lines[k];
			if (rowIdx % 2 == 0) {
				ctx.fill(x + 12, ly - 1, x + PW - 12, ly + 9, 0x14FFFFFF);
			}
			int col = l.startsWith("+") ? UiKit.COL_GREEN : UiKit.COL_TEXT;
			UiKit.label(ctx, textRenderer, x + 16, ly, trim(l, 48), col);
			ly += 11;
			rowIdx++;
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

	// ------------------------------ Профиль ------------------------------

	/** Статичная клиентская таблица достижений (прогресс и флаги — с сервера). */
	private record AchRow(String id, String name, Item icon) {}

	private static final AchRow[] ACH_DEFS = {
			new AchRow("first_job", "Первая смена", Items.IRON_PICKAXE),
			new AchRow("chief", "Мастер цеха", Items.REDSTONE),
			new AchRow("logistics", "Логист", Items.CHEST_MINECART),
			new AchRow("workaholic", "Трудяга", Items.NETHERITE_PICKAXE),
			new AchRow("shopper", "Шопоголик", Items.EMERALD),
			new AchRow("merchant", "Торговец", Items.GOLD_INGOT),
			new AchRow("gambler", "Лудоман", Items.TARGET),
			new AchRow("lucky", "Счастливчик", Items.DIAMOND),
			new AchRow("investor", "Инвестор", Items.PAPER),
			new AchRow("giver", "Меценат", Items.WRITABLE_BOOK),
			new AchRow("engineer", "Инженер связи", Items.REDSTONE_TORCH),
			new AchRow("millionaire", "Миллионер", Items.NETHERITE_INGOT),
	};

	private boolean profileAch;

	private void renderProfile(DrawContext ctx, int x, int y, int mx, int my) {
		NbtCompound p = sub(data, "profile");
		NbtCompound c = sub(p, "c");

		// ---- шапка: имя, уровень, звание, полоса опыта ----
		UiKit.card(ctx, x + 8, y + 2, PW - 16, 32, UiKit.COL_PANEL_HI);
		String pname = mc() != null && mc().player != null ? mc().player.getName().getString() : "игрок";
		UiKit.label(ctx, textRenderer, x + 16, y + 8, trim(pname, 18), UiKit.COL_TEXT);
		String lv = "Ур." + i(p, "lvl") + " «" + str(p, "rank") + "»";
		ctx.drawText(textRenderer, Text.literal(lv), x + PW - 16 - textRenderer.getWidth(lv), y + 8,
				UiKit.COL_YELLOW, false);
		long xp = lng(p, "xp");
		long base = lng(p, "xpBase");
		long next = lng(p, "xpNext");
		int frac = next <= base ? 100 : (int) Math.max(0, Math.min(100, (xp - base) * 100 / Math.max(1, next - base)));
		UiKit.progress(ctx, x + 16, y + 24, PW - 32, 4, frac, UiKit.COL_ACCENT);
		String xpTxt = frac >= 100 ? "МАКС" : xp + " / " + next + " XP";
		ctx.drawText(textRenderer, Text.literal(xpTxt), x + PW - 16 - textRenderer.getWidth(xpTxt), y + 15,
				UiKit.COL_TEXT_DIM, false);

		// ---- переключатель подвкладок ----
		UiKit.button(ctx, textRenderer, x + 8, y + 40, 90, 14, "Сводка", mx, my, true);
		UiKit.button(ctx, textRenderer, x + 102, y + 40, 90, 14, "Достижения", mx, my, true);
		ctx.fill(profileAch ? x + 104 : x + 10, y + 55, profileAch ? x + 190 : x + 96, y + 57, UiKit.COL_ACCENT);
		clickable(x + 8, y + 40, 90, 14, () -> profileAch = false);
		clickable(x + 102, y + 40, 90, 14, () -> profileAch = true);

		if (profileAch) {
			renderProfileAch(ctx, x, y, p);
			return;
		}

		long earnJobs = lng(c, "earnJobs");
		long earnSales = lng(c, "earnSales");
		long earnStocks = lng(c, "stocksEarn");
		long earnCasino = lng(c, "casinoWon");
		long earnedTotal = earnJobs + earnSales + earnStocks + earnCasino;

		// ---- левая колонка: работа и связь ----
		int ly2 = y + 68;
		UiKit.label(ctx, textRenderer, x + 10, ly2, "РАБОТА", UiKit.COL_ACCENT);
		ly2 += 12;
		UiKit.label(ctx, textRenderer, x + 10, ly2, "Смен выполнено: " + lng(c, "jobsDone"), UiKit.COL_TEXT);
		ly2 += 11;
		UiKit.label(ctx, textRenderer, x + 10, ly2,
				"завод " + lng(c, "jobsFactory") + " · грузч " + lng(c, "jobsLoader"), UiKit.COL_TEXT_DIM);
		ly2 += 11;
		UiKit.label(ctx, textRenderer, x + 10, ly2,
				"повар " + lng(c, "jobsCook") + " · курьер " + lng(c, "jobsCourier"), UiKit.COL_TEXT_DIM);
		ly2 += 11;
		UiKit.label(ctx, textRenderer, x + 10, ly2, "Зарплата: +" + earnJobs + " CR", UiKit.COL_GREEN);
		ly2 += 11;
		UiKit.label(ctx, textRenderer, x + 10, ly2,
				"Срывы: " + lng(c, "jobsCanceled") + " (−" + lng(c, "finesPaid") + ")", UiKit.COL_TEXT_DIM);
		ly2 += 14;
		UiKit.label(ctx, textRenderer, x + 10, ly2, "СВЯЗЬ", UiKit.COL_ACCENT);
		ly2 += 12;
		UiKit.label(ctx, textRenderer, x + 10, ly2,
				"Вышек прокачано: " + lng(c, "towerUpgrades"), UiKit.COL_TEXT);
		ly2 += 11;
		UiKit.label(ctx, textRenderer, x + 10, ly2,
				"Вложено: " + lng(c, "towerInvested") + " CR", UiKit.COL_TEXT_DIM);

		// ---- правая колонка: экономика ----
		int rx2 = x + 156;
		int ry2 = y + 68;
		UiKit.label(ctx, textRenderer, rx2, ry2, "ЭКОНОМИКА", UiKit.COL_ACCENT);
		ry2 += 12;
		UiKit.label(ctx, textRenderer, rx2, ry2, "Заработано: " + earnedTotal + " CR", UiKit.COL_YELLOW);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"заказы " + lng(c, "ordersBought") + " · посылки " + lng(c, "parcelsClaimed"),
				UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"продажи +" + earnSales + " (" + lng(c, "itemsSold") + " шт)", UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"биржа +" + earnStocks + " · див " + lng(c, "dividendsGot"), UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"казино: " + lng(c, "spins") + " спинов · " + lng(c, "spinWins") + " побед",
				UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"ставки " + lng(c, "casinoWagered") + " · призы +" + earnCasino, UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"барахолка: " + lng(c, "marketListed") + " лот · " + lng(c, "marketBought") + " купл.",
				UiKit.COL_TEXT_DIM);
		ry2 += 11;
		UiKit.label(ctx, textRenderer, rx2, ry2,
				"переводы → " + lng(c, "transfersSent") + " CR", UiKit.COL_TEXT_DIM);

		// ---- подвал: счётчик достижений ----
		int un = 0;
		for (NbtCompound r : rows(p, "ach")) un += i(r, "un");
		UiKit.label(ctx, textRenderer, x + 10, y + 185,
				"Достижений открыто: " + un + " / " + ACH_DEFS.length, UiKit.COL_TEXT_DIM);
	}

	/** Сетка 3×4 плиток достижений с прогрессом. */
	private void renderProfileAch(DrawContext ctx, int x, int y, NbtCompound p) {
		java.util.Map<String, NbtCompound> byId = new java.util.HashMap<>();
		for (NbtCompound r : rows(p, "ach")) byId.put(str(r, "id"), r);
		for (int k = 0; k < ACH_DEFS.length; k++) {
			AchRow def = ACH_DEFS[k];
			NbtCompound r = byId.get(def.id());
			long cur = r == null ? 0 : lng(r, "cur");
			long need = r == null ? 1 : Math.max(1, lng(r, "need"));
			boolean un = r != null && i(r, "un") == 1;
			int tx = x + 8 + (k % 3) * 96;
			int ty = y + 64 + (k / 3) * 32;
			UiKit.card(ctx, tx, ty, 94, 30, un ? 0xFF1E3020 : UiKit.COL_PANEL);
			ctx.drawItem(def.icon().getDefaultStack(), tx + 4, ty + 4);
			UiKit.label(ctx, textRenderer, tx + 24, ty + 3, trim(def.name(), 11),
					un ? UiKit.COL_TEXT : UiKit.COL_TEXT_DIM);
			UiKit.label(ctx, textRenderer, tx + 24, ty + 13,
					un ? "✓ получено" : cur + " / " + need,
					un ? UiKit.COL_GREEN : UiKit.COL_TEXT_DIM);
			UiKit.progress(ctx, tx + 4, ty + 24, 86, 3,
					un ? 100 : (int) Math.min(100, cur * 100 / need),
					un ? UiKit.COL_GREEN : UiKit.COL_ACCENT);
		}
		UiKit.label(ctx, textRenderer, x + 10, y + 66 + 4 * 32,
				"каждое достижение даёт опыт профиля", UiKit.COL_TEXT_DIM);
	}

	// ----------------------------------------------------------------

	private void lockOverlay(DrawContext ctx, int x, int y, String msg) {
		lockOverlay(ctx, x, y, msg, "связь дают вышки рядом с деревнями");
	}

	/** UX-4: колесо мыши листает пейджеры активной вкладки. */
	@Override
	protected boolean onScroll(double mx, double my, double dir) {
		int step = dir < 0 ? 1 : -1;
		switch (tab) {
			case SHOP -> {
				NbtCompound shop = sub(data, "shop");
				return scrollTo(i(shop, "page"), i(shop, "pages"), step, p -> sendQuery(query, p));
			}
			case MARKET -> {
				NbtCompound market = sub(data, "market");
				return scrollTo(i(market, "page"), i(market, "pages"), step, this::marketQuery);
			}
			case CASINO -> {
				if (!casinoPickMode) return false;
				NbtCompound targets = sub(sub(data, "casino"), "targets");
				return scrollTo(i(targets, "page"), i(targets, "pages"), step, p -> casinoQuery(targetQuery, p));
			}
			default -> {
				return false;
			}
		}
	}

	private static boolean scrollTo(int page, int pages, int step, java.util.function.IntConsumer go) {
		int np = Math.max(0, Math.min(pages - 1, page + step));
		if (np == page) return false;
		go.accept(np);
		return true;
	}

	/** L7: у каждого лока — своя полезная подсказка (GPS ≠ сотовая связь). */
	private void lockOverlay(DrawContext ctx, int x, int y, String msg, String hint) {
		int w = PW - 32;
		UiKit.card(ctx, x + 16, y + 60, w, 40, UiKit.COL_PANEL_HI);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), x + PW / 2, y + 76, UiKit.COL_RED);
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), x + PW / 2, y + 88, UiKit.COL_TEXT_DIM);
	}
}
