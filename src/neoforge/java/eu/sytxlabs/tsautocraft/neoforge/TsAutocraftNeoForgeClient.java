package eu.sytxlabs.tsautocraft.neoforge;

import java.util.Map;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.tom.storagemod.client.BakedPaintedModel;

import eu.sytxlabs.tsautocraft.ModContent;
import eu.sytxlabs.tsautocraft.TsAutocraft;
import eu.sytxlabs.tsautocraft.TsAutocraftClient;
import eu.sytxlabs.tsautocraft.compat.ConfigScreen;

@Mod(value = TsAutocraft.MODID, dist = Dist.CLIENT)
public class TsAutocraftNeoForgeClient {

	public TsAutocraftNeoForgeClient(IEventBus modBus, ModContainer container) {
		TsAutocraftClient.init();
		modBus.addListener(RegisterMenuScreensEvent.class, e -> TsAutocraftClient.registerScreens(e::register));
		// Framed blocks: Tom's painted model around our model (like Tom's own framed blocks; tint comes from the paint)
		modBus.addListener(ModelEvent.ModifyBakingResult.class, e -> {
			Map<BlockState, BlockStateModel> models = e.getBakingResult().blockStateModels();
			for (Block b : ModContent.FRAMED_BLOCKS) {
				for (BlockState s : b.getStateDefinition().getPossibleStates()) {
					BlockStateModel m = models.get(s);
					if (m != null && !(m instanceof BakedPaintedModel)) models.put(s, new BakedPaintedModel(b, m));
				}
			}
		});
		// Tom's NeoForge jar does not ship Cloth Config (the Fabric one does): config screen only with Cloth installed
		if (net.neoforged.fml.ModList.get().isLoaded("cloth_config")) {
			container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> ConfigScreen.build(parent));
		}
	}
}
