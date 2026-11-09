package eu.sytxlabs.tsautocraft.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraft;

/**
 * Client-only "Autocrafting Guide" (opened by the guide book item). Each page is one text file per language,
 * {@code assets/tsautocraft/guide/<lang>/<id>.txt} (first line = title, the rest = text; missing language = en_us, so
 * resource packs can add translations), plus optional icons and an optional diagram (drawn above the text of its first sheet).
 * Long texts are split into several sheets automatically; sheet 0 is the table of contents.
 * To add a page: add it to {@link #PAGES} and add its file to every language folder.
 * <p>
 * Diagram: one string per row, cells separated by spaces. A cell is an item id ({@code mc:} = minecraft, {@code ts:} =
 * Tom's Storage, {@code ac:} = this mod, or a full id), an arrow ({@code > < ^ v}), a cable piece ({@code - |}) or {@code .} (empty).
 */
public class GuideScreen extends Screen {
	private static final int W = 280, H = 200;
	private static final int TEXT_X = 10, TEXT_Y = 30, TEXT_W = W - 20, TEXT_BOTTOM = H - 30;
	private static final int TOC_ROW_H = 12, TOC_COL_W = (W - 20) / 2;
	private static final int CELL = 20;
	private static final int TEXT_COLOR = 0xFF303030, TITLE_COLOR = 0xFF1F3F5F, LINK_COLOR = 0xFF1F4F8F, LINK_HOVER = 0xFF2F8F2F;

	private record Page(String id, List<Supplier<ItemLike>> icons, List<String[]> diagram) {
		/** Same page with a diagram (see class doc). */
		Page diagram(String... rows) {
			List<String[]> cells = new ArrayList<>(rows.length);
			for (String r : rows) cells.add(r.trim().split("\\s+"));
			return new Page(id, icons, cells);
		}

		int diagramHeight() {
			return diagram.isEmpty() ? 0 : diagram.size() * CELL + 8;
		}
	}

	/** Title and text of one page in the selected language. */
	private record Content(Component title, Component text) {}

	/** Reads {@code guide/<lang>/<id>.txt} (selected language, else en_us); first line = title, the rest = text. */
	private static Content load(String id) {
		Minecraft mc = Minecraft.getInstance();
		String lang = mc.getLanguageManager().getSelected();
		for (String l : lang.equals(DEFAULT_LANG) ? List.of(DEFAULT_LANG) : List.of(lang, DEFAULT_LANG)) {
			var res = mc.getResourceManager().getResource(TsAutocraft.id("guide/" + l + "/" + id + ".txt"));
			if (res.isEmpty()) continue;
			try (BufferedReader r = res.get().openAsReader()) {
				String title = r.readLine();
				if (title == null) continue;
				String text = r.lines().collect(Collectors.joining("\n")).strip();
				return new Content(Component.literal(title.replace("﻿", "").strip()), Component.literal(text));
			} catch (IOException e) {
				TsAutocraft.LOGGER.warn("Could not read guide page {} ({})", id, l, e);
			}
		}
		return new Content(Component.literal(id), Component.empty());
	}

	@SafeVarargs
	private static Page page(String id, Supplier<ItemLike>... icons) {
		return new Page(id, List.of(icons), List.of());
	}

