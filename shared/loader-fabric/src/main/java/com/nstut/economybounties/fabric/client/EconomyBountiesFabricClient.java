package com.nstut.economybounties.fabric.client;

import com.nstut.economybounties.network.BountyNetwork;
import net.fabricmc.api.ClientModInitializer;

public final class EconomyBountiesFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() { BountyNetwork.registerClient(); }
}
