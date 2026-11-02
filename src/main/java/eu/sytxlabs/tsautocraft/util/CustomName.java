package eu.sytxlabs.tsautocraft.util;

import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Anvil name of a block, like vanilla containers: taken from the placed item, kept in the block entity,
 * shown as the GUI title (and in Jade / job status) and put back on the dropped item (loot table {@code copy_components}).
 * The block entity forwards {@code applyImplicitComponents}, {@code collectImplicitComponents} and its save/load to this.
 */
public final class CustomName {
	private static final String TAG = "CustomName";

	private Component name;

	public Component get() {
		return name;
	}

	/** The custom name, or {@code fallback} without one. */
	public Component orElse(Component fallback) {
		return name != null ? name : fallback;
	}

	public void apply(DataComponentGetter components) {
		name = components.get(DataComponents.CUSTOM_NAME);
	}

	public void collect(DataComponentMap.Builder builder) {
		builder.set(DataComponents.CUSTOM_NAME, name);
	}

	public void save(ValueOutput out) {
		out.storeNullable(TAG, ComponentSerialization.CODEC, name);
	}

	public void load(ValueInput in) {
		name = BlockEntity.parseCustomNameSafe(in, TAG);
	}
}
