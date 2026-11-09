package eu.sytxlabs.tsautocraft.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.tooltip.TooltipAppender;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.api.MachineProfiles;
import eu.sytxlabs.tsautocraft.job.JobScheduler;

@Mod(TsAutocraft.MODID)
public class TsAutocraftNeoForge {
	private static boolean registered;

	public TsAutocraftNeoForge(IEventBus modBus, ModContainer container) {
		// ModContent registers everything in its static initializer: run it in the first registry event, while
		// NeoForge has the registries open
		modBus.addListener(RegisterEvent.class, e -> {
			if (registered) return;
			registered = true;
			TsAutocraft.init();
		});
		modBus.addListener(RegisterTooltipAppendersEvent.class, e -> {
			e.registerComponentAppenderAfterAll(ModContent.PATTERN_COMPONENT, TooltipAppender.createComponentAppender(ModContent.PATTERN_COMPONENT));
			e.registerComponentAppenderAfterAll(ModContent.DESCRIPTION_COMPONENT, TooltipAppender.createComponentAppender(ModContent.DESCRIPTION_COMPONENT));
		});
		NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
			if (e.getLevel() instanceof ServerLevel level) JobScheduler.tick(level);
		});
		NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent.class, e -> e.addListener(TsAutocraft.id("machines"), MachineProfiles.RELOADER));
	}
}
