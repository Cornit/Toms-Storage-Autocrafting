package eu.sytxlabs.tsautocraft.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.api.MachineProfiles;
import eu.sytxlabs.tsautocraft.job.JobScheduler;

public class TsAutocraftFabric implements ModInitializer {

	@Override
	public void onInitialize() {
		TsAutocraft.init();
		ItemComponentTooltipProviderRegistry.addLast(ModContent.PATTERN_COMPONENT);
		ItemComponentTooltipProviderRegistry.addLast(ModContent.DESCRIPTION_COMPONENT);
		ServerTickEvents.END_LEVEL_TICK.register(JobScheduler::tick);
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(TsAutocraft.id("machines"), MachineProfiles.RELOADER);
	}
}
