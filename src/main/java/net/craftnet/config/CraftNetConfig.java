package net.craftnet.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Конфигурация сервера CraftNet — файл {@code config/craftnet.json}.
 * При первом запуске создаётся с дефолтами; ключи, которых не хватает
 * в существующем файле, берут дефолт и дописываются обратно (self-healing).
 * Все суммы — в CR, времена — в тиках (20 тиков = 1 с, сутки = 24000).
 */
public final class CraftNetConfig {

	// ---- старт ----
	/** Стартовый капитал новичка (выдаётся с первым смартфоном). */
	public int startBonus = 100;

	// ---- логистика / ПВЗ ----
	/** Базовая доставка при 4G (3G ×2, 2G ×3). 2400 = 2 минуты. */
	public int deliveryTravelTicks = 2400;
	/** Срок жизни незабранной посылки после созревания (истекает в компенсацию). 120000 = 5 игровых дней. */
	public long deliveryTtlTicks = 120000;

	// ---- барахолка ----
	/** Комиссия с выплаты продавца, %. */
	public double marketFeePct = 5.0;
	/** Время жизни лота. 72000 = 3 игровых дня. */
	public long marketTtlTicks = 72000;
	/** Возврат лота почтой после снятия/истечения. 1200 = 1 минута. */
	public int marketReturnTicks = 1200;
	/** Максимум одновременных лотов на игрока. */
	public int marketMaxPerPlayer = 6;
	/** Потолок цены лота за штуку. */
	public int marketPriceMax = 1_000_000;

	// ---- работы: неустойки ----
	/** Неустойка за ОТМЕНУ смены, % от оплаты. */
	public double jobCancelFeePct = 25.0;
	/** Неустойка за ТАЙМАУТ смены, % от оплаты. */
	public double jobTimeoutFeePct = 30.0;
	/** Штраф за потерянный ящик грузчика (при отмене/таймауте), CR. */
	public int jobCargoLossFee = 80;
	/** Смену каждого типа можно отработать раз в N окон офферов (1 = одна на 10-мин окно; 0 = без лимита). */
	public int jobWindowCooldown = 1;

	// ---- работы: оплата (лестница: завод > грузчик > повар > курьер; окна не пересекаются) ----
	/** Курьер: оплата = base + perPortion × пакеты. */
	public int jobCourierBase = 10;
	public int jobCourierPerPortion = 5;
	/** Курьер: пакетов в заказе (мин/макс). */
	public int jobCourierMinPortions = 3;
	public int jobCourierMaxPortions = 8;
	/** Грузчик: CR за блок пути; итог зажат в [minPay..maxPay]. */
	public int jobLoaderPerBlock = 4;
	public int jobLoaderMinPay = 150;
	public int jobLoaderMaxPay = 190;
	/** Завод (мини-игра): оплата = base + perPart × детали + джиттер 0..jitter. */
	public int jobFactoryBase = 90;
	public int jobFactoryPerPart = 35;
	public int jobFactoryJitter = 20;
	/** Завод (мини-игра): деталей в схеме (мин/макс). */
	public int jobFactoryMinParts = 3;
	public int jobFactoryMaxParts = 5;
	/** Цеховой заказ: оплата = clamp(round(материалы × matsPct/100) + bonus, minPay..maxPay).
	 *  Дефолты подобраны симуляцией (tools/simulate_jobs.py): медианный заказ ≈ центр окна,
	 *  клампы срабатывают только на ~20% экстремальных составов. */
	public int jobOrderFactoryMatsPct = 100;
	public int jobOrderFactoryBonus = 90;
	public int jobOrderFactoryMinPay = 195;
	public int jobOrderFactoryMaxPay = 285;
	/** Повар: оплата по той же формуле (медианный заказ ≈ центр окна). */
	public int jobOrderCookMatsPct = 45;
	public int jobOrderCookBonus = 60;
	public int jobOrderCookMinPay = 65;
	public int jobOrderCookMaxPay = 140;

	// ---- банк ----
	/** Ежедневный процент на остаток, % (0.15 = 0.15%). */
	public double bankInterestPctPerDay = 0.15;
	/** Потолок ежедневного процента, CR. */
	public long bankInterestCap = 25;
	/** Минимальный баланс для процента, CR. */
	public long bankInterestMinBalance = 50;

