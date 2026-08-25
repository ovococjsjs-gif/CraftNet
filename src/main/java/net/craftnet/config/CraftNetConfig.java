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

	// ---- работы ----
	/** Неустойка за ОТМЕНУ смены, % от оплаты. */
	public double jobCancelFeePct = 25.0;
	/** Неустойка за ТАЙМАУТ смены, % от оплаты. */
	public double jobTimeoutFeePct = 30.0;

	// ---- банк ----
	/** Ежедневный процент на остаток, % (0.15 = 0.15%). */
	public double bankInterestPctPerDay = 0.15;
	/** Потолок ежедневного процента, CR. */
	public long bankInterestCap = 25;
	/** Минимальный баланс для процента, CR. */
	public long bankInterestMinBalance = 50;

	// ---- казино-апгрейдер ----
	/** Максимум предметов одного вида в ставке. */
	public int casinoMaxStakeUnits = 64;
	/** Минимальный шанс спина, в базисных пунктах (100 = 1%). */
	public int casinoMinChanceBp = 100;
	/** Максимальный шанс спина, в базисных пунктах (9500 = 95%). */
	public int casinoMaxChanceBp = 9500;

	// ---- вышки / магазин ----
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
		if (bankInterestPctPerDay < 0) bankInterestPctPerDay = 0;
		if (bankInterestCap < 0) bankInterestCap = 0;
		if (bankInterestMinBalance < 0) bankInterestMinBalance = 0;
		if (casinoMaxStakeUnits < 1) casinoMaxStakeUnits = 1;
		if (casinoMinChanceBp < 1) casinoMinChanceBp = 1;
		if (casinoMaxChanceBp > 9900) casinoMaxChanceBp = 9900;
		if (casinoMaxChanceBp < casinoMinChanceBp) casinoMaxChanceBp = 9500;
		if (shopSmartTowerDiscountPct < 0) shopSmartTowerDiscountPct = 0;
		if (shopSmartTowerDiscountPct > 50) shopSmartTowerDiscountPct = 50;
		if (screenSyncTicks < 4) screenSyncTicks = 4;
		if (screenSyncTicks > 100) screenSyncTicks = 100;
	}
}
