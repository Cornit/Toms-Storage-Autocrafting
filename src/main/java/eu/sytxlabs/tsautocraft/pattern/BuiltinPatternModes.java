package eu.sytxlabs.tsautocraft.pattern;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import eu.sytxlabs.tsautocraft.api.CraftingProvider.DispatchResult;
import eu.sytxlabs.tsautocraft.api.MachineProfile;
import eu.sytxlabs.tsautocraft.api.MachineProfiles;
import eu.sytxlabs.tsautocraft.api.PatternMode;
import eu.sytxlabs.tsautocraft.blockentity.PatternProviderBlockEntity;
import eu.sytxlabs.tsautocraft.util.FluidUtil;
import eu.sytxlabs.tsautocraft.util.ItemKey;
import eu.sytxlabs.tsautocraft.util.StorageUtil;

/** The pattern modes of this mod. Registered by {@link eu.sytxlabs.tsautocraft.api.PatternModes}. */
public final class BuiltinPatternModes {
	private BuiltinPatternModes() {}

	/** Vanilla crafting recipe, delivered into a {@code minecraft:crafter}. */
	public static final PatternMode CRAFTING = new PatternMode() {
		@Override
		public Identifier id() {
			return PatternData.CRAFTING;
		}

		@Override
		public ItemStack icon() {
			return new ItemStack(Items.CRAFTER);
		}

		@Override
		public boolean fixedInputs() {
			return true;
		}

		@Override
		public boolean keepsGridPositions() {
			return true;
		}

		@Override
		public List<ItemStack> computeOutputs(Level level, List<ItemStack> grid) {
			return PatternEncoder.findRecipe(level, grid).map(r -> List.of(r.value().assemble(PatternEncoder.craftInput(grid)))).orElse(List.of());
		}

		@Override
		public PatternEncoder.Result encode(Level level, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
			return PatternEncoder.encodeCrafting(level, grid, substitutes);
		}

		@Override
		public boolean isValid(PatternData d) {
			return PatternMode.super.isValid(d) && d.inputs().size() == 9;
		}

		@Override
		public List<ItemStack> transferInputs(Level level, List<ItemStack> inputs) {
			return PatternEncoder.layoutGrid(level, inputs);
		}

		@Override
		public boolean matchesRecipe(Object recipe) {
			return recipe instanceof RecipeHolder<?> h && h.value() instanceof CraftingRecipe;
		}

		@Override
		public boolean matchesReiCategory(Identifier category) {
			return category.getNamespace().equals("minecraft") && category.getPath().equals("plugins/crafting");
		}

		@Override
		public Outcome dispatch(DispatchContext ctx, PatternData pattern) {
			return ((PatternProviderBlockEntity) ctx.provider()).dispatchCrafter(ctx, pattern);
		}
	};

	/** Any machine: all inputs are inserted at once, if everything fits. */
	public static final PatternMode PROCESSING = new PatternMode() {
		@Override
		public Identifier id() {
			return PatternData.PROCESSING;
		}

		@Override
		public ItemStack icon() {
			return new ItemStack(Items.HOPPER);
		}

		@Override
		public PatternEncoder.Result encode(Level level, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
			return PatternEncoder.encodeFree(id(), grid, outputs);
		}

		@Override
		public Outcome dispatch(DispatchContext ctx, PatternData pattern) {
			return dispatchInsert(ctx, pattern);
		}
	};

	/**
	 * Like processing, but inputs that are filled fluid containers (water bucket, potion, mod tanks...) are emptied into
	 * the machine's fluid storage; the empty containers go back into the network. Fluid results come back through an
	 * Importer with a filled container as filter (it fills empty containers from the network).
	 */
	public static final PatternMode FLUID = new PatternMode() {
		@Override
		public Identifier id() {
			return PatternData.FLUID;
		}

		@Override
		public ItemStack icon() {
			return new ItemStack(Items.WATER_BUCKET);
		}

		@Override
		public PatternEncoder.Result encode(Level level, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
			return PatternEncoder.encodeFree(id(), grid, outputs);
		}

		@Override
		public Outcome dispatch(DispatchContext ctx, PatternData pattern) {
			return dispatchFluid(ctx, pattern);
		}
	};