	// ---- продажа игрок → сервер (ступенчатая кривая от цены покупки) ----
	/** Коэффициент продажи дешёвых товаров (buy ≤ 9 CR): антифарм хлама. */
	public double sellRatioCheap = 0.65;
	/** Коэффициент продажи средних товаров (buy 10..499 CR). */
	public double sellRatioMid = 0.65;
	/** Коэффициент продажи дорогих товаров (buy ≥ 500 CR). */
	public double sellRatioExpensive = 0.65;

	// ---- казино-апгрейдер ----
	/** Возврат игроку, % (RTP). Шанс спина = stake/цель × rtp/100: 90 = казино в среднем удерживает 10% оборота. */
	public double casinoRtpPct = 90.0;
	/** Максимум предметов одного вида в ставке. */
	public int casinoMaxStakeUnits = 64;
	/** Минимальный шанс спина, в базисных пунктах (100 = 1%). */
	public int casinoMinChanceBp = 100;
	/** Максимальный шанс спина, в базисных пунктах (9500 = 95%). */
	public int casinoMaxChanceBp = 9500;

	// ---- биржа ----
	/** Лимит рыночной стоимости портфеля акций на игрока, CR (анти «пассивный ультрадоход»). */
	public long stocksMaxExposure = 50_000;
	/** Потолок дивидендов с одной компании за игровые сутки, CR. */
	public long stocksDividendCapPerCompany = 2_500;

	// ---- вышки / магазин ----
	/** Стоимость переходов вышки 0→1→2→3→4. */
	public int towerUpgradeCost1 = 500;
	public int towerUpgradeCost2 = 1500;
	public int towerUpgradeCost3 = 4000;
	public int towerUpgradeCost4 = 9000;
	/** Скидка «умной вышки» ур.4 в магазине, %. */
	public double shopSmartTowerDiscountPct = 5.0;
	/** Период ресинхронизации открытых экранов, тиков (20 = 1 с). */
	public int screenSyncTicks = 20;

	// ============================ загрузка ============================

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CraftNetConfig instance;

