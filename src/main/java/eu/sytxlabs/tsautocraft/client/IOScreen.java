package eu.sytxlabs.tsautocraft.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.tom.storagemod.screen.AbstractFilteredScreen;

import eu.sytxlabs.tsautocraft.api.IOMode;
import eu.sytxlabs.tsautocraft.blockentity.AbstractIOBlockEntity;
import eu.sytxlabs.tsautocraft.menu.IOMenu;

/** Exporter/Importer: uses the vanilla dispenser GUI (3x3), just like Tom's item filter; mode button and (with a Redstone Upgrade) redstone mode button left of it. */
public class IOScreen extends AbstractFilteredScreen<IOMenu> {
	private static final Identifier DISPENSER = Identifier.parse("textures/gui/container/dispenser.png");
	private static final String[] REDSTONE_KEYS = {"high", "low", "toggle"};
	private Button modeBtn, redstoneBtn;
	private int shownMode = -1, shownRedstone = -1;

	public IOScreen(IOMenu menu, Inventory inv, Component title) {
		super(menu, inv, title);
	}

	@Override
	protected void init() {
		super.init();
		//noinspection DataFlowIssue
		modeBtn = addRenderableWidget(Button.builder(Component.empty(), b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0)).bounds(leftPos + 30, topPos + 35, 20, 20).build());
		//noinspection DataFlowIssue
		redstoneBtn = addRenderableWidget(Button.builder(Component.empty(), b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 1)).bounds(leftPos + 8, topPos + 35, 20, 20).build());
		shownMode = shownRedstone = -1;
	}

	/** Icon of a redstone mode: with signal, without signal, toggle. */
	private static ItemStack redstoneIcon(int mode) {
		return new ItemStack(mode == AbstractIOBlockEntity.RS_LOW ? Items.LEVER : mode == AbstractIOBlockEntity.RS_TOGGLE ? Items.STONE_BUTTON : Items.REDSTONE_TORCH);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (modeBtn != null && shownMode != menu.mode) {
			shownMode = menu.mode;
			IOMode m = menu.modeType();
			modeBtn.setTooltip(Tooltip.create(Component.translatable("label.tsautocraft.mode", m.name()).append("\n").append(m.description())));
		}
		if (redstoneBtn != null) {
			redstoneBtn.visible = menu.hasRedstoneUpgrade();
			if (shownRedstone != menu.redstoneMode) {
				shownRedstone = menu.redstoneMode;
				redstoneBtn.setTooltip(Tooltip.create(Component.translatable("tooltip.tsautocraft.redstone." + REDSTONE_KEYS[Math.clamp(menu.redstoneMode, 0, REDSTONE_KEYS.length - 1)])));
			}
		}
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (modeBtn != null) graphics.item(menu.modeType().icon(), modeBtn.getX() + 2, modeBtn.getY() + 2);
		if (redstoneBtn != null && redstoneBtn.visible) graphics.item(redstoneIcon(menu.redstoneMode), redstoneBtn.getX() + 2, redstoneBtn.getY() + 2);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor gr, int mouseX, int mouseY, float a) {
		super.extractBackground(gr, mouseX, mouseY, a);
		gr.blit(RenderPipelines.GUI_TEXTURED, DISPENSER, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
		GuiUtil.upgradePanel(gr, leftPos, topPos, imageWidth, AbstractIOBlockEntity.UPGRADE_SLOTS);
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
		consumer.accept(GuiUtil.upgradeBox(leftPos, topPos, imageWidth, AbstractIOBlockEntity.UPGRADE_SLOTS));
	}
}
