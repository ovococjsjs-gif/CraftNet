# CraftNet — дизайн-документ

Fabric-мод для **Minecraft 1.21.11**: «смартфон в Minecraft» — онлайн-магазин,
банк, биржа, GPS-карта, сотовая связь (2G/3G/4G), новые здания в деревнях,
вышки связи и рабочие места (завод / грузчик / повар / курьер).

## Версии (проверено по meta.fabricmc.net и maven.fabricmc.net, 2026-08-24)

| Компонент | Версия |
|---|---|
| Minecraft | 1.21.11 |
| Yarn | 1.21.11+build.6 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.6+1.21.11 |
| Fabric Loom | 1.17.15 |
| Gradle | 8.14.3 (совместим с Loom 1.17; можно поднять до 9.x) |
| Java | 21 |

## Ключевые факты API 1.21.11 (проверено по yarn-javadoc)

- `Item.Settings.registryKey(RegistryKey<Item>)` — обязателен.
- `AbstractBlock.Settings.create().registryKey(RegistryKey<Block>)` — обязателен.
- `Item.use(World, PlayerEntity, Hand)` возвращает **`ActionResult`** (не TypedActionResult).
- `Item.appendTooltip(ItemStack, Item.TooltipContext, TooltipDisplayComponent, Consumer<Text>, TooltipType)`.
- Персистентные данные мира: `PersistentState` (абстрактный, `markDirty()`),
  `PersistentStateType<T>` — record `(String id, Supplier<T>, Codec<T>, DataFixTypes)`,
  `PersistentStateManager.getOrCreate(type)`.
- Поиск структур: `ChunkGenerator.locateStructure(ServerWorld, RegistryEntryList<Structure>, BlockPos, int radius, boolean)`.
  Получение списка по тегу: `registry.getOrThrow(StructureTags.VILLAGE)`.
- GUI (новый рендерер 1.21.6+): `DrawContext.drawTexture(RenderPipeline, Identifier, x, y, u, v, w, h, tw, th)`,
  `drawText/drawTextWithShadow/drawCenteredTextWithShadow`, `fill`, `enableScissor`,
  `getMatrices()` → `Matrix3x2fStack` (JOML). Пайплайны: `net.minecraft.client.gl.RenderPipelines` (`GUI_TEXTURED`, `GUI`).
- Динамические текстуры: `NativeImageBackedTexture(Supplier<String>, w, h, useStb)` + `upload()`,
  `NativeImage.setColorArgb(x, y, 0xAARRGGBB)`, регистрация через `TextureManager.registerTexture(Identifier, AbstractTexture)`.
- Пулы структур: `StructurePool` — приватные списки `elements` и `elementWeights`;
  расширяем accessor-миксином. `StructurePoolElement.ofLegacySingle(String id)` → функция с `Projection.RIGID` для деревень.
- Папки датапаков (1.21+): `data/<ns>/structure/*.nbt`, `loot_table/`, `recipe/`.
- Клиентские модели предметов (1.21.4+): `assets/<ns>/items/<id>.json` → `{"model":{"type":"minecraft:model","model":"<ns>:item/<id>"}}`.

## Архитектура

```
net.craftnet
├── CraftNet                 — ModInitializer
├── CraftNetClient           — ClientModInitializer (сеть, GUI, кейбинды)
├── block/ModBlocks          — блоки: пвз-стойка, банк-терминал, станок завода,
│                              стойка кафе, ядро вышки, грузовой ящик
├── block/TowerCoreBlock     — хуки onPlaced/onStateReplaced → VillageManager
├── item/ModItems            — телефон, банкнота, грузовой ящик-предмет, пакет еды
├── item/PhoneItem           — открытие телефона
├── component/ModComponents  — DataComponent: номинал банкноты
├── econ/MoneyManager        — балансы игроков (persistent)
├── econ/PriceManager        — прайс-лист (таблица + эвристика fallback), спред
├── econ/StocksManager       — биржа: 5 компаний, random-walk, истории, портфели
├── orders/OrderManager      — заказы магазина: доставка в ПВЗ, выплаты за продажу
├── village/VillageManager   — обнаружение деревень, вышки, офлайн, уровни сигнала
├── village/VillageStructureInjector — инъекция NBT в пулы деревень (SERVER_STARTED)
├── network/ModPackets       — 3 generic-пейлоада: OpenScreen/ScreenSync/ScreenAction
├── network/ServerActions    — серверная обработка действий GUI
├── jobs/JobManager          — задания: завод/грузчик/повар/курьер, выплаты по лестнице
├── command/AdminCommands    — /craftnet money|signal|village|tower
└── mixin/StructurePoolAccessor
net.craftnet.client
├── NetHooks                 — client-ресиверы пакетов
├── Keybinds                 — клавиша P = телефон
├── gui/PhoneScreen          — телефон: Главная, Магазин, Биржа, GPS, Банк, Связь
├── gui/PvzScreen            — ПВЗ: получение заказов + отправка на продажу
├── gui/BankScreen           — обналичивание баланса в банкноты и обратно
├── gui/JobScreen            — задания от бригадира/бариста
├── gui/widgets              — мелкие UI-хелперы
└── GpsMapRenderer           — NativeImage-карта местности по загруженным чанкам
resources
├── fabric.mod.json, craftnet.mixins.json
├── assets/craftnet/{lang,items,models,blockstates,textures}
└── data/craftnet/{structure,loot_table,recipe}
tools/gen_textures.py        — генерация пиксельных PNG без PIL
tools/gen_structures.py      — ASCII-планы зданий → бинарные NBT
```

## Геймдизайн

