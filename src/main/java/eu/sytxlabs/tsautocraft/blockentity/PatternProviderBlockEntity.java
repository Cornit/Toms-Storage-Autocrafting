package eu.sytxlabs.tsautocraft.blockentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.block.entity.PaintedBlockEntity;
import com.tom.storagemod.util.TickerUtil.TickableServer;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.TsAutocraftConfig.CrafterTrigger;
import eu.sytxlabs.tsautocraft.block.DirectionalNodeBlock;
import eu.sytxlabs.tsautocraft.block.PatternProviderBlock;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;
import eu.sytxlabs.tsautocraft.network.NetworkLink;
import eu.sytxlabs.tsautocraft.pattern.PatternData;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * Pattern Provider: holds up to 9 patterns and supplies the machine in front of it.
 * <ul>
 *   <li><b>Crafting patterns</b> (target must be a {@code minecraft:crafter}): ingredients are placed into
 *       slots 0-8 as configured in the pattern (order = crafter grid, top left to bottom right), moved to enabled
 *       slots if needed. How the crafter is triggered depends on {@code crafterTrigger} in the config (the player's
 *       own redstone circuit, a direct block tick, or a 2-tick redstone pulse from the provider).
 *       The next delivery only happens once the crafter is empty again.</li>
 *   <li><b>Processing patterns</b>: all inputs are inserted into the machine at once, but only if everything fits.
 *       In blocking mode (default) only once none of the pattern's inputs are left in the machine.</li>
 * </ul>
 * Results do NOT return automatically: they must reach the network through an Importer (or a chest that is part
 * of the storage).
 */
public class PatternProviderBlockEntity extends PaintedBlockEntity implements TickableServer, MenuProvider {
	public static final int SLOTS = 9;
	// Pulse length, delivery cooldown and stuck timeout come from TsAutocraftConfig (crafter*Ticks)

	public enum DispatchResult {
		OK, BUSY, NO_TARGET, MISSING, NO_SPACE, SLOT_DISABLED
	}

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
	private long lastDispatch = Long.MIN_VALUE / 2;
	private final List<ItemStack> buffer = new ArrayList<>();
	private final NetworkLink network = new NetworkLink();

