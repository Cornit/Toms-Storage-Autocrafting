package eu.sytxlabs.tsautocraft.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.job.CraftingJob.TreeLine;

/**
 * Full screen recipe tree of a request preview: the target on top, every pattern input as a node below the item it is
 * crafted into, with right-angled edges. A node is a slot (item + amount); the frame shows how the need is covered
 * (red = something missing, blue = crafted, grey = from storage), details in the tooltip.
 * <p>
 * Drag (or arrow keys) moves the view, the mouse wheel zooms (50-200 %) towards the mouse, a click on a node collapses /
 * expands it ("+N" = hidden nodes). The tree starts collapsed from depth 2 on, except for the paths to missing items.
 * "Start" goes back to the request dialog and starts the job there; "Back" returns to the material list.
 */
public class RecipeTreeScreen extends Screen implements IDataReceiver {
	private static final int NODE = 20, GAP_X = 6, LEVEL_H = 34, MARGIN = 6, TOP = 22, BOTTOM = 28;
	private static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 2f;
	private static final int COLOR_MISSING = 0xFFD03030, COLOR_CRAFTED = 0xFF3F8FE0, COLOR_STORED = 0xFF9A9A9A;

	private static final class Node {
		final TreeLine line;
		final List<Node> children = new ArrayList<>();
		boolean collapsed;
		int x, y, width;

		Node(TreeLine line) {
			this.line = line;
		}

		boolean more() {
			return line.item().isEmpty();
		}

		boolean missingBelow() {
			if (line.missing() > 0) return true;
			for (Node c : children) if (c.missingBelow()) return true;
			return false;
		}

		int descendants() {
			int n = 0;
			for (Node c : children) n += 1 + c.descendants();
			return n;
		}
	}

	private final CraftRequestScreen parent;
	private final ItemStack target;
	private final long amount;
	private final boolean planOk;
	private final Node root;
	private float camX, camY, zoom = 1;
	private boolean centered;
	private boolean dragging, dragged;
	private double pressX, pressY;

	public RecipeTreeScreen(CraftRequestScreen parent, List<TreeLine> lines, ItemStack target, long amount, boolean planOk) {
		super(Component.translatable("screen.tsautocraft.recipe_tree"));
		this.parent = parent;
		this.target = target;
		this.amount = amount;
		this.planOk = planOk;
		this.root = build(lines);
		if (root != null) initCollapse(root, 0);
		layout();
	}

	/** Pre-order lines with depth -> nodes; the trailing "n more" line hangs below the target. */
	private static Node build(List<TreeLine> lines) {
		Node root = null;
		List<Node> stack = new ArrayList<>();
		for (TreeLine l : lines) {
			Node n = new Node(l);
			if (l.item().isEmpty()) {
				if (root != null) root.children.add(n);
				continue;
			}
			if (root == null) {
				root = n;
				stack.add(n);
				continue;
			}
			int d = Math.max(1, l.depth());
			while (stack.size() > d) stack.removeLast();
			stack.getLast().children.add(n);
			stack.add(n);
		}
		return root;
	}

	private static void initCollapse(Node n, int depth) {
		n.collapsed = depth >= 2 && !n.children.isEmpty() && !n.missingBelow();
		for (Node c : n.children) initCollapse(c, depth + 1);
	}

	//region Layout (world coordinates, origin = top left of the target's subtree)

	private void layout() {
		if (root == null) return;
		measure(root);
		place(root, 0, 0);
	}

	private static int measure(Node n) {
		if (n.collapsed || n.children.isEmpty()) return n.width = NODE;
		int w = 0;
		for (Node c : n.children) w += measure(c);
		w += GAP_X * (n.children.size() - 1);
		return n.width = Math.max(NODE, w);
	}

	private static void place(Node n, int left, int y) {
		n.x = left + (n.width - NODE) / 2;
		n.y = y;
		if (n.collapsed) return;
		int span = 0;
		for (Node c : n.children) span += c.width;
		span += GAP_X * Math.max(0, n.children.size() - 1);
		int cx = left + (n.width - span) / 2;
		for (Node c : n.children) {
			place(c, cx, y + LEVEL_H);
			cx += c.width + GAP_X;
		}
	}

