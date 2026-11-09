package eu.sytxlabs.tsautocraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.tom.storagemod.menu.AbstractFilteredMenu;
import com.tom.storagemod.menu.slot.PhantomSlot;
import com.tom.storagemod.screen.PlatformContainerScreen;

import eu.sytxlabs.tsautocraft.menu.MenuUtil;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu.GhostSlot;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;

/** Ghost slots an item from JEI/REI can be dragged onto (shared by both integrations). */
public final class GhostTargets {
	private GhostTargets() {}

	/** Screen area (absolute) and what happens with a dropped item. */
	public record Target(int x, int y, int w, int h, Consumer<ItemStack> accept) {
		public boolean contains(double px, double py) {
			return px >= x && py >= y && px < x + w && py < y + h;
		}
	}

	public static boolean handles(Screen screen) {
		return screen instanceof PatternTerminalScreen || screen instanceof RequesterScreen || screen instanceof LevelMaintainerScreen || screen instanceof CraftingEmitterScreen;
	}

	public static List<Target> of(Screen screen) {
		List<Target> out = new ArrayList<>();
		if (!handles(screen) || !(screen instanceof PlatformContainerScreen<?> gui)) return out;
		Player player = Minecraft.getInstance().player;
		if (player == null) return out;
		for (Slot slot : gui.getMenu().slots) {
			var pos = (eu.sytxlabs.tsautocraft.mixin.client.AbstractContainerScreenAccessor) gui;
			int x = pos.tsac$leftPos() + slot.x, y = pos.tsac$topPos() + slot.y;
			if (gui.getMenu() instanceof PatternTerminalMenu menu) {
				if (!(slot instanceof GhostSlot g) || (g.output && menu.outputsLocked())) continue;
				int index = slot.index;
				out.add(new Target(x, y, 16, 16, st -> MenuUtil.sendToServer(player, o -> {
					o.putInt("ghost_slot", index);
					o.store("ghost_item", ItemStack.CODEC, st.copyWithCount(1));
				})));
			} else if (slot instanceof PhantomSlot && gui.getMenu() instanceof AbstractFilteredMenu menu) {
				out.add(new Target(x, y, 16, 16, st -> menu.setPhantom(slot, st)));
			}
		}
		return out;
	}
}