	/** Furnace, blast furnace or smoker: the input goes straight into the input slot. */
	public static final PatternMode SMELTING = new PatternMode() {
		@Override
		public Identifier id() {
			return PatternData.SMELTING;
		}

		@Override
		public ItemStack icon() {
			return new ItemStack(Items.FURNACE);
		}

		@Override
		public boolean fixedInputs() {
			return true;
		}

		@Override
		public List<ItemStack> computeOutputs(Level level, List<ItemStack> grid) {
			ItemStack r = PatternEncoder.smeltingResult(level, PatternEncoder.firstItem(grid));
			return r.isEmpty() ? List.of() : List.of(r);
		}

		@Override
		public PatternEncoder.Result encode(Level level, List<ItemStack> grid, List<ItemStack> outputs, boolean substitutes) {
			return PatternEncoder.encodeSmelting(level, grid);
		}

		@Override
		public boolean isValid(PatternData d) {
			return PatternMode.super.isValid(d) && d.inputs().stream().filter(e -> !e.isEmpty()).count() == 1;
		}

		@Override
		public boolean matchesRecipe(Object recipe) {
			return recipe instanceof RecipeHolder<?> h && h.value() instanceof AbstractCookingRecipe;
		}

		@Override
		public boolean matchesReiCategory(Identifier category) {
			return category.getNamespace().equals("minecraft") && (category.getPath().equals("plugins/smelting") || category.getPath().equals("plugins/blasting") || category.getPath().equals("plugins/smoking"));
		}

		@Override
		public Outcome dispatch(DispatchContext ctx, PatternData pattern) {
			return dispatchFurnace(ctx, pattern);
		}
	};

	/**
	 * Processing: one set after the other into the target's item storage, each set must fit completely.
	 * Blocking mode: only while none of the pattern's inputs are left in the target. A {@link MachineProfile} of the target may choose the faces to insert through, force blocking and ask for a redstone pulse after the delivery.
	 */
	public static PatternMode.Outcome dispatchInsert(PatternMode.DispatchContext ctx, PatternData p) {
        //noinspection resource
        MachineProfile profile = MachineProfiles.find(ctx.level().getBlockState(ctx.targetPos()));
		List<Storage<ItemVariant>> targets = new ArrayList<>();
		if (profile.sides().isEmpty()) {
			Storage<ItemVariant> s = StorageUtil.facingStorage(ctx.level(), ctx.providerPos(), ctx.facing());
			if (s != null) targets.add(s);
		} else {
			for (Direction side : profile.sides()) {
				Storage<ItemVariant> s = ItemStorage.SIDED.find(ctx.level(), ctx.targetPos(), side);
				if (s != null) targets.add(s);
			}
		}
		if (targets.isEmpty()) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		if (profile.blocking().orElse(ctx.blocking()) && targets.stream().anyMatch(t -> containsAnyInput(t, p))) {
			return PatternMode.Outcome.fail(DispatchResult.BUSY);
		}

		List<ItemStack> in = p.inputStacks();
		DispatchResult fail = DispatchResult.OK;
		int crafts = 0;
		for (int set = 0; set < ctx.maxCrafts(); set++) {
			if (!StorageUtil.insertAll(targets, in, false)) {
				fail = DispatchResult.NO_SPACE;
				break;
			}
			List<ItemStack> got = ctx.pull(in);
			if (got == null) {
				fail = DispatchResult.MISSING;
				break;
			}
			if (!StorageUtil.insertAll(targets, got, true)) got.forEach(ctx::giveBack); // changed in between: back to storage
			crafts++;
		}
		if (crafts > 0) ctx.redstonePulse(profile.redstonePulse());
		return crafts > 0 ? PatternMode.Outcome.ok(crafts) : PatternMode.Outcome.fail(fail);
	}

	/** Fluid processing: fluid containers are emptied into the target's fluid storage, other inputs are inserted. */
	public static PatternMode.Outcome dispatchFluid(PatternMode.DispatchContext ctx, PatternData p) {
		List<ItemStack> in = p.inputStacks();
		boolean[] fluid = new boolean[in.size()];
		boolean anyFluid = false, anyItem = false;
		for (int i = 0; i < in.size(); i++) {
			fluid[i] = FluidUtil.content(in.get(i)) != null;
			anyFluid |= fluid[i];
			anyItem |= !fluid[i];
		}
		Storage<FluidVariant> tank = FluidStorage.SIDED.find(ctx.level(), ctx.targetPos(), ctx.facing().getOpposite());
		Storage<ItemVariant> items = StorageUtil.facingStorage(ctx.level(), ctx.providerPos(), ctx.facing());
		if (anyFluid && tank == null || anyItem && items == null) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		List<ItemStack> itemIn = new ArrayList<>();
		for (int i = 0; i < in.size(); i++) if (!fluid[i]) itemIn.add(in.get(i));
		if (ctx.blocking() && (anyItem && containsAnyInput(items, p) || anyFluid && FluidUtil.containsAny(tank, FluidUtil.fluidsOf(in)))) {
			return PatternMode.Outcome.fail(DispatchResult.BUSY);
		}

		DispatchResult fail = DispatchResult.OK;
		int crafts = 0;
		for (int set = 0; set < ctx.maxCrafts(); set++) {
			if (anyFluid && !emptyAll(in, fluid, tank) || anyItem && !StorageUtil.insertAll(List.of(items), itemIn, false)) {
				fail = DispatchResult.NO_SPACE;
				break;
			}
			List<ItemStack> got = ctx.pull(in);
			if (got == null) {
				fail = DispatchResult.MISSING;
				break;
			}
			List<ItemStack> gotItems = new ArrayList<>();
			for (int i = 0; i < got.size(); i++) if (!fluid[i]) gotItems.add(got.get(i));
			if (anyFluid) {
				List<ItemStack> empties = emptyAllCommit(got, fluid, tank);
				if (empties == null) {
					got.forEach(ctx::giveBack); // changed in between: everything back to storage
					break;
				}
				empties.forEach(ctx::giveBack);
			}
			if (anyItem && !StorageUtil.insertAll(List.of(items), gotItems, true)) gotItems.forEach(ctx::giveBack);
			crafts++;
		}
		return crafts > 0 ? PatternMode.Outcome.ok(crafts) : PatternMode.Outcome.fail(fail);
	}

