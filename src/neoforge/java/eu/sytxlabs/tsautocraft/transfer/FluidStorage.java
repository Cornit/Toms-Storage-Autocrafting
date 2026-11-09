package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** NeoForge: fluid handlers of blocks ({@code Capabilities.Fluid.BLOCK}). */
public final class FluidStorage {
	private FluidStorage() {}

	public static final Lookup SIDED = new Lookup();

	public static final class Lookup {
		private Lookup() {}

		/** Fluid storage of the block at {@code pos}, accessed from {@code side}; null if there is none. */
		public Storage<FluidVariant> find(Level level, BlockPos pos, Direction side) {
			return wrap(level.getCapability(Capabilities.Fluid.BLOCK, pos, side));
		}

		public Storage<FluidVariant> find(Level level, BlockPos pos, BlockState state, BlockEntity be, Direction side) {
			return wrap(level.getCapability(Capabilities.Fluid.BLOCK, pos, state, be, side));
		}
	}

	static Storage<FluidVariant> wrap(ResourceHandler<FluidResource> h) {
		return h == null ? null : new NeoStorage<>(h, FluidVariant::resource, FluidVariant::of);
	}
}
