package com.nstut.economybounties.forge;

import com.nstut.economybounties.minecraft.EconomyBountiesRuntime;
import com.nstut.economybounties.network.BountyNetwork;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(EconomyBountiesRuntime.MOD_ID)
public final class EconomyBountiesForge {
    public EconomyBountiesForge() {
        // Architectury deferred registries require the Forge mod event bus first.
        EventBuses.registerModEventBus(EconomyBountiesRuntime.MOD_ID,
                FMLJavaModLoadingContext.get().getModEventBus());
        EconomyBountiesRuntime.init();
        if (FMLEnvironment.dist == Dist.CLIENT) BountyNetwork.registerClient();
    }
}
