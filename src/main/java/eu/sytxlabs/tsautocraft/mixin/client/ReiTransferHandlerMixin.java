package eu.sytxlabs.tsautocraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tom.storagemod.rei.ReiTransferHandler;

import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;

import eu.sytxlabs.tsautocraft.compat.rei.CraftMissingRei;

/** REI "+" in Tom's Crafting Terminal: craft missing ingredients ({@link CraftMissingRei}). Only applied when REI loads Tom's handler class. */
@Mixin(value = ReiTransferHandler.class, remap = false)
public abstract class ReiTransferHandlerMixin {

	@Inject(method = "handle", at = @At("RETURN"), cancellable = true)
	private void tsac$craftMissing(TransferHandler.Context ctx, CallbackInfoReturnable<TransferHandler.Result> cir) {
		cir.setReturnValue(CraftMissingRei.handle(cir.getReturnValue(), ctx));
	}
}
