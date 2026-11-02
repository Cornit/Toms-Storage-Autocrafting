package eu.sytxlabs.tsautocraft.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import eu.sytxlabs.tsautocraft.TsAutocraft;

/**
 * Registry of {@link IOMode}s. The order (built-ins first) is the cycle order of the mode button and is synced by index, so register on client and server alike.
 */
public final class IOModes {
	private static final List<IOMode> MODES = new ArrayList<>();

	/** Exporter: next filter slot after the last one that moved something; Importer: next inventory slot. */
	public static final IOMode ROUND_ROBIN = register(new Builtin("round_robin", Items.COMPARATOR, true) {
		@Override
		public int[] order(Context ctx, int slots) {
			int[] o = new int[slots];
			for (int i = 0; i < slots; i++) o[i] = Math.floorMod(ctx.cursor() + i, slots);
			return o;
		}

		@Override
		public int start(Context ctx, int size) {
			return size <= 0 ? 0 : Math.floorMod(ctx.cursor(), size);
		}

		@Override
		public void moved(Context ctx, int index) {
			ctx.setCursor(index + 1);
		}
	});

	/** Always the first filter slot / inventory slot that works (filters top left first). */
	public static final IOMode PRIORITY = register(new Builtin("priority", Items.REPEATER, true));

	/** Random filter slot / start slot each operation. */
	public static final IOMode RANDOM = register(new Builtin("random", Items.DROPPER, true) {
		@Override
		public int[] order(Context ctx, int slots) {
			int[] o = super.order(ctx, slots);
			for (int i = slots - 1; i > 0; i--) {
				int j = ctx.random().nextInt(i + 1);
				int t = o[i];
				o[i] = o[j];
				o[j] = t;
			}
			return o;
		}

		@Override
		public int start(Context ctx, int size) {
			return size <= 0 ? 0 : ctx.random().nextInt(size);
		}
	});

	/** Importer only: everything except the filtered items. */
	public static final IOMode BLACKLIST = register(new Builtin("blacklist", Items.BARRIER, false) {
		@Override
		public boolean blacklist() {
			return true;
		}
	});

	private IOModes() {}

	public static synchronized IOMode register(IOMode mode) {
		for (IOMode m : MODES) {
			if (m.id().equals(mode.id())) throw new IllegalArgumentException("IO mode " + mode.id() + " is already registered");
		}
		MODES.add(mode);
		return mode;
	}

	public static List<IOMode> all() {
		return Collections.unmodifiableList(MODES);
	}

	/** Mode by id; {@code fallback} for unknown ids or modes {@code host} does not support. */
	public static IOMode get(Identifier id, Identifier host, IOMode fallback) {
		for (IOMode m : MODES) if (m.id().equals(id) && m.supports(host)) return m;
		return fallback;
	}

	public static int indexOf(IOMode mode) {
		return Math.max(0, MODES.indexOf(mode));
	}

	public static IOMode byIndex(int i) {
		return i >= 0 && i < MODES.size() ? MODES.get(i) : PRIORITY;
	}

	/** Next mode that {@code host} supports. */
	public static IOMode next(IOMode mode, Identifier host) {
		int start = indexOf(mode);
		for (int k = 1; k <= MODES.size(); k++) {
			IOMode m = MODES.get((start + k) % MODES.size());
			if (m.supports(host)) return m;
		}
		return mode;
	}

	private static class Builtin implements IOMode {
		private final Identifier id;
		private final net.minecraft.world.item.Item icon;
		private final boolean exporter;

		Builtin(String name, net.minecraft.world.item.Item icon, boolean exporter) {
			this.id = TsAutocraft.id(name);
			this.icon = icon;
			this.exporter = exporter;
		}

		@Override
		public Identifier id() {
			return id;
		}

		@Override
		public ItemStack icon() {
			return new ItemStack(icon);
		}

		@Override
		public boolean supports(Identifier host) {
			return host.equals(Upgrades.IMPORTER) || exporter && host.equals(Upgrades.EXPORTER);
		}
	}
}
