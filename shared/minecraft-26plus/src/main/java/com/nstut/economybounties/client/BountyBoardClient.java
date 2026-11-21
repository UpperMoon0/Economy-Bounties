package com.nstut.economybounties.client;

import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.network.BountyNetwork;
import com.nstut.openui.api.HStack;
import com.nstut.openui.api.UIComponent;
import com.nstut.openui.api.Ui;
import com.nstut.openui.minecraft.UiScreen;
import com.nstut.openui.layout.Alignment;
import com.nstut.openui.state.Computed;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Instant;
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
        private static final int MAX_OBJECTIVES = 16;

        private final Signal<String> tab = Signals.of("Orders");
        private final Signal<List<BoardSnapshot.OrderEntry>> orders = Signals.of(List.of());
        private final Signal<String> orderFilter = Signals.of("All");
        private final Computed<List<BoardSnapshot.OrderEntry>> visibleOrders = Signals.computed(() ->
                orders.get().stream().filter(order -> switch (orderFilter.get()) {
                    case "Personal" -> "PERSONAL".equals(order.scope());
                    case "Town" -> "SHARED".equals(order.scope());
                    case "Special" -> order.board().contains("daily_") || order.board().contains("festival");
                    default -> true;
                }).toList());
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
        private final Signal<List<ObjectiveForm>> objectiveForms = Signals.of(List.of(
                new ObjectiveForm("economy_bounties:deliver_item", "minecraft:iron_ingot", "16")));

        private BountyBoardScreen(BoardSnapshot snapshot) {
            super(Component.literal("Bounty Board"));
            apply(snapshot);
        }

        private void apply(BoardSnapshot snapshot) {
            if (snapshot == null) return;
            Signals.batch(() -> {
                pools.set(snapshot.pools());
                orders.set(snapshot.orders() == null ? List.of() : snapshot.orders());
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
                    centeredRow(
                            Ui.title("Bounty Board"),
                            Ui.spacer(),
                            Ui.button("Refresh", () -> BountyNetwork.sendRequest(BoardRequest.refresh())).small(),
                            Ui.button("Close", this::onClose).ghost().small()
                    ).gap(6),
                    Ui.text(notice),
                    Ui.tabs(tab).tab("Orders", "Orders").tab("Generated", "Contracts")
                            .tab("Posted", "Posted").tab("Create", "Create"),
                    Ui.switcher(tab)
                            .when("Orders", this::ordersPanel)
                            .when("Generated", this::generatedPanel)
                            .when("Posted", this::postedPanel)
                            .when("Create", this::createPanel)
            ).gap(7)).padding(10).elevated(true);
        }

        /**
         * Automatic orders are a curated, server-owned storefront. No manual Roll
         * button is needed. A slot is locked on acceptance before a bounty grant.
         */
        private UIComponent ordersPanel() {
            return Ui.column(
                    centeredRow(Ui.heading("Dispatch board"), Ui.spacer(),
                            Ui.badge("AUTO-RESTOCK")),
                    Ui.text("Orders restock automatically. Rare deliveries earn more."),
                    Ui.tabs(orderFilter).tab("All", "All").tab("Personal", "Personal")
                            .tab("Town", "Town").tab("Special", "Special"),
                    Ui.list(visibleOrders, this::orderCard).height(Math.max(120, uiHeight() - 130))
            ).gap(5);
        }

        private UIComponent orderCard(BoardSnapshot.OrderEntry order) {
            boolean ready = order.canAccept();
            boolean isWaiting = "RESTOCKING".equals(order.status()) || "CLAIMED".equals(order.status());
            String kind = "SHARED".equals(order.scope()) ? "TOWN" : "PERSONAL";
            String badge = order.rarity().isBlank() ? kind : order.rarity().toUpperCase(java.util.Locale.ROOT) + " / " + kind;
            String headline = isWaiting ? "Slot " + (order.slot() + 1) + " - " + order.status()
                    : order.title();
            List<UIComponent> lines = new ArrayList<>();
            lines.add(centeredRow(Ui.heading(headline), Ui.spacer(), Ui.badge(badge)).gap(4));
            if (!isWaiting) {
                lines.add(centeredRow(
                        Ui.text("Deliver " + order.quantity() + " " + readableItem(order.item())),
                        Ui.spacer(), Ui.badge("$" + order.reward())).gap(4));
                if (ready) {
                    long owned = order.inInventory();
                    lines.add(centeredRow(Ui.text("In inventory: " + owned + "/" + order.quantity()),
                            Ui.spacer(), Ui.badge(owned >= order.quantity() ? "READY" : "NEED ITEMS")).gap(4));
                }
            }
            List<UIComponent> controls = new ArrayList<>();
            lines.add(Ui.text(orderTime(order)));
            if (ready) {
                if (order.inInventory() >= order.quantity()) {
                    controls.add(Ui.button("Deliver now", () -> BountyNetwork.sendRequest(BoardRequest.order(
                            BoardRequest.Action.FULFILL_ORDER, order.board(), order.slot(), order.offerId())))
                            .success().small());
                }
                controls.add(Ui.button("Accept", () -> BountyNetwork.sendRequest(BoardRequest.order(
                        BoardRequest.Action.ACCEPT_ORDER, order.board(), order.slot(), order.offerId())))
                        .primary().small());
                if (order.canDiscard()) {
                    controls.add(Ui.button("Discard", () -> BountyNetwork.sendRequest(BoardRequest.order(
                            BoardRequest.Action.DISCARD_ORDER, order.board(), order.slot(), order.offerId())))
                            .ghost().small());
                }
            } else if ("TAKEN".equals(order.status())) {
                controls.add(Ui.button("View contract", () -> tab.set("Generated")).small());
            }
            lines.add(centeredRow(controls.toArray(UIComponent[]::new)).gap(5));
            return Ui.card(Ui.column(lines.toArray(UIComponent[]::new)).gap(4)).padding(6);
        }

        private static String readableItem(String id) {
            if (id == null || id.isBlank()) return "items";
            String value = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            return Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }

        private static String orderTime(BoardSnapshot.OrderEntry order) {
            long remaining = order.expiresAt() - Instant.now().getEpochSecond();
            if ("TAKEN".equals(order.status())) {
                return "Accepted. Finish and claim it under Contracts.";
            }
            if ("CLAIMED".equals(order.status())) {
                return "Another player is fulfilling this order.";
            }
            if ("RESTOCKING".equals(order.status())) {
                long restock = Math.max(0, order.restocksAt() - Instant.now().getEpochSecond());
                return "Next order in " + formatTime(restock);
            }
            return switch (order.timer()) {
                case "NONE" -> "Refreshes in " + formatTime(Math.max(0, remaining)) + " | No delivery deadline";
                case "ACCEPT" -> "Accept in " + formatTime(Math.max(0, remaining))
                        + " | Deliver within " + formatTime(order.deadlineSeconds());
                default -> remaining > 0 ? "Rush: " + formatTime(remaining) + " left"
                        : "Rush order expired";
            };
        }

        private static String formatTime(long seconds) {
            if (seconds >= 86400) return (seconds / 86400) + "d " + ((seconds % 86400) / 3600) + "h";
            if (seconds >= 3600) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }

        private int refreshTicks;
        @Override
        public void tick() {
            super.tick();
            // A bounded server refresh keeps time windows and restocks current
            // without a per-frame packet or a client-side claim decision.
            if (++refreshTicks % 400 == 0) {
                BountyNetwork.sendRequest(BoardRequest.refresh());
            }
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
                    centeredRow(Ui.heading("Player-posted bounties"), Ui.spacer(),
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
                            BoardRequest.deliver(bounty.source(), bounty.id(), objective.index(), objective.type(), objective.target()))).success().small());
                }
                objectiveRows.add(centeredRow(row.toArray(UIComponent[]::new)).gap(4));
            }
            List<UIComponent> actions = new ArrayList<>();
            if (bounty.canAccept()) actions.add(Ui.button("Accept", () -> sendBountyAction(bounty, true, false, false)).primary().small());
            if (bounty.canClaim()) actions.add(Ui.button("Claim", () -> sendBountyAction(bounty, false, false, true)).success().small());
            if (bounty.canCancel()) actions.add(Ui.button("Cancel", () -> sendBountyAction(bounty, false, true, false)).danger().small());

            List<UIComponent> content = new ArrayList<>();
            content.add(centeredRow(Ui.heading(bounty.title()), Ui.spacer(), Ui.badge(bounty.status())).gap(5));
            if (!bounty.subtitle().isBlank()) content.add(Ui.text(bounty.subtitle()));
            if (!bounty.description().isBlank()) content.add(Ui.text(bounty.description()));
            content.add(Ui.text("Reward: " + bounty.reward()));
            content.addAll(objectiveRows);
            if (!actions.isEmpty()) content.add(centeredRow(actions.toArray(UIComponent[]::new)).gap(5));
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
                    centeredRow(field("Reward", reward, 90), field("Lifetime min", lifetime, 90)).gap(8),
                    Ui.divider(),
                    centeredRow(Ui.heading("Objectives"), Ui.spacer(),
                            Ui.button("+ Objective", this::addObjectiveForm).small()).gap(6),
                    Ui.list(objectiveForms, this::objectiveRow).height(118),
                    Ui.text("Up to 16 objectives. Built-ins: deliver_item, deliver_fluid, kill_entity, craft_item, mine_block, visit_location"),
                    Ui.divider(),
                    Ui.heading("Audience"),
                    Ui.checkbox("Public", publicAccess),
                    field("Allowed players (name/UUID, comma)", allowedPlayers, 300),
                    field("Allowed groups (comma)", allowedGroups, 300),
                    field("Denied players (name/UUID, comma)", deniedPlayers, 300),
                    field("Progression group (optional)", progressionGroup, 220),
                    centeredRow(field("Min level", minLevel, 80), field("Max level", maxLevel, 80)).gap(8),
                    centeredRow(
                            Ui.button("Back", () -> tab.set("Posted")).ghost(),
                            Ui.button("Create & fund", this::submitCreate).primary()
                    ).gap(8)
            ).gap(5));
        }

        private UIComponent objectiveRow(ObjectiveForm form) {
            return centeredRow(
                    Ui.textField(form.type).placeholder("objective type").width(145),
                    Ui.textField(form.target).placeholder("target id").width(145),
                    Ui.textField(form.amount).placeholder("amount").width(65),
                    Ui.button("×", () -> removeObjectiveForm(form)).danger().small()
            ).gap(4);
        }

        private void addObjectiveForm() {
            List<ObjectiveForm> current = objectiveForms.get();
            if (current.size() >= MAX_OBJECTIVES) {
                notice.set("A posted bounty can have at most " + MAX_OBJECTIVES + " objectives");
                return;
            }
            List<ObjectiveForm> next = new ArrayList<>(current);
            next.add(new ObjectiveForm("", "", "1"));
            objectiveForms.set(List.copyOf(next));
        }

        private void removeObjectiveForm(ObjectiveForm form) {
            List<ObjectiveForm> current = objectiveForms.get();
            if (current.size() <= 1) {
                notice.set("A posted bounty needs at least one objective");
                return;
            }
            List<ObjectiveForm> next = new ArrayList<>(current);
            next.remove(form);
            objectiveForms.set(List.copyOf(next));
        }

        /**
         * Center the cross-axis of every board row. OpenUI rows default to STRETCH:
         * text labels otherwise render at the top of a taller input or button.
         */
        private static HStack centeredRow(UIComponent... children) {
            return Ui.row(children).align(Alignment.CENTER);
        }

        private UIComponent field(String label, Signal<String> value, int width) {
            return centeredRow(Ui.text(label + ':'), Ui.textField(value).width(width)).gap(5);
        }

        private void submitCreate() {
            List<BoardRequest.ObjectiveDraft> objectives = new ArrayList<>();
            for (ObjectiveForm form : objectiveForms.get()) {
                addObjective(objectives, form.type.get(), form.target.get(), form.amount.get());
            }
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

        private static final class ObjectiveForm {
            private final Signal<String> type;
            private final Signal<String> target;
            private final Signal<String> amount;

            private ObjectiveForm(String type, String target, String amount) {
                this.type = Signals.of(type);
                this.target = Signals.of(target);
                this.amount = Signals.of(amount);
            }
        }
    }
}
