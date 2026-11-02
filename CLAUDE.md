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

## Status (2026-11-02)

- `gradlew build` (also with `-Xlint:deprecation,unchecked,rawtypes`: no warnings) and `plannerTest` are green; the dev client
  boots into a world (Jade + our plugin load, machine profile loads).
- 0.2.0 (not all tested in game yet): job scheduler with priority/pause and per-step status, public API package,
  machine profiles, planner hooks, fluid pattern mode, Exporter/Importer modes, stackable upgrades, Redstone Upgrade, anvil
  names, wireless access, Jade plugin, Mod Menu config screen, full-block (omni) Pattern Provider, Crafting Emitter variants
  (full block, connector, framed), tooltips via components.
- Next: test in game (job list buttons/tooltips, Jade, upgrades stacking, IO modes + ghost/real filters, Redstone Upgrade on
  IO and in front of a crafter, omni provider with several crafters, fluid pattern + Importer filling, brewing stand profile,
  wireless terminals, config screen with `-PwithModMenu`, framed directional emitter).
- 26.3 API notes: GUI uses `GuiGraphicsExtractor`, `extractBackground/extractLabels/extractRenderState`, `MouseButtonEvent`,
  `KeyEvent`; `Inventory.placeItemBackInInventory(stack, Prediction)`; `Item.appendHoverText` and
  `BlockEntity.removeComponentsFromTag` are deprecated. When unsure, check Tom's Storage source (github.com/tom5454/Toms-Storage,
  branch `snapshot` = 26.3) or the merged MC jar in `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*-26.3.jar`
  (`javap`; on Windows use `cygpath -w` and `;` in the class path).

## Architecture

- `planner/CraftingPlanner` – pure Java recursive planner (virtual stock, surplus reuse, cycle/depth guard, fallback patterns). Tested by `PlannerSelfTest`.
- `job/CraftingJob` – planned job, persisted (Codec) in its owner BE; states RUNNING -> WAITING -> DONE/CANCELLED (+ priority, paused).
  Dispatched crafts are `inFlight` per step until the Importer reports the results (`TerminalCrafting.itemsArrived`, by primary output).
  Once a job got such a report it is DONE exactly when nothing is in flight; otherwise stock/idle fallback (results via plain chests).
- `network/NetworkLink` – finds Tom's `IInventoryConnector` via `InventoryCableNetwork` (like Tom's Inventory Interface) and collects Pattern Providers on the network.
- Blocks: `NodeBlock` (full block node), `DirectionalNodeBlock` (full block with FACING), `PatternProviderBlock` (NodeBlock + POWERED),
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
  It lists the jobs of all job owners on the network (`CraftingJob.uid` = runtime id for cancel/pause/priority).
- Requests go through `CraftRequestProtocol` (`tsac_plan` -> material list preview, `tsac_req` -> start); UI = `CraftRequestScreen`.
- Block/item textures are generated from Tom's textures (wood panels, `inventory_interface` frame, `item_filter` for patterns).
- The dev client often dies natively (0xC0000005, no hs_err even with `-XX:ErrorFile`, no Windows event) during the
  resource reload, also without this mod/JEI, with any graphics backend, muted audio and fewer worker threads.
  Cause unknown (machine/driver/overlay); just retry. `gradlew runClient -PnoJei` starts without JEI, `-PnoJade` without Jade, `-PwithModMenu` adds Mod Menu (off by default, the native crash seemed more frequent with it).
- Config `config/tsautocraft.json` (`TsAutocraftConfig`, Gson, read at startup, written by the Mod Menu screen
  `compat/modmenu` built by reflection over the public fields, labels `config.tsautocraft.<field>[.desc]`; Cloth Config ships
  inside Tom's jar): `crafterTrigger` = `external` (default, player's
  redstone circuit crafts; no stuck cleanup) | `direct` (scheduled block tick on the crafter) | `redstone` (2-tick pulse; the
  provider emits only in this mode, with a Redstone Upgrade or for a machine profile pulse, per side:
  `PatternProviderBlockEntity.emitsSignal(Direction)`); `enableDisabledCrafterSlots` (default false = recipe is shifted to enabled crafter slots);
  plus timings/limits (`crafter*Ticks`, `maxJobsPerTerminal`, `maxRequestAmount`, `job*Seconds`, `requester*`, `io*`,
  `craftablesSyncTicks`) - no more hard-coded constants for these, read them via `TsAutocraftConfig.get()`.
