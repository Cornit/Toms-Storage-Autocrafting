package eu.sytxlabs.tsautocraft.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob.PlanEntry;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu.Missing;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;

/**
 * AE2 style request dialog, opened from a Tom's terminal (Ctrl + click on a craftable item, or click on a
 * craftable item that is not in storage) or from the Autocrafting Terminal.
 * <ol>
 *   <li>Amount -> "Calculate" sends {@code tsac_plan}.</li>
 *   <li>Material list (from storage / to craft / missing, all crafting steps) -> "Start" sends {@code tsac_req}.</li>
 * </ol>
 * Tom's sync packets keep going to the screen underneath, so its item list stays valid.
 */
public class CraftRequestScreen extends Screen implements IDataReceiver {
	private static final int W = 230, H = 196;
	private static final int LIST_Y = 40, ROW_H = 18, ROWS = 6;
	private static final int MAX_MISSING_LINES = 3;

	private final Screen parent;
	private final ItemStack item;
	private final long initialAmount;
	private EditBox amountBox;
	private Button calcBtn, startBtn, backBtn, maxBtn;
	private long maxPossible = -1;
	private Component message = Component.empty();
	private final List<Missing> missing = new ArrayList<>();
	private final List<PlanEntry> materials = new ArrayList<>();
	private boolean preview, planOk;
	private long previewAmount;
	private int scroll;
	private int left, top;

	public CraftRequestScreen(Screen parent, ItemStack item) {
		this(parent, item, 1);
	}

	public CraftRequestScreen(Screen parent, ItemStack item, long amount) {
		super(Component.translatable("screen.tsautocraft.request"));
		this.parent = parent;
		this.item = item.copyWithCount(1);
		this.initialAmount = amount;
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = (height - H) / 2;
		amountButtons.clear();
		String old = amountBox != null ? amountBox.getValue() : Long.toString(initialAmount);
		amountBox = new EditBox(font, left + 8, top + 42, 60, 16, Component.translatable("label.tsautocraft.amount"));
		amountBox.setMaxLength(6);
		amountBox.setValue(old);
		amountBox.setResponder(s -> AutocraftingTerminalScreen.digitsOnly(amountBox, s));
		addRenderableWidget(amountBox);
		addAmountButton("+1", 1, 72);
		addAmountButton("+16", 16, 96);
		addAmountButton("+64", 64, 124);
		calcBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.calculate"), _ -> calculate()).bounds(left + 8, top + H - 24, 104, 18).build());
		startBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.start"), _ -> start()).bounds(left + 8, top + H - 24, 104, 18).build());
		maxBtn = addRenderableWidget(Button.builder(maxLabel(), _ -> craftPossible()).bounds(left + 8, top + H - 24, 104, 18).build());
		backBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.back"), _ -> back()).bounds(left + W - 112, top + H - 24, 104, 18).build());
		updateWidgets();
		if (!preview) setInitialFocus(amountBox);
	}

	private void addAmountButton(String label, int n, int x) {
		amountButtons.add(addRenderableWidget(Button.builder(Component.literal(label), _ -> addAmount(n)).bounds(left + x, top + 42, 26, 16).build()));
	}

	private final List<Button> amountButtons = new ArrayList<>();

