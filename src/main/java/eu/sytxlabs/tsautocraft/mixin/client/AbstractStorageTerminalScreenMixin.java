package eu.sytxlabs.tsautocraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

import com.tom.storagemod.menu.StorageTerminalMenu;
import com.tom.storagemod.menu.StorageTerminalMenu.SlotStorage;
import com.tom.storagemod.screen.AbstractStorageTerminalScreen;

import eu.sytxlabs.tsautocraft.client.CraftRequestScreen;
import eu.sytxlabs.tsautocraft.menu.CraftableTerminal;

/**
 * Requests from Tom's terminals: craftable items get a green "+" marker. A normal click on a craftable item that is not in storage (amount 0), or Ctrl + click on any craftable item, opens {@link CraftRequestScreen}.
 */
@Mixin(value = AbstractStorageTerminalScreen.class, remap = false)
public abstract class AbstractStorageTerminalScreenMixin {
	@Shadow protected int slotIDUnderMouse;

	@Unique
	private AbstractStorageTerminalScreen<?> tsac$self() {
		return (AbstractStorageTerminalScreen<?>) (Object) this;
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void tsac$requestClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (slotIDUnderMouse < 0) return;
		AbstractStorageTerminalScreen<?> self = tsac$self();
		StorageTerminalMenu menu = self.getMenu();
		if (!(menu instanceof CraftableTerminal ct) || !menu.getCarried().isEmpty()) return;
		SlotStorage slot = menu.getSlotByID(slotIDUnderMouse);
		if (slot.stack == null || !ct.tsac$isCraftable(slot.stack) || !self.pullNormal(event)) return;
		if (slot.stack.getQuantity() > 0 && !event.hasControlDown()) return;
		Minecraft.getInstance().gui.setScreen(new CraftRequestScreen(self, slot.stack.getStack()));
		cir.setReturnValue(true);
	}

	@Inject(method = "drawSlot", at = @At("RETURN"))
	private void tsac$drawCraftable(GuiGraphicsExtractor g, SlotStorage slot, int mouseX, int mouseY, CallbackInfoReturnable<Boolean> cir) {
		if (slot.stack == null || !(tsac$self().getMenu() instanceof CraftableTerminal ct) || !ct.tsac$isCraftable(slot.stack)) return;
		g.pose().pushMatrix();
		g.pose().translate(slot.xDisplayPosition, slot.yDisplayPosition - 1);
		g.pose().scale(0.75f, 0.75f);
		g.text(Minecraft.getInstance().font, "+", 0, 0, 0xFF55FF55, true);
		g.pose().popMatrix();
	}
}
