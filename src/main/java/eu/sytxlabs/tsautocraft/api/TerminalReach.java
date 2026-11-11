package eu.sytxlabs.tsautocraft.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Extra reach for the menus of our terminal blocks (API 4), e.g. Wireless Antennas of Tom's Storage Network: a hook
 * returning true lets {@code player} keep using the terminal {@code be} beyond the normal / wireless terminal range.
 */
public final class TerminalReach {
	private static final List<BiPredicate<BlockEntity, Player>> HOOKS = new CopyOnWriteArrayList<>();

	private TerminalReach() {}

	public static void register(BiPredicate<BlockEntity, Player> hook) {
		HOOKS.add(hook);
	}

	private static final List<BiPredicate<BlockEntity, Player>> DENY = new CopyOnWriteArrayList<>();

	/**
	 * A hook returning true keeps {@code player} out of the terminal {@code be} (in any range), e.g. the Network
	 * Lock of Tom's Storage Network.
	 */
	public static void registerDeny(BiPredicate<BlockEntity, Player> hook) {
		DENY.add(hook);
	}

	/** Does a deny hook keep {@code player} out of {@code be}? */
	public static boolean denied(BlockEntity be, Player player) {
		for (var h : DENY) if (h.test(be, player)) return true;
		return false;
	}

	public static boolean extended(BlockEntity be, Player player) {
		for (var h : HOOKS) if (h.test(be, player)) return true;
		return false;
	}
}
