package net.craftnet.econ;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Переопределения цен из {@code config/craftnet_prices.json} (см. ECONOMY.md).
 *
 * <p>Файл — плоский объект {@code "minecraft:diamond": 140}. При первом запуске
 * записывается полный дефолтный прайс (самодокументируемый). Действуют правила:
 * <ul>
 * <li>ключ должен быть торгуемым id (namespace {@code minecraft:} либо
 *     {@code craftnet:tower_core}); чужие/битые ключи — в лог и мимо;</li>
 * <li>цена — целое 0..1 000 000; <b>0 = вывести предмет из торговли</b>;</li>
 * <li>при загрузке файл пересохраняется: дефолты сливаются с переопределениями,
 *     так что после обновления мода новые позиции появляются сами;</li>
 * <li>перечитать без рестарта — {@code /craftnet reload}.</li>
 * </ul>
 */
public final class CraftNetPrices {
	private CraftNetPrices() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Integer> OVERRIDES = new TreeMap<>();

	public static synchronized void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("craftnet_prices.json");
		Map<String, Integer> found = new TreeMap<>();
		if (Files.isRegularFile(path)) {
			try {
				JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
					String id = e.getKey();
					if (!id.startsWith("minecraft:") && !id.equals("craftnet:tower_core")) {
						System.err.println("[CraftNet] prices: пропущен неторгуемый id: " + id);
						continue;
					}
					JsonElement el = e.getValue();
					if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
						System.err.println("[CraftNet] prices: не число у " + id + " — пропущено");
						continue;
					}
					long v = el.getAsLong();
					if (v < 0 || v > 1_000_000) {
						System.err.println("[CraftNet] prices: цена вне 0..1000000 у " + id + " — пропущено");
						continue;
					}
					found.put(id, (int) v);
				}
			} catch (Throwable t) {
				System.err.println("[CraftNet] Файл цен повреждён, используются дефолты: " + t);
				found.clear();
			}
		}
		OVERRIDES.clear();
		OVERRIDES.putAll(found);
		save(path); // self-heal: дописать новые дефолты / создать файл
		System.out.println("[CraftNet] Цены загружены: " + OVERRIDES.size() + " переопределений из " + path);
	}

	/** Записать файл: дефолты ∪ переопределения (переопределения побеждают). */
	private static void save(Path path) {
		TreeMap<String, Integer> merged = new TreeMap<>(PriceManager.defaults());
		merged.putAll(OVERRIDES);
		JsonObject obj = new JsonObject();
		for (Map.Entry<String, Integer> e : merged.entrySet()) obj.addProperty(e.getKey(), e.getValue());
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(obj) + "\n");
		} catch (IOException e) {
			System.err.println("[CraftNet] Не удалось записать файл цен: " + e);
		}
	}

	/** Переопределение цены покупки для id или null (тогда — дефолт/эвристика). */
	public static Integer override(String itemId) {
		return OVERRIDES.get(itemId);
	}
}