	@SuppressWarnings("unchecked")
	private static final List<Page> PAGES = List.of(
			page("overview", () -> ModContent.GUIDE_BOOK),
			page("setup", () -> ModContent.AUTOCRAFTING_TERMINAL, () -> ModContent.PATTERN_TERMINAL, () -> ModContent.PATTERN_PROVIDER, () -> Items.CRAFTER, () -> ModContent.IMPORTER).diagram(
					"mc:chest ts:inventory_connector - - ac:autocrafting_terminal",
					". . | . ac:pattern_terminal",
					". . ac:pattern_provider > mc:crafter > mc:chest < ac:importer",
					". . | . . . . |",
					". . - - - - - -"),
			page("pattern_terminal", () -> ModContent.PATTERN_TERMINAL, () -> ModContent.BLANK_PATTERN, () -> ModContent.PATTERN),
			page("pattern_provider", () -> ModContent.PATTERN_PROVIDER, () -> ModContent.PATTERN_PROVIDER_CONNECTOR, () -> ModContent.PATTERN_PROVIDER_CONNECTOR_FRAMED).diagram(
					". mc:crafter . . . .",
					"mc:furnace ac:pattern_provider mc:crafter . ac:pattern_provider_connector > mc:blast_furnace",
					". | . . | .",
					". - - - - ."),
			page("autocrafting_terminal", () -> ModContent.AUTOCRAFTING_TERMINAL),
			page("requesting", () -> ModContent.AUTOCRAFTING_TERMINAL),
			page("pattern_access_terminal", () -> ModContent.PATTERN_ACCESS_TERMINAL),
			page("requester", () -> ModContent.REQUESTER),
			page("level_maintainer", () -> ModContent.LEVEL_MAINTAINER).diagram(
					"ac:level_maintainer - - ac:autocrafting_terminal - - ac:pattern_provider > mc:crafter"),
			page("crafting_emitter", () -> ModContent.CRAFTING_EMITTER, () -> ModContent.CRAFTING_EMITTER_DIRECTIONAL, () -> ModContent.CRAFTING_EMITTER_DIRECTIONAL_FRAMED),
			page("exporter_importer", () -> ModContent.EXPORTER, () -> ModContent.IMPORTER).diagram(
					"- ac:exporter > mc:chest . mc:hopper > mc:chest < ac:importer -"),
			page("upgrades", () -> ModContent.CAPACITY_UPGRADE, () -> ModContent.SPEED_UPGRADE, () -> ModContent.STACK_UPGRADE, () -> ModContent.CRAFTING_UPGRADE, () -> ModContent.REDSTONE_UPGRADE),
			page("mode_crafting", () -> Items.CRAFTER).diagram(
					"ac:pattern_provider_connector > mc:crafter > mc:chest < ac:importer"),
			page("mode_processing", () -> Items.CHEST).diagram(
					"ac:pattern_provider_connector > mc:dispenser > mc:water_bucket",
					". . ^ .",
					". . ac:importer ."),
			page("mode_smelting", () -> Items.FURNACE).diagram(
					"ac:pattern_provider_connector > mc:furnace",
					". . v",
					". . ac:importer"),
			page("mode_fluid", () -> Items.WATER_BUCKET).diagram(
					"ac:pattern_provider_connector > mc:cauldron < ac:importer"),
			page("mode_brewing", () -> Items.BREWING_STAND).diagram(
					"ac:pattern_provider_connector > mc:brewing_stand < mc:blaze_powder",
					". . v .",
					". . ac:importer ."),
			page("jei", () -> Items.KNOWLEDGE_BOOK),
			page("config", () -> Items.COMPARATOR));

	/** One screen of text: the page it belongs to and its lines; page -1 = table of contents. */
	private record Sheet(int page, List<FormattedCharSequence> lines, int part, int parts) {}

	private static final String DEFAULT_LANG = "en_us";

	private final List<Content> contents = new ArrayList<>();
	private final List<Sheet> sheets = new ArrayList<>();
	private final int[] firstSheet = new int[PAGES.size()];
	private int current;
	private int left, top;
	private Button prevBtn, nextBtn, tocBtn;

	public GuideScreen() {
		super(Component.translatable("screen.tsautocraft.guide"));
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = (height - H) / 2;
		buildSheets();
		prevBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.guide.prev"), _ -> turn(-1)).bounds(left + 8, top + H - 24, 20, 18).build());
		nextBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.guide.next"), _ -> turn(1)).bounds(left + W - 28, top + H - 24, 20, 18).build());
		tocBtn = addRenderableWidget(Button.builder(Component.translatable("button.tsautocraft.guide.contents"), _ -> show(0)).bounds(left + 32, top + H - 24, 70, 18).build());
		show(current);
	}

