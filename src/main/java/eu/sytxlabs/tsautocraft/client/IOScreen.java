package eu.sytxlabs.tsautocraft.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import com.tom.storagemod.screen.AbstractFilteredScreen;

import eu.sytxlabs.tsautocraft.menu.IOMenu;

/** Exporter/Importer: uses the vanilla dispenser GUI (3x3), just like Tom's item filter. */
public class IOScreen extends AbstractFilteredScreen<IOMenu> {
	private static final Identifier DISPENSER = Identifier.parse("textures/gui/container/dispenser.png");

	public IOScreen(IOMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor gr, int mouseX, int mouseY, float a) {
		super.extractBackground(gr, mouseX, mouseY, a);
		gr.blit(RenderPipelines.GUI_TEXTURED, DISPENSER, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
	}
}
