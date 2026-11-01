package eu.sytxlabs.tsautocraft.menu;

import com.tom.storagemod.inventory.StoredItemStack;

/** Implemented by Tom's {@code StorageTerminalMenu} through {@code StorageTerminalMenuMixin} (client side data). */
public interface CraftableTerminal {
	boolean tsac$isCraftable(StoredItemStack stack);
}