	/** Splits every page into sheets that fit the text area (depends on the font, so done in init). */
	private void buildSheets() {
		sheets.clear();
		contents.clear();
		for (Page p : PAGES) contents.add(load(p.id()));
		int tocRows = (TEXT_BOTTOM - TEXT_Y) / TOC_ROW_H;
		int tocSheets = Math.max(1, (PAGES.size() + 2 * tocRows - 1) / (2 * tocRows));
		for (int i = 0; i < tocSheets; i++) sheets.add(new Sheet(-1, List.of(), i, tocSheets));
		int perSheet = (TEXT_BOTTOM - TEXT_Y) / font.lineHeight;
		for (int p = 0; p < PAGES.size(); p++) {
			List<FormattedCharSequence> lines = font.split(contents.get(p).text(), TEXT_W);
			int first = Math.max(1, perSheet - (PAGES.get(p).diagramHeight() + font.lineHeight - 1) / font.lineHeight); // the diagram takes room on the first sheet
			int parts = 1 + Math.max(0, (lines.size() - first + perSheet - 1) / perSheet);
			firstSheet[p] = sheets.size();
			for (int i = 0, from = 0; i < parts; i++) {
				int to = Math.min(lines.size(), from + (i == 0 ? first : perSheet));
				sheets.add(new Sheet(p, lines.subList(from, to), i, parts));
				from = to;
			}
		}
	}

	private void turn(int delta) {
		show(current + delta);
	}

	private void show(int sheet) {
		current = Math.clamp(sheet, 0, sheets.size() - 1);
		if (prevBtn != null) prevBtn.active = current > 0;
		if (nextBtn != null) nextBtn.active = current < sheets.size() - 1;
		if (tocBtn != null) tocBtn.active = sheets.get(current).page() >= 0;
	}

