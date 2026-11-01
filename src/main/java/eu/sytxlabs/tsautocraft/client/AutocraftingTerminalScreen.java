package eu.sytxlabs.tsautocraft.client;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;

import com.tom.storagemod.screen.TSContainerScreen;
import com.tom.storagemod.util.IDataReceiver;

import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.job.CraftingJob;
import eu.sytxlabs.tsautocraft.job.CraftingJob.JobView;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu;
import eu.sytxlabs.tsautocraft.menu.AutocraftingTerminalMenu.Craftable;
import eu.sytxlabs.tsautocraft.menu.MenuUtil;

/**
 * Top: craftable items (9x3, mouse wheel scrolls). Click selects, double click requests.
 * Middle: amount + "Request", below that the message/missing items.
 * Bottom: running jobs, click the cross to cancel.
 */
public class AutocraftingTerminalScreen extends TSContainerScreen<AutocraftingTerminalMenu> implements IDataReceiver {
	private static final Identifier GUI = TsAutocraft.id("textures/gui/autocrafting_terminal.png");
	private static final int COLS = 9, ROWS = 3, GRID_X = 8, GRID_Y = 18;
	private static final int JOBS_Y = 128, JOB_H = 11, JOB_ROWS = 3;

	private EditBox amountBox;
	private int scroll, jobScroll;
	private ItemStack selected = ItemStack.EMPTY;

	public AutocraftingTerminalScreen(AutocraftingTerminalMenu menu, Inventory inv, Component title) {
		super(menu, inv, title, 176, 256);
		this.inventoryLabelY = AutocraftingTerminalMenu.PLAYER_Y - 11;
		menu.onUpdate = this::onUpdate;
	}

	@Override
	protected void init() {
		super.init();
		amountBox = new EditBox(font, leftPos + 8, topPos + 95, 40, 14, Component.translatable("label.tsautocraft.amount"));
		amountBox.setMaxLength(6);
		amountBox.setValue("1");
		amountBox.setResponder(s -> digitsOnly(amountBox, s)); // EditBox has no input filter in 26.x
		addRenderableWidget(amountBox);
		addRenderableWidget(Button.builder(Component.literal("+16"), b -> addAmount(16)).bounds(leftPos + 50, topPos + 94, 24, 16).build());
		addRenderableWidget(Button.builder(Component.literal("+64"), b -> addAmount(64)).bounds(leftPos + 75, topPos + 94, 24, 16).build());
		addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.request"), b -> request()).bounds(leftPos + 102, topPos + 93, 68, 18).build());
	}

	private void addAmount(int n) {
		long v = parseAmount() + n;
		if (parseAmount() == 1 && n > 1) v = n; // from 1 jump straight to 16/64
		amountBox.setValue(Long.toString(Math.min(v, 100_000)));
	}

