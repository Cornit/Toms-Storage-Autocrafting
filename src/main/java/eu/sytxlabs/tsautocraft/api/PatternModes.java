package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.resources.Identifier;

import eu.sytxlabs.tsautocraft.pattern.BuiltinPatternModes;

/**
 * Registry of {@link PatternMode}s. The order (built-ins first, then registration order) is the cycle order of the
 * Pattern Terminal's mode button and is synced by index, so register on client and server alike (common init).
 */
public final class PatternModes {
	private static final List<PatternMode> MODES = new ArrayList<>();

	public static final PatternMode CRAFTING = register(BuiltinPatternModes.CRAFTING);
	public static final PatternMode PROCESSING = register(BuiltinPatternModes.PROCESSING);
	public static final PatternMode SMELTING = register(BuiltinPatternModes.SMELTING);
	public static final PatternMode FLUID = register(BuiltinPatternModes.FLUID);
	public static final PatternMode BREWING = register(BuiltinPatternModes.BREWING);

	private PatternModes() {}

	public static synchronized PatternMode register(PatternMode mode) {
		for (PatternMode m : MODES) {
			if (m.id().equals(mode.id())) throw new IllegalArgumentException("Pattern mode " + mode.id() + " is already registered");
		}
		MODES.add(mode);
		return mode;
	}

	public static List<PatternMode> all() {
		return Collections.unmodifiableList(MODES);
	}

	/** Mode by id, {@link #PROCESSING} for unknown ids. */
	public static PatternMode get(Identifier id) {
		for (PatternMode m : MODES) if (m.id().equals(id)) return m;
		return PROCESSING;
	}

	public static int indexOf(PatternMode mode) {
		return Math.max(0, MODES.indexOf(mode));
	}

	/** Mode by index (menu sync), {@link #CRAFTING} if out of range. */
	public static PatternMode byIndex(int i) {
		return i >= 0 && i < MODES.size() ? MODES.get(i) : CRAFTING;
	}

	public static PatternMode next(PatternMode mode) {
		return MODES.get((indexOf(mode) + 1) % MODES.size());
	}

	/** Mode for a JEI recipe object: the first that claims it, else processing. */
	public static PatternMode forRecipe(Object recipe) {
		for (PatternMode m : MODES) if (m != PROCESSING && m.matchesRecipe(recipe)) return m;
		return PROCESSING;
	}

	/** Mode for an REI display category: the first that claims it, else processing. */
	public static PatternMode forReiCategory(Identifier category) {
		for (PatternMode m : MODES) if (m != PROCESSING && m.matchesReiCategory(category)) return m;
		return PROCESSING;
	}
}
