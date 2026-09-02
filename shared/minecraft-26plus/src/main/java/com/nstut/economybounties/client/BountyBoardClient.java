package com.nstut.economybounties.client;

import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.network.BountyNetwork;
import com.nstut.openui.api.UIComponent;
import com.nstut.openui.api.Ui;
import com.nstut.openui.minecraft.UiScreen;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Client-only OpenUI board. It displays server snapshots and sends intent packets only. */
public final class BountyBoardClient {
    private BountyBoardClient() { }

    public static void receive(BoardSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BountyBoardScreen board) board.apply(snapshot);
        else minecraft.setScreen(new BountyBoardScreen(snapshot));
    }

    private static final class BountyBoardScreen extends UiScreen {
        private final Signal<String> tab = Signals.of("Generated");
        private final Signal<List<BoardSnapshot.PoolEntry>> pools = Signals.of(List.of());
        private final Signal<List<BoardSnapshot.BountyEntry>> generated = Signals.of(List.of());
        private final Signal<List<BoardSnapshot.BountyEntry>> posted = Signals.of(List.of());
        private final Signal<String> notice = Signals.of("");

        private final Signal<String> title = Signals.of("");
        private final Signal<String> description = Signals.of("");
        private final Signal<String> icon = Signals.of("minecraft:paper");
        private final Signal<String> reward = Signals.of("100");
        private final Signal<String> lifetime = Signals.of("1440");
        private final Signal<Boolean> publicAccess = Signals.of(true);
        private final Signal<String> allowedPlayers = Signals.of("");
        private final Signal<String> allowedGroups = Signals.of("");
        private final Signal<String> deniedPlayers = Signals.of("");
        private final Signal<String> progressionGroup = Signals.of("");
        private final Signal<String> minLevel = Signals.of("0");
        private final Signal<String> maxLevel = Signals.of(String.valueOf(Integer.MAX_VALUE));

        private final Signal<String> type1 = Signals.of("economy_bounties:deliver_item");
        private final Signal<String> target1 = Signals.of("minecraft:iron_ingot");
        private final Signal<String> amount1 = Signals.of("16");
        private final Signal<String> type2 = Signals.of("");
        private final Signal<String> target2 = Signals.of("");
        private final Signal<String> amount2 = Signals.of("");
        private final Signal<String> type3 = Signals.of("");
        private final Signal<String> target3 = Signals.of("");
        private final Signal<String> amount3 = Signals.of("");

        private BountyBoardScreen(BoardSnapshot snapshot) {
            super(Component.literal("Bounty Board"));
            apply(snapshot);
        }

        private void apply(BoardSnapshot snapshot) {
            if (snapshot == null) return;
            Signals.batch(() -> {
                pools.set(snapshot.pools());
                generated.set(snapshot.generated());
                posted.set(snapshot.posted());
                notice.set(snapshot.notice());
            });
        }

        @Override protected int uiLeft() { return Math.max(8, width / 2 - 230); }
        @Override protected int uiTop() { return Math.max(8, height / 2 - 155); }
        @Override protected int uiWidth() { return Math.min(460, Math.max(120, width - 16)); }
        @Override protected int uiHeight() { return Math.min(310, Math.max(100, height - 16)); }

        @Override
        protected UIComponent buildUI() {
            return Ui.card(Ui.column(
                    Ui.row(
                            Ui.title("Bounty Board"),
                            Ui.spacer(),
                            Ui.button("Refresh", () -> BountyNetwork.sendRequest(BoardRequest.refresh())).small(),
                            Ui.button("Close", this::onClose).ghost().small()
                    ).gap(6),
                    Ui.text(notice),
                    Ui.tabs(tab).tab("Generated", "Generated").tab("Posted", "Posted").tab("Create", "Create"),
                    Ui.switcher(tab)
                            .when("Generated", this::generatedPanel)
                            .when("Posted", this::postedPanel)
                            .when("Create", this::createPanel)
            ).gap(7)).padding(10).elevated(true);
        }

        private UIComponent generatedPanel() {
            return Ui.column(
                    Ui.heading("Pools"),
                    Ui.list(pools, pool -> Ui.button("Roll " + pool.id(), () ->
                            BountyNetwork.sendRequest(BoardRequest.roll(pool.id()))).small()).height(54),
                    Ui.heading("Your bounties"),
                    Ui.list(generated, this::bountyCard).height(180)
            ).gap(5);
        }

        private UIComponent postedPanel() {
            return Ui.column(
                    Ui.row(Ui.heading("Player-posted bounties"), Ui.spacer(),
                            Ui.button("Create", () -> tab.set("Create")).primary().small()).gap(6),
                    Ui.list(posted, this::bountyCard).height(235)
            ).gap(5);
        }

        private UIComponent bountyCard(BoardSnapshot.BountyEntry bounty) {
            List<UIComponent> objectiveRows = new ArrayList<>();
            for (BoardSnapshot.ObjectiveEntry objective : bounty.objectives()) {
                List<UIComponent> row = new ArrayList<>();
                row.add(Ui.text(objective.target() + "  " + objective.progress() + "/" + objective.targetAmount()));
                row.add(Ui.spacer());
                if (objective.deliverable()) {
                    row.add(Ui.button("Deliver", () -> BountyNetwork.sendRequest(
                            BoardRequest.deliver(objective.type(), objective.target()))).success().small());
                }
                objectiveRows.add(Ui.row(row.toArray(UIComponent[]::new)).gap(4));
            }
            List<UIComponent> actions = new ArrayList<>();
            if (bounty.canAccept()) actions.add(Ui.button("Accept", () -> sendBountyAction(bounty, true, false, false)).primary().small());
            if (bounty.canClaim()) actions.add(Ui.button("Claim", () -> sendBountyAction(bounty, false, false, true)).success().small());
            if (bounty.canCancel()) actions.add(Ui.button("Cancel", () -> sendBountyAction(bounty, false, true, false)).danger().small());

            List<UIComponent> content = new ArrayList<>();
            content.add(Ui.row(Ui.heading(bounty.title()), Ui.spacer(), Ui.badge(bounty.status())).gap(5));
            if (!bounty.subtitle().isBlank()) content.add(Ui.text(bounty.subtitle()));
            if (!bounty.description().isBlank()) content.add(Ui.text(bounty.description()));
            content.add(Ui.text("Reward: " + bounty.reward()));
            content.addAll(objectiveRows);
            if (!actions.isEmpty()) content.add(Ui.row(actions.toArray(UIComponent[]::new)).gap(5));
            return Ui.card(Ui.column(content.toArray(UIComponent[]::new)).gap(3)).padding(6);
        }

        private void sendBountyAction(BoardSnapshot.BountyEntry bounty, boolean accept, boolean cancel, boolean claim) {
            boolean postedSource = "posted".equals(bounty.source());
            BoardRequest.Action action;
            if (accept) action = postedSource ? BoardRequest.Action.ACCEPT_POSTED : BoardRequest.Action.ACCEPT_GENERATED;
            else if (cancel) action = postedSource ? BoardRequest.Action.CANCEL_POSTED : BoardRequest.Action.CANCEL_GENERATED;
            else if (claim) action = postedSource ? BoardRequest.Action.CLAIM_POSTED : BoardRequest.Action.CLAIM_GENERATED;
            else return;
            BountyNetwork.sendRequest(BoardRequest.bounty(action, bounty.id()));
        }

        private UIComponent createPanel() {
            return Ui.scroll(Ui.column(
                    Ui.heading("Create player bounty"),
                    field("Title", title, 210),
                    field("Description", description, 300),
                    field("Icon id", icon, 180),
                    Ui.row(field("Reward", reward, 90), field("Lifetime min", lifetime, 90)).gap(8),
                    Ui.divider(),
                    Ui.heading("Objectives"),
                    objectiveRow("1", type1, target1, amount1),
                    objectiveRow("2", type2, target2, amount2),
                    objectiveRow("3", type3, target3, amount3),
                    Ui.text("Built-ins: deliver_item, deliver_fluid, kill_entity, craft_item, mine_block, visit_location"),
                    Ui.divider(),
                    Ui.heading("Audience"),
                    Ui.checkbox("Public", publicAccess),
                    field("Allowed player UUIDs (comma)", allowedPlayers, 300),
                    field("Allowed groups (comma)", allowedGroups, 300),
                    field("Denied player UUIDs (comma)", deniedPlayers, 300),
                    field("Progression group (optional)", progressionGroup, 220),
                    Ui.row(field("Min level", minLevel, 80), field("Max level", maxLevel, 80)).gap(8),
                    Ui.row(
                            Ui.button("Back", () -> tab.set("Posted")).ghost(),
                            Ui.button("Create & fund", this::submitCreate).primary()
                    ).gap(8)
            ).gap(5));
        }

        private UIComponent objectiveRow(String number, Signal<String> type, Signal<String> target, Signal<String> amount) {
            return Ui.row(
                    Ui.text("#" + number),
                    Ui.textField(type).placeholder("objective type").width(150),
                    Ui.textField(target).placeholder("target id").width(150),
                    Ui.textField(amount).placeholder("amount").width(70)
            ).gap(5);
        }

        private UIComponent field(String label, Signal<String> value, int width) {
            return Ui.row(Ui.text(label + ':'), Ui.textField(value).width(width)).gap(5);
        }

        private void submitCreate() {
            List<BoardRequest.ObjectiveDraft> objectives = new ArrayList<>();
            addObjective(objectives, type1.get(), target1.get(), amount1.get());
            addObjective(objectives, type2.get(), target2.get(), amount2.get());
            addObjective(objectives, type3.get(), target3.get(), amount3.get());
            BoardRequest.AudienceDraft audience = new BoardRequest.AudienceDraft(
                    publicAccess.get(), split(allowedPlayers.get()), split(allowedGroups.get()), split(deniedPlayers.get()),
                    progressionGroup.get(), parseInt(minLevel.get(), 0), parseInt(maxLevel.get(), Integer.MAX_VALUE));
            BoardRequest.CreateDraft draft = new BoardRequest.CreateDraft(
                    title.get(), description.get(), icon.get(), reward.get(), parseLong(lifetime.get(), 0), objectives, audience);
            BountyNetwork.sendRequest(BoardRequest.create(draft));
            tab.set("Posted");
        }

        private static void addObjective(List<BoardRequest.ObjectiveDraft> output, String type, String target, String amount) {
            if ((type == null || type.isBlank()) && (target == null || target.isBlank()) && (amount == null || amount.isBlank())) return;
            output.add(new BoardRequest.ObjectiveDraft(type, target, parseLong(amount, 0)));
        }

        private static List<String> split(String value) {
            if (value == null || value.isBlank()) return List.of();
            return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isBlank()).toList();
        }

        private static long parseLong(String value, long fallback) {
            try { return Long.parseLong(value == null ? "" : value.trim()); }
            catch (NumberFormatException error) { return fallback; }
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value == null ? "" : value.trim()); }
            catch (NumberFormatException error) { return fallback; }
        }
    }
}
