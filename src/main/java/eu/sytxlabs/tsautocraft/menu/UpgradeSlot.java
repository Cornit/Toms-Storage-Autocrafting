package eu.sytxlabs.tsautocraft.menu;

import java.util.function.Predicate;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import eu.sytxlabs.tsautocraft.util.UpgradeContainer;

/** Slot of the upgrade panel right of a GUI ({@code client/GuiUtil.upgradePanel} draws the frame). */
public class UpgradeSlot extends Slot {
	private final Predicate<ItemStack> mayTake;

	public UpgradeSlot(Container c, int index, int imageWidth) {
		this(c, index, imageWidth, s -> true);
	}

	/** @param mayTake false = the upgrade cannot be taken out right now (e.g. its pattern row is still in use) */
	public UpgradeSlot(Container c, int index, int imageWidth, Predicate<ItemStack> mayTake) {
		super(c, index, x(imageWidth), y(index));
		this.mayTake = mayTake;
	}

	public static int x(int imageWidth) {
		return imageWidth + 5;
	}

	public static int y(int index) {
		return 6 + index * 18;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return container.canPlaceItem(getContainerSlot(), stack);
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return container instanceof UpgradeContainer u ? u.limit(getContainerSlot(), stack) : 1;
	}

	@Override
	public boolean mayPickup(Player player) {
		return mayTake.test(getItem());
	}
}