### Телефон
- Стартовый предмет при первом входе (также крафт: железо+редстоун+медь+стекло).
- Клавиша `P` или ПКМ по предмету. Приложения: Магазин, Биржа, GPS, Банк (просмотр), Связь.
- Магазин/Биржа требуют связь ≥2G; GPS — спутниковый приём (не глубоко под землёй).

### Связь (уровни от расстояния до ближайшей ОНЛАЙН-деревни)
| Уровень | Радиус | Эффекты |
|---|---|---|
| 4G | ≤ 60 | доставка ×1, биржа да |
| 3G | ≤ 160 | доставка ×2, биржа да |
| 2G | ≤ 320 | доставка ×3, биржа нет (нужен 3G) |
| нет | > 320 | только офлайн-экран телефона |

Деревня онлайн, если её вышка цела (ядро вышки не разрушено).

### Вышка связи
- Ставится автоматически при обнаружении деревни на кольце 40–64 блока от центра
  (не внутри), процедурная постройка + `tower_core` на вершине (светится).
- Разрушение `tower_core` → деревня офлайн. Починка: установить новое ядро рядом
  с деревней (крафт/покупка в магазине) → деревня снова онлайн.

### Деревенские здания (инъекция в пулы `village/*/houses`, вес 6)
1. **ПВЗ** (`pvz`) — сотрудник со скином странствующего торговца (NoAI, Invulnerable,
   тег `craftnet:pvz`). ПКМ → экран ПВЗ: получение заказов и отправка товаров на продажу.
2. **Банк** (`bank`) — библиотекарь «Банкир» (тег `craftnet:bank`). Обналичивание:
   баланс → предметы «Банкнота» с номиналом (текстуру нарисует заказчик), и обратно.
3. **Завод** (`factory`) — бригадир (тег `craftnet:foreman`): производственные заказы
   (принести компоненты редстоун-тематики), максимальная оплата.
4. **Кафе** (`cafe`) — бариста (тег `craftnet:barista`): заказы на еду, старт курьерки.

### Экономика
- Валюта «кредит» (CR), баланс на игрока. Обмен курсов нет — 1 CR = 1 CR.
- Магазин: buy-цена из прайса (`PriceManager`, ~150 позиций + fallback-эвристика),
  sell = 72% от buy. Комиссия/спред биржи 2%.
- Оплата работ (лестница): **завод ×1.6, грузчик ×1.35, повар ×1.15, курьер ×1.0** от базы.
- Продажа товаров: оставляем в ПВЗ → выплата приходит с задержкой (как «продал онлайн»).

### Доставка
- База — 3 игровых минуты (3600 тиков) × множитель связи + штраф расстояния.
- Получение — только в ПВЗ деревни, из экрана сотрудника.

### GPS
- Карта строится из загруженных на клиенте чанков (`NativeImage`, цвет верхнего блока,
  оттенок по высоте). Деревни — метками (координаты с сервера), игрок — центр.
- Под землёй (ниже уровня моря −12 и без неба над головой) — «нет сигнала GPS».

### Работы (MVP-механика, экраны-миниигры — этап 2)
- **Завод**: заказ бригадира = список редстоун-компонентов. Принёс → ×1.6.
- **Грузчик**: взять «грузовой ящик» в ПВЗ → тащить (Slowness I «тяжесть») к
  случайному жителю деревни → оплата за расстояние ×1.35.
- **Повар**: заказ бариста = список блюд → ×1.15.
- **Курьер**: «пакет еды» жителю, сумма от числа порций ×1.0.
- Целевой житель подсвечивается именем над головой (CustomName + теги), принимается
  ПКМ по нему при наличии предмета в руке/инвентаре.

## Этап 2+ (дорожная карта)
- Мини-игра «конвейер» на заводе (ритм-клики по деталям), мини-игра «готовка».
- ИИ жителей в кафе: кастомный Brain-goal (посиделки за столиками) — mixin в
  `VillagerTaskListProvider`, POI типа `meeting` для кафе.
- События биржи (новости, дивиденды), падение цен при массовых продажах игроков.
- Страхование посылок, репутация курьера, расширение каталога прайсов из датапаков.
- Кастомные профессии жителей вместо NoAI-NPC (когда заказчик утвердит текстуры).
- Текстура банкноты — от заказчика.

## Известные точки риска — ИТОГ ПРОВЕРКИ (2026-08-24, по yarn-javadoc 1.21.11+build.6)
1. `TextureManager.registerTexture(Identifier, AbstractTexture)` — **есть**, ровно с этой сигнатурой.
2. `Registry#getOrThrow(TagKey)` — **есть** (наследуется от `RegistryEntryLookup`).
3. `getPersistentStateManager()` на ServerWorld — **есть**, возвращает `PersistentStateManager`.
4. `RenderPipelines.GUI_TEXTURED`, `DrawContext.drawTexture(RenderPipeline, Identifier, x, y, u, v, w, h, tw, th)` — **есть**.
5. `NativeImageBackedTexture(Supplier<String>, int, int, boolean)` + `upload()` — **есть**.
6. DataVersion в NBT-структурах — **4671** (официальное значение 1.21.11), поправлено.
7. `Screen.render` с 1.20.2 вызывает `renderBackground` (blur+darkening) — **учтено**:
   `CraftNetScreen.renderBackground` переопределён светлой заливкой без блюра,
   контент рисуется ПОСЛЕ `super.render`.
8. **`ServerWorld.getEntity(UUID)` переименован в `getEntityAnyDimension(UUID)`** — учтено (JobManager).
9. Удобный `ServerWorld.locateStructure(TagKey<Structure>, BlockPos, int, boolean)` — **используем его**
   вместо ручного `ChunkGenerator.locateStructure` (VillageManager).
10. `StructurePool.elements` (`ObjectArrayList`) и `elementWeights` (`List<Pair>`) — **подтверждены**,
    миксин-аксессор и инжектор на них опираются.
