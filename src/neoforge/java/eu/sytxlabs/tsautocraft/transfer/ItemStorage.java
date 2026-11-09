package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** NeoForge: item handlers of blocks ({@code Capabilities.Item.BLOCK}). */
public final class ItemStorage {
	private ItemStorage() {}

	public static final Lookup SIDED = new Lookup();

	public static final class Lookup {
		private Lookup() {}

		/** Item storage of the block at {@code pos}, accessed from {@code side}; null if there is none. */
		public Storage<ItemVariant> find(Level level, BlockPos pos, Direction side) {
			return wrap(level.getCapability(Capabilities.Item.BLOCK, pos, side));
		}

		public Storage<ItemVariant> find(Level level, BlockPos pos, BlockState state, BlockEntity be, Direction side) {
			return wrap(level.getCapability(Capabilities.Item.BLOCK, pos, state, be, side));
		}
	}

	static Storage<ItemVariant> wrap(ResourceHandler<ItemResource> h) {
		return h == null ? null : new NeoStorage<>(h, ItemVariant::resource, ItemVariant::of);
	}
}
