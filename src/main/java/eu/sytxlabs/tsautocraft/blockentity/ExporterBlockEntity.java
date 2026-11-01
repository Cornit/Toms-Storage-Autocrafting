package eu.sytxlabs.tsautocraft.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.InventorySlot;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.inventory.filter.ItemPredicate;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.menu.IOMenu;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/**
 * Exporter: takes filtered items from the network and pushes them into the inventory in front of it.
 * Without filters nothing happens (like AE2).
 */
public class ExporterBlockEntity extends AbstractIOBlockEntity {

	public ExporterBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.EXPORTER_BE, pos, state);
	}

	@Override
	protected void work() {
		Storage<ItemVariant> target = StorageUtil.facingStorage(level, worldPosition, facing());
		if (target == null) return;

		if (!buffer.isEmpty()) {
			buffer = StorageUtil.insert(target, buffer);
			setChanged();
			if (!buffer.isEmpty()) {
				backoff = 4;
				return;
			}
		}

		IInventoryAccess net = network.access(level, worldPosition);
		if (net == PlatformInventoryAccess.EMPTY) return;
		var tracker = net.tracker();
		tracker.getChangeTracker(level);

		for (int k = 0; k < SLOTS; k++) {
			int i = (nextSlot + k) % SLOTS;
			ItemPredicate pred = predicate(i);
			if (pred == null) continue;
			pred.updateState();

			InventorySlot slot = tracker.findSlot(pred, false);
			if (slot == null) continue;
			ItemStack proto = slot.getStack();
			if (proto.isEmpty()) continue;

			long room = StorageUtil.simulateInsert(target, proto, Math.min(maxPerOp(), proto.getMaxStackSize()));
			if (room <= 0) continue;

			ItemStack pulled = net.pullMatchingStack(proto, room);
			if (pulled.isEmpty()) continue;

			ItemStack rest = StorageUtil.insert(target, pulled);
			if (!rest.isEmpty()) {
				rest = net.pushStack(rest);
				buffer = rest;
			}
			nextSlot = i + 1;
			setChanged();
			return;
		}
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		return new IOMenu(ModContent.EXPORTER_MENU, id, inv, this);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.tsautocraft.exporter");
	}
}
