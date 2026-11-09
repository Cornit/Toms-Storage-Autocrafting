package eu.sytxlabs.tsautocraft;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Addon for Tom's Simple Storage (Fabric and NeoForge). The loader entry points ({@code fabric/TsAutocraftFabric},
 * {@code neoforge/TsAutocraftNeoForge}) call {@link #init()} and hook up ticks, reload listeners and registries.
 * Network packets: we reuse Tom's existing {@code toms_storage:data} payload. Tom forwards it on the server to the
 * open menu if it implements {@link com.tom.storagemod.util.IDataReceiver}, and on the client to the open screen.
 * This way the addon needs no payload types of its own.
 */
public final class TsAutocraft {
	public static final String MODID = "tsautocraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

	private TsAutocraft() {}

	public static Identifier id(String path) {
		return Identifier.tryBuild(MODID, path);
	}

	/** Config and registrations (on NeoForge called while its registries are open). */
	public static void init() {
		TsAutocraftConfig.load();
		ModContent.init();
		LOGGER.info("Tom's Storage Autocrafting loaded");
	}
}
