package eu.sytxlabs.tsautocraft.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.screen.TSContainerScreen;
import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.blockentity.CraftingEmitterBlockEntity;
import eu.sytxlabs.tsautocraft.menu.CraftingEmitterMenu;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;

/** Ghost item, amount and mode of the Crafting Emitter. */
public class CraftingEmitterScreen extends TSContainerScreen<CraftingEmitterMenu> implements IDataReceiver {
	private static final String[] MODES = {"below", "above", "craft"};
	private EditBox amountBox;
	private Button modeBtn;
	private boolean updating;

	public CraftingEmitterScreen(CraftingEmitterMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 176, 166);
		this.inventoryLabelY = CraftingEmitterMenu.PLAYER_Y - 11;
		menu.onUpdate = this::onUpdate;
	}

	@Override
	protected void init() {
		super.init();
		amountBox = new EditBox(font, leftPos + 30, topPos + CraftingEmitterMenu.SLOT_Y + 1, 60, 14, Component.translatable("label.tsautocraft.amount"));
		amountBox.setMaxLength(7);
		amountBox.setValue(Integer.toString(menu.amount));
		amountBox.setResponder(s -> {
			String digits = s.replaceAll("[^0-9]", "");
			if (!digits.equals(s)) {
				amountBox.setValue(digits); // EditBox has no input filter in 26.x
				return;
			}
			if (updating) return;
			int v;
			try {
				v = s.isEmpty() ? 0 : Integer.parseInt(s);
			} catch (NumberFormatException e) {
				return;
			}
			if (minecraft.player == null) return;
			MenuUtil.sendToServer(minecraft.player, out -> out.putInt("amt", v));
		});
		addRenderableWidget(amountBox);
        //noinspection DataFlowIssue
        modeBtn = addRenderableWidget(Button.builder(modeLabel(), _ -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0)).bounds(leftPos + 7, topPos + 44, 162, 18).build());
	}

	private String modeName() {
		return MODES[Math.clamp(menu.mode, 0, MODES.length - 1)];
	}

	private Component modeLabel() {
		return Component.translatable("button.tsautocraft.emitter." + modeName());
	}

	private void onUpdate() {
		if (amountBox == null || amountBox.isFocused()) return;
		updating = true;
		try {
			amountBox.setValue(Integer.toString(menu.amount));
		} finally {
			updating = false;
		}
	}

	@Override
	public void receive(ValueInput in) {
		menu.receive(in);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) return super.keyPressed(event);
		if (amountBox != null && amountBox.isFocused() && (amountBox.keyPressed(event) || amountBox.canConsumeInput())) return true;
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (modeBtn != null) {
			modeBtn.setMessage(modeLabel());
			modeBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.emitter." + modeName())));
		}
		if (amountBox != null) amountBox.visible = menu.mode != CraftingEmitterBlockEntity.MODE_CRAFT;
		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor gr, int mouseX, int mouseY, float a) {
		super.extractBackground(gr, mouseX, mouseY, a);
		GuiUtil.panel(gr, leftPos, topPos, imageWidth, imageHeight);
		GuiUtil.slot(gr, leftPos + CraftingEmitterMenu.SLOT_X - 1, topPos + CraftingEmitterMenu.SLOT_Y - 1);
		for (int i = 0; i < 36; i++) {
			int row = i / 9;
			GuiUtil.slot(gr, leftPos + 7 + i % 9 * 18, topPos + CraftingEmitterMenu.PLAYER_Y - 1 + (row < 3 ? row * 18 : 58));
		}
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
	}
}
