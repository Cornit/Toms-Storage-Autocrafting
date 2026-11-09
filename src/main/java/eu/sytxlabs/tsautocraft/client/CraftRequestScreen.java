package eu.sytxlabs.tsautocraft.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.blockentity.AutocraftingTerminalBlockEntity;
import eu.sytxlabs.tsautocraft.job.CraftingJob.PlanEntry;
import eu.sytxlabs.tsautocraft.job.CraftingJob.TreeLine;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu.Missing;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;
import eu.sytxlabs.tsautocraft.util.MathExpr;

/**
 * AE2 style request dialog, opened from a Tom's terminal (Ctrl + click on a craftable item, or click on a
 * craftable item that is not in storage) or from the Autocrafting Terminal.
 * <ol>
 *   <li>Amount -> "Calculate" sends {@code tsac_plan}.</li>
 *   <li>Material list (from storage / to craft / missing, all crafting steps) -> "Start" sends {@code tsac_req}.
 *   The tree button switches to the recipe tree (which step crafts what from what; click a line to collapse/expand it).</li>
 * </ol>
 * Tom's sync packets keep going to the screen underneath, so its item list stays valid.
 */
public class CraftRequestScreen extends Screen implements IDataReceiver {
	private static final int W = 230, H = 196, H_INPUT = 132;
	/** Amount view: field at this y, two button rows below, then one message line. */
	private static final int AMOUNT_Y = 38, MESSAGE_Y = AMOUNT_Y + 58;
	private static final int LIST_Y = 40, ROW_H = 18, ROWS = 6;
	private static final int MAX_MISSING_LINES = 3;
	private static final int TREE_ROW_H = 12, TREE_ROWS = ROWS * ROW_H / TREE_ROW_H, INDENT = 8;

	private final Screen parent;
	private final ItemStack item;
	private final long initialAmount;
	private EditBox amountBox;
	private Button calcBtn, startBtn, backBtn, maxBtn, viewBtn;
	private long maxPossible = -1;
	private Component message = Component.empty();
	private final List<Missing> missing = new ArrayList<>();
	private final List<PlanEntry> materials = new ArrayList<>();
	private final List<TreeLine> tree = new ArrayList<>();
	/** Indices (into {@link #tree}) of collapsed lines. */
	private final java.util.Set<Integer> collapsed = new java.util.HashSet<>();
	private boolean showTree;
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

