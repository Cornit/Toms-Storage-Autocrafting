package eu.sytxlabs.tsautocraft.blockentity;

import eu.sytxlabs.tsautocraft.transfer.FluidStorage;
import eu.sytxlabs.tsautocraft.transfer.FluidVariant;
import eu.sytxlabs.tsautocraft.transfer.ItemVariant;
import eu.sytxlabs.tsautocraft.transfer.Storage;
import eu.sytxlabs.tsautocraft.transfer.StorageView;
import eu.sytxlabs.tsautocraft.transfer.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.item.IItemFilter;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.api.IOMode;
import eu.sytxlabs.tsautocraft.api.IOModes;
import eu.sytxlabs.tsautocraft.api.Upgrades;
import eu.sytxlabs.tsautocraft.menu.IOMenu;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
import eu.sytxlabs.tsautocraft.util.FluidUtil;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * Importer: pulls items from the inventory in front of it into the network. Without filters everything is imported,
 * with filters only matching items. Typical use: emptying the output chest behind a crafter/machine.
 */
public class ImporterBlockEntity extends AbstractIOBlockEntity {

	public ImporterBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.IMPORTER_BE, pos, state);
	}

	@Override
	public Identifier upgradeHost() {
		return Upgrades.IMPORTER;
	}

	@Override
	protected IOMode defaultMode() {
		return IOModes.PRIORITY;
	}

	@Override
	protected boolean work() {
		IInventoryAccess net = network.access(level, worldPosition);
		if (net == PlatformInventoryAccess.EMPTY) return false;
		Storage<ItemVariant> source = StorageUtil.facingStorage(level, worldPosition, facing());

		if (!buffer.isEmpty()) {
			ItemStack before = buffer.copy();
			buffer = StorageUtil.push(net, buffer);
			TerminalCrafting.itemsArrived(level, worldPosition, before, before.getCount() - buffer.getCount());
			if (!buffer.isEmpty() && source != null) buffer = StorageUtil.insert(source, buffer);
			if (buffer.getCount() != before.getCount()) setChanged();
			if (!buffer.isEmpty()) {
				backoff = 4;
				return true;
			}
		}
		if (importFluid(net)) return true;
		if (source == null || level == null) return false;
		if (level.getBlockEntity(worldPosition.relative(facing())) instanceof BrewingStandBlockEntity stand && !stand.getItem(3).isEmpty()) return false;

		IOMode mode = getMode();
		boolean filtered = hasAnyFilter();
		int size = 0;
		for (StorageView<ItemVariant> _ : source) size++;
		int start = mode.start(modeContext, size);
		ItemVariant chosen = null, wrapped = null;
		long amount = 0, wrappedAmount = 0;
		int index = -1, chosenIndex = -1, wrappedIndex = -1;
		for (StorageView<ItemVariant> view : source) {
			index++;
			if (view.isResourceBlank() || view.getAmount() <= 0) continue;
			ItemVariant v = view.getResource();
			ItemStack probe = v.toStack(1);
			if (filtered && matchesAny(probe) == mode.blacklist()) continue;
			long n = Math.min(view.getAmount(), Math.min(maxPerOp(), probe.getMaxStackSize()));
			if (index >= start) {
				chosen = v;
				amount = n;
				chosenIndex = index;
				break;
			}
			if (wrapped == null) {
				wrapped = v;
				wrappedAmount = n;
				wrappedIndex = index;
			}
		}
		if (chosen == null) {
			chosen = wrapped;
			amount = wrappedAmount;
			chosenIndex = wrappedIndex;
		}
		if (chosen == null) return false;

		long extracted;
		try (Transaction t = Transaction.openOuter()) {
			extracted = source.extract(chosen, amount, t);
			t.commit();
		}
		if (extracted <= 0) return false;
		mode.moved(modeContext, chosenIndex);

		ItemStack rest = StorageUtil.push(net, chosen.toStack((int) extracted));
		TerminalCrafting.itemsArrived(level, worldPosition, chosen.toStack(1), extracted - rest.getCount());
		if (!rest.isEmpty()) {
			rest = StorageUtil.insert(source, rest);
			buffer = rest;
			backoff = 8;
		}
		setChanged();
		return true;
	}

	private final ItemStack[] fluidKey = new ItemStack[SLOTS];
	private final FluidUtil.Content[] fluidContent = new FluidUtil.Content[SLOTS];

	/** Fluid content of filter {@code i}, cached until the filter changes (an item API lookup per filter and operation otherwise). */
	private FluidUtil.Content fluidContent(int i, ItemStack f) {
		if (fluidKey[i] == null || !ItemStack.isSameItemSameComponents(fluidKey[i], f)) {
			fluidKey[i] = f.copyWithCount(1);
			fluidContent[i] = FluidUtil.content(fluidKey[i].copy());
		}
		return fluidContent[i];
	}

	/** Fluids: a filter that is a filled container (e.g. lava bucket) takes a matching empty container from the network, fills it from the machine's fluid storage and stores the filled one. Not with blacklist modes. */
	private boolean importFluid(IInventoryAccess net) {
		if (getMode().blacklist()) return false;
		Storage<FluidVariant> tank = null;
		for (int i = 0; i < SLOTS; i++) {
			ItemStack f = filters.getItem(i);
			if (f.isEmpty() || f.getItem() instanceof IItemFilter) continue;
			FluidUtil.Content c = fluidContent(i, f);
			if (c == null || c.empty().isEmpty() || level == null) continue;
			if (tank == null) tank = FluidStorage.SIDED.find(level, worldPosition.relative(facing()), facing().getOpposite());
			if (tank == null) return false;
			try (Transaction t = Transaction.openOuter()) {
				if (tank.extract(c.fluid(), c.amount(), t) < c.amount()) continue; // not enough yet
			}
			ItemStack empty = StorageUtil.pull(net, c.empty(), 1);
			if (empty.isEmpty()) continue;
			ItemStack filled = FluidUtil.fill(empty, c.fluid(), f.copyWithCount(1), tank);
			if (filled == null) {
				buffer = StorageUtil.push(net, empty);
				if (!buffer.isEmpty()) return true; // the buffer holds one stack only: stop here
				continue;
			}
			ItemStack rest = StorageUtil.push(net, filled.copy());
			TerminalCrafting.itemsArrived(level, worldPosition, filled, filled.getCount() - rest.getCount());
			if (!rest.isEmpty()) buffer = rest;
			setChanged();
			return true;
		}
		return false;
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new IOMenu(ModContent.IMPORTER_MENU, id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return customName.orElse(Component.translatable("block.tsautocraft.importer"));
	}
}
