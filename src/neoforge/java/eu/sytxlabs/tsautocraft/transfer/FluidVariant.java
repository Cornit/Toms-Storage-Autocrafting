package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/** NeoForge: a fluid with components, without amount (wraps a {@link FluidResource}). */
public final class FluidVariant {
	private final FluidResource resource;

	private FluidVariant(FluidResource resource) {
		this.resource = resource;
	}

	public static FluidVariant of(FluidResource resource) {
		return new FluidVariant(resource);
	}

	public boolean isBlank() {
		return resource.isEmpty();
	}

	public Fluid getFluid() {
		return resource.getFluid();
	}

	public FluidResource resource() {
		return resource;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FluidVariant v && resource.equals(v.resource);
	}

	@Override
	public int hashCode() {
		return resource.hashCode();
	}
}
