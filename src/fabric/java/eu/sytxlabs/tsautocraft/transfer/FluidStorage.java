package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric: fluid storages of blocks ({@code FluidStorage.SIDED}). */
public final class FluidStorage {
	private FluidStorage() {}

	public static final Lookup SIDED = new Lookup();

	public static final class Lookup {
		private Lookup() {}

		/** Fluid storage of the block at {@code pos}, accessed from {@code side}; null if there is none. */
		public Storage<FluidVariant> find(Level level, BlockPos pos, Direction side) {
			return wrap(net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(level, pos, side));
		}

		public Storage<FluidVariant> find(Level level, BlockPos pos, BlockState state, BlockEntity be, Direction side) {
			return wrap(net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(level, pos, state, be, side));
		}
	}

	static Storage<FluidVariant> wrap(net.fabricmc.fabric.api.transfer.v1.storage.Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> s) {
		return s == null ? null : new FabricStorage<>(s, FluidVariant::fabric, FluidVariant::of);
	}
}
