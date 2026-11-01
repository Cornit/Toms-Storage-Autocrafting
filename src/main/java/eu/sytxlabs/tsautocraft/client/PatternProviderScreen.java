package eu.sytxlabs.tsautocraft.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import com.tom.storagemod.screen.TSContainerScreen;

import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;

public class PatternProviderScreen extends TSContainerScreen<PatternProviderMenu> {
	private static final Identifier DISPENSER = Identifier.parse("textures/gui/container/dispenser.png");
	private Button blockingBtn, substBtn;

	public PatternProviderScreen(PatternProviderMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Override
	protected void init() {
		super.init();
		blockingBtn = addRenderableWidget(Button.builder(label(), b -> click(0))
				.bounds(leftPos + 7, topPos + 17, 50, 20).build());
		blockingBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.blocking")));
		// Substitutes for patterns encoded here with JEI's "+"
		substBtn = addRenderableWidget(Button.builder(substLabel(), b -> click(1))
				.bounds(leftPos + 7, topPos + 39, 50, 20).build());
		substBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.substitutes_provider")));
	}

	private Component label() {
		return Component.translatable(menu.blocking ? "button.tsautocraft.blocking_on" : "button.tsautocraft.blocking_off");
	}

	private Component substLabel() {
		return Component.translatable(menu.substitutes ? "button.tsautocraft.substitutes_on" : "button.tsautocraft.substitutes_off");
	}

	private void click(int id) {
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (blockingBtn != null) blockingBtn.setMessage(label());
		if (substBtn != null) substBtn.setMessage(substLabel());
		super.extractRenderState(graphics, mouseX, mouseY, a);
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