	private long parseAmount() {
		try {
			return Math.max(1, Long.parseLong(amountBox.getValue()));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private void request() {
		if (selected.isEmpty()) return;
		minecraft.gui.setScreen(new CraftRequestScreen(this, selected, parseAmount()));
	}

	private void onUpdate() {
		// Drop the selection if its pattern is gone
		if (!selected.isEmpty() && menu.craftables.stream().noneMatch(c -> ItemStack.isSameItemSameComponents(c.item(), selected))) {
			selected = ItemStack.EMPTY;
		}
		scroll = Mth.clamp(scroll, 0, maxScroll());
		jobScroll = Mth.clamp(jobScroll, 0, maxJobScroll());
	}

	private int maxJobScroll() {
		return Math.max(0, menu.jobs.size() - JOB_ROWS);
	}

	private int maxScroll() {
		int rows = (menu.craftables.size() + COLS - 1) / COLS;
		return Math.max(0, rows - ROWS);
	}

	@Override
	public void receive(ValueInput in) {
		menu.receive(in);
	}

	// ------------------------------------------------------------------ Input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x() - leftPos, my = event.y() - topPos;
		int idx = gridIndexAt(mx, my);
		if (idx >= 0 && idx < menu.craftables.size()) {
			selected = menu.craftables.get(idx).item();
			if (doubleClick) request();
			return true;
		}
		// Cancel cross in the job list
		if (mx >= 160 && mx < 170 && my >= JOBS_Y && my < JOBS_Y + JOB_ROWS * JOB_H) {
			int row = (int) ((my - JOBS_Y) / JOB_H);
			List<JobView> visible = visibleJobs();
			if (row < visible.size()) {
				int id = visible.get(row).id();
				MenuUtil.sendToServer(minecraft.player, out -> out.putInt("cancel", id));
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		double mx = x - leftPos, my = y - topPos;
		if (mx >= GRID_X && mx < GRID_X + COLS * 18 && my >= GRID_Y && my < GRID_Y + ROWS * 18) {
			scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, maxScroll());
			return true;
		}
		if (mx >= GRID_X && mx < 170 && my >= JOBS_Y && my < JOBS_Y + JOB_ROWS * JOB_H) {
			jobScroll = Mth.clamp(jobScroll - (int) Math.signum(dy), 0, maxJobScroll());
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) return super.keyPressed(event);
		if (amountBox != null && amountBox.isFocused()) {
			if (amountBox.keyPressed(event) || amountBox.canConsumeInput()) return true;
		}
		return super.keyPressed(event);
	}

	private int gridIndexAt(double mx, double my) {
		if (mx < GRID_X || my < GRID_Y) return -1;
		int col = (int) ((mx - GRID_X) / 18), row = (int) ((my - GRID_Y) / 18);
		if (col >= COLS || row >= ROWS) return -1;
		return (row + scroll) * COLS + col;
	}

	private List<JobView> visibleJobs() {
		int from = Mth.clamp(jobScroll, 0, maxJobScroll());
		return menu.jobs.subList(from, Math.min(from + JOB_ROWS, menu.jobs.size()));
	}

	// ------------------------------------------------------------------ Rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor gr, int mouseX, int mouseY, float a) {
		super.extractBackground(gr, mouseX, mouseY, a);
		gr.blit(RenderPipelines.GUI_TEXTURED, GUI, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int idx = gridIndexAt(mouseX - leftPos, mouseY - topPos);
		if (idx >= 0 && idx < menu.craftables.size()) {
			graphics.setTooltipForNextFrame(font, menu.craftables.get(idx).item(), mouseX, mouseY);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		super.extractLabels(g, xm, ym);

		// Grid
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < COLS; c++) {
				int i = (r + scroll) * COLS + c;
				if (i >= menu.craftables.size()) continue;
				Craftable cr = menu.craftables.get(i);
				int x = GRID_X + c * 18 + 1, y = GRID_Y + r * 18 + 1;
				if (!selected.isEmpty() && ItemStack.isSameItemSameComponents(cr.item(), selected)) {
					g.fill(x - 1, y - 1, x + 17, y + 17, 0x8000AAFF);
				}
				g.item(cr.item(), x, y);
				if (cr.stock() > 0) {
					String s = shortNumber(cr.stock());
					g.text(font, s, x + 17 - font.width(s), y + 9, 0xFFFFFFFF, true);
				}
			}
		}
		if (maxScroll() > 0) {
			g.text(font, (scroll + 1) + "/" + (maxScroll() + 1), 150, 6, 0xFF404040, false);
		}

		// Selection
		if (!selected.isEmpty()) {
			g.item(selected, 8, 75);
			g.text(font, selected.getHoverName(), 28, 79, 0xFF404040, false);
		} else {
			g.text(font, Component.translatable("label.tsautocraft.select"), 8, 79, 0xFF707070, false);
		}

		// Message + missing items
		if (!menu.message.isEmpty()) {
			MutableComponent line = Component.translatable(menu.message);
			for (var m : menu.missing) {
				line.append(" ").append(Component.translatable("label.tsautocraft.item_count", m.count(), m.item().getHoverName()));
			}
			String text = font.plainSubstrByWidth(line.getString(), 162);
			int color = menu.missing.isEmpty() && menu.message.endsWith("started") ? 0xFF006400 : 0xFF8B0000;
			g.text(font, text, 8, 114, color, false);
		}

		// Jobs
		List<JobView> visible = visibleJobs();
		if (visible.isEmpty()) {
			g.text(font, Component.translatable("label.tsautocraft.no_jobs"), 8, JOBS_Y + 1, 0xFF707070, false);
		}
		for (int i = 0; i < visible.size(); i++) {
			JobView j = visible.get(i);
			int y = JOBS_Y + i * JOB_H + 1;
			Component lineText = Component.translatable("label.tsautocraft.job_line", j.amount(), j.target().getHoverName(),
					j.done(), j.total(), statusText(j));
			// Requester jobs in blue
			g.text(font, font.plainSubstrByWidth(lineText.getString(), 150), 8, y, j.requester() ? 0xFF1F3F8F : 0xFF303030, false);
			if (j.state() <= CraftingJob.State.WAITING.ordinal()) g.text(font, "✕", 161, y, 0xFFAA0000, false);
		}
		if (menu.jobs.size() > JOB_ROWS) {
			// Mouse wheel over the list scrolls; show the visible range
			int from = Mth.clamp(jobScroll, 0, maxJobScroll());
			String range = (from + 1) + "-" + (from + visible.size()) + "/" + menu.jobs.size();
			g.text(font, range, 170 - font.width(range), JOBS_Y + JOB_ROWS * JOB_H, 0xFF707070, false);
		}
	}

	static Component statusText(JobView j) {
		CraftingJob.State st = CraftingJob.State.values()[Mth.clamp(j.state(), 0, CraftingJob.State.values().length - 1)];
		if (st == CraftingJob.State.DONE) return Component.translatable("status.tsautocraft.done");
		if (st == CraftingJob.State.CANCELLED) return Component.translatable("status.tsautocraft.cancelled");
		CraftingJob.Reason r = CraftingJob.Reason.values()[Mth.clamp(j.reason(), 0, CraftingJob.Reason.values().length - 1)];
		String key = "reason.tsautocraft." + r.name().toLowerCase(java.util.Locale.ROOT);
		return j.reasonItem().isEmpty() ? Component.translatable(key) : Component.translatable(key, j.reasonItem().getHoverName());
	}

	/** Strips everything but digits; re-entrant call from setValue is a no-op because the text is then clean. */
	static void digitsOnly(EditBox box, String s) {
		String digits = s.replaceAll("[^0-9]", "");
		if (!digits.equals(s)) box.setValue(digits);
	}

	static String shortNumber(long n) {
		if (n < 1000) return Long.toString(n);
		if (n < 1_000_000) return Component.translatable("label.tsautocraft.number_thousands", n / 1000).getString();
		return Component.translatable("label.tsautocraft.number_millions", n / 1_000_000).getString();
	}

	@Override
	public void getExclusionAreas(Consumer<Box> consumer) {
	}
}
