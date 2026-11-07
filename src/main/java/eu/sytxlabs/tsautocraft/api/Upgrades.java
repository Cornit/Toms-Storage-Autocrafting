package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import eu.sytxlabs.tsautocraft.TsAutocraft;

/**
 * Which upgrade items fit into which block ("host"). Other mods register their own upgrade items for our hosts in their {@code ModInitializer} (both sides)
 * and read the installed amount through {@link UpgradeHost#upgradeCount}; the effect is up to them (e.g. a {@link PatternMode} that delivers faster with it).
 */
public final class Upgrades {
	public static final Identifier PATTERN_PROVIDER = TsAutocraft.id("pattern_provider"), AUTOCRAFTING_TERMINAL = TsAutocraft.id("autocrafting_terminal"), EXPORTER = TsAutocraft.id("exporter"), IMPORTER = TsAutocraft.id("importer");
	public static final Identifier LEVEL_MAINTAINER = TsAutocraft.id("level_maintainer");

	private static final Map<Identifier, List<Item>> ALLOWED = new LinkedHashMap<>();
	private static final Map<Identifier, Map<Item, Integer>> MAX = new LinkedHashMap<>();

	private Upgrades() {}

	/** Lets one {@code upgrade} into the upgrade slots of {@code host}. */
	public static void register(Identifier host, Item upgrade) {
		register(host, upgrade, 1);
	}

	/**
	 * Lets up to {@code max} of {@code upgrade} into the upgrade slots of {@code host}; several of the same kind may
	 * share one slot.
	 */
	public static synchronized void register(Identifier host, Item upgrade, int max) {
		List<Item> l = ALLOWED.computeIfAbsent(host, h -> new ArrayList<>());
		if (!l.contains(upgrade)) l.add(upgrade);
		MAX.computeIfAbsent(host, h -> new LinkedHashMap<>()).put(upgrade, Math.max(1, max));
	}

	/** Most upgrades of {@code item} a {@code host} takes (0 = not allowed). */
	public static int max(Identifier host, Item item) {
		return MAX.getOrDefault(host, Map.of()).getOrDefault(item, 0);
	}

	/** Upgrade items that fit into {@code host}. */
	public static List<Item> allowed(Identifier host) {
		return List.copyOf(ALLOWED.getOrDefault(host, List.of()));
	}

	public static boolean isUpgrade(Item item) {
		for (List<Item> l : ALLOWED.values()) if (l.contains(item)) return true;
		return false;
	}

	/** A block entity with upgrade slots. */
	public interface UpgradeHost {
		Identifier upgradeHost();

		/** Installed upgrades of {@code item}. */
		int upgradeCount(Item item);
	}
}
