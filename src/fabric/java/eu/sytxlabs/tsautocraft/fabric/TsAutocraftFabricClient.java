package eu.sytxlabs.tsautocraft.fabric;

import java.util.HashSet;
import java.util.Set;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.block.entity.PaintedBlockEntity;
import com.tom.storagemod.client.UnbakedPaintedModel;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraftClient;

public class TsAutocraftFabricClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		TsAutocraftClient.init();
		TsAutocraftClient.registerScreens(MenuScreens::register);

		// Framed blocks: Tom's painted model around our model, tinted like the block they are painted with
		ModelLoadingPlugin.register(new ModelLoadingPlugin() {
			private final Set<BlockState> states = new HashSet<>();
			@Override
			public void initialize(Context ctx) {
				for (Block b : ModContent.FRAMED_BLOCKS) states.addAll(b.getStateDefinition().getPossibleStates());
				ctx.modifyBlockModelOnLoad().register((model, c) -> {
					if (states.contains(c.state()) && !(model instanceof UnbakedPaintedModel)) return new UnbakedPaintedModel(model);
					return model;
				});
			}
		});
		BlockColorRegistry.register((state, level, pos, tintValues) -> {
			BlockEntity tile = level.getBlockEntity(pos);
			if (!(tile instanceof PaintedBlockEntity be)) return;
			BlockState paint = be.getPaintedBlockState();
			if (paint == null || paint == Blocks.AIR.defaultBlockState()) return;
			for (BlockTintSource source : Minecraft.getInstance().getBlockColors().getTintSources(paint)) tintValues.add(source.colorInWorld(paint, level, pos));
		}, ModContent.FRAMED_BLOCKS.toArray(Block[]::new));
	}
}
