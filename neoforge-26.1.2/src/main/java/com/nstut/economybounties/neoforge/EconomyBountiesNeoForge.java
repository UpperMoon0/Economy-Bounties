package com.nstut.economybounties.neoforge;

import com.nstut.economybounties.minecraft.EconomyBountiesRuntime;
import com.nstut.economybounties.minecraft.BountyBoardRegistries;
import com.nstut.economybounties.network.BountyNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(EconomyBountiesRuntime.MOD_ID)
public final class EconomyBountiesNeoForge {
    public EconomyBountiesNeoForge(IEventBus modEventBus) {
        BountyBoardRegistries.init(modEventBus);
        EconomyBountiesRuntime.init();
        if (FMLEnvironment.getDist() == Dist.CLIENT) BountyNetwork.registerClient();
    }
}
