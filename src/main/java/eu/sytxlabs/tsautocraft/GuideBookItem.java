package eu.sytxlabs.tsautocraft;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * "Autocrafting Guide": right click opens the client-only guide screen ({@code client/GuideScreen}).
 * The client sets {@link #opener}; on a dedicated server it stays a no-op.
 */
public class GuideBookItem extends Item {
	public static Runnable opener = () -> {};

	public GuideBookItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) opener.run();
		return InteractionResult.SUCCESS;
	}
}