- Blocking off: crafting patterns stack more sets of the same recipe/layout into a crafter (`fitOnTop`).
- Substitutes (toggle in Pattern Terminal + Pattern Provider for JEI "+"): `PatternData.Entry.alternatives`, computed at encode
  time by testing every registry item at that grid position against the recipe (same result -> substitute, max 32).
  Planner uses the best-stocked option per position (`CraftingJob.resolvedInputs`), the provider picks per craft (`chooseInputs`).
  Crafting patterns only; processing patterns stay exact.
- Pattern Provider full block (`PatternProviderBlock extends NodeBlock`, no front): supplies every adjacent crafter/machine
  round robin (`targetSides`, never cables/`IInventoryCable`/connectors); connector variants keep their `FACING`. Cooldown and
  redstone signal are per side (`lastCrafter`, `signalTicks`): a pulse only reaches the machine that was just filled.
- Pattern Provider: crafting patterns fill `minecraft:crafter` slots 0-8 in pattern order (shifted if needed), trigger per config;
  processing patterns insert all-or-nothing, optional blocking mode. Results return via Importer.
- Pattern modes are an API: `api/PatternMode` + `api/PatternModes.register(...)` (built-ins in `pattern/BuiltinPatternModes`:
  crafting, processing, smelting, fluid). `PatternData.mode` is the mode id (old patterns: `crafting`/`smelting` flags in the codec).
  A mode encodes (`encode`, `computeOutputs`), delivers (`dispatch(DispatchContext, pattern)`, items only via `ctx.pull`)
  and claims JEI recipes / REI categories. Menu sync by registry index; the Pattern Terminal button shows `mode.icon()`.
- Job execution: `api/CraftingProvider` (Pattern Provider, Crafting Emitter) and `api/JobOwner` (Autocrafting Terminal,
  Requester, Exporter with Crafting Upgrade). `job/Reservations`: jobs reserve their raw materials (planner `fromStock`) and
  intermediates their own crafts brought back; plans, other jobs and Exporters only see stock minus reservations.
- `job/JobScheduler`: owners only `submit` themselves every `JobRunner.INTERVAL` ticks; at `END_LEVEL_TICK` all jobs of the
  level tick sorted by priority (high/normal/low, then uid) via `owner.tickJob` -> `JobRunner` (net/providers/reservations
  cached per tick). New jobs: `JobScheduler.started`. Jobs can be paused (no reservations, reason PAUSED) and prioritized
  from the Autocrafting Terminal (`pause`/`prio` messages). Every step keeps a transient reason (missing item have/need,
  provider pos/name) shown in the job tooltip (`StepView`).
- Public API package `api/` (+ `TsAutocraftApi.VERSION`, `gradlew build` also makes `-api.jar` with the data types it uses):
  `CraftingEvents` (PLAN_CREATED, JOB_STARTED, STEP_DISPATCHED, JOB_FINISHED), `Upgrades` (host -> allowed items + max count;
  `UpgradeHost`), `MachineProfiles` (datapack `data/<ns>/tsautocraft/machines/*.json`: `blocks`, `sides`, `redstone_pulse`,
  `blocking`; built-in brewing stand), `PlannerHooks` (pattern order, craftable/substitute filters; item tags
  `tsautocraft:not_autocraftable`, `tsautocraft:no_substitute`), `IOMode`/`IOModes` (Exporter/Importer: round robin,
  priority, random, blacklist [Importer]).