	private void updateWidgets() {
		amountBox.setVisible(!preview);
		amountButtons.forEach(b -> b.visible = !preview);
		calcBtn.visible = !preview;
		boolean offerMax = preview && !planOk && maxPossible > 0;
		startBtn.visible = preview && !offerMax;
		startBtn.active = planOk;
		maxBtn.visible = offerMax;
		maxBtn.setMessage(maxLabel());
		maxBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("tooltip.tsautocraft.craft_possible")));
		backBtn.setMessage(Component.translatable(preview ? "button.tsautocraft.back" : "gui.cancel"));
	}

	private void addAmount(int n) {
		long v = parseAmount() + n;
		if (parseAmount() == 1 && n > 1) v = n;
		amountBox.setValue(Long.toString(Math.min(v, AutocraftingTerminalBlockEntity.maxRequest())));
	}

	private long parseAmount() {
		try {
			return Math.clamp(Long.parseLong(amountBox.getValue()), 1, AutocraftingTerminalBlockEntity.maxRequest());
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private Component maxLabel() {
		return Component.translatable("button.tsautocraft.craft_possible", AutocraftingTerminalScreen.shortNumber(Math.max(0, maxPossible)));
	}

	/** Plans again with the largest possible amount; the player then starts it as usual. */
	private void craftPossible() {
		if (maxPossible <= 0) return;
		amountBox.setValue(Long.toString(maxPossible));
		calculate();
	}

	private void calculate() {
		previewAmount = parseAmount();
		message = Component.empty();
		missing.clear();
		if (minecraft.player == null) return;
		MenuUtil.sendToServer(minecraft.player, out -> {
			out.store("tsac_plan", ItemStack.CODEC, item);
			out.putLong("tsac_amt", previewAmount);
		});
	}

	private void start() {
		if (!planOk) return;
		if (minecraft.player == null) return;
		MenuUtil.sendToServer(minecraft.player, out -> {
			out.store("tsac_req", ItemStack.CODEC, item);
			out.putLong("tsac_amt", previewAmount);
		});
	}

	private void back() {
		if (preview) {
			preview = false;
			message = Component.empty();
			updateWidgets();
			setInitialFocus(amountBox);
		} else {
			onClose();
		}
	}

	@Override
	public void receive(ValueInput in) {
		if (in.getBooleanOr("tsac_preview", false)) {
			materials.clear();
			in.listOrEmpty("tsac_mat", PlanEntry.CODEC).stream().forEach(materials::add);
			planOk = in.getBooleanOr("tsac_ok", false);
			maxPossible = in.getLongOr("tsac_max", -1);
			preview = true;
			scroll = 0;
			message = planOk ? Component.empty() : maxPossible > 0 ? Component.translatable("msg.tsautocraft.request.missing_possible", maxPossible) : Component.translatable("msg.tsautocraft.request.missing");
			updateWidgets();
			return;
		}
		var res = in.getString("tsac_res");
		if (res.isEmpty()) {
			if (parent instanceof IDataReceiver r) r.receive(in);
			return;
		}
		missing.clear();
		in.listOrEmpty("missing", Missing.CODEC).stream().forEach(missing::add);
		if (missing.isEmpty() && res.get().equals("msg.tsautocraft.request.started")) {
			if (minecraft.player == null) return;
			minecraft.player.sendOverlayMessage(Component.translatable(res.get()));
			onClose();
			return;
		}
		message = Component.translatable(res.get());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			if (preview) start();
			else calculate();
			return true;
		}
		if (event.isEscape() && preview) {
			back();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (preview) {
			scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, Math.max(0, materials.size() - ROWS));
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		g.fill(left, top, left + W, top + H, 0xFF000000);
		g.fill(left + 1, top + 1, left + W - 1, top + H - 1, 0xFFC6C6C6);
		g.fill(left + 1, top + 1, left + W - 2, top + 2, 0xFFFFFFFF);
		g.fill(left + 1, top + 1, left + 2, top + H - 2, 0xFFFFFFFF);
		g.fill(left + 2, top + H - 2, left + W - 1, top + H - 1, 0xFF555555);
		g.fill(left + W - 2, top + 2, left + W - 1, top + H - 1, 0xFF555555);

		g.text(font, title, left + 8, top + 6, 0xFF404040, false);
		g.item(item, left + 8, top + 18);
		String name = item.getHoverName().getString();
		if (preview) name = previewAmount + "x " + name;
		g.text(font, font.plainSubstrByWidth(name, W - 36), left + 28, top + 22, 0xFF404040, false);

		if (preview) extractMaterials(g, mouseX, mouseY);
		else extractMessage(g, top + 64);
	}

	private void extractMaterials(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x0 = left + 7, y0 = top + LIST_Y, x1 = left + W - 7, y1 = y0 + ROWS * ROW_H;
		g.fill(x0, y0, x1, y1, 0xFF8B8B8B);
		ItemStack hovered = ItemStack.EMPTY;
		for (int r = 0; r < ROWS && r + scroll < materials.size(); r++) {
			PlanEntry e = materials.get(r + scroll);
			int y = y0 + r * ROW_H;
			if (e.missing() > 0) g.fill(x0, y, x1, y + ROW_H, 0xFFB06060);
			g.item(e.item(), x0 + 1, y + 1);
			int x = x1 - 2;
			x = amountText(g, x, y + 5, e.missing(), "label.tsautocraft.plan.missing", 0xFFFFFFFF);
			x = amountText(g, x, y + 5, e.crafted(), "label.tsautocraft.plan.crafted", 0xFFC0E0FF);
			x = amountText(g, x, y + 5, e.stored(), "label.tsautocraft.plan.stored", 0xFFE0E0E0);
			g.text(font, font.plainSubstrByWidth(e.item().getHoverName().getString(), Math.max(0, x - (x0 + 20) - 4)), x0 + 20, y + 5, 0xFFFFFFFF, true);
			if (mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + ROW_H) hovered = e.item();
		}
		if (materials.size() > ROWS) {
			g.text(font, (scroll + 1) + "-" + Math.min(scroll + ROWS, materials.size()) + "/" + materials.size(), left + W - 50, top + 6, 0xFF404040, false);
		}
		extractMessage(g, y1 + 2);
		if (!hovered.isEmpty()) g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
	}

	/** Right-aligned "n label" part of a material row; returns the new right edge. */
	private int amountText(GuiGraphicsExtractor g, int right, int y, long n, String key, int color) {
		if (n <= 0) return right;
		String s = Component.translatable(key, AutocraftingTerminalScreen.shortNumber(n)).getString();
		int x = right - font.width(s);
		g.text(font, s, x, y, color, true);
		return x - 6;
	}

	private void extractMessage(GuiGraphicsExtractor g, int y) {
		if (message.getString().isEmpty()) return;
		g.text(font, font.plainSubstrByWidth(message.getString(), W - 16), left + 8, y, 0xFF8B0000, false);
		y += 10;
		for (int i = 0; i < missing.size() && i < MAX_MISSING_LINES; i++) {
			Missing m = missing.get(i);
			String line = Component.translatable("label.tsautocraft.item_count", m.count(), m.item().getHoverName()).getString();
			g.text(font, font.plainSubstrByWidth(line, W - 16), left + 12, y, 0xFF8B0000, false);
			y += 9;
		}
	}
}
