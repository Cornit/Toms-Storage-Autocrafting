package eu.sytxlabs.tsautocraft.util;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.tom.storagemod.item.WirelessTerminal;
import com.tom.storagemod.util.PlayerInvUtil;

/**
 * Menu range of our own terminal blocks: 8 blocks, or the range of a Tom's (Advanced) Wireless Terminal anywhere in
 * the player's inventory (both open blocks of the {@code toms_storage:remote_activate} tag from afar).
 */
public final class TerminalAccess {
	private static final int NEAR = 8;

	private TerminalAccess() {}

	@SuppressWarnings("resource") // Level is AutoCloseable, but these getters only return the existing level
	public static boolean stillValid(BlockEntity be, Player player) {
		if (be.getLevel() == null || be.getLevel().getBlockEntity(be.getBlockPos()) != be) return false;
		if (eu.sytxlabs.tsautocraft.api.TerminalReach.denied(be, player)) return false;
		if (player.level() != be.getLevel()) return eu.sytxlabs.tsautocraft.api.TerminalReach.extended(be, player);
		int range = Math.max(NEAR, PlayerInvUtil.findItem(player, s -> s.getItem() instanceof WirelessTerminal, 0, s -> ((WirelessTerminal) s.getItem()).getRange(player, s)));
		var p = be.getBlockPos();
		return player.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) <= (double) range * range || eu.sytxlabs.tsautocraft.api.TerminalReach.extended(be, player);
	}
}
