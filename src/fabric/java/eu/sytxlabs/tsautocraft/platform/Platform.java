package eu.sytxlabs.tsautocraft.platform;

import java.nio.file.Path;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.CreativeModeTab;

/** Loader specific bits used by the shared code; NeoForge has a class with the same methods. Fabric version. */
public final class Platform {
	private Platform() {}

	public static final String LOADER = "fabric";

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	public static boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	/** Usable from mixin plugins, before mods are constructed. */
	public static boolean isModLoadedEarly(String id) {
		return isModLoaded(id);
	}

	public static CreativeModeTab.Builder creativeTab() {
		return FabricCreativeModeTab.builder();
	}
}
