package eu.sytxlabs.tsautocraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import eu.sytxlabs.tsautocraft.client.AutocraftingTerminalScreen;
import eu.sytxlabs.tsautocraft.client.CraftingEmitterScreen;
import eu.sytxlabs.tsautocraft.client.IOScreen;
import eu.sytxlabs.tsautocraft.client.PatternAccessTerminalScreen;
import eu.sytxlabs.tsautocraft.client.PatternProviderScreen;
import eu.sytxlabs.tsautocraft.client.PatternTerminalScreen;
import eu.sytxlabs.tsautocraft.client.RequesterScreen;
import eu.sytxlabs.tsautocraft.client.LevelMaintainerScreen;
import eu.sytxlabs.tsautocraft.pattern.PatternItem;

/** Client setup shared by the loaders (their client entry points also register models and tints). */
public final class TsAutocraftClient {
	private TsAutocraftClient() {}

	/** Registers a screen for a menu type (vanilla {@code MenuScreens.register} or NeoForge's event). */
	public interface ScreenRegistrar {
		<M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(MenuType<? extends M> type, MenuScreens.ScreenConstructor<M, U> factory);
	}

	public static void init() {
		PatternItem.shiftDown = () -> Minecraft.getInstance().hasShiftDown();
		GuideBookItem.opener = () -> Minecraft.getInstance().gui.setScreen(new eu.sytxlabs.tsautocraft.client.GuideScreen());
	}

	public static void registerScreens(ScreenRegistrar r) {
		r.register(ModContent.EXPORTER_MENU, IOScreen::new);
		r.register(ModContent.IMPORTER_MENU, IOScreen::new);
		r.register(ModContent.PATTERN_PROVIDER_MENU, PatternProviderScreen::new);
		r.register(ModContent.PATTERN_TERMINAL_MENU, PatternTerminalScreen::new);
		r.register(ModContent.AUTOCRAFTING_TERMINAL_MENU, AutocraftingTerminalScreen::new);
		r.register(ModContent.PATTERN_ACCESS_TERMINAL_MENU, PatternAccessTerminalScreen::new);
		r.register(ModContent.REQUESTER_MENU, RequesterScreen::new);
		r.register(ModContent.LEVEL_MAINTAINER_MENU, LevelMaintainerScreen::new);
		r.register(ModContent.CRAFTING_EMITTER_MENU, CraftingEmitterScreen::new);
	}
}
