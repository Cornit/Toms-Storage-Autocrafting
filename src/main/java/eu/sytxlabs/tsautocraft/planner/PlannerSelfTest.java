package eu.sytxlabs.tsautocraft.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minecraft-free self test of the planner. Run: {@code ./gradlew plannerTest}. */
public final class PlannerSelfTest {
	record Pat(String name, Map<String, Long> in, Map<String, Long> out) {
		@Override public String toString() { return name; }
	}

	private static int failures;

	public static void main(String[] args) {
		Pat planks = pat("planks", Map.of("log", 1L), Map.of("planks", 4L));
		Pat sticks = pat("sticks", Map.of("planks", 2L), Map.of("stick", 4L));
		Pat chest = pat("chest", Map.of("planks", 8L), Map.of("chest", 1L));
		Pat torch = pat("torch", Map.of("stick", 1L, "coal", 1L), Map.of("torch", 4L));
		Pat charcoal = pat("charcoal", Map.of("log", 1L), Map.of("coal", 1L));

		// 1) Chest from logs: 8 planks = 2 crafts from 2 logs
		{
			var r = planner(Map.of("log", 64L), planks, chest).plan("chest", 1, false);
			check("chest complete", r.isComplete());
			check("chest steps", stepsOf(r).equals(Map.of("planks", 2L, "chest", 1L)));
			check("chest order", r.steps().get(0).pattern() == planks);
			check("chest logs", r.fromStock().get("log") == 2L);
		}
		// 2) Reuse surplus: sticks and torches share plank crafts
		{
			var r = planner(Map.of("log", 64L, "coal", 64L), planks, sticks, torch).plan("torch", 16, false);
			// 16 torches = 4 crafts -> 4 sticks = 1 craft -> 2 planks = 1 craft -> 1 log
			check("torch steps", stepsOf(r).equals(Map.of("planks", 1L, "sticks", 1L, "torch", 4L)));
			check("torch coal", r.fromStock().get("coal") == 4L);
		}
		// 3) Missing items are reported
		{
			var r = planner(Map.of("log", 1L), planks, chest).plan("chest", 1, false);
			check("missing logs", r.missing().equals(Map.of("log", 1L)));
		}
		// 4) Stock is used for intermediates
		{
			var r = planner(Map.of("planks", 8L), planks, chest).plan("chest", 1, false);
			check("stock planks", stepsOf(r).equals(Map.of("chest", 1L)) && r.isComplete());
		}
		// 5) Requester mode: target stock counts
		{
			var r = planner(Map.of("chest", 3L, "log", 10L), planks, chest).plan("chest", 3, true);
			check("requester satisfied", r.steps().isEmpty() && r.isComplete());
		}
		// 6) Cycle ends as missing instead of an endless loop
		{
			Pat a = pat("a", Map.of("b", 1L), Map.of("a", 1L));
			Pat b = pat("b", Map.of("a", 1L), Map.of("b", 1L));
			var r = planner(Map.of(), a, b).plan("a", 1, false);
			check("cycle missing", !r.isComplete());
		}
		// 7) Second pattern as fallback (coal vs. charcoal)
		{
			Pat coalFromOre = pat("coal_ore", Map.of("coal_ore", 1L), Map.of("coal", 1L));
			var r = planner(Map.of("log", 5L), coalFromOre, charcoal).plan("coal", 3, false);
			check("fallback complete", r.isComplete());
			check("fallback uses charcoal", stepsOf(r).equals(Map.of("charcoal", 3L)));
		}
		// 8) Repeated use of the same pattern is merged
		{
			Pat ladder = pat("ladder", Map.of("stick", 7L), Map.of("ladder", 3L));
			Pat both = pat("both", Map.of("ladder", 1L, "chest", 1L), Map.of("both", 1L));
			var r = planner(Map.of("log", 64L), planks, sticks, chest, ladder, both).plan("both", 1, false);
			check("merged complete", r.isComplete());
			long plankCrafts = stepsOf(r).get("planks");
			// Ladder: 7 sticks = 2 stick crafts = 4 planks; chest 8 planks -> 12 planks = 3 crafts
			check("merged planks=3 (got " + plankCrafts + ")", plankCrafts == 3L);
		}

		if (failures > 0) {
			System.err.println(failures + " test(s) failed");
			System.exit(1);
		}
		System.out.println("All planner tests passed");
	}

	private static Pat pat(String n, Map<String, Long> in, Map<String, Long> out) {
		return new Pat(n, in, out);
	}

	private static CraftingPlanner<String, Pat> planner(Map<String, Long> stock, Pat... pats) {
		Map<String, List<Pat>> byOut = new HashMap<>();
		for (Pat p : pats) p.out().keySet().forEach(k -> byOut.computeIfAbsent(k, x -> new ArrayList<>()).add(p));
		return new CraftingPlanner<>(k -> stock.getOrDefault(k, 0L), byOut::get, Pat::in, Pat::out);
	}

	private static Map<String, Long> stepsOf(CraftingPlanner.Result<String, Pat> r) {
		Map<String, Long> m = new LinkedHashMap<>();
		r.steps().forEach(s -> m.merge(s.pattern().name(), s.crafts(), Long::sum));
		return m;
	}

	private static void check(String name, boolean ok) {
		System.out.println((ok ? "OK   " : "FAIL ") + name);
		if (!ok) failures++;
	}
}