	public PatternProviderBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.PATTERN_PROVIDER_BE, pos, state);
	}

	public SimpleContainer getPatternInventory() {
		return patterns;
	}

	public List<PatternData> getPatterns() {
		if (patternCache == null) {
			List<PatternData> l = new ArrayList<>();
			for (int i = 0; i < SLOTS; i++) {
				PatternData d = PatternItem.get(patterns.getItem(i));
				if (d != null) l.add(d);
			}
			patternCache = Collections.unmodifiableList(l);
		}
		return patternCache;
	}

	public boolean hasPattern(PatternData p) {
		return getPatterns().contains(p);
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

	private Direction facing() {
		return getBlockState().getValue(DirectionalNodeBlock.FACING);
	}

	@Override
	public void updateServer() {
		if (pulse > 0 && --pulse == 0) setPowered(false);
		if (!buffer.isEmpty() && level.getGameTime() % 20 == 0) {
			flushBuffer(network.access(level, worldPosition));
		}
	}

	/**
	 * Tries to trigger exactly one operation of the pattern. At most one per tick and provider.
	 */
	public DispatchResult dispatch(PatternData p, IInventoryAccess net) {
		long now = level.getGameTime();
		if (now == lastDispatch) return DispatchResult.BUSY;
		if (net == PlatformInventoryAccess.EMPTY) return DispatchResult.MISSING;
		if (!buffer.isEmpty()) {
			flushBuffer(net);
			if (!buffer.isEmpty()) return DispatchResult.BUSY;
		}
		try {
			return p.crafting() ? dispatchCrafter(p, net, now) : dispatchProcessing(p, net, now);
		} catch (StorageUtil.LeftoverException e) {
			buffer.add(e.leftover());
			setChanged();
			return DispatchResult.BUSY;
		}
	}

	private DispatchResult dispatchCrafter(PatternData p, IInventoryAccess net, long now) {
		BlockPos tp = worldPosition.relative(facing());
		if (!(level.getBlockEntity(tp) instanceof CrafterBlockEntity crafter)) return DispatchResult.NO_TARGET;
		CrafterTrigger trigger = TsAutocraftConfig.get().crafterTrigger;
		boolean external = trigger == CrafterTrigger.EXTERNAL;
		if (pulse > 0 || now - lastDispatch < TsAutocraftConfig.get().crafterCooldownTicks) return DispatchResult.BUSY;

		BlockState cs = level.getBlockState(tp);
		// External circuit: a crafter that is still powered simply crafts on the next rising edge
		if (!external && cs.hasProperty(CrafterBlock.TRIGGERED) && cs.getValue(CrafterBlock.TRIGGERED)) return DispatchResult.BUSY;

		int[] target = crafterSlots(p.inputs(), crafter);
		if (!crafter.isEmpty()) {
			// Blocking off: another set may go on top of the same recipe until a stack is full
			Fit fit = blocking || target == null ? Fit.FOREIGN : fitOnTop(crafter, p.inputs(), target);
			if (fit != Fit.ROOM) {
				// Result not crafted (invalid recipe, output blocked, foreign items)? Clean up after a while.
				// Not with an external circuit (it may simply be slower than us) and not when it is just full.
				if (fit == Fit.FOREIGN && !external && now - lastDispatch > TsAutocraftConfig.get().crafterStuckTicks) returnCrafterContents(crafter, net);
				return DispatchResult.BUSY;
			}
		}
		if (target == null) return DispatchResult.SLOT_DISABLED;

		List<ItemStack> wanted = chooseInputs(net, p.inputs(), crafter, target);
		if (wanted == null) return DispatchResult.MISSING;
		List<ItemStack> got = StorageUtil.pullAll(net, level, wanted);
		if (got == null) return DispatchResult.MISSING;

		for (int i = 0; i < 9; i++) {
			ItemStack add = got.get(i);
			if (add.isEmpty()) continue;
			ItemStack cur = crafter.getItem(target[i]);
			crafter.setItem(target[i], cur.isEmpty() ? add : cur.copyWithCount(cur.getCount() + add.getCount()));
		}
		crafter.setChanged();
		setPowered(true); // lights the front; a redstone signal only in REDSTONE mode
		pulse = TsAutocraftConfig.get().crafterPulseTicks;
		lastDispatch = now;
		if (trigger == CrafterTrigger.DIRECT) level.scheduleTick(tp, cs.getBlock(), 1); // CrafterBlock.tick crafts once
		return DispatchResult.OK;
	}

	/**
	 * One concrete item per crafter position: the pattern's item or, if the pattern has substitutes, the first
	 * substitute that is in stock. A position that already holds items (blocking off) keeps that exact item.
	 * Returns null if some position cannot be served.
	 */
	private List<ItemStack> chooseInputs(IInventoryAccess net, List<PatternData.Entry> in, CrafterBlockEntity crafter, int[] target) {
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
				if (StorageUtil.count(net, level, k) >= need) {
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
		// Try the pattern's own position first, then every other position the recipe fits in
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

	private void returnCrafterContents(CrafterBlockEntity crafter, IInventoryAccess net) {
		for (int i = 0; i < crafter.getContainerSize(); i++) {
			ItemStack st = crafter.getItem(i);
			if (st.isEmpty()) continue;
			ItemStack rest = net.pushStack(st.copy());
			crafter.setItem(i, rest);
		}
		crafter.setChanged();
		lastDispatch = level.getGameTime();
	}

	private DispatchResult dispatchProcessing(PatternData p, IInventoryAccess net, long now) {
		Storage<ItemVariant> target = StorageUtil.facingStorage(level, worldPosition, facing());
		if (target == null) return DispatchResult.NO_TARGET;
		if (blocking && containsAnyInput(target, p)) return DispatchResult.BUSY;

		List<ItemStack> in = p.inputStacks();
		if (!StorageUtil.canInsertAll(target, in)) return DispatchResult.NO_SPACE;

		List<ItemStack> got = StorageUtil.pullAll(net, level, in);
		if (got == null) return DispatchResult.MISSING;

		for (ItemStack st : got) {
			ItemStack rest = StorageUtil.insert(target, st);
			if (!rest.isEmpty()) {
				rest = net.pushStack(rest);
				if (!rest.isEmpty()) buffer.add(rest);
			}
		}
		lastDispatch = now;
		setChanged();
		return DispatchResult.OK;
	}

	private static boolean containsAnyInput(Storage<ItemVariant> target, PatternData p) {
		Set<ItemKey> keys = new HashSet<>(p.inputTotals().keySet());
		for (StorageView<ItemVariant> view : target) {
			if (view.isResourceBlank() || view.getAmount() <= 0) continue;
			if (keys.contains(new ItemKey(view.getResource().toStack(1)))) return true;
		}
		return false;
	}

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

	private void setPowered(boolean powered) {
		BlockState st = getBlockState();
		if (st.hasProperty(PatternProviderBlock.POWERED) && st.getValue(PatternProviderBlock.POWERED) != powered) {
			level.setBlock(worldPosition, st.setValue(PatternProviderBlock.POWERED, powered), 3);
		}
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		var list = out.list("Patterns", ItemStack.OPTIONAL_CODEC);
		for (int i = 0; i < SLOTS; i++) list.add(patterns.getItem(i));
		var buf = out.list("Buffer", ItemStack.CODEC);
		buffer.forEach(buf::add);
		out.putBoolean("Blocking", blocking);
		out.putBoolean("Substitutes", substitutes);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		List<ItemStack> list = in.listOrEmpty("Patterns", ItemStack.OPTIONAL_CODEC).stream().toList();
		for (int i = 0; i < SLOTS; i++) patterns.setItem(i, i < list.size() ? list.get(i) : ItemStack.EMPTY);
		buffer.clear();
		in.listOrEmpty("Buffer", ItemStack.CODEC).stream().forEach(buffer::add);
		blocking = in.getBooleanOr("Blocking", true);
		substitutes = in.getBooleanOr("Substitutes", false);
		patternCache = null;
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		Containers.dropContents(level, pos, patterns);
		for (ItemStack st : buffer) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), st);
		buffer.clear();
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new PatternProviderMenu(id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.tsautocraft.pattern_provider");
	}

	public boolean stillValid(Player player) {
		if (level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
	}
}
