package com.nstut.economybounties.forge;

import com.nstut.economybounties.minecraft.EconomyBountiesRuntime;
import com.nstut.economybounties.network.BountyNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(EconomyBountiesRuntime.MOD_ID)
public final class EconomyBountiesForge {
    public EconomyBountiesForge() {
        EconomyBountiesRuntime.init();
        if (FMLEnvironment.dist == Dist.CLIENT) BountyNetwork.registerClient();
    }
}
