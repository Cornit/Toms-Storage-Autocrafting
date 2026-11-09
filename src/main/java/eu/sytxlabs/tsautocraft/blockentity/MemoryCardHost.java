package eu.sytxlabs.tsautocraft.blockentity;

import net.minecraft.world.Container;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/**
 * Block entity whose settings the Memory Card copies ({@code MemoryCardItem}). Settings never contain items that exist
 * for real: ghost entries are fine, real filter items and upgrades go through {@link #cardFilters()} / {@link #cardUpgrades()}
 * and are taken from the player's inventory. Anvil names are never copied.
 */
public interface MemoryCardHost {
	/** Writes the settings (modes, priorities, ghost entries, amounts). */
	void saveCard(ValueOutput out);

	/** Applies settings written by {@link #saveCard} of the same block entity type. */
	void loadCard(ValueInput in);

	/** Filter slots holding ghosts and real filter items (Tom's {@code IItemFilter}), null if the block has none. */
	default Container cardFilters() {
		return null;
	}

	/** Upgrade slots, null if the block has none. */
	default UpgradeContainer cardUpgrades() {
		return null;
	}
}
