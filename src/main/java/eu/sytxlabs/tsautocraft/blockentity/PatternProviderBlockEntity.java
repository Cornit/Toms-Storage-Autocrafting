package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.block.IInventoryCable;
import com.tom.storagemod.block.entity.IInventoryConnector;
import com.tom.storagemod.block.entity.PaintedBlockEntity;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.util.TickerUtil.TickableServer;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig.CrafterTrigger;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.api.CraftingProvider;
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.block.ConnectorNodeBlock;
import eu.sytxlabs.tsautocraft.block.PatternProviderBlock;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.CustomName;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;
import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/**
 * Pattern Provider: holds up to 9 patterns and supplies the machine in front of it.
 * <ul>
 *   <li><b>Crafting patterns</b> (target must be a {@code minecraft:crafter}): ingredients are placed into slots 0-8 as configured in the pattern (order = crafter grid, top left to bottom right), moved to enabled
 *       slots if needed. How the crafter is triggered depends on {@code crafterTrigger} in the config (the player's own redstone circuit, a direct block tick, or a 2-tick redstone pulse from the provider).
 *       The next delivery only happens once the crafter is empty again.</li>
 *   <li><b>Processing patterns</b>: all inputs are inserted into the machine at once, but only if everything fits. In blocking mode (default) only once none of the pattern's inputs are left in the machine.</li>
 *   <li><b>Smelting patterns</b> (target must be a furnace, blast furnace or smoker): the input goes straight into the input slot, from any side. Fuel is up to the player.</li>
 *   <li>Modes of other mods ({@link eu.sytxlabs.tsautocraft.api.PatternModes}) deliver through {@link PatternMode#dispatch}.</li>
 * </ul>
 * 9 pattern slots, each Capacity Upgrade unlocks 9 more (up to {@value #SLOTS}). Speed Upgrades: one more delivery per
 * tick and a shorter crafter cooldown each; Stack Upgrades: 4 times more crafts per delivery each (up to {@value #MAX_SETS}).
 * Results do NOT return automatically: they must reach the network through an Importer (or a chest that is part
 * of the storage).
 */
public class PatternProviderBlockEntity extends PaintedBlockEntity implements TickableServer, MenuProvider, CraftingProvider, Upgrades.UpgradeHost, StatusInfo {
	private final CustomName customName = new CustomName();
	public static final int ROW = 9, MAX_CAPACITY = 3, SLOTS = ROW * (1 + MAX_CAPACITY), UPGRADE_SLOTS = 4;
	public static final int MAX_SETS = 64;
	private final UpgradeContainer upgrades = new UpgradeContainer(UPGRADE_SLOTS, Upgrades.PATTERN_PROVIDER, () -> {
		this.patternCache = null;
		this.setChanged();
	});

	private final SimpleContainer patterns = new SimpleContainer(SLOTS) {
		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return stack.getItem() instanceof PatternItem;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}