	/** Index of the page whose table of contents entry is under the mouse, -1 if none. */
	private int tocEntryAt(double mx, double my) {
		Sheet s = sheets.get(current);
		if (s.page() >= 0) return -1;
		int rows = (TEXT_BOTTOM - TEXT_Y) / TOC_ROW_H;
		int x = (int) mx - left - TEXT_X, y = (int) my - top - TEXT_Y;
		if (x < 0 || y < 0 || x >= 2 * TOC_COL_W || y >= rows * TOC_ROW_H) return -1;
		int idx = s.part() * 2 * rows + (x / TOC_COL_W) * rows + y / TOC_ROW_H;
		return idx < PAGES.size() ? idx : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int idx = tocEntryAt(event.x(), event.y());
		if (idx >= 0) {
			show(firstSheet[idx]);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (dy != 0) {
			turn(dy > 0 ? -1 : 1);
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_PAGEUP) {
			turn(-1);
			return true;
		}
		if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_PAGEDOWN) {
			turn(1);
			return true;
		}
		return super.keyPressed(event);
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
		// paper area
		g.fill(left + 6, top + 24, left + W - 6, top + TEXT_BOTTOM + 3, 0xFF8B8B8B);
		g.fill(left + 7, top + 25, left + W - 7, top + TEXT_BOTTOM + 2, 0xFFF3ECD8);

		Sheet s = sheets.get(current);
		ItemStack hovered = ItemStack.EMPTY;
		if (s.page() < 0) {
			g.text(font, Component.translatable("guide.tsautocraft.contents"), left + 8, top + 9, TITLE_COLOR, false);
			int rows = (TEXT_BOTTOM - TEXT_Y) / TOC_ROW_H;
			int hover = tocEntryAt(mouseX, mouseY);
			for (int i = 0; i < 2 * rows; i++) {
				int idx = s.part() * 2 * rows + i;
				if (idx >= PAGES.size()) break;
				int x = left + TEXT_X + (i / rows) * TOC_COL_W, y = top + TEXT_Y + (i % rows) * TOC_ROW_H;
				String label = Component.translatable("guide.tsautocraft.toc_entry", idx + 1, contents.get(idx).title()).getString();
				g.text(font, font.plainSubstrByWidth(label, TOC_COL_W - 4), x, y + 2, idx == hover ? LINK_HOVER : LINK_COLOR, false);
			}
		} else {
			Page p = PAGES.get(s.page());
			Component pageTitle = contents.get(s.page()).title();
			Component title = s.parts() > 1 ? Component.translatable("guide.tsautocraft.part", pageTitle, s.part() + 1, s.parts()) : pageTitle;
			int iconX = left + W - 8 - 17 * p.icons().size();
			g.text(font, font.plainSubstrByWidth(title.getString(), iconX - left - 12), left + 8, top + 9, TITLE_COLOR, false);
			for (int i = 0; i < p.icons().size(); i++) {
				ItemStack icon = new ItemStack(p.icons().get(i).get());
				int x = iconX + i * 17, y = top + 4;
				g.item(icon, x, y);
				if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) hovered = icon;
			}
			int y = top + TEXT_Y;
			if (s.part() == 0 && !p.diagram().isEmpty()) {
				ItemStack h = drawDiagram(g, p.diagram(), y, mouseX, mouseY);
				if (!h.isEmpty()) hovered = h;
				y += p.diagramHeight();
			}
			for (FormattedCharSequence line : s.lines()) {
				g.text(font, line, left + TEXT_X, y, TEXT_COLOR, false);
				y += font.lineHeight;
			}
		}
		Component pageNo = Component.translatable("guide.tsautocraft.page", current + 1, sheets.size());
		g.text(font, pageNo, left + (102 + W - 28 - font.width(pageNo)) / 2, top + H - 19, 0xFF404040, false);
		if (!hovered.isEmpty()) g.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
	}

	/** Draws a diagram centered at {@code y}; returns the item under the mouse (or empty). */
	private ItemStack drawDiagram(GuiGraphicsExtractor g, List<String[]> rows, int y, int mouseX, int mouseY) {
		int cols = 0;
		for (String[] r : rows) cols = Math.max(cols, r.length);
		int x0 = left + (W - cols * CELL) / 2;
		g.fill(x0 - 3, y - 1, x0 + cols * CELL + 3, y + rows.size() * CELL + 3, 0xFFE4DAC0);
		ItemStack hovered = ItemStack.EMPTY;
		for (int row = 0; row < rows.size(); row++) {
			String[] cells = rows.get(row);
			for (int col = 0; col < cells.length; col++) {
				int x = x0 + col * CELL, cy = y + 1 + row * CELL;
				String c = cells[col];
				switch (c) {
					case "." -> {}
					case "-" -> g.fill(x, cy + CELL / 2 - 2, x + CELL, cy + CELL / 2 + 1, 0xFF6B6B6B);
					case "|" -> g.fill(x + CELL / 2 - 2, cy, x + CELL / 2 + 1, cy + CELL, 0xFF6B6B6B);
					case ">", "<", "^", "v" -> {
						String arrow = switch (c) {
							case ">" -> "\u2192";
							case "<" -> "\u2190";
							case "^" -> "\u2191";
							default -> "\u2193";
						};
						g.text(font, arrow, x + (CELL - font.width(arrow)) / 2, cy + (CELL - font.lineHeight) / 2 + 1, 0xFF8F2F1F, false);
					}
					default -> {
						ItemStack st = diagramItem(c);
						if (st.isEmpty()) continue;
						g.fill(x + 1, cy + 1, x + CELL - 1, cy + CELL - 1, 0xFFB8AE94);
						g.item(st, x + 2, cy + 2);
						if (mouseX >= x && mouseX < x + CELL && mouseY >= cy && mouseY < cy + CELL) hovered = st;
					}
				}
			}
		}
		return hovered;
	}

	/** Parsed diagram cells (drawn every frame). Read only stacks. */
	private static final java.util.Map<String, ItemStack> DIAGRAM_ITEMS = new java.util.HashMap<>();

	private static ItemStack diagramItem(String cell) {
		return DIAGRAM_ITEMS.computeIfAbsent(cell, GuideScreen::parseDiagramItem);
	}

	private static ItemStack parseDiagramItem(String cell) {
		String id = cell.startsWith("mc:") ? "minecraft:" + cell.substring(3) : cell.startsWith("ts:") ? "toms_storage:" + cell.substring(3) : cell.startsWith("ac:") ? "tsautocraft:" + cell.substring(3) : cell;
		Identifier key = Identifier.tryParse(id);
		if (key == null) return ItemStack.EMPTY;
		Item item = BuiltInRegistries.ITEM.getValue(key);
		return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
	}
}
