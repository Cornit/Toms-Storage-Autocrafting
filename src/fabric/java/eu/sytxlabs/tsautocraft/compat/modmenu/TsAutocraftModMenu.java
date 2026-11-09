package eu.sytxlabs.tsautocraft.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import eu.sytxlabs.tsautocraft.compat.ConfigScreen;

/** Mod Menu entry point: opens {@link ConfigScreen}. */
public class TsAutocraftModMenu implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::build;
	}
}
