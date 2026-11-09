package eu.sytxlabs.tsautocraft.platform;

import java.nio.file.Path;

import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

/** Loader specific bits used by the shared code; Fabric has a class with the same methods. NeoForge version. */
public final class Platform {
	private Platform() {}

	public static final String LOADER = "neoforge";

	public static Path configDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	public static boolean isModLoaded(String id) {
		return ModList.get().isLoaded(id);
	}

	/** Usable from mixin plugins, before {@link ModList} exists. */
	public static boolean isModLoadedEarly(String id) {
		return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null;
	}

	public static CreativeModeTab.Builder creativeTab() {
		return CreativeModeTab.builder();
	}
}
