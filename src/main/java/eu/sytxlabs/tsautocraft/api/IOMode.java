package eu.sytxlabs.tsautocraft.api;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * How an Exporter or Importer picks what to move next. Built-in: round robin, priority, random, blacklist (Importer). Other mods register their own with {@link IOModes#register} (both sides, same order).
 * <ul>
 *   <li>Exporter: tries its 9 filter slots in {@link #order} and moves the first one that works.</li>
 *   <li>Importer: looks at the slots ("views") of the inventory in front starting at {@link #start}, and moves the first item that passes the filters ({@link #blacklist()} inverts them).</li>
 * </ul>
 * {@link Context#cursor()} is a number the block keeps for the mode (saved with the block).
 */
public interface IOMode {
	Identifier id();

	/** Item shown on the mode button. */
	ItemStack icon();

	/** Translation key {@code io_mode.<namespace>.<path>} by default. */
	default Component name() {
		return Component.translatable("io_mode." + id().getNamespace() + "." + id().getPath());
	}

	/** Tooltip line of the mode button, {@code io_mode.<namespace>.<path>.desc} by default. */
	default Component description() {
		return Component.translatable("io_mode." + id().getNamespace() + "." + id().getPath() + ".desc");
	}

	/** Is this mode offered by {@code host} ({@link Upgrades#EXPORTER} or {@link Upgrades#IMPORTER})? */
	boolean supports(Identifier host);

	/** Exporter: order of the filter slots (indices 0 to {@code slots - 1}) for this operation. */
	default int[] order(Context ctx, int slots) {
		int[] o = new int[slots];
		for (int i = 0; i < slots; i++) o[i] = i;
		return o;
	}

	/** Importer: index of the first inventory slot to look at ({@code size} slots); the ones before it come last. */
	default int start(Context ctx, int size) {
		return 0;
	}

	/** Importer: filters list what must stay in the inventory (no filters = everything is imported). */
	default boolean blacklist() {
		return false;
	}

	/** Something was moved from filter slot (Exporter) or inventory slot (Importer) {@code index}. */
	default void moved(Context ctx, int index) {}

	interface Context {
		Level level();

		BlockPos pos();

		RandomSource random();

		int cursor();

		void setCursor(int cursor);
	}
}
