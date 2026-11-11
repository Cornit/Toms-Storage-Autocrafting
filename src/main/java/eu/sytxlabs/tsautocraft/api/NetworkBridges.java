package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Links between cable networks in different levels (API 4), e.g. a Dimensional Link of Tom's Storage Network. For every
 * node block entity of a network the registered functions may name entry points (a node position in another level) of
 * networks that belong to it; Autocrafting then sees the Pattern Providers, job owners and jobs of all of them as one
 * network (visited check, so rings are fine). Same-level links that merge Tom's cable networks themselves need nothing.
 */
public final class NetworkBridges {
	public record Target(Level level, BlockPos pos) {}

	private static final List<Function<BlockEntity, List<Target>>> BRIDGES = new CopyOnWriteArrayList<>();

	private NetworkBridges() {}

	/** {@code bridge} returns the linked entry points of a node block entity, or an empty list. */
	public static void register(Function<BlockEntity, List<Target>> bridge) {
		BRIDGES.add(bridge);
	}

	/** Linked entry points of {@code be} over all registered bridges. */
	public static List<Target> targets(BlockEntity be) {
		if (BRIDGES.isEmpty()) return List.of();
		List<Target> l = new ArrayList<>();
		for (var b : BRIDGES) {
			List<Target> t = b.apply(be);
			if (t != null) l.addAll(t);
		}
		return l;
	}

	public static boolean any() {
		return !BRIDGES.isEmpty();
	}
}
