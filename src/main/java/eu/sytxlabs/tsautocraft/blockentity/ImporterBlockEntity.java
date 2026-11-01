package eu.sytxlabs.tsautocraft.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.menu.IOMenu;
import eu.sytxlabs.tsautocraft.network.TerminalCrafting;
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
	protected void work() {
		IInventoryAccess net = network.access(level, worldPosition);
		if (net == PlatformInventoryAccess.EMPTY) return;
		Storage<ItemVariant> source = StorageUtil.facingStorage(level, worldPosition, facing());

		if (!buffer.isEmpty()) {
			ItemStack before = buffer.copy();
			buffer = net.pushStack(buffer);
			TerminalCrafting.itemsArrived(level, worldPosition, before, before.getCount() - buffer.getCount());
			if (!buffer.isEmpty() && source != null) buffer = StorageUtil.insert(source, buffer);
			setChanged();
			if (!buffer.isEmpty()) {
				backoff = 4;
				return;
			}
		}
		if (source == null) return;

		boolean filtered = hasAnyFilter();
		ItemVariant chosen = null;
		long amount = 0;
		// Select first, extract outside the loop (do not modify the storage while iterating)
		for (StorageView<ItemVariant> view : source) {
			if (view.isResourceBlank() || view.getAmount() <= 0) continue;
			ItemVariant v = view.getResource();
			ItemStack probe = v.toStack(1);
			if (filtered && !matchesAny(probe)) continue;
			chosen = v;
			amount = Math.min(view.getAmount(), Math.min(maxPerOp(), probe.getMaxStackSize()));
			break;
		}
		if (chosen == null) return;

		long extracted;
		try (Transaction t = Transaction.openOuter()) {
			extracted = source.extract(chosen, amount, t);
			t.commit();
		}
		if (extracted <= 0) return;

		ItemStack rest = net.pushStack(chosen.toStack((int) extracted));
		// Tell the crafting jobs of the network that results came back
		TerminalCrafting.itemsArrived(level, worldPosition, chosen.toStack(1), extracted - rest.getCount());
		if (!rest.isEmpty()) {
			// Network full: put back, otherwise buffer
			rest = StorageUtil.insert(source, rest);
			buffer = rest;
			backoff = 8;
		}
		setChanged();
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new IOMenu(ModContent.IMPORTER_MENU, id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.tsautocraft.importer");
	}
}
