# Базовые правила разработки Metro World

Предпочтения пользователя, согласованные 5 октября 2026 года. Применять к следующим Minecraft-модам, пока пользователь не задаст другие условия.

- Целевая версия: Minecraft 1.21.1, Fabric Loader (>=0.16.10), Java 21. Для серверных модов использовать Polymer (клиентам не нужен клиентский мод).
- Репозиторий должен содержать Gradle Wrapper, CI/CD, Dockerfile, Compose, `.env.example` и инструкцию запуска.
- В CI и терминале вызывать Gradle строго через `bash ./gradlew --no-daemon test build`, чтобы Windows/ZIP не ломали запуск потерей executable-бита.
- Серверный контейнер строить на `itzg/minecraft-server:java21`, задавать Minecraft/Fabric явно и включать собранный мод, Fabric API и совместимые серверные оптимизации: Lithium, FerriteCore, Krypton.
- По умолчанию `EULA=TRUE`, **`ONLINE_MODE=FALSE`**. Пользователь отдельно исправил первоначальное TRUE на FALSE; не возвращать в TRUE.
- Внутри контейнера Minecraft слушает TCP 25565. Compose публикует его на настраиваемый порт (`METRO_WORLD_PORT`, по умолчанию 25565; при необходимости 80). RCON не публиковать наружу. Для CI использовать свободный порт только на 127.0.0.1.
- До публикации обязательно запускать контейнер с временным миром. Проверять успешный старт, установленные моды, EULA, offline mode, доступность Minecraft-порта, кастомное измерение и генерацию чанков.
- При ошибке проверки сохранять серверные логи как артефакт (`build/container-check/`) и блокировать публикацию образа. Удалять временные контейнеры и тома после проверки.
- После проверки публиковать проверенный образ: `dev-latest` для `main` и обычную версию для тега `v*`. Сохранять JAR и архив `docker save` как CI-артефакты.
- Проверять совместимость сторонних модов с точной версией Minecraft и загрузчиком, проверять контрольные суммы (`scripts/prepare-server-mods.py`), сохранять манифест в `server/mods/server-mods.json`.
- Пользователю отдавать полный ZIP корня репозитория: файлы сразу в корне архива, включая `.github` и `.env.example`. Не включать `.git`, кэши, временные миры, секреты и локальный `.env`.
- При изменении имён/удалении файлов явно сообщать, какие старые файлы удалить. Не удалять пользовательские миры (`btr-infrastructure_btr-world`) при обновлении контейнера.
- Не заявлять, что Minecraft или контейнер проверены, если запуск недоступен. Указывать, что проверено локально, а что должен выполнить CI.
- В CI и shell-скриптах не предполагать наличие ripgrep: использовать стандартный POSIX `grep`.

---

## Архитектура и структура репозитория

- **Основной пакет**: `eu.metroworld.infrastructure` (mod ID `metro-world`).
  - Точки входа (`fabric.mod.json`): main — `InfrastructureMod`, preLaunch — `TrainPackBootstrap`.
- **Пакет совместимости**: `eu.kessoku.infrastructure` и namespace `btr_infrastructure` сохранены для обратной совместимости старых сохранений и миров.
- **Измерение**: `metro-world:metro-world` (высоты от −256 до 255). Масштаб координат 0.125 (1 блок метро = 8 блоков Overworld).
  - Миграция старого мира: `bash server/migrate-metro-world-dimension.sh` (копирует чанки из `btr_infrastructure/metro`).
- **Polymer & Ресурс-пак**: AutoHost автоматически раздаёт сгенерированный ресурс-пак на порту Minecraft.
  - Текстуры и модели лежат в `src/main/resources/assets/btr_infrastructure/`.
  - Генерация ресурс-пака запускается на старте сервера через `TrainModels.buildPack()`.

---

## Команды разработки и проверки

### 1. Быстрые Standalone-чеки (без Gradle daemon и без загрузки Minecraft)
Компилируются и запускаются напрямую через `javac` / `java`:
- `bash scripts/check-trains.sh` — физика вагонов, двери, компрессор, тормоза, габариты, диагонали.
- `bash scripts/check-rail-geometry.sh` — связность путей, 3D-шум, варианты станций, расписание музыки, купола, разрушения станций, контент.

### 2. Python тесты и утилиты
- `python3 -m unittest discover -s scripts/tests` — 18 тестов: таблицы лута, парсер TrainCarts, оркестрация smoke-теста.
- Установка зависимостей для работы с моделями поездов: `python3 -m pip install -r scripts/train-model-requirements.txt`.
- Перегенерация графики пульта и иконок: `python3 scripts/generate-train-assets.py`.
- Перегенерация текстур циферблатов: `python3 scripts/generate-train-readouts.py`.

### 3. Gradle (Loom + JUnit)
Требуется Java 21 (`JAVA_HOME` указывает на JDK 21):
- Все тесты: `bash ./gradlew --no-daemon test`
- Одиночный тест: `bash ./gradlew --no-daemon test --tests "eu.metroworld.infrastructure.TransitScaleTest"`
- Сборка JAR: `bash ./gradlew --no-daemon build` (выходной файл: `build/libs/metro-world-*.jar`)

### 4. Подготовка сервера и Container Smoke Test
- Полная подготовка локального образа: `bash server/prepare.sh` (собирает JAR, скачивает моды через `scripts/prepare-server-mods.py`, собирает `metro-world:local`).
- Прогон smoke-теста контейнера: `bash scripts/container-smoke-test.sh metro-world:ci` (или `metro-world:local`).
  - Проверяет `server.properties`, EULA, моды, RCON, генерацию измерения, команды `/metro-world validate-tracks`, `/metro-world validate-features`, `/metro-train validate`, локацию станций и TCP-статус.

---

## Важные игровые и RCON-команды

- Валидация в тестовом мире (OP lvl 2):
  - `/metro-world validate-tracks` — проверка непрерывности рельсов и стрелок.
  - `/metro-world validate-features` — проверка сундуков, аквариумов и блоков.
  - `/metro-train validate` — проверка контроллеров и сущностей поездов.
- Поиск станций: `/metro-world locate station|passenger|mini|interchange|terminal|freight|mixed|biocenter|aquarium|spiral|entrance`.
- Управление поездами: `/metro-train spawn blue|red|freight`, `/metro-train panel`, `/metro-train board`, `/metro-train cargo`.
- Эскалаторы: `/metro-escalator build <rise> up|down north|south|east|west` (rise 1..32, направление указывает к верхней площадке).

