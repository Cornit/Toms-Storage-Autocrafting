package eu.sytxlabs.tsautocraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** GUI position of any container screen (vanilla keeps it protected; the loaders' getters differ). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int tsac$leftPos();

	@Accessor("topPos")
	int tsac$topPos();
}
