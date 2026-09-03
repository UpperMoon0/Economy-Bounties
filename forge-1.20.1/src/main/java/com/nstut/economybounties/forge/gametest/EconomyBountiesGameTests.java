package com.nstut.economybounties.forge.gametest;

import com.nstut.economybounties.api.BountyDefinition;
import com.nstut.economybounties.api.BountyService;
import com.nstut.economybounties.api.BountyStatus;
import com.nstut.economybounties.api.BountyView;
import com.nstut.economybounties.api.BuiltinObjectiveTypes;
import com.nstut.economybounties.api.DecimalRange;
import com.nstut.economybounties.api.LongRange;
import com.nstut.economybounties.api.NamespacedId;
import com.nstut.economybounties.api.ObjectiveDefinition;
import com.nstut.economybounties.api.RewardDefinition;
import com.nstut.economybounties.minecraft.BountyDeliveryService;
import com.nstut.economybounties.minecraft.EconomyBountiesRuntime;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real-server coverage for runtime binding and finite-resource delivery semantics. */
@GameTestHolder(EconomyBountiesRuntime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EconomyBountiesGameTests {
    private EconomyBountiesGameTests() {
    }

    @GameTest(template = "economy_bounties_gametest_empty", timeoutTicks = 80)
    public static void generatedItemDeliveryConsumesRealInventoryAndCompletesExactObjective(GameTestHelper helper) {
        helper.assertTrue(EconomyBountiesRuntime.ready(),
                "Economy Bounties runtime must be ready before GameTests execute");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NamespacedId group = NamespacedId.parse("economy_bounties:gametest_delivery");
        BountyDefinition definition = new BountyDefinition(
                NamespacedId.parse("economy_bounties:gametest_iron_delivery"),
                group,
                1,
                0,
                100,
                1,
                List.of(new ObjectiveDefinition(
                        BuiltinObjectiveTypes.DELIVER_ITEM,
                        "minecraft:iron_ingot",
                        new LongRange(5, 5),
                        Map.of())),
                new RewardDefinition(new DecimalRange(BigDecimal.ONE, BigDecimal.ONE), Map.of()),
                Duration.ofMinutes(5),
                Duration.ZERO,
                Set.of("gametest"));

        EconomyBountiesRuntime.replaceData(List.of(definition), Map.of());
        Instant now = Instant.now();
        BountyView offered = EconomyBountiesRuntime.generated().rollOffer(
                        player.getUUID(),
                        group,
                        new BountyService.RollContext(helper.getLevel().getSeed(), 0, 0, now))
                .orElseThrow(() -> new AssertionError("GameTest bounty must roll"));
        BountyView active = EconomyBountiesRuntime.generated()
                .accept(player.getUUID(), offered.instanceId(), now.plusMillis(1))
                .orElseThrow(() -> new AssertionError("GameTest bounty must accept"));
        helper.assertTrue(active.status() == BountyStatus.ACTIVE,
                "Accepted GameTest bounty must become active");

        player.getInventory().add(new ItemStack(Items.IRON_INGOT, 8));
        String result = BountyDeliveryService.deliver(
                player,
                "generated",
                offered.instanceId().toString(),
                0,
                BuiltinObjectiveTypes.DELIVER_ITEM.toString(),
                "minecraft:iron_ingot");

        BountyView after = EconomyBountiesRuntime.generated()
                .get(player.getUUID(), offered.instanceId(), Instant.now())
                .orElseThrow(() -> new AssertionError("Delivered bounty must still exist"));
        helper.assertTrue(after.objectives().get(0).progress() == 5,
                "Delivery must credit exactly the required five items");
        helper.assertTrue(after.status() == BountyStatus.COMPLETED,
                "Completing the only objective must complete the bounty");
        helper.assertTrue(countItem(player, Items.IRON_INGOT) == 3,
                "Server inventory must retain exactly the three undelivered iron ingots");
        helper.assertTrue(result.startsWith("Delivered 5 item"),
                "Delivery response must report the credited amount");

        helper.succeed();
    }

    private static int countItem(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