		@Override
		public void setChanged() {
			super.setChanged();
			patternCache = null;
			PatternProviderBlockEntity.this.setChanged();
		}
	};
	private List<PatternData> patternCache;
	private boolean blocking = true;
	private boolean substitutes;
	private int pulse;
	/** Redstone signal ticks left per side (index = direction from the provider to the machine). */
	private final int[] signalTicks = new int[6];
	/** Last crafter delivery per side (each crafter has its own cooldown). */
	private final long[] lastCrafter = new long[6];
	/** Full block: side to try first next time (round robin over the machines around it). */
	private int nextSide;
	private long lastDispatch = Long.MIN_VALUE / 2;
	private int dispatchesThisTick;
	private final List<ItemStack> buffer = new ArrayList<>();
	private final NetworkLink network = new NetworkLink();

	public PatternProviderBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.PATTERN_PROVIDER_BE, pos, state);
	}

	public SimpleContainer getPatternInventory() {
		return patterns;
	}

	public UpgradeContainer getUpgrades() {
		return upgrades;
	}

	@Override
	public Identifier upgradeHost() {
		return Upgrades.PATTERN_PROVIDER;
	}

	@Override
	public int upgradeCount(net.minecraft.world.item.Item item) {
		return upgrades.count(item);
	}

	/** Usable pattern slots: one row plus one per Capacity Upgrade. */
	public static int activeSlots(net.minecraft.world.Container upgrades) {
		return ROW * (1 + Math.min(MAX_CAPACITY, UpgradeContainer.count(upgrades, ModContent.CAPACITY_UPGRADE)));
	}

	private int speed() {
		return upgrades.count(ModContent.SPEED_UPGRADE);
	}

	/** Crafts per delivery: 4 times more per Stack Upgrade. */
	private int sets(long wanted) {
		return (int) Math.clamp(wanted, 1, Math.min(MAX_SETS, 1L << (2 * upgrades.count(ModContent.STACK_UPGRADE))));
	}

	@Override
	public List<PatternData> getPatterns() {
		if (patternCache == null) {
			List<PatternData> l = new ArrayList<>();
			int active = activeSlots(upgrades);
			for (int i = 0; i < active; i++) {
				PatternData d = PatternItem.get(patterns.getItem(i));
				if (d != null) l.add(d);
			}
			patternCache = Collections.unmodifiableList(l);
		}
		return patternCache;
	}

	public boolean isBlocking() {
		return blocking;
	}

	public void setBlocking(boolean blocking) {
		this.blocking = blocking;
		setChanged();
	}

	/** Patterns encoded here with JEI's "+" get substitutes. */
	public boolean isSubstitutes() {
		return substitutes;
	}

	public void setSubstitutes(boolean substitutes) {
		this.substitutes = substitutes;
		setChanged();
	}

	/**
	 * Sides to deliver to: the front of the connector variant, or (full block) every side with a machine, starting after
	 * the last side that got something. Cables and other nodes of Tom's network are never targets.
	 */
	private List<Direction> targetSides() {
		BlockState st = getBlockState();
		if (st.hasProperty(ConnectorNodeBlock.FACING)) return List.of(st.getValue(ConnectorNodeBlock.FACING));
		List<Direction> l = new ArrayList<>(6);
		for (int i = 0; i < 6; i++) {
			Direction d = Direction.from3DDataValue((nextSide + i) % 6);
			if (isTarget(d)) l.add(d);
		}
		return l;
	}

	private boolean isTarget(Direction d) {
		BlockPos p = worldPosition.relative(d);
		BlockState s = level.getBlockState(p);
		if (s.isAir() || s.getBlock() instanceof IInventoryCable) return false;
		return !(level.getBlockEntity(p) instanceof IInventoryConnector);
	}

	@Override
	public void updateServer() {
		if (level == null) return;
		if (pulse > 0 && --pulse == 0) setPowered(false);
		boolean signalOff = false;
		for (int i = 0; i < 6; i++) if (signalTicks[i] > 0 && --signalTicks[i] == 0) signalOff = true;
		if (signalOff) level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
		if (!buffer.isEmpty() && level.getGameTime() % 20 == 0) {
			flushBuffer(network.access(level, worldPosition));
		}
	}

	/**
	 * Tries to deliver the inputs for up to {@code wanted} crafts (one, or more with Stack Upgrades) through the
	 * pattern's {@link PatternMode}. One delivery per tick, plus one per Speed Upgrade.
	 */
	@Override
	public Dispatch dispatch(PatternData p, IInventoryAccess net, ToLongFunction<ItemKey> available, long wanted) {
		if (level == null) return Dispatch.fail(DispatchResult.NO_TARGET);
		long now = level.getGameTime();
		if (now == lastDispatch && dispatchesThisTick > speed()) return Dispatch.fail(DispatchResult.BUSY);
		if (net == PlatformInventoryAccess.EMPTY) return Dispatch.fail(DispatchResult.MISSING);
		if (!buffer.isEmpty()) {
			flushBuffer(net);
			if (!buffer.isEmpty()) return Dispatch.fail(DispatchResult.BUSY);
		}
		Context ctx = null;
		PatternMode.Outcome r = PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		DispatchResult fail = DispatchResult.NO_TARGET;
		for (Direction side : targetSides()) {
			ctx = new Context(net, available, sets(wanted), side);
			try {
				r = p.modeType().dispatch(ctx, p);
			} catch (StorageUtil.LeftoverException e) {
				buffer.add(e.leftover());
				setChanged();
				return Dispatch.fail(DispatchResult.BUSY);
			}
			if (r.result() == DispatchResult.OK && r.crafts() > 0) {
				nextSide = (side.get3DDataValue() + 1) % 6;
				break;
			}
			// Report the most useful reason (a busy machine beats "no machine" on another side)
			if (fail == DispatchResult.NO_TARGET) fail = r.result() == DispatchResult.OK ? DispatchResult.BUSY : r.result();
		}
		if (ctx == null || r.result() != DispatchResult.OK || r.crafts() <= 0) return Dispatch.fail(fail);
		dispatchesThisTick = lastDispatch == now ? dispatchesThisTick + 1 : 1;
		lastDispatch = now;
		setChanged();
		Map<ItemKey, Long> consumed = new java.util.HashMap<>();
		for (ItemStack st : ctx.pulled) if (!st.isEmpty()) consumed.merge(new ItemKey(st), (long) st.getCount(), Long::sum);
		// Redstone Upgrade: a pulse to the machine after every delivery (also starts a crafter in any trigger mode)
		if (upgrades.count(ModContent.REDSTONE_UPGRADE) > 0) ctx.redstonePulse(TsAutocraftConfig.get().crafterPulseTicks);
		return new Dispatch(DispatchResult.OK, r.crafts(), consumed);
	}

	/** What the pattern modes get for one delivery. */
	private final class Context implements PatternMode.DispatchContext {
		private final IInventoryAccess net;
		private final ToLongFunction<ItemKey> available;
		private final int sets;
		private final Direction side;
		final List<ItemStack> pulled = new ArrayList<>();

		Context(IInventoryAccess net, ToLongFunction<ItemKey> available, int sets, Direction side) {
			this.net = net;
			this.available = available;
			this.sets = sets;
			this.side = side;
		}

		@Override
		public Level level() {
			return level;
		}

		@Override
		public BlockPos providerPos() {
			return worldPosition;
		}

		@Override
		public Direction facing() {
			return side;
		}

		@Override
		public IInventoryAccess network() {
			return net;
		}

		@Override
		public ToLongFunction<ItemKey> available() {
			return available;
		}

		@Override
		public int maxCrafts() {
			return sets;
		}

		@Override
		public boolean blocking() {
			return blocking;
		}

		@Override
		public int speed() {
			return PatternProviderBlockEntity.this.speed();
		}

		@Override
		public List<ItemStack> pull(List<ItemStack> wanted) {
			Map<ItemKey, Long> totals = new java.util.HashMap<>();
			for (ItemStack w : wanted) if (!w.isEmpty()) totals.merge(new ItemKey(w), (long) w.getCount(), Long::sum);
			for (var e : totals.entrySet()) if (available.applyAsLong(e.getKey()) < e.getValue()) return null;
			List<ItemStack> got = StorageUtil.pullAll(net, level, wanted);
			if (got != null) pulled.addAll(got);
			return got;
		}

		@Override
		public void giveBack(ItemStack stack) {
			ItemStack rest = net.pushStack(stack);
			if (!rest.isEmpty()) buffer.add(rest);
		}

		@Override
		public Object provider() {
			return PatternProviderBlockEntity.this;
		}

		@Override
		public void redstonePulse(int ticks) {
			if (ticks <= 0 || level == null) return;
			signal(side, ticks);
		}
	}

	/** Built-in crafting mode: fills a {@code minecraft:crafter} (see class doc). */
	public PatternMode.Outcome dispatchCrafter(PatternMode.DispatchContext ctx, PatternData p) {
		if (level == null) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		long now = level.getGameTime();
		Direction side = ctx.facing();
		BlockPos tp = ctx.targetPos();
		if (!(level.getBlockEntity(tp) instanceof CrafterBlockEntity crafter)) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		CrafterTrigger trigger = TsAutocraftConfig.get().crafterTrigger;
		boolean external = trigger == CrafterTrigger.EXTERNAL;
		if (signalTicks[side.ordinal()] > 0 || now - lastCrafter[side.ordinal()] < Math.max(1, TsAutocraftConfig.get().crafterCooldownTicks >> speed())) {
			return PatternMode.Outcome.fail(DispatchResult.BUSY);
		}

		BlockState cs = level.getBlockState(tp);
		if (!external && cs.hasProperty(CrafterBlock.TRIGGERED) && cs.getValue(CrafterBlock.TRIGGERED)) return PatternMode.Outcome.fail(DispatchResult.BUSY);

		int[] target = crafterSlots(p.inputs(), crafter);
		if (!crafter.isEmpty()) {
			Fit fit = blocking || target == null ? Fit.FOREIGN : fitOnTop(crafter, p.inputs(), target);
			if (fit != Fit.ROOM) {
				if (fit == Fit.FOREIGN && !external && now - lastCrafter[side.ordinal()] > TsAutocraftConfig.get().crafterStuckTicks) returnCrafterContents(crafter, ctx.network(), side);
				return PatternMode.Outcome.fail(DispatchResult.BUSY);
			}
		}
		if (target == null) return PatternMode.Outcome.fail(DispatchResult.SLOT_DISABLED);
		int crafts = 0;
		for (int set = 0; set < (blocking ? 1 : ctx.maxCrafts()); set++) {
			if (set > 0 && fitOnTop(crafter, p.inputs(), target) != Fit.ROOM) break;
			List<ItemStack> wanted = chooseInputs(ctx.available(), p.inputs(), crafter, target);
			if (wanted == null) break;
			List<ItemStack> got = ctx.pull(wanted);
			if (got == null) break;
			for (int i = 0; i < 9; i++) {
				ItemStack add = got.get(i);
				if (add.isEmpty()) continue;
				ItemStack cur = crafter.getItem(target[i]);
				crafter.setItem(target[i], cur.isEmpty() ? add : cur.copyWithCount(cur.getCount() + add.getCount()));
			}
			crafts++;
		}
		if (crafts == 0) return PatternMode.Outcome.fail(DispatchResult.MISSING);
		crafter.setChanged();
		lastCrafter[side.ordinal()] = now;
		int ticks = TsAutocraftConfig.get().crafterPulseTicks;
		// Lights the provider; a redstone signal to THIS crafter only in REDSTONE mode
		if (trigger == CrafterTrigger.REDSTONE) signal(side, ticks);
		else light(ticks);
		if (trigger == CrafterTrigger.DIRECT) level.scheduleTick(tp, cs.getBlock(), 1); // CrafterBlock.tick crafts once
		return PatternMode.Outcome.ok(crafts);
	}

	/**
	 * One concrete item per crafter position: the pattern's item or, if the pattern has substitutes, the first substitute that is in stock. A position that already holds items (blocking off) keeps that exact item.
	 * Returns null if some position cannot be served.
	 */
	private List<ItemStack> chooseInputs(ToLongFunction<ItemKey> available, List<PatternData.Entry> in, CrafterBlockEntity crafter, int[] target) {
		java.util.Map<ItemKey, Long> used = new java.util.HashMap<>();
		List<ItemStack> wanted = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) {
			PatternData.Entry e = i < in.size() ? in.get(i) : PatternData.Entry.EMPTY;
			if (e.isEmpty()) {
				wanted.add(ItemStack.EMPTY);
				continue;
			}
			ItemStack cur = crafter.getItem(target[i]);
			List<ItemStack> options = cur.isEmpty() ? e.options() : List.of(cur);
			ItemStack chosen = null;
			for (ItemStack o : options) {
				ItemKey k = new ItemKey(o);
				long need = used.getOrDefault(k, 0L) + e.count();
				if (available.applyAsLong(k) >= need) {
					used.put(k, need);
					chosen = o.copyWithCount(e.count());
					break;
				}
			}
			if (chosen == null) return null;
			wanted.add(chosen);
		}
		return wanted;
	}

	private enum Fit { FOREIGN, FULL, ROOM }

	/** Is the crafter filled with this recipe in this layout (FULL/ROOM), or with something else (FOREIGN)? */
	private static Fit fitOnTop(CrafterBlockEntity crafter, List<PatternData.Entry> in, int[] target) {
		PatternData.Entry[] expected = new PatternData.Entry[9];
		java.util.Arrays.fill(expected, PatternData.Entry.EMPTY);
		for (int i = 0; i < 9 && i < in.size(); i++) {
			if (!in.get(i).isEmpty()) expected[target[i]] = in.get(i);
		}
		boolean room = true;
		for (int s = 0; s < 9; s++) {
			ItemStack cur = crafter.getItem(s);
			if (expected[s].isEmpty()) {
				if (!cur.isEmpty()) return Fit.FOREIGN;
			} else if (!cur.isEmpty()) {
				if (!expected[s].accepts(cur)) return Fit.FOREIGN; // a substitute counts as the same recipe
				if (cur.getCount() + 1 > cur.getMaxStackSize()) room = false;
			}
		}
		return room ? Fit.ROOM : Fit.FULL;
	}

	/**
	 * Crafter slot for each of the 9 pattern positions. With {@code enableDisabledCrafterSlots} the pattern positions
	 * are used as they are (the crafter re-enables slots it gets items in). Otherwise the recipe is moved inside the
	 * 3x3 grid (crafting recipes match anywhere in the grid) so that it only uses enabled slots; null if impossible.
	 */
	private static int[] crafterSlots(List<PatternData.Entry> in, CrafterBlockEntity crafter) {
		int minX = 3, minY = 3, maxX = -1, maxY = -1;
		for (int i = 0; i < 9 && i < in.size(); i++) {
			if (in.get(i).isEmpty()) continue;
			minX = Math.min(minX, i % 3);
			maxX = Math.max(maxX, i % 3);
			minY = Math.min(minY, i / 3);
			maxY = Math.max(maxY, i / 3);
		}
		int[] identity = {0, 1, 2, 3, 4, 5, 6, 7, 8};
		if (maxX < 0 || TsAutocraftConfig.get().enableDisabledCrafterSlots) return identity;
		List<int[]> offsets = new ArrayList<>();
		offsets.add(new int[] {0, 0});
		for (int dy = -minY; dy <= 2 - maxY; dy++) {
			for (int dx = -minX; dx <= 2 - maxX; dx++) {
				if (dx != 0 || dy != 0) offsets.add(new int[] {dx, dy});
			}
		}
		for (int[] o : offsets) {
			int[] map = new int[9];
			boolean ok = true;
			for (int i = 0; i < 9; i++) {
				int x = i % 3 + o[0], y = i / 3 + o[1];
				boolean used = i < in.size() && !in.get(i).isEmpty();
				if (!used) {
					map[i] = i; // empty position: nothing is inserted
					continue;
				}
				int slot = x + y * 3;
				if (crafter.isSlotDisabled(slot)) {
					ok = false;
					break;
				}
				map[i] = slot;
			}
			if (ok) return map;
		}
		return null;
	}

	private void returnCrafterContents(CrafterBlockEntity crafter, IInventoryAccess net, Direction side) {
		for (int i = 0; i < crafter.getContainerSize(); i++) {
			ItemStack st = crafter.getItem(i);
			if (st.isEmpty()) continue;
			ItemStack rest = net.pushStack(st.copy());
			crafter.setItem(i, rest);
		}
		crafter.setChanged();
		if (level == null) return;
		lastCrafter[side.ordinal()] = level.getGameTime();
	}

	/** Smelting: the single input goes straight into the furnace's input slot. */
	private void flushBuffer(IInventoryAccess net) {
		if (net == PlatformInventoryAccess.EMPTY) return;
		List<ItemStack> keep = new ArrayList<>();
		for (ItemStack st : buffer) {
			ItemStack rest = net.pushStack(st);
			if (!rest.isEmpty()) keep.add(rest);
		}
		buffer.clear();
		buffer.addAll(keep);
		setChanged();
	}

	/** Redstone signal from the provider to the machine on {@code toward} ("redstone" trigger mode, Redstone Upgrade, machine profile). */
	public boolean emitsSignal(Direction toward) {
		return signalTicks[toward.ordinal()] > 0;
	}

	/** Lights the provider for {@code ticks} ticks. */
	private void light(int ticks) {
		pulse = Math.max(pulse, ticks);
		setPowered(true);
	}

	/** Redstone pulse of {@code ticks} ticks to the machine on {@code toward} only (the one that just got its items). */
	private void signal(Direction toward, int ticks) {
		signalTicks[toward.ordinal()] = Math.max(signalTicks[toward.ordinal()], ticks);
		light(ticks);
		// The lit state may not change (already lit): tell the neighbors that the signal is on
		level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
	}

	private void setPowered(boolean powered) {
		BlockState st = getBlockState();
		if (st.hasProperty(PatternProviderBlock.POWERED) && st.getValue(PatternProviderBlock.POWERED) != powered && level != null) {
			level.setBlock(worldPosition, st.setValue(PatternProviderBlock.POWERED, powered), 3);
		}
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		customName.save(out);
		var list = out.list("Patterns", ItemStack.OPTIONAL_CODEC);
		for (int i = 0; i < SLOTS; i++) list.add(patterns.getItem(i));
		upgrades.save(out, "Upgrades");
		var buf = out.list("Buffer", ItemStack.CODEC);
		buffer.forEach(buf::add);
		out.putBoolean("Blocking", blocking);
		out.putBoolean("Substitutes", substitutes);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		customName.load(in);
		List<ItemStack> list = in.listOrEmpty("Patterns", ItemStack.OPTIONAL_CODEC).stream().toList();
		for (int i = 0; i < SLOTS; i++) patterns.setItem(i, i < list.size() ? list.get(i) : ItemStack.EMPTY);
		upgrades.load(in, "Upgrades");
		buffer.clear();
		in.listOrEmpty("Buffer", ItemStack.CODEC).stream().forEach(buffer::add);
		blocking = in.getBooleanOr("Blocking", true);
		substitutes = in.getBooleanOr("Substitutes", false);
		patternCache = null;
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		if (level == null) return;
		Containers.dropContents(level, pos, patterns);
		Containers.dropContents(level, pos, upgrades);
		for (ItemStack st : buffer) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), st);
		buffer.clear();
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new PatternProviderMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return customName.orElse(Component.translatable("block.tsautocraft.pattern_provider"));
	}

	public boolean stillValid(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
	}

	//region Anvil name

	/** Anvil name of the placed item, null without one. */
	public Component customName() {
		return customName.get();
	}

	@Override
	public Component providerName() {
		return customName.get();
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		customName.apply(components);
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder builder) {
		super.collectImplicitComponents(builder);
		customName.collect(builder);
	}
	//endregion

	@Override
	public void appendStatus(Consumer<Component> lines) {
		lines.accept(Component.translatable("jade.tsautocraft.patterns", getPatterns().size(), activeSlots(upgrades)));
		lines.accept(Component.translatable("jade.tsautocraft.blocking", Component.translatable(blocking ? "jade.tsautocraft.on" : "jade.tsautocraft.off")));
		if (level == null) return;
		List<Direction> sides = targetSides();
		if (sides.isEmpty()) {
			lines.accept(Component.translatable("jade.tsautocraft.no_target"));
		} else {
			MutableComponent names = Component.empty();
			for (int i = 0; i < sides.size(); i++) {
				if (i > 0) names.append(", ");
				names.append(level.getBlockState(worldPosition.relative(sides.get(i))).getBlock().getName());
			}
			lines.accept(Component.translatable("jade.tsautocraft.target", names));
		}
		if (!buffer.isEmpty()) lines.accept(Component.translatable("jade.tsautocraft.buffered", buffer.stream().mapToInt(ItemStack::getCount).sum()));
		if (!network.isConnected(level, worldPosition)) lines.accept(Component.translatable("reason.tsautocraft.no_network"));
	}
}
