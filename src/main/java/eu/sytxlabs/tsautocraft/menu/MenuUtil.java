package eu.sytxlabs.tsautocraft.menu;

import java.util.function.Consumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueOutput;

import com.tom.storagemod.network.NetworkHandler;

/** Menu helpers: player inventory slots and packets over Tom's data channel. */
public final class MenuUtil {
	private MenuUtil() {}

	public interface SlotAdder {
		Slot add(Slot slot);
	}

	public static void addPlayerSlots(SlotAdder adder, Inventory inv, int x, int y) {
		for (int row = 0; row < 3; ++row) {
			for (int col = 0; col < 9; ++col) {
				adder.add(new Slot(inv, col + row * 9 + 9, x + col * 18, y + row * 18));
			}
		}
		for (int col = 0; col < 9; ++col) {
			adder.add(new Slot(inv, col, x + col * 18, y + 58));
		}
	}

	public static void sendToClient(Player player, Consumer<ValueOutput> writer) {
		if (!(player instanceof ServerPlayer sp)) return;
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, sp.registryAccess());
		writer.accept(out);
		NetworkHandler.sendTo(sp, out.buildResult());
	}

	public static void sendToServer(Player player, Consumer<ValueOutput> writer) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
		writer.accept(out);
		NetworkHandler.sendDataToServer(out.buildResult());
	}
}
