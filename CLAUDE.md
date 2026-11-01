# Tom's Storage Autocrafting (tsautocraft)

Fabric **26.3** addon for Tom's Simple Storage (`toms_storage` 2.12.2) adding AE2-style autocrafting.
Package `eu.sytxlabs.tsautocraft`, mod id `tsautocraft`. Code, comments and docs in English; every visible string is a translation key
(`src/main/resources/assets/tsautocraft/lang/en_us.json` + `de_de.json`, keys must stay in sync).
The user (Shaun) talks German; reply in German.

## Build / run

- JDK 25, Gradle 9.4.1 wrapper, Loom 1.16 (26.x is unobfuscated: plain `implementation`, no mappings).
- `gradlew build`, `gradlew runClient`, `gradlew plannerTest` (Minecraft-free planner self test, must print "All planner tests passed").
- Runtime log: `run/logs/latest.log`.
- `_to_delete/` holds stale files from the initial file transfer; safe to delete, ignored by git.

## Status (2026-11-01)

- Code was written without compiling against Minecraft (no Maven access in the authoring sandbox), then fixed by compiler feedback:
  - `ConnectorNodeBlock.box(...)` clashed with public `Block.box` -> renamed `shapeBox`.
  - `EditBox.setFilter` does not exist in 26.3 -> digits are stripped in the responder instead.
- `gradlew build` is green, `plannerTest` passes, client boots (no mod errors in log).
- Next: test in game: creative tab, connector models, framed painting, crafter dispatch, requester.
- Expect more 26.3 API mismatches in client code (GUI uses `GuiGraphicsExtractor`, `extractBackground/extractLabels/extractRenderState`,
  `MouseButtonEvent`, `KeyEvent`) and possibly `CrafterBlockEntity.isSlotDisabled`, `CrafterBlock.TRIGGERED`, `ValueOutput.list(...)`.
  When unsure, check Tom's Storage source (github.com/tom5454/Toms-Storage, branch `snapshot` = 26.3) or the merged MC jar in
  `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-26.3.jar`.

## Architecture

- `planner/CraftingPlanner` – pure Java recursive planner (virtual stock, surplus reuse, cycle/depth guard, fallback patterns). Tested by `PlannerSelfTest`.
- `job/CraftingJob` – planned job, persisted (Codec) in its owner BE; states RUNNING -> WAITING -> DONE/CANCELLED; no item reservation.
  Dispatched crafts are `inFlight` per step until the Importer reports the results (`TerminalCrafting.itemsArrived`, by primary output).
  Once a job got such a report it is DONE exactly when nothing is in flight; otherwise stock/idle fallback (results via plain chests).
