package com.nstut.economybounties.fabric;

import com.nstut.economybounties.minecraft.EconomyBountiesRuntime;
import net.fabricmc.api.ModInitializer;

public final class EconomyBountiesFabric implements ModInitializer {
    @Override public void onInitialize() { EconomyBountiesRuntime.init(); }
}
