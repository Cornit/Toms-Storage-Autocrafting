package eu.sytxlabs.tsautocraft.blockentity;

import java.util.function.Consumer;

import net.minecraft.network.chat.Component;

/** Server side status lines of a block entity, shown by the Jade plugin when looking at the block. */
public interface StatusInfo {
	void appendStatus(Consumer<Component> lines);
}