	/** Puts the target at the top center at 100 %. */
	private void center() {
		zoom = 1;
		if (root == null) return;
		camX = root.x + NODE / 2f;
		camY = root.y + (viewBottom() - viewTop()) / 2f - MARGIN - 4;
	}
	//endregion

	@Override
	protected void init() {
		int by = height - BOTTOM + 5, bw = 90;
		int x = width / 2 - (bw * 3 + 8) / 2;
		addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.back"), _ -> onClose()).bounds(x, by, bw, 18).build());
		addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.tree.center"), _ -> center()).bounds(x + bw + 4, by, bw, 18).build());
		Button start = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.start"), _ -> start()).bounds(x + 2 * (bw + 4), by, bw, 18).build());
		start.active = planOk;
		if (!centered) {
			centered = true;
			center();
		}
	}

	private void start() {
		minecraft.gui.setScreen(parent);
		parent.start();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Tom's sync packets and request results go to the request dialog. */
	@Override
	public void receive(ValueInput in) {
		parent.receive(in);
	}

	private int viewTop() {
		return TOP;
	}

	private int viewBottom() {
		return height - BOTTOM;
	}

	private boolean inView(double mx, double my) {
		return mx >= MARGIN && mx < width - MARGIN && my >= viewTop() && my < viewBottom();
	}

	private float worldX(double mx) {
		return camX + (float) (mx - width / 2.0) / zoom;
	}

	private float worldY(double my) {
		return camY + (float) (my - (viewTop() + viewBottom()) / 2.0) / zoom;
	}

	private Node nodeAt(double mx, double my) {
		if (root == null || !inView(mx, my)) return null;
		return find(root, worldX(mx), worldY(my));
	}

	private static Node find(Node n, float wx, float wy) {
		if (wx >= n.x && wx < n.x + NODE && wy >= n.y && wy < n.y + NODE) return n;
		if (n.collapsed) return null;
		for (Node c : n.children) {
			Node f = find(c, wx, wy);
			if (f != null) return f;
		}
		return null;
	}

	//region Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inView(event.x(), event.y())) {
			dragging = true;
			dragged = false;
			pressX = event.x();
			pressY = event.y();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (!dragging) return super.mouseDragged(event, dx, dy);
		if (Math.abs(event.x() - pressX) + Math.abs(event.y() - pressY) > 3) dragged = true;
		camX -= (float) dx / zoom;
		camY -= (float) dy / zoom;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragging = false;
			if (!dragged) {
				Node n = nodeAt(event.x(), event.y());
				if (n != null && !n.children.isEmpty()) toggle(n, event.x(), event.y());
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	/** Collapses / expands {@code n}; the node stays under the mouse. */
	private void toggle(Node n, double mx, double my) {
		float offX = worldX(mx) - n.x, offY = worldY(my) - n.y;
		n.collapsed = !n.collapsed;
		layout();
		camX = n.x + offX - (float) (mx - width / 2.0) / zoom;
		camY = n.y + offY - (float) (my - (viewTop() + viewBottom()) / 2.0) / zoom;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (!inView(x, y) || dy == 0) return super.mouseScrolled(x, y, dx, dy);
		float wx = worldX(x), wy = worldY(y);
		zoom = Mth.clamp(zoom * (dy > 0 ? 1.25f : 0.8f), MIN_ZOOM, MAX_ZOOM);
		if (Math.abs(zoom - 1) < 0.05f) zoom = 1;
		camX = wx - (float) (x - width / 2.0) / zoom;
		camY = wy - (float) (y - (viewTop() + viewBottom()) / 2.0) / zoom;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		float step = 24 / zoom;
		switch (event.key()) {
			case InputConstants.KEY_LEFT -> camX -= step;
			case InputConstants.KEY_RIGHT -> camX += step;
			case InputConstants.KEY_UP -> camY -= step;
			case InputConstants.KEY_DOWN -> camY += step;
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}
	//endregion

	//region Rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		g.fill(MARGIN - 1, viewTop() - 1, width - MARGIN + 1, viewBottom() + 1, 0xFF000000);
		g.fill(MARGIN, viewTop(), width - MARGIN, viewBottom(), 0xFF8B8B8B);
		Component head = Component.translatable("label.tsautocraft.tree.title", AutocraftingTerminalScreen.shortNumber(amount), target.getHoverName());
		g.text(font, head, width / 2 - font.width(head) / 2, 8, 0xFFFFFFFF, true);
		String z = Math.round(zoom * 100) + "%";
		g.text(font, z, width - MARGIN - font.width(z), 8, 0xFFA0A0A0, true);
		if (root == null) return;

		g.enableScissor(MARGIN, viewTop(), width - MARGIN, viewBottom());
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(width / 2f, (viewTop() + viewBottom()) / 2f);
		pose.scale(zoom, zoom);
		pose.translate(-camX, -camY);
		edges(g, root);
		nodes(g, root);
		pose.popMatrix();
		g.disableScissor();

		Node hovered = dragging ? null : nodeAt(mouseX, mouseY);
		if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
	}

	private static void edges(GuiGraphicsExtractor g, Node n) {
		if (n.collapsed || n.children.isEmpty()) return;
		int px = n.x + NODE / 2, mid = n.y + NODE + (LEVEL_H - NODE) / 2;
		g.fill(px, n.y + NODE, px + 1, mid, 0xFF3A3A3A);
		Node first = n.children.getFirst(), last = n.children.getLast();
		g.fill(Math.min(px, first.x + NODE / 2), mid, Math.max(px, last.x + NODE / 2) + 1, mid + 1, 0xFF3A3A3A);
		for (Node c : n.children) {
			g.fill(c.x + NODE / 2, mid, c.x + NODE / 2 + 1, c.y, 0xFF3A3A3A);
			edges(g, c);
		}
	}

	private void nodes(GuiGraphicsExtractor g, Node n) {
		TreeLine l = n.line;
		int color = n.more() ? COLOR_STORED : l.missing() > 0 ? COLOR_MISSING : l.crafts() > 0 ? COLOR_CRAFTED : COLOR_STORED;
		g.fill(n.x, n.y, n.x + NODE, n.y + NODE, color);
		g.fill(n.x + 1, n.y + 1, n.x + NODE - 1, n.y + NODE - 1, 0xFF373737);
		g.fill(n.x + 2, n.y + 2, n.x + NODE - 2, n.y + NODE - 2, 0xFF6F6F6F);
		if (n.more()) {
			String s = "…";
			g.text(font, s, n.x + NODE / 2 - font.width(s) / 2, n.y + 6, 0xFFFFFFFF, true);
		} else {
			g.item(l.item(), n.x + 2, n.y + 2);
			g.itemDecorations(font, l.item(), n.x + 2, n.y + 2, l.amount() > 1 ? AutocraftingTerminalScreen.shortNumber(l.amount()) : null);
		}
		if (n.collapsed) {
			String s = "+" + n.descendants();
			g.text(font, s, n.x + NODE / 2 - font.width(s) / 2, n.y + NODE + 2, 0xFFFFFF80, true);
			return;
		}
		for (Node c : n.children) nodes(g, c);
	}

	/** Item and amount, then how the need is covered (stored / crafts -> made / missing). */
	private static List<Component> tooltip(Node n) {
		TreeLine l = n.line;
		List<Component> lines = new ArrayList<>();
		if (n.more()) {
			lines.add(Component.translatable("label.tsautocraft.tree.more", l.amount()));
			return lines;
		}
		lines.add(Component.translatable("label.tsautocraft.item_count", l.amount(), l.item().getHoverName()));
		if (l.stored() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.stored", l.stored()).withStyle(ChatFormatting.GRAY));
		if (l.crafts() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.crafted", l.crafts(), l.made()).withStyle(ChatFormatting.AQUA));
		if (l.missing() > 0) lines.add(Component.translatable("tooltip.tsautocraft.tree.missing", l.missing()).withStyle(ChatFormatting.RED));
		if (!n.children.isEmpty()) lines.add(Component.translatable(n.collapsed ? "tooltip.tsautocraft.tree.expand" : "tooltip.tsautocraft.tree.collapse").withStyle(ChatFormatting.DARK_GRAY));
		return lines;
	}
	//endregion
}
