package eu.sytxlabs.tsautocraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/tsautocraft.json}, read once at startup (server side behaviour; restart to apply changes).
 * Missing options are filled with their defaults and written back.
 */
public final class TsAutocraftConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static TsAutocraftConfig instance = new TsAutocraftConfig();

	public enum CrafterTrigger {
		/** The provider only fills the crafter; the player's own redstone circuit makes it craft. */
		@SerializedName("external") EXTERNAL,
		/** The provider makes the crafter craft directly (scheduled block tick), no redstone signal. */
		@SerializedName("direct") DIRECT,
		/** 2-tick redstone pulse from the provider to the crafter; the provider is then a redstone source. */
		@SerializedName("redstone") REDSTONE
	}

	/** How a filled crafter is triggered: "external" (default), "direct" or "redstone". */
	public CrafterTrigger crafterTrigger = CrafterTrigger.EXTERNAL;

	/**
	 * false (default): crafter slot settings are never changed. The recipe is moved inside the 3x3 grid so that it only
	 * uses enabled slots; if it does not fit, the job waits with "crafter slot disabled".
	 * true: inputs go exactly to the pattern's positions and the crafter re-enables disabled slots it gets items in.
	 */
	public boolean enableDisabledCrafterSlots = false;

	// ------------------------------------------------------------------ Pattern Provider / crafter

	/** Minimum ticks between two deliveries to the same crafter. */
	public int crafterCooldownTicks = 6;
	/** Length of the redstone pulse in "redstone" mode, also how long the provider front lights up. */
	public int crafterPulseTicks = 2;
	/** "direct"/"redstone": a crafter still holding foreign items after this many ticks is emptied back into the network. */
	public int crafterStuckTicks = 20;

	// ------------------------------------------------------------------ Jobs

	/** Running jobs per Autocrafting Terminal. */
	public int maxJobsPerTerminal = 8;
	/** Largest amount one request may ask for. */
	public int maxRequestAmount = 100_000;
	/** A waiting job ends after this many seconds without any result coming back. */
	public int jobResultTimeoutSeconds = 120;
	/** Without Importer reports: a waiting job ends after this many seconds without the target stock rising. */
	public int jobIdleSeconds = 20;

	// ------------------------------------------------------------------ Requester

	/** How often (ticks) a Requester checks its stock. */
	public int requesterCheckTicks = 20;
	/** At most this many items per Requester job; the rest follows in the next round. */
	public int requesterMaxBatch = 1024;
	/** A Requester job that waits this many seconds for missing ingredients is planned again. */
	public int requesterReplanSeconds = 10;

	// ------------------------------------------------------------------ Importer / Exporter / terminals

	/** Ticks between two Importer/Exporter operations. */
	public int ioIntervalTicks = 8;
	/** Items moved per Importer/Exporter operation (at most one stack). */
	public int ioItemsPerOperation = 16;
	/** How often (ticks) Tom's terminals refresh the list of craftable items. */
	public int craftablesSyncTicks = 20;

	public static TsAutocraftConfig get() {
		return instance;
	}

	/** Pattern Providers are redstone sources only in {@link CrafterTrigger#REDSTONE} mode. */
	public static boolean providerEmitsRedstone() {
		return instance.crafterTrigger == CrafterTrigger.REDSTONE;
	}

	/** Unknown or nonsensical values -> defaults / sane minimums. */
	private void sanitize() {
		if (crafterTrigger == null) crafterTrigger = CrafterTrigger.EXTERNAL;
		crafterCooldownTicks = Math.max(1, crafterCooldownTicks);
		crafterPulseTicks = Math.max(1, crafterPulseTicks);
		crafterStuckTicks = Math.max(1, crafterStuckTicks);
		maxJobsPerTerminal = Math.max(1, maxJobsPerTerminal);
		maxRequestAmount = Math.max(1, maxRequestAmount);
		jobResultTimeoutSeconds = Math.max(1, jobResultTimeoutSeconds);
		jobIdleSeconds = Math.max(1, jobIdleSeconds);
		requesterCheckTicks = Math.max(1, requesterCheckTicks);
		requesterMaxBatch = Math.max(1, requesterMaxBatch);
		requesterReplanSeconds = Math.max(1, requesterReplanSeconds);
		ioIntervalTicks = Math.max(1, ioIntervalTicks);
		ioItemsPerOperation = Math.clamp(ioItemsPerOperation, 1, 64);
		craftablesSyncTicks = Math.max(1, craftablesSyncTicks);
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(TsAutocraft.MODID + ".json");
		TsAutocraftConfig cfg = null;
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				cfg = GSON.fromJson(r, TsAutocraftConfig.class);
			} catch (IOException | JsonParseException e) {
				TsAutocraft.LOGGER.error("Could not read {}, using defaults", file, e);
			}
		}
		instance = cfg != null ? cfg : new TsAutocraftConfig();
		instance.sanitize();
		try (Writer w = Files.newBufferedWriter(file)) {
			GSON.toJson(instance, w);
		} catch (IOException e) {
			TsAutocraft.LOGGER.error("Could not write {}", file, e);
		}
	}
}
