package eu.sytxlabs.tsautocraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.tom.storagemod.screen.IScreen.Box;

public final class GuiUtil {
	public static final int UPGRADE_W = 26;

	private GuiUtil() {}

	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
		g.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
		g.fill(x + 1, y + 1, x + w - 2, y + 3, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 3, y + h - 2, 0xFFFFFFFF);
		g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, 0xFF555555);
		g.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
	}

	/** Sunken 18x18 slot frame; (x, y) = top left of the frame (item position - 1). */
	public static void slot(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
		g.fill(x, y, x + 17, y + 1, 0xFF373737);
		g.fill(x, y, x + 1, y + 17, 0xFF373737);
		g.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
		g.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
	}

	/** Upgrade panel right of the GUI with {@code n} slots (slot positions: {@code menu/UpgradeSlot}). */
	public static void upgradePanel(GuiGraphicsExtractor g, int leftPos, int topPos, int imageWidth, int n) {
		int x = leftPos + imageWidth - 2;
		panel(g, x, topPos, UPGRADE_W + 2, 10 + n * 18);
		for (int i = 0; i < n; i++) slot(g, x + 6, topPos + 5 + i * 18);
	}

	/** Exclusion area for JEI/REI. */
	public static Box upgradeBox(int leftPos, int topPos, int imageWidth, int n) {
		return new Box(leftPos + imageWidth - 2, topPos, UPGRADE_W + 2, 10 + n * 18);
	}
}