- Fluids: Tom's has none, so they travel as filled containers. Fluid pattern mode empties containers into the target's
  `FluidStorage`, empties go back; Importer with a filled container as filter fills empty containers from the network
  (`util/FluidUtil`).
- Item tooltips: `Item.appendHoverText` and `BlockEntity.removeComponentsFromTag` are deprecated in 26.3. Tooltips come from
  `TooltipProvider` components registered in Fabric's `ItemComponentTooltipProviderRegistry` (`PatternData`, default
  component `ItemDescription` on block items/upgrades, key `tooltip.tsautocraft.<id>`).
- Exporter/Importer filters (`IOMenu.clicked`): plain items are ghosts, Tom's filter items (`IItemFilter`) are real items
  (taken out again, dropped on break); JEI/REI `setPhantom` only places ghosts.
- Anvil names: our BEs keep `CUSTOM_NAME` (`util/CustomName`, loot tables `copy_components`), used as GUI title, Jade and
  provider name in job status; re-encoding a named pattern keeps its name.
- Wireless: our terminals are in `toms_storage:remote_activate`; `util/TerminalAccess` uses the Wireless Terminal range.
- Jade: `compat/jade` (entrypoint `jade`), BEs implement `blockentity/StatusInfo`, lines sent as components. WTHIT/EMI have
  no 26.3 build (2026-11-02).
- Upgrades (`util/UpgradeContainer`, `menu/UpgradeSlot`, panel right of the GUI via `client/GuiUtil`; same kind stacks in a
  slot up to the max from `ModContent`'s `Upgrades.register`): Capacity (terminal
  +`jobsPerCapacityUpgrade` jobs, provider +9 pattern slots up to 36), Speed (IO interval >> n, provider +1 delivery/tick),
  Stack (IO items << 2n, provider 4^n crafts per delivery up to 64), Crafting (Exporter crafts missing filter items).
  Redstone (Importer/Exporter: `RS_HIGH`/`RS_LOW`/`RS_TOGGLE`, checked every tick in `AbstractIOBlockEntity.redstoneAllows`, button in
  `IOScreen` only with the upgrade; Pattern Provider: `ctx.redstonePulse(crafterPulseTicks)` after every delivery).
- Crafting Emitter (full block, signal on all sides; directional variant = `ConnectorNodeBlock`/framed with `emitsPulse`, signal only
  out of the front; BE extends `PaintedBlockEntity`): redstone below/above an amount, or "craft" mode = pattern without inputs, signal while a job needs it.
- `CraftRequestScreen`: failed preview gets `tsac_max` (binary search `CraftingJob.maxPossible`) -> "Craft N" button.
- REI: API compile only (`me.shedaniel:RoughlyEnoughItems-api-fabric` + `cloth basic-math`), plugin `compat/rei` via
  entrypoint `rei_client`, not in the dev client (untested in game). Ghost targets shared in `client/GhostTargets`.
  EMI has no 26.3 build (2026-11-01).
- Published on Modrinth (https://modrinth.com/mod/toms-storage-autocrafting) and CurseForge
  (https://www.curseforge.com/minecraft/mc-mods/toms-storage-autocrafting).
- Stop the dev client before `gradlew build`: rebuilding under a running game swaps the classes (crash with
  `NoClassDefFoundError`, e.g. `JobScheduler$Entry`). Start it again only when the user asks.
- IntelliJ may save stale buffers over files changed on disk (happened to `ModContent.java`): if edits vanish, check the file.
- Pixel art: inner areas of the 16px textures are 8px (even), so a 3x3 grid of 1px dots cannot be centered; use 2x2 dots with
  1px gaps (crafting upgrade, provider front).
- Menus/screens sync through Tom's existing `toms_storage:data` packet (`NetworkHandler.sendTo/sendDataToServer`, menus implement `IDataReceiver`).
- Assets were generated by a Python script (pixel textures, GUI backgrounds, connector models derived from Tom's `cable_connector/*` models).
