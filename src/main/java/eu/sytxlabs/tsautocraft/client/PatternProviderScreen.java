package eu.sytxlabs.tsautocraft.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import com.tom.storagemod.screen.TSContainerScreen;

import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.menu.PatternProviderMenu;

/** 4 rows of pattern slots (locked rows greyed out), blocking/substitutes buttons, upgrade panel on the right. */
public class PatternProviderScreen extends TSContainerScreen<PatternProviderMenu> {
	private static final int BTN_Y = 94;
	private Button blockingBtn, substBtn;

	public PatternProviderScreen(PatternProviderMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, PatternProviderMenu.WIDTH, PatternProviderMenu.HEIGHT);
		this.inventoryLabelY = PatternProviderMenu.PLAYER_Y - 11;
	}

	@Override
	protected void init() {
		super.init();
		blockingBtn = addRenderableWidget(Button.builder(label(), b -> click(0)).bounds(leftPos + 7, topPos + BTN_Y, 80, 16).build());
		blockingBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.blocking")));
		substBtn = addRenderableWidget(Button.builder(substLabel(), b -> click(1)).bounds(leftPos + 89, topPos + BTN_Y, 80, 16).build());
		substBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.substitutes_provider")));
	}

	private Component label() {
		return Component.translatable(menu.blocking ? "button.tsautocraft.blocking_on" : "button.tsautocraft.blocking_off");
	}

	private Component substLabel() {
		return Component.translatable(menu.substitutes ? "button.tsautocraft.substitutes_on" : "button.tsautocraft.substitutes_off");
	}

	private void click(int id) {
        //noinspection DataFlowIssue
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
		GuiUtil.panel(gr, leftPos, topPos, imageWidth, imageHeight);
		int active = menu.activeSlots();
		for (int i = 0; i < PatternProviderBlockEntity.SLOTS; i++) {
			int x = leftPos + 7 + i % 9 * 18, y = topPos + PatternProviderMenu.GRID_Y - 1 + i / 9 * 18;
			GuiUtil.slot(gr, x, y);
			if (i >= active) gr.fill(x + 1, y + 1, x + 17, y + 17, 0xFF6B6B6B); // locked: needs a Capacity Upgrade
		}
		for (int i = 0; i < 36; i++) {
			int row = i / 9;
			GuiUtil.slot(gr, leftPos + 7 + i % 9 * 18, topPos + PatternProviderMenu.PLAYER_Y - 1 + (row < 3 ? row * 18 : 58));
		}
		GuiUtil.upgradePanel(gr, leftPos, topPos, imageWidth, PatternProviderBlockEntity.UPGRADE_SLOTS);
	}

	@Override
	protected boolean hasClickedOutside(double mx, double my, int left, int top) {
		return super.hasClickedOutside(mx, my, left, top) && !GuiUtil.inUpgradePanel(mx, my, left, top, imageWidth, PatternProviderBlockEntity.UPGRADE_SLOTS);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		super.extractLabels(g, xm, ym);
		int active = menu.activeSlots();
		if (active < PatternProviderBlockEntity.SLOTS) {
			Component hint = Component.translatable("label.tsautocraft.capacity_hint");
			g.text(font, font.plainSubstrByWidth(hint.getString(), 160), 88 - Math.min(160, font.width(hint)) / 2, PatternProviderMenu.GRID_Y + (active / 9) * 18 + 4, 0xFFE0E0E0, true);
		}
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
		consumer.accept(GuiUtil.upgradeBox(leftPos, topPos, imageWidth, PatternProviderBlockEntity.UPGRADE_SLOTS));
	}
}