- `network/NetworkLink` – finds Tom's `IInventoryConnector` via `InventoryCableNetwork` (like Tom's Inventory Interface) and collects Pattern Providers on the network.
- Blocks: `NodeBlock` (full block node), `DirectionalNodeBlock` (full block with FACING), `PatternProviderBlock` (+ POWERED pulse),
  `ConnectorNodeBlock` (shape/model like Tom's Inventory Cable Connector, FACING + 6 connection props + POWERED),
  `FramedConnectorNodeBlock` (full hitbox, `IPaintable`, BE must extend Tom's `PaintedBlockEntity`; client wraps models with Tom's `UnbakedPaintedModel`).
- Blocks never expose item storage, so Tom's Inventory Connector does not pick them up as storage.
- Pattern Terminal = Tom's terminal: `PatternTerminalBlock extends AbstractStorageTerminalBlock`, BE extends `StorageTerminalBlockEntity`,
  menu extends `StorageTerminalMenu`, screen extends `AbstractStorageTerminalScreen` (GUI = Tom's crafting_terminal.png with encoding area).
  Model parents `toms_storage:block/crafting_terminal`. Every BE on a block using Tom's `TickerUtil` must implement `TickableServer`.
- Requests from Tom's terminals via mixins (`tsautocraft.mixins.json`): `StorageTerminalMenuMixin` syncs craftables (`tsac_craft`) and handles
  `tsac_req`; `AbstractStorageTerminalScreenMixin` marks craftables (green +) and opens `CraftRequestScreen` (click on amount 0 / Ctrl+click).
  Jobs run in an Autocrafting Terminal on the same cable network (`TerminalCrafting`).
- 26.3: `Minecraft.gui.setScreen(...)` (not `Minecraft.setScreen`).
- Multipart blockstates: the first part must have a `particle` texture (Tom's `cable_closed/open` have none) -> `*_particle` models first.
- Blocks with a GUI that are `IPaintable` must return `PASS` from `useItemOn` for Tom's `PaintKitItem`, else the GUI eats the click.
- Dev client has JEI + MezzConfig (`localRuntime`, Modrinth version ids in `gradle.properties`); JEI API is `compileOnly`,
  plugin `compat/jei/TsAutocraftJeiPlugin` via entrypoint `jei_mod_plugin`: "+" for Pattern Terminal (fills grid), Pattern Provider
  (encodes into a free slot, uses a Blank Pattern from the inventory), Requester (output -> entry); ghost drag for Pattern Terminal + Requester.
  Encoding logic shared in `pattern/PatternEncoder`.
- Autocrafting Terminal is a Tom's terminal block too (`AutocraftingTerminalBlock`, model parent `toms_storage:block/storage_terminal`).
  It lists its own jobs plus Requester jobs on the network (`CraftingJob.uid` = runtime id for cancel).
- Requests go through `CraftRequestProtocol` (`tsac_plan` -> material list preview, `tsac_req` -> start); UI = `CraftRequestScreen`.
- Block/item textures are generated from Tom's textures (wood panels, `inventory_interface` frame, `item_filter` for patterns).
- The dev client often dies natively (0xC0000005, no hs_err even with `-XX:ErrorFile`, no Windows event) during the
  resource reload, also without this mod/JEI, with any graphics backend, muted audio and fewer worker threads.
  Cause unknown (machine/driver/overlay); just retry. `gradlew runClient -PnoJei` starts without JEI.
- Config `config/tsautocraft.json` (`TsAutocraftConfig`, Gson, read at startup): `crafterTrigger` = `external` (default, player's
  redstone circuit crafts; no stuck cleanup) | `direct` (scheduled block tick on the crafter) | `redstone` (2-tick pulse, provider is a
  signal source only in this mode); `enableDisabledCrafterSlots` (default false = recipe is shifted to enabled crafter slots);
  plus timings/limits (`crafter*Ticks`, `maxJobsPerTerminal`, `maxRequestAmount`, `job*Seconds`, `requester*`, `io*`,
  `craftablesSyncTicks`) - no more hard-coded constants for these, read them via `TsAutocraftConfig.get()`.
- Blocking off: crafting patterns stack more sets of the same recipe/layout into a crafter (`fitOnTop`).
- Substitutes (toggle in Pattern Terminal + Pattern Provider for JEI "+"): `PatternData.Entry.alternatives`, computed at encode
  time by testing every registry item at that grid position against the recipe (same result -> substitute, max 32).
  Planner uses the best-stocked option per position (`CraftingJob.resolvedInputs`), the provider picks per craft (`chooseInputs`).
  Crafting patterns only; processing patterns stay exact.
- Pattern Provider: crafting patterns fill `minecraft:crafter` slots 0-8 in pattern order (shifted if needed), trigger per config;
  processing patterns insert all-or-nothing, optional blocking mode. Results return via Importer.
- Menus/screens sync through Tom's existing `toms_storage:data` packet (`NetworkHandler.sendTo/sendDataToServer`, menus implement `IDataReceiver`).
- Assets were generated by a Python script (pixel textures, GUI backgrounds, connector models derived from Tom's `cable_connector/*` models).
