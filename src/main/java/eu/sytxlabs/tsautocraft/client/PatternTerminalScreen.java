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

public class PatternTerminalScreen extends AbstractStorageTerminalScreen<PatternTerminalMenu> {
	private static final Identifier GUI = TsAutocraft.id("textures/gui/pattern_terminal.png");
	private Button modeBtn, substBtn;
	private int shownMode;
	private boolean shownSubst;
	private Component message = Component.empty();
	private int messageTicks;

	public PatternTerminalScreen(PatternTerminalMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 5, 194, 256, 7, 17);
	}

	@Override
	public Identifier getGui() {
		return GUI;
	}

	private int areaY() {
		return PatternTerminalMenu.AREA_Y + (rowCount - textureSlotCount) * 18;
	}

	@Override
	protected void init() {
		super.init();
		int y = topPos + areaY() + 16;
		modeBtn = addRenderableWidget(Button.builder(modeLabel(), b -> click(0)).bounds(leftPos + 5, y, 18, 18).build());
		shownMode = menu.mode;
		modeBtn.setTooltip(modeTooltip());
		Button clear = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.clear_short"), b -> click(3)).bounds(leftPos + 5, y + 20, 18, 16).build());
		clear.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.clear")));
		Button load = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.load_short"), b -> click(2)).bounds(leftPos + 5, y + 38, 18, 16).build());
		load.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.load")));
		addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.encode"), b -> click(1)).bounds(leftPos + 122, y + 19, 48, 16).build());
		substBtn = addRenderableWidget(Button.builder(substLabel(), b -> click(4)).bounds(leftPos + 150, y + 1, 20, 16).build());
		shownSubst = menu.substitutes;
		substBtn.setTooltip(substTooltip());
		onPacket();
	}

	private Component substLabel() {
		return Component.translatable("button.tsautocraft.substitutes_short").withStyle(menu.substitutes ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
	}

	private Tooltip substTooltip() {
		return Tooltip.create(Component.translatable(menu.substitutes ? "tooltip.tsautocraft.substitutes_on" : "tooltip.tsautocraft.substitutes_off"));
	}

	/** The mode button shows the mode's icon item instead of text. */
	private Component modeLabel() {
		return Component.empty();
	}

	private void click(int id) {
        //noinspection DataFlowIssue
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
		return Tooltip.create(Component.empty().append(Component.translatable("label.tsautocraft.mode", menu.modeType().name())).append("\n").append(Component.translatable("tooltip.tsautocraft.mode")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (modeBtn != null && shownMode != menu.mode) {
			shownMode = menu.mode;
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
		if (modeBtn != null) graphics.item(menu.modeType().icon(), modeBtn.getX() + 1, modeBtn.getY() + 1);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
		super.extractLabels(graphics, xm, ym);
		if (messageTicks > 0) {
			graphics.text(font, message, 60, inventoryLabelY, 0xFF8B0000, false);
		}
	}
}