	/** Height: compact while entering the amount, tall for the material list / tree. */
	private int h() {
		return preview ? H : H_INPUT;
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = (height - h()) / 2;
		amountButtons.clear();
		String old = amountBox != null ? amountBox.getValue() : Long.toString(initialAmount);
		amountBox = new EditBox(font, left + 8, top + AMOUNT_Y, W - 16, 16, Component.translatable("label.tsautocraft.amount"));
		amountBox.setMaxLength(32);
		amountBox.setValue(old);
		amountBox.setResponder(_ -> amountChanged());
		amountBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("tooltip.tsautocraft.amount_math")));
		addRenderableWidget(amountBox);
		String[] labels = {"+1", "+16", "+64", "×2", "-1", "-16", "-64", "÷2"};
		for (int i = 0; i < labels.length; i++) {
			int n = i % 4, row = i / 4;
			int bw = (W - 16 - 3 * 2) / 4;
			int x = left + 8 + n * (bw + 2), y = top + AMOUNT_Y + 20 + row * 18;
			int step = n == 0 ? 1 : n == 1 ? 16 : 64;
			Button.OnPress press = n == 3 ? _ -> scaleAmount(row == 0) : row == 0 ? _ -> addAmount(step) : _ -> addAmount(-step);
			amountButtons.add(addRenderableWidget(Button.builder(Component.literal(labels[i]), press).bounds(x, y, bw, 16).build()));
		}
		int by = top + h() - 24;
		calcBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.calculate"), _ -> calculate()).bounds(left + 8, by, 104, 18).build());
		startBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.start"), _ -> start()).bounds(left + 8, by, 104, 18).build());
		maxBtn = addRenderableWidget(Button.builder(maxLabel(), _ -> craftPossible()).bounds(left + 8, by, 104, 18).build());
		backBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.back"), _ -> back()).bounds(left + W - 112, by, 104, 18).build());
		viewBtn = addRenderableWidget(Button.builder(viewLabel(), _ -> toggleView()).bounds(left + W - 60, top + 18, 52, 16).build());
		updateWidgets();
		amountChanged();
		if (!preview) setInitialFocus(amountBox);
	}

	private final List<Button> amountButtons = new ArrayList<>();

	/** Amount field changed: red text and no "Calculate" while the expression is invalid. */
	private void amountChanged() {
		String v = amountBox.getValue();
		StringBuilder clean = new StringBuilder(v.length());
		for (char c : v.toCharArray()) if (c == ' ' || MathExpr.allowed(c)) clean.append(c);
		if (clean.length() != v.length()) {
			amountBox.setValue(clean.toString()); // calls this again with the clean text
			return;
		}
		boolean ok = !Double.isNaN(MathExpr.eval(amountBox.getValue()));
		amountBox.setTextColor(ok ? 0xFFE0E0E0 : 0xFFFF5555);
		if (calcBtn != null) calcBtn.active = ok;
	}

	/** Switches between the compact amount view and the tall preview (size and widget positions change). */
	private void setPreview(boolean p) {
		if (preview == p) {
			updateWidgets();
			return;
		}
		preview = p;
		rebuildWidgets();
	}

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
		viewBtn.visible = preview && !tree.isEmpty();
		viewBtn.setMessage(viewLabel());
		viewBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(showTree ? "tooltip.tsautocraft.view_list" : "tooltip.tsautocraft.view_tree")));
	}

	private Component viewLabel() {
		return Component.translatable(showTree ? "button.tsautocraft.view_list" : "button.tsautocraft.view_tree");
	}

	private void toggleView() {
		showTree = !showTree;
		scroll = 0;
		updateWidgets();
	}

	private void addAmount(int n) {
		long v = parseAmount() + n;
		if (parseAmount() == 1 && n > 1) v = n;
		setAmount(v);
	}

	private void scaleAmount(boolean up) {
		setAmount(up ? parseAmount() * 2 : parseAmount() / 2);
	}

	private void setAmount(long v) {
		amountBox.setValue(Long.toString(Math.clamp(v, 1, AutocraftingTerminalBlockEntity.maxRequest())));
	}

	/** Value of the amount field (a number or a math expression), rounded and clamped; 1 if invalid. */
	private long parseAmount() {
		double v = MathExpr.eval(amountBox.getValue());
		if (Double.isNaN(v)) return 1;
		return Math.clamp(Math.round(v), 1, AutocraftingTerminalBlockEntity.maxRequest());
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
		if (Double.isNaN(MathExpr.eval(amountBox.getValue()))) return;
		previewAmount = parseAmount();
		amountBox.setValue(Long.toString(previewAmount));
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
			message = Component.empty();
			setPreview(false);
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
			tree.clear();
			in.listOrEmpty("tsac_tree", TreeLine.CODEC).stream().forEach(tree::add);
			collapsed.clear();
			if (tree.isEmpty()) showTree = false;
			planOk = in.getBooleanOr("tsac_ok", false);
			maxPossible = in.getLongOr("tsac_max", -1);
			scroll = 0;
			message = planOk ? Component.empty() : maxPossible > 0 ? Component.translatable("msg.tsautocraft.request.missing_possible", maxPossible) : Component.translatable("msg.tsautocraft.request.missing");
			setPreview(true);
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
			scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	private int maxScroll() {
		return showTree ? Math.max(0, visibleTree().size() - TREE_ROWS) : Math.max(0, materials.size() - ROWS);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (preview && showTree) {
			int line = treeLineAt(event.x(), event.y());
			if (line >= 0 && hasChildren(line)) {
				if (!collapsed.remove(line)) collapsed.add(line);
				scroll = Mth.clamp(scroll, 0, maxScroll());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** Index into {@link #tree} of the line under the mouse, -1 if none. */
	private int treeLineAt(double mx, double my) {
		int x0 = left + 7, y0 = top + LIST_Y;
		if (mx < x0 || mx >= left + W - 7 || my < y0) return -1;
		int row = (int) ((my - y0) / TREE_ROW_H);
		List<Integer> visible = visibleTree();
		return row < TREE_ROWS && row + scroll < visible.size() ? visible.get(row + scroll) : -1;
	}

	private boolean hasChildren(int i) {
		return i + 1 < tree.size() && !tree.get(i + 1).item().isEmpty() && tree.get(i + 1).depth() > tree.get(i).depth();
	}

	/** Indices of the tree lines not hidden by a collapsed parent. */
	private List<Integer> visibleTree() {
		List<Integer> out = new ArrayList<>();
		int hideBelow = Integer.MAX_VALUE;
		for (int i = 0; i < tree.size(); i++) {
			TreeLine l = tree.get(i);
			if (!l.item().isEmpty() && l.depth() > hideBelow) continue;
			hideBelow = Integer.MAX_VALUE;
			out.add(i);
			if (collapsed.contains(i)) hideBelow = l.depth();
		}
		return out;
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
		int h = h();
		g.fill(left, top, left + W, top + h, 0xFF000000);
		g.fill(left + 1, top + 1, left + W - 1, top + h - 1, 0xFFC6C6C6);
		g.fill(left + 1, top + 1, left + W - 2, top + 2, 0xFFFFFFFF);
		g.fill(left + 1, top + 1, left + 2, top + h - 2, 0xFFFFFFFF);
		g.fill(left + 2, top + h - 2, left + W - 1, top + h - 1, 0xFF555555);
		g.fill(left + W - 2, top + 2, left + W - 1, top + h - 1, 0xFF555555);

		g.text(font, title, left + 8, top + 6, 0xFF404040, false);
		String name = item.getHoverName().getString();
		if (preview) {
			name = previewAmount + "x " + name;
			g.item(item, left + 8, top + 18);
			g.text(font, font.plainSubstrByWidth(name, W - 36 - (viewBtn.visible ? 56 : 0)), left + 28, top + 22, 0xFF404040, false);
		} else {
			// Amount view: icon + name centered
			name = font.plainSubstrByWidth(name, W - 36);
			int x = left + (W - 20 - font.width(name)) / 2;
			g.item(item, x, top + 18);
			g.text(font, name, x + 20, top + 22, 0xFF404040, false);
		}

		if (preview && showTree) extractTree(g, mouseX, mouseY);
		else if (preview) extractMaterials(g, mouseX, mouseY);
		else extractMessage(g, top + MESSAGE_Y, 0); // compact: one line, no missing list
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
		extractMessage(g, y1 + 2, MAX_MISSING_LINES);
		if (!hovered.isEmpty()) g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
	}

	/** Recipe tree: indented lines "8x Planks", right side crafts / stored / missing; the target is the first line. */
	private void extractTree(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x0 = left + 7, y0 = top + LIST_Y, x1 = left + W - 7, y1 = y0 + ROWS * ROW_H;
		g.fill(x0, y0, x1, y1, 0xFF8B8B8B);
		List<Integer> visible = visibleTree();
		TreeLine hovered = null;
		for (int r = 0; r < TREE_ROWS && r + scroll < visible.size(); r++) {
			int idx = visible.get(r + scroll);
			TreeLine l = tree.get(idx);
			int y = y0 + r * TREE_ROW_H;
			if (l.item().isEmpty()) {
				g.text(font, Component.translatable("label.tsautocraft.tree.more", l.amount()), x0 + 3, y + 2, 0xFFE0E0E0, true);
				continue;
			}
			if (l.missing() > 0) g.fill(x0, y, x1, y + TREE_ROW_H, 0xFFB06060);
			int x = x1 - 2;
			x = amountText(g, x, y + 2, l.missing(), "label.tsautocraft.plan.missing", 0xFFFFFFFF);
			x = amountText(g, x, y + 2, l.crafts(), "label.tsautocraft.tree.crafts", 0xFFC0E0FF);
			x = amountText(g, x, y + 2, l.stored(), "label.tsautocraft.plan.stored", 0xFFE0E0E0);
			int tx = x0 + 3 + Math.min(l.depth(), 12) * INDENT;
			String marker = hasChildren(idx) ? (collapsed.contains(idx) ? "\u25B8 " : "\u25BE ") : "  ";
			String text = marker + Component.translatable("label.tsautocraft.item_count", AutocraftingTerminalScreen.shortNumber(l.amount()), l.item().getHoverName()).getString();
			g.text(font, font.plainSubstrByWidth(text, Math.max(0, x - tx - 4)), tx, y + 2, 0xFFFFFFFF, true);
			if (mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y + TREE_ROW_H) hovered = l;
		}
		if (visible.size() > TREE_ROWS) {
			g.text(font, (scroll + 1) + "-" + Math.min(scroll + TREE_ROWS, visible.size()) + "/" + visible.size(), left + W - 50, top + 6, 0xFF404040, false);
		}
		extractMessage(g, y1 + 2, MAX_MISSING_LINES);
		if (hovered != null) g.setComponentTooltipForNextFrame(font, treeTooltip(hovered), mouseX, mouseY);
	}

	/** Item name, then how the need is covered (stored / crafts x pattern -> made / missing). */
	private static List<Component> treeTooltip(TreeLine l) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("label.tsautocraft.item_count", l.amount(), l.item().getHoverName()));
		if (l.stored() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.stored", l.stored()).withStyle(net.minecraft.ChatFormatting.GRAY));
		if (l.crafts() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.crafted", l.crafts(), l.made()).withStyle(net.minecraft.ChatFormatting.AQUA));
		if (l.missing() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.missing", l.missing()).withStyle(net.minecraft.ChatFormatting.RED));
		return lines;
	}

	/** Right-aligned "n label" part of a material row; returns the new right edge. */
	private int amountText(GuiGraphicsExtractor g, int right, int y, long n, String key, int color) {
		if (n <= 0) return right;
		String s = Component.translatable(key, AutocraftingTerminalScreen.shortNumber(n)).getString();
		int x = right - font.width(s);
		g.text(font, s, x, y, color, true);
		return x - 6;
	}

	private void extractMessage(GuiGraphicsExtractor g, int y, int maxMissing) {
		if (message.getString().isEmpty()) return;
		g.text(font, font.plainSubstrByWidth(message.getString(), W - 16), left + 8, y, 0xFF8B0000, false);
		y += 10;
		for (int i = 0; i < missing.size() && i < maxMissing; i++) {
			Missing m = missing.get(i);
			String line = Component.translatable("label.tsautocraft.item_count", m.count(), m.item().getHoverName()).getString();
			g.text(font, font.plainSubstrByWidth(line, W - 16), left + 12, y, 0xFF8B0000, false);
			y += 9;
		}
	}
}
