package eu.sytxlabs.tsautocraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

/**
 * Addon for Tom's Simple Storage (Fabric 26.3).
 * <p>
 * Network packets: we reuse Tom's existing {@code toms_storage:data} payload. Tom forwards it on the server to the
 * open menu if it implements {@link com.tom.storagemod.util.IDataReceiver}, and on the client to the open screen.
 * This way the addon needs no payload types of its own.
 */
public class TsAutocraft implements ModInitializer {
	public static final String MODID = "tsautocraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

	public static Identifier id(String path) {
		return Identifier.tryBuild(MODID, path);
	}

	@Override
	public void onInitialize() {
		TsAutocraftConfig.load();
		ModContent.init();
		LOGGER.info("Tom's Storage Autocrafting loaded");
	}
}
