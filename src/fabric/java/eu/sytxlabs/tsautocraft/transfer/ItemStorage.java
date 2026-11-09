package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric: item storages of blocks ({@code ItemStorage.SIDED}). */
public final class ItemStorage {
	private ItemStorage() {}

	public static final Lookup SIDED = new Lookup();

	public static final class Lookup {
		private Lookup() {}

		/** Item storage of the block at {@code pos}, accessed from {@code side}; null if there is none. */
		public Storage<ItemVariant> find(Level level, BlockPos pos, Direction side) {
			return wrap(net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, side));
		}

		public Storage<ItemVariant> find(Level level, BlockPos pos, BlockState state, BlockEntity be, Direction side) {
			return wrap(net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, state, be, side));
		}
	}

	static Storage<ItemVariant> wrap(net.fabricmc.fabric.api.transfer.v1.storage.Storage<net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> s) {
		return s == null ? null : new FabricStorage<>(s, ItemVariant::fabric, ItemVariant::of);
	}
}
