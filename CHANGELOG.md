# Changelog — animastor-android

Нормативно — `animastor-docs` (`CHANGELOG.md`, `architecture/ORCHESTRATION_V2.md` §11,
паритет — `architecture/ANDROID_WEB_PARITY.md`). Здесь — краткая запись по репозиторию.

## 0.3.5 (18) — 2026-11-27 — generate API v2 (ветка `feat/generate-api-v2`)

- Генератор говорит с новым API оркестрации (ADR ORCHESTRATION_V2 §11.1–11.2):
  `POST /book/:id/generate` `{scope, layers, mode, client_action_id}`, `POST /generate/stop`,
  `GET /generate/progress`, SSE `GET /generate/stream` (`network/GenProgressStream.kt`).
  Вместо `/regenerate`, `cancel-generation`, `progress-panel`; `build_id` из ответа больше не читается.
- Диалог масштаба слоя — две кнопки: «Сгенерировать недостающее» (`mode:"missing"`) и
  «Перегенерировать» (`mode:"all"`, подтверждение, если область шире сцены). VBook «Сгенерировать всё» — без изменений.
- `client_action_id` = id нажатия (`GeneratorCommands.userAction`); повтор того же id сервер возвращает
  как `existing`.
- Стоп трёх видов: ■ строки → `{command_id}`, ■ раздела → `{layer}`, «Стоп всё» → `{}`; VBook / обложка —
  по-прежнему `cancel-worker` (не слои оркестрации, задача H).
- Прогресс — агрегат §9 (`GenerateApiV2.aggregateToPanel` → прежняя модель строк); `/progress-stream`
  остаётся только для событий VBook / анализа.
- Удалены мёртвые декларации: `resumeBootstrap`, `triggerNextWindow`, `getGenerationState`,
  `regenerateBook(new_book)` (+ `regenerateBookScoped`, `cancelGeneration`, `getProgressPanel`) и их модели.
- Тесты: `GenerateApiV2Test` (те же фикстуры, что web `generateApi.test.ts`), `GeneratorButtonsTest`,
  `GeneratorCommandsTest`, `BootstrapIdempotencyTest` обновлены.
- **Совместимость:** нужен backend с `/generate*` (ветка `feat/generate-api-v2` backend). APK 0.3.4 и старше
  продолжают работать на старых именах до шага 5 удаления legacy.
