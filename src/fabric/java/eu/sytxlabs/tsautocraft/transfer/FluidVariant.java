package eu.sytxlabs.tsautocraft.transfer;

import net.minecraft.world.level.material.Fluid;

/** Fabric: a fluid with components, without amount (wraps Fabric's {@code FluidVariant}). */
public final class FluidVariant {
	private final net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant fabric;

	private FluidVariant(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant fabric) {
		this.fabric = fabric;
	}

	public static FluidVariant of(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant fabric) {
		return new FluidVariant(fabric);
	}

	public boolean isBlank() {
		return fabric.isBlank();
	}

	public Fluid getFluid() {
		return fabric.getFluid();
	}

	public net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant fabric() {
		return fabric;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FluidVariant v && fabric.equals(v.fabric);
	}

	@Override
	public int hashCode() {
		return fabric.hashCode();
	}
}
