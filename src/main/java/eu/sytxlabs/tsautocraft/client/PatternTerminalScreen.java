package eu.sytxlabs.tsautocraft.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.screen.AbstractStorageTerminalScreen;

import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.menu.PatternTerminalMenu;

/** Tom's terminal screen (same texture layout as the Crafting Terminal) with the pattern encoding area. */
public class PatternTerminalScreen extends AbstractStorageTerminalScreen<PatternTerminalMenu> {
	private static final Identifier GUI = TsAutocraft.id("textures/gui/pattern_terminal.png");
	private Button modeBtn, substBtn;
	private boolean shownMode, shownSubst;
	private Component message = Component.empty();
	private int messageTicks;

	public PatternTerminalScreen(PatternTerminalMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 5, 194, 256, 7, 17);
	}

	@Override
	public Identifier getGui() {
		return GUI;
	}

	/** Top of the encoding area relative to the GUI; moves down in tall mode. */
	private int areaY() {
		return PatternTerminalMenu.AREA_Y + (rowCount - textureSlotCount) * 18;
	}

	@Override
	protected void init() {
		super.init();
		int y = topPos + areaY() + 16;
		modeBtn = addRenderableWidget(Button.builder(modeLabel(), b -> click(0)).bounds(leftPos + 5, y, 18, 18).build());
		shownMode = menu.craftingMode;
		modeBtn.setTooltip(modeTooltip());
		Button clear = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.clear_short"), b -> click(3)).bounds(leftPos + 5, y + 20, 18, 16).build());
		clear.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.clear")));
		Button load = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.load_short"), b -> click(2)).bounds(leftPos + 5, y + 38, 18, 16).build());
		load.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.load")));
		addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.encode"), b -> click(1))
				.bounds(leftPos + 122, y + 19, 48, 16).build());
		// Substitutes toggle, right of the blank pattern slot
		substBtn = addRenderableWidget(Button.builder(substLabel(), b -> click(4)).bounds(leftPos + 150, y + 1, 20, 16).build());
		shownSubst = menu.substitutes;
		substBtn.setTooltip(substTooltip());
		onPacket();
	}

	private Component substLabel() {
		return Component.translatable("button.tsautocraft.substitutes_short")
				.withStyle(menu.substitutes ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
	}

	private Tooltip substTooltip() {
		return Tooltip.create(Component.translatable(menu.substitutes ? "tooltip.tsautocraft.substitutes_on" : "tooltip.tsautocraft.substitutes_off"));
	}

	private Component modeLabel() {
		return Component.translatable(menu.craftingMode ? "button.tsautocraft.mode_crafting_short" : "button.tsautocraft.mode_processing_short");
	}

	private void click(int id) {
		this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
	}

	@Override
	public void receive(ValueInput in) {
		var msg = in.getString("msg");
		if (msg.isPresent()) {
			message = Component.translatable(msg.get());
			messageTicks = 80;
			return;
		}
		super.receive(in);
	}

	private Tooltip modeTooltip() {
		Component mode = Component.translatable(menu.craftingMode ? "label.tsautocraft.mode_crafting" : "label.tsautocraft.mode_processing");
		return Tooltip.create(Component.empty().append(mode).append("\n").append(Component.translatable("tooltip.tsautocraft.mode")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (modeBtn != null && shownMode != menu.craftingMode) {
			shownMode = menu.craftingMode;
			modeBtn.setMessage(modeLabel());
			modeBtn.setTooltip(modeTooltip());
		}
		if (substBtn != null && shownSubst != menu.substitutes) {
			shownSubst = menu.substitutes;
			substBtn.setMessage(substLabel());
			substBtn.setTooltip(substTooltip());
		}
		if (messageTicks > 0) messageTicks--;
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		super.extractLabels(graphics, xm, ym);
		if (messageTicks > 0) {
			graphics.text(font, message, 60, inventoryLabelY, 0xFF8B0000, false);
		}
	}
}