	/** Test: do the fluids of all containers marked in {@code fluid} fit into {@code tank}? */
	private static boolean emptyAll(List<ItemStack> stacks, boolean[] fluid, Storage<FluidVariant> tank) {
		try (Transaction t = Transaction.openOuter()) {
			for (int i = 0; i < stacks.size(); i++) {
				if (!fluid[i]) continue;
				ItemStack st = stacks.get(i);
				for (int n = 0; n < st.getCount(); n++) if (FluidUtil.emptyInto(st.copyWithCount(1), tank, t) == null) return false;
			}
			return true;
		}
	}

	/** Empties the containers for real; returns the empty containers, or null (nothing changed) if something did not fit. */
	private static List<ItemStack> emptyAllCommit(List<ItemStack> stacks, boolean[] fluid, Storage<FluidVariant> tank) {
		List<ItemStack> empties = new ArrayList<>();
		try (Transaction t = Transaction.openOuter()) {
			for (int i = 0; i < stacks.size(); i++) {
				if (!fluid[i]) continue;
				ItemStack st = stacks.get(i);
				for (int n = 0; n < st.getCount(); n++) {
					ItemStack empty = FluidUtil.emptyInto(st.copyWithCount(1), tank, t);
					if (empty == null) return null;
					if (!empty.isEmpty()) empties.add(empty);
				}
			}
			t.commit();
		}
		return empties;
	}

	/** Smelting: the input goes into the furnace's input slot, several at once with Stack Upgrades. */
	public static PatternMode.Outcome dispatchFurnace(PatternMode.DispatchContext ctx, PatternData p) {
        //noinspection resource
        if (!(ctx.level().getBlockEntity(ctx.targetPos()) instanceof AbstractFurnaceBlockEntity furnace)) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		List<ItemStack> in = p.inputStacks();
		if (in.size() != 1) return PatternMode.Outcome.fail(DispatchResult.NO_TARGET);
		ItemStack want = in.getFirst();
		ItemStack cur = furnace.getItem(0);
		int room = want.getMaxStackSize();
		if (!cur.isEmpty()) {
			if (ctx.blocking() || !ItemStack.isSameItemSameComponents(cur, want)) return PatternMode.Outcome.fail(DispatchResult.BUSY);
			room -= cur.getCount();
		}
		int per = Math.max(1, want.getCount());
		int n = Math.min(ctx.maxCrafts(), room / per);
		if (n <= 0) return PatternMode.Outcome.fail(DispatchResult.BUSY);
		long avail = ctx.available().applyAsLong(new ItemKey(want));
		n = (int) Math.min(n, avail / per);
		if (n <= 0) return PatternMode.Outcome.fail(DispatchResult.MISSING);
		List<ItemStack> got = ctx.pull(List.of(want.copyWithCount(n * per)));
		if (got == null) return PatternMode.Outcome.fail(DispatchResult.MISSING);
		ItemStack add = got.getFirst();
		furnace.setItem(0, cur.isEmpty() ? add : cur.copyWithCount(cur.getCount() + add.getCount()));
		furnace.setChanged();
		return PatternMode.Outcome.ok(n);
	}

	private static boolean containsAnyInput(Storage<ItemVariant> target, PatternData p) {
		Set<ItemKey> keys = new HashSet<>(p.inputTotals().keySet());
		for (StorageView<ItemVariant> view : target) {
			if (view.isResourceBlank() || view.getAmount() <= 0) continue;
			if (keys.contains(new ItemKey(view.getResource().toStack(1)))) return true;
		}
		return false;
	}

	/** All non-empty stacks of a grid, in order (inputs of modes without grid positions). */
	public static List<ItemStack> compact(List<ItemStack> grid) {
		List<ItemStack> l = new ArrayList<>();
		for (ItemStack st : grid) if (!st.isEmpty()) l.add(st);
		return l;
	}
}
