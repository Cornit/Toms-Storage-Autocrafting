package eu.sytxlabs.tsautocraft.blockentity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.inventory.filter.ItemPredicate;
import com.tom.storagemod.item.IItemFilter;
import com.tom.storagemod.block.entity.PaintedBlockEntity;
import com.tom.storagemod.util.BlockFaceReference;
import com.tom.storagemod.util.TickerUtil.TickableServer;

import eu.sytxlabs.tsautocraft.TsAutocraftConfig;
import eu.sytxlabs.tsautocraft.block.DirectionalNodeBlock;
import eu.sytxlabs.tsautocraft.network.NetworkLink;

/**
 * Shared base for Exporter and Importer.
 * <p>
 * 9 filter slots like Tom's filters: either a ghost item (template only) or a real
 * filter item from Tom's Storage (item filter, tag filter, polymorphic filter).
 * <p>
 * Anything that fits nowhere goes into {@link #buffer} instead of being lost, and is retried later.
 */
public abstract class AbstractIOBlockEntity extends PaintedBlockEntity implements TickableServer, MenuProvider {
	public static final int SLOTS = 9;

	/** Maximum items moved per operation (config {@code ioItemsPerOperation}). */
	protected static int maxPerOp() {
		return TsAutocraftConfig.get().ioItemsPerOperation;
	}

	protected final SimpleContainer filters = new SimpleContainer(SLOTS) {
		@Override
		public void setChanged() {
			super.setChanged();
			predicatesDirty = true;
			AbstractIOBlockEntity.this.setChanged();
		}
	};
	private final ItemPredicate[] predicates = new ItemPredicate[SLOTS];
	private boolean predicatesDirty = true;

	protected final NetworkLink network = new NetworkLink();
	protected ItemStack buffer = ItemStack.EMPTY;
	protected int nextSlot;
	/** Wait after "no space" so full targets are not retried every round. */
	protected int backoff;

	protected AbstractIOBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public SimpleContainer getFilters() {
		return filters;
	}

	protected Direction facing() {
		return getBlockState().getValue(DirectionalNodeBlock.FACING);
	}

	@Override
	public void updateServer() {
		if ((level.getGameTime() + Math.abs(worldPosition.hashCode())) % TsAutocraftConfig.get().ioIntervalTicks != 0) return;
		if (backoff > 0) {
			backoff--;
			return;
		}
		if (predicatesDirty) rebuildPredicates();
		work();
	}

	protected abstract void work();

	private void rebuildPredicates() {
		predicatesDirty = false;
		for (int i = 0; i < SLOTS; i++) {
			ItemStack st = filters.getItem(i);
			if (st.isEmpty()) {
				predicates[i] = null;
			} else if (st.getItem() instanceof IItemFilter f) {
				predicates[i] = f.createFilter(BlockFaceReference.touching(level, worldPosition, facing()), st);
			} else {
				ItemStack copy = st.copyWithCount(1);
				predicates[i] = s -> ItemStack.isSameItemSameComponents(s.getStack(), copy);
			}
		}
	}

	protected ItemPredicate predicate(int i) {
		return predicates[i];
	}

	protected boolean hasAnyFilter() {
		for (ItemPredicate p : predicates) if (p != null) return true;
		return false;
	}

	protected boolean matchesAny(ItemStack stack) {
		StoredItemStack s = new StoredItemStack(stack);
		for (ItemPredicate p : predicates) {
			if (p == null) continue;
			p.updateState();
			if (p.test(s)) return true;
		}
		return false;
	}

	@Override
	public void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		var list = out.list("Filters", ItemStack.OPTIONAL_CODEC);
		for (int i = 0; i < SLOTS; i++) list.add(filters.getItem(i));
		if (!buffer.isEmpty()) out.store("Buffer", ItemStack.CODEC, buffer);
	}

	@Override
	public void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		List<ItemStack> list = in.listOrEmpty("Filters", ItemStack.OPTIONAL_CODEC).stream().toList();
		for (int i = 0; i < SLOTS; i++) filters.setItem(i, i < list.size() ? list.get(i) : ItemStack.EMPTY);
		buffer = in.read("Buffer", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
		predicatesDirty = true;
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		// Drop real filter items and the buffer, not ghost items
		for (int i = 0; i < SLOTS; i++) {
			ItemStack st = filters.getItem(i);
			if (!st.isEmpty() && st.getItem() instanceof IItemFilter)
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), st);
		}
		if (!buffer.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), buffer);
		buffer = ItemStack.EMPTY;
	}

	public boolean stillValid(Player player) {
		if (level.getBlockEntity(worldPosition) != this) return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D) <= 64.0D;
	}
}