	public static CraftNetConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static synchronized void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("craftnet.json");
		CraftNetConfig cfg = null;
		if (Files.isRegularFile(path)) {
			try {
				// Gson использует no-arg конструктор → отсутствующие ключи
				// сохраняют дефолты из инициализаторов полей выше
				cfg = GSON.fromJson(Files.readString(path), CraftNetConfig.class);
			} catch (Throwable t) {
				System.err.println("[CraftNet] Конфиг повреждён, загружены дефолты: " + t);
			}
		}
		if (cfg == null) cfg = new CraftNetConfig();
		cfg.sanitize();
		instance = cfg;
		save(); // дописать недостающие ключи / создать файл
		System.out.println("[CraftNet] Конфиг загружен: " + path);
	}

	private static void save() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("craftnet.json");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(instance) + "\n");
		} catch (IOException e) {
			System.err.println("[CraftNet] Не удалось записать конфиг: " + e);
		}
	}

	/** Проверка здравых пределов — защита от отрицательных/нулевых значений в файле. */
	private void sanitize() {
		if (startBonus < 0) startBonus = 0;
		if (deliveryTravelTicks < 200) deliveryTravelTicks = 200;
		if (deliveryTtlTicks < 24000) deliveryTtlTicks = 24000;
		if (marketFeePct < 0) marketFeePct = 0;
		if (marketFeePct > 50) marketFeePct = 50;
		if (marketTtlTicks < 12000) marketTtlTicks = 12000;
		if (marketReturnTicks < 20) marketReturnTicks = 20;
		if (marketMaxPerPlayer < 1) marketMaxPerPlayer = 1;
		if (marketPriceMax < 100) marketPriceMax = 100;
		if (jobCancelFeePct < 0) jobCancelFeePct = 0;
		if (jobTimeoutFeePct < 0) jobTimeoutFeePct = 0;
		if (jobCargoLossFee < 0) jobCargoLossFee = 0;
		if (jobCargoLossFee > 10_000) jobCargoLossFee = 10_000;
		if (jobWindowCooldown < 0) jobWindowCooldown = 0;
		if (jobWindowCooldown > 10) jobWindowCooldown = 10;
		if (jobCourierBase < 0) jobCourierBase = 0;
		if (jobCourierPerPortion < 0) jobCourierPerPortion = 0;
		if (jobCourierMinPortions < 1) jobCourierMinPortions = 1;
		if (jobCourierMaxPortions > 64) jobCourierMaxPortions = 64;
		if (jobCourierMaxPortions < jobCourierMinPortions) jobCourierMaxPortions = jobCourierMinPortions;
		if (jobLoaderPerBlock < 0) jobLoaderPerBlock = 0;
		if (jobLoaderPerBlock > 100) jobLoaderPerBlock = 100;
		if (jobLoaderMinPay < 0) jobLoaderMinPay = 0;
		if (jobLoaderMaxPay < jobLoaderMinPay) jobLoaderMaxPay = jobLoaderMinPay;
		if (jobFactoryBase < 0) jobFactoryBase = 0;
		if (jobFactoryPerPart < 0) jobFactoryPerPart = 0;
		if (jobFactoryPerPart > 10_000) jobFactoryPerPart = 10_000;
		if (jobFactoryJitter < 0) jobFactoryJitter = 0;
		if (jobFactoryJitter > 10_000) jobFactoryJitter = 10_000;
		if (jobFactoryMinParts < 1) jobFactoryMinParts = 1;
		if (jobFactoryMaxParts > 16) jobFactoryMaxParts = 16;
		if (jobFactoryMaxParts < jobFactoryMinParts) jobFactoryMaxParts = jobFactoryMinParts;
		if (jobOrderFactoryMatsPct < 0) jobOrderFactoryMatsPct = 0;
		if (jobOrderFactoryMatsPct > 1000) jobOrderFactoryMatsPct = 1000;
		if (jobOrderFactoryBonus < 0) jobOrderFactoryBonus = 0;
		if (jobOrderFactoryMinPay < 0) jobOrderFactoryMinPay = 0;
		if (jobOrderFactoryMaxPay < jobOrderFactoryMinPay) jobOrderFactoryMaxPay = jobOrderFactoryMinPay;
		if (jobOrderCookMatsPct < 0) jobOrderCookMatsPct = 0;
		if (jobOrderCookMatsPct > 1000) jobOrderCookMatsPct = 1000;
		if (jobOrderCookBonus < 0) jobOrderCookBonus = 0;
		if (jobOrderCookMinPay < 0) jobOrderCookMinPay = 0;
		if (jobOrderCookMaxPay < jobOrderCookMinPay) jobOrderCookMaxPay = jobOrderCookMinPay;
		if (bankInterestPctPerDay < 0) bankInterestPctPerDay = 0;
		if (bankInterestCap < 0) bankInterestCap = 0;
		if (bankInterestMinBalance < 0) bankInterestMinBalance = 0;
		if (casinoMaxStakeUnits < 1) casinoMaxStakeUnits = 1;
		if (casinoRtpPct < 50) casinoRtpPct = 50;
		if (casinoRtpPct > 100) casinoRtpPct = 100;
		if (casinoMinChanceBp < 1) casinoMinChanceBp = 1;
		if (casinoMaxChanceBp > 9900) casinoMaxChanceBp = 9900;
		if (casinoMaxChanceBp < casinoMinChanceBp) casinoMaxChanceBp = 9500;
		if (stocksMaxExposure < 1_000) stocksMaxExposure = 1_000;
		if (stocksMaxExposure > 10_000_000) stocksMaxExposure = 10_000_000;
		if (stocksDividendCapPerCompany < 0) stocksDividendCapPerCompany = 0;
		towerUpgradeCost1 = clampCost(towerUpgradeCost1);
		towerUpgradeCost2 = clampCost(towerUpgradeCost2);
		towerUpgradeCost3 = clampCost(towerUpgradeCost3);
		towerUpgradeCost4 = clampCost(towerUpgradeCost4);
		if (shopSmartTowerDiscountPct < 0) shopSmartTowerDiscountPct = 0;
		if (shopSmartTowerDiscountPct > 50) shopSmartTowerDiscountPct = 50;
		if (sellRatioCheap < 0.05 || sellRatioCheap > 0.95) sellRatioCheap = 0.65;
		if (sellRatioMid < 0.05 || sellRatioMid > 0.95) sellRatioMid = 0.65;
		if (sellRatioExpensive < 0.05 || sellRatioExpensive > 0.95) sellRatioExpensive = 0.65;
		// кривая обязана быть неубывающей — иначе сбрасываем на дефолты
		if (!(sellRatioCheap <= sellRatioMid && sellRatioMid <= sellRatioExpensive)) {
			sellRatioCheap = 0.65;
			sellRatioMid = 0.65;
			sellRatioExpensive = 0.65;
		}
		if (screenSyncTicks < 4) screenSyncTicks = 4;
		if (screenSyncTicks > 100) screenSyncTicks = 100;
	}

	private static int clampCost(int value) {
		return Math.max(0, Math.min(10_000_000, value));
	}
}
