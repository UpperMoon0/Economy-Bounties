package com.nstut.economybounties.client;

import com.nstut.economybounties.board.BoardRequest;
import com.nstut.economybounties.board.BoardSnapshot;
import com.nstut.economybounties.network.BountyNetwork;
import com.nstut.openui.api.HStack;
import com.nstut.openui.controls.Badge;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

        private final Signal<String> orderSearch = Signals.of("");
        private final Signal<String> orderScope = Signals.of("All");
        private final Signal<String> orderType = Signals.of("All");
        private final Signal<String> orderStatus = Signals.of("All");
        private final Signal<String> orderSort = Signals.of("Ready");
        private final Signal<String> orderView = Signals.of("Grid");
        private final Signal<Boolean> readyOnly = Signals.of(false);
        private final Computed<List<BoardSnapshot.OrderEntry>> visibleOrders = Signals.computed(() -> {
            String query = orderSearch.get().trim().toLowerCase(Locale.ROOT);
            String scope = orderScope.get(), type = orderType.get(), status = orderStatus.get();
            String sort = orderSort.get();
            boolean onlyReady = readyOnly.get();
            Comparator<BoardSnapshot.OrderEntry> comparer = switch (sort) {
                case "Reward" -> Comparator.comparing(
                        (BoardSnapshot.OrderEntry o) -> moneyValue(o.reward())).reversed();
                case "Time" -> Comparator.comparingLong(BoardSnapshot.OrderEntry::expiresAt);
                case "Item" -> Comparator.comparing(o -> readableItem(o.item()).toLowerCase(Locale.ROOT));
                default -> Comparator.comparingInt((BoardSnapshot.OrderEntry o) -> isReady(o) ? 0 : 1)
                        .thenComparing(Comparator.comparing(
                                (BoardSnapshot.OrderEntry o) -> moneyValue(o.reward())).reversed());
            };
            return orders.get().stream()
                    .filter(o -> scope.equals("All")
                            || scope.equals("Personal") && "PERSONAL".equals(o.scope())
                            || scope.equals("Global") && "SHARED".equals(o.scope()))
                    .filter(o -> type.equals("All") || (type.equals("Special") == isSpecial(o)))
                    .filter(o -> switch (status) {
                        case "Ready" -> isReady(o);
                        case "Available" -> o.canAccept();
                        case "Taken" -> "TAKEN".equals(o.status()) || "CLAIMED".equals(o.status());
                        case "Restocking" -> "RESTOCKING".equals(o.status());
                        default -> true;
                    })
                    .filter(o -> !onlyReady || isReady(o))
                    .filter(o -> query.isEmpty()
                            || (o.title() + " " + o.item() + " " + o.rarity() + " " + o.board())
                                    .toLowerCase(Locale.ROOT).contains(query))
                    .sorted(comparer)
                    .toList();
        });
        private final Signal<String> selectedOrderKey = Signals.of("");
        private final Computed<List<BoardSnapshot.OrderEntry>> selectedOrder = Signals.computed(() -> {
            List<BoardSnapshot.OrderEntry> choices = visibleOrders.get();
            if (choices.isEmpty()) return List.of();
            String selected = selectedOrderKey.get();
            return List.of(choices.stream()
                    .filter(order -> orderKey(order).equals(selected))
                    .findFirst().orElse(choices.get(0)));
        });
        /** Selected styling is part of the item snapshot so VirtualList repaints on selection. */
        private record OrderRow(BoardSnapshot.OrderEntry order, boolean selected) { }
        private final Computed<List<OrderRow>> orderRows = Signals.computed(() -> {
            String selection = selectedOrderKey.get();
            return visibleOrders.get().stream()
                    .map(order -> new OrderRow(order, orderKey(order).equals(selection))).toList();
        });
        private final Signal<List<BoardSnapshot.PoolEntry>> pools = Signals.of(List.of());
        private final Signal<List<BoardSnapshot.BountyEntry>> generated = Signals.of(List.of());
        private final Signal<List<BoardSnapshot.BountyEntry>> posted = Signals.of(List.of());
        private final Signal<String> contractSearch = Signals.of("");
        private final Signal<String> contractStatus = Signals.of("All");
        private final Signal<String> contractSort = Signals.of("Expiry");
        private final Signal<String> contractView = Signals.of("List");
        private final Signal<String> contractSelection = Signals.of("");
        private final Signal<String> postedSearch = Signals.of("");
        private final Signal<String> postedStatus = Signals.of("All");
        private final Signal<String> postedSort = Signals.of("Expiry");
        private final Signal<String> postedView = Signals.of("List");
        private final Signal<String> postedSelection = Signals.of("");
        private final Signal<Boolean> advancedCreate = Signals.of(false);

        private final Computed<List<BoardSnapshot.BountyEntry>> visibleContracts =
                Signals.computed(() -> filterBounties(generated.get(), contractSearch.get(),
                        contractStatus.get(), contractSort.get(), false));
        private final Computed<List<BoardSnapshot.BountyEntry>> visiblePosted =
                Signals.computed(() -> filterBounties(posted.get(), postedSearch.get(),
                        postedStatus.get(), postedSort.get(), true));
        private record BountyRow(BoardSnapshot.BountyEntry bounty, boolean selected) { }
        private final Computed<List<BountyRow>> contractRows = Signals.computed(() -> {
            String selected = contractSelection.get();
            return visibleContracts.get().stream()
                    .map(b -> new BountyRow(b, b.id().equals(selected))).toList();
        });
        private final Computed<List<BountyRow>> postedRows = Signals.computed(() -> {
            String selected = postedSelection.get();
            return visiblePosted.get().stream()
                    .map(b -> new BountyRow(b, b.id().equals(selected))).toList();
        });
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

        @Override protected int uiLeft() { return Math.max(6, (width - uiWidth()) / 2); }
        @Override protected int uiTop() { return Math.max(6, (height - uiHeight()) / 2); }
        @Override protected int uiWidth() { return Math.min(660, Math.max(145, width - 12)); }
        @Override protected int uiHeight() { return Math.min(390, Math.max(130, height - 12)); }

        private int sideWidth() { return uiWidth() >= 410 ? 84 : 62; }
        private int mainWidth() { return Math.max(130, uiWidth() - sideWidth() - 26); }
        private int mainHeight() { return Math.max(110, uiHeight() - 21); }
        private int browserHeight() { return Math.max(92, mainHeight() - 91); }

        @Override protected UIComponent buildUI() {
            int side = sideWidth(), contentWidth = mainWidth(), height = mainHeight();
            UIComponent sidebar = Ui.scroll(Ui.column(
                    Ui.text("BOUNTY").nowrap(),
                    Ui.text("BOARD").nowrap(),
                    Ui.divider(),
                    navButton("Orders", "Orders"),
                    navButton("Contracts", "Generated"),
                    navButton("Posted", "Posted"),
                    navButton("Create", "Create")
            ).gap(5)).width(side).height(height);
            UIComponent main = Ui.column(
                    centeredRow(Ui.heading("Bounty Board"), Ui.spacer(),
                            Ui.button("Refresh", () -> BountyNetwork.sendRequest(BoardRequest.refresh())).small(),
                            Ui.button("X", this::onClose).ghost().small()).gap(4),
                    Ui.text(notice).height(11),
                    Ui.switcher(tab)
                            .when("Orders", this::ordersPanel)
                            .when("Generated", this::generatedPanel)
                            .when("Posted", this::postedPanel)
                            .when("Create", this::createPanel)
            ).gap(5).width(contentWidth).height(height);
            return Ui.card(centeredRow(sidebar, main).gap(7))
                    .padding(7).height(uiHeight()).elevated(true);
        }

        /** Reactive, narrow, independently scrollable Economy-style navigation. */
        private UIComponent navButton(String caption, String destination) {
            return Ui.component(() -> {
                String selected = tab.get();
                return List.of(Ui.node("nav", destination + selected, () -> {
                    var button = Ui.button(caption, () -> tab.set(destination)).small();
                    return destination.equals(selected) ? button.primary() : button.ghost();
                }));
            }).width(sideWidth() - 7).height(27);
        }

        private static java.math.BigDecimal moneyValue(String amount) {
            try { return new java.math.BigDecimal(amount); }
            catch (NumberFormatException error) { return java.math.BigDecimal.ZERO; }
        }
        private static boolean isSpecial(BoardSnapshot.OrderEntry order) {
            String board = order.board().toLowerCase(Locale.ROOT);
            return board.contains("daily") || board.contains("festival") || board.contains("event");
        }
        private static boolean isReady(BoardSnapshot.OrderEntry order) {
            return order.canAccept() && order.inInventory() >= order.quantity();
        }

        private static List<BoardSnapshot.BountyEntry> filterBounties(
                List<BoardSnapshot.BountyEntry> items, String search, String status,
                String sort, boolean postedTab) {
            String query = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
            Comparator<BoardSnapshot.BountyEntry> comparator = switch (sort) {
                case "Reward" -> Comparator.comparing(
                        (BoardSnapshot.BountyEntry b) -> moneyValue(b.reward())).reversed();
                case "Progress" -> Comparator.comparingDouble(
                        (BoardSnapshot.BountyEntry b) -> bountyProgress(b)).reversed();
                default -> Comparator.comparingLong(BoardSnapshot.BountyEntry::expiresAtEpochSecond);
            };
            return items.stream().filter(b -> query.isEmpty()
                    || (b.title() + " " + b.subtitle() + " " + b.description()
                        + " " + b.objectives().stream().map(BoardSnapshot.ObjectiveEntry::target)
                                .reduce("", (a, c) -> a + " " + c))
                            .toLowerCase(Locale.ROOT).contains(query))
                    .filter(b -> switch (status) {
                        case "Active" -> b.status().equals("ACTIVE");
                        case "Claimable" -> b.canClaim();
                        case "Completed" -> b.status().equals("COMPLETED");
                        case "Expired" -> b.status().equals("EXPIRED");
                        case "Open" -> b.status().equals("OPEN") || b.status().equals("OFFERED");
                        case "Mine" -> postedTab && b.ownedByPlayer();
                        default -> true;
                    })
                    .sorted(comparator).toList();
        }

        private static double bountyProgress(BoardSnapshot.BountyEntry b) {
            long need = 0, made = 0;
            for (BoardSnapshot.ObjectiveEntry objective : b.objectives()) {
                need += objective.targetAmount();
                made += Math.min(objective.targetAmount(), objective.progress());
            }
            return need <= 0 ? 0 : made / (double) need;
        }

        private static String bountyIcon(BoardSnapshot.BountyEntry b) {
            if (b.icon() != null && !b.icon().isBlank()
                    && !b.icon().equals("minecraft:paper")) return b.icon();
            return b.objectives().isEmpty() ? "minecraft:paper"
                    : b.objectives().get(0).target();
        }
        /**
         * Automatic orders are a curated, server-owned storefront. No manual Roll
         * button is needed. A slot is locked on acceptance before a bounty grant.
         */
        private UIComponent ordersPanel() {
            int panelHeight = Math.max(92, browserHeight() - 49);
            int availableWidth = Math.max(145, mainWidth() - 10);
            int listWidth = Math.max(80, (availableWidth * 54) / 100);
            int detailWidth = Math.max(82, availableWidth - listWidth - 6);

            UIComponent browser = Ui.switcher(orderView)
                    .when("Grid", () -> Ui.virtualGrid(orderRows, this::orderGridCard)
                            .key(row -> orderKey(row.order()))
                            .minCellWidth(96).cellHeight(82).gap(4)
                            .height(panelHeight).width(listWidth))
                    .when("List", () -> Ui.list(orderRows, this::orderListRow)
                            .key(row -> orderKey(row.order()))
                            .itemHeight(39).gap(4)
                            .height(panelHeight).width(listWidth));
            UIComponent details = Ui.component(() -> {
                List<BoardSnapshot.OrderEntry> selection = selectedOrder.get();
                if (selection.isEmpty()) return List.of(Ui.node("orders-empty", "empty",
                        () -> Ui.card(Ui.column(
                                Ui.heading("No matching orders"),
                                Ui.text("Adjust your search or filters.")
                        ).gap(6)).padding(7).height(panelHeight)));
                BoardSnapshot.OrderEntry order = selection.get(0);
                return List.of(Ui.node("order-detail", orderKey(order) + "@" + order.hashCode(),
                        () -> orderDetails(order, panelHeight)));
            }).width(detailWidth).height(panelHeight);

            UIComponent browserBody = Ui.card(browser).padding(2)
                    .width(listWidth).height(panelHeight);
            UIComponent rightBody = details.width(detailWidth);
            UIComponent main = mainWidth() >= 215
                    ? centeredRow(browserBody, rightBody).gap(6)
                    : Ui.scroll(Ui.column(browserBody.height(93), rightBody.height(160)).gap(5))
                            .height(panelHeight);

            return Ui.column(
                    centeredRow(
                            Ui.textField(orderSearch).placeholder("Search item or order...").width(Math.max(85, mainWidth() - 152)),
                            Ui.select(orderScope).option("All", "All").option("Personal", "Personal")
                                    .option("Global", "Global").width(72),
                            Ui.button("Refresh", () -> BountyNetwork.sendRequest(BoardRequest.refresh())).small()
                    ).gap(4),
                    centeredRow(
                            Ui.select(orderType).option("All types", "All").option("Normal", "Normal")
                                    .option("Special", "Special").width(73),
                            Ui.select(orderStatus).option("Any state", "All").option("Ready", "Ready")
                                    .option("Available", "Available").option("Taken", "Taken")
                                    .option("Restocking", "Restocking").width(76),
                            Ui.select(orderSort).option("Ready first", "Ready").option("Reward", "Reward")
                                    .option("Soonest", "Time").option("Item A-Z", "Item").width(73),
                            Ui.button(readyOnly.get() ? "Ready ✓" : "Ready",
                                    () -> readyOnly.set(!readyOnly.get())).small(),
                            Ui.button("▦", () -> orderView.set("Grid"))
                                    .small(),
                            Ui.button("☷", () -> orderView.set("List")).small()
                    ).gap(3),
                    main
            ).gap(4);
        }

        private UIComponent orderGridCard(OrderRow row) {
            BoardSnapshot.OrderEntry o = row.order();
            String item = readableItem(o.item());
            return Ui.card(Ui.column(
                    centeredRow(OrderItemIcons.icon(o.item()),
                            Ui.text(o.quantity() + " " + item).nowrap().ellipsis(true).flex()).gap(4),
                    centeredRow(Ui.heading("$" + o.reward()),
                            Ui.spacer(), Ui.text(compactRarity(o.rarity())).nowrap().ellipsis(true)).gap(2),
                    centeredRow(Ui.text(compactScope(o.scope())),
                            Ui.spacer(), Ui.text(isSpecial(o) ? "★ Special" : shortStatus(o.status()))
                                    .nowrap().ellipsis(true)).gap(3),
                    Ui.progress(Signals.of(o.quantity() == 0 ? 0
                            : Math.min(1.0, o.inInventory() / (double) o.quantity()))).height(6),
                    Ui.text(o.canAccept()
                            ? o.inInventory() + "/" + o.quantity() + "  | " + shortOrderTime(o)
                            : shortStatus(o.status())).nowrap().ellipsis(true)
            ).gap(3)).padding(4).selected(row.selected()).elevated(false)
                    .onClick(() -> selectedOrderKey.set(orderKey(o)));
        }

        private UIComponent orderListRow(OrderRow row) {
            BoardSnapshot.OrderEntry o = row.order();
            return Ui.card(centeredRow(OrderItemIcons.icon(o.item()),
                    Ui.column(
                            Ui.text(o.quantity() + " " + readableItem(o.item())).nowrap().ellipsis(true),
                            Ui.text("$" + o.reward() + " | " + compactScope(o.scope())
                                    + (isSpecial(o) ? " ★" : "")).nowrap().ellipsis(true)
                    ).gap(3).flex()
            ).gap(5)).padding(4).selected(row.selected()).elevated(false)
                    .onClick(() -> selectedOrderKey.set(orderKey(o)));
        }

        private static String orderKey(BoardSnapshot.OrderEntry order) {
            return order.board() + "#" + order.slot();
        }

        private static String compactScope(String value) {
            return "SHARED".equals(value) ? "Global" : "Personal";
        }

        private static String compactRarity(String rarity) {
            return rarity == null || rarity.isBlank() ? "Standard" : rarity;
        }

        private static String shortStatus(String status) {
            return switch (status) {
                case "TAKEN" -> "Accepted";
                case "CLAIMED" -> "Taken";
                case "RESTOCKING" -> "Restocking";
                default -> "Available";
            };
        }

        private static Badge.Variant rarityColor(String rarity) {
            if (rarity == null) return Badge.Variant.NEUTRAL;
            return switch (rarity.toLowerCase(Locale.ROOT)) {
                case "rare", "epic", "event" -> Badge.Variant.WARNING;
                case "uncommon" -> Badge.Variant.PRIMARY;
                default -> Badge.Variant.NEUTRAL;
            };
        }

        private static String shortOrderTime(BoardSnapshot.OrderEntry order) {
            if (!order.canAccept()) return shortStatus(order.status());
            long seconds = Math.max(0, order.expiresAt() - Instant.now().getEpochSecond());
            return formatTime(seconds);
        }

        private UIComponent orderDetails(BoardSnapshot.OrderEntry order, int height) {
            List<UIComponent> content = new ArrayList<>();
            content.add(centeredRow(
                    OrderItemIcons.icon(order.item()).size(25),
                    Ui.column(
                            Ui.heading(order.title()).nowrap().ellipsis(true),
                            Ui.text(readableItem(order.item())).nowrap().ellipsis(true)
                    ).gap(3).flex()
            ).gap(6));
            content.add(centeredRow(
                    Ui.badge(Component.literal(compactScope(order.scope())), Badge.Variant.NEUTRAL),
                    Ui.badge(Component.literal(compactRarity(order.rarity())), rarityColor(order.rarity())),
                    isSpecial(order) ? Ui.badge(Component.literal("Special"), Badge.Variant.WARNING) : Ui.spacer()
            ).gap(3));
            if (order.canAccept()) {
                content.add(Ui.divider());
                content.add(centeredRow(
                        OrderItemIcons.icon(order.item()),
                        Ui.heading("Need " + order.quantity()),
                        Ui.spacer(),
                        Ui.badge(Component.literal(order.inInventory() + "/" + order.quantity()),
                                isReady(order) ? Badge.Variant.SUCCESS : Badge.Variant.NEUTRAL)
                ).gap(3));
                double ratio = order.quantity() <= 0 ? 0
                        : Math.max(0, Math.min(1, order.inInventory() / (double) order.quantity()));
                content.add(Ui.progress(Signals.of(ratio)).height(7).fillWidth());
                content.add(Ui.text(isReady(order) ? "Ready to deliver"
                        : "Missing " + Math.max(0, order.quantity() - order.inInventory()) + " items"));
            } else {
                content.add(Ui.text(shortStatus(order.status())));
            }
            content.add(Ui.divider());
            content.add(centeredRow(Ui.icon("$", 0x5BE0BC),
                    Ui.heading("$" + order.reward()), Ui.spacer()).gap(4));
            content.add(Ui.text(orderTime(order)));
            if (order.canAccept()) content.add(Ui.text(switch (order.timer()) {
                case "ACCEPT" -> "Delivery clock begins on acceptance";
                case "OFFER" -> "Rush deadline already active";
                default -> "No delivery deadline";
            }));

            List<UIComponent> actions = new ArrayList<>();
            if (order.canAccept()) {
                if (isReady(order)) actions.add(Ui.button("Deliver", () ->
                        BountyNetwork.sendRequest(BoardRequest.order(
                                BoardRequest.Action.FULFILL_ORDER, order.board(), order.slot(),
                                order.offerId()))).success().small());
                actions.add(Ui.button("Accept", () -> BountyNetwork.sendRequest(BoardRequest.order(
                        BoardRequest.Action.ACCEPT_ORDER, order.board(), order.slot(), order.offerId())))
                        .primary().small());
                if (order.canDiscard()) actions.add(Ui.button("Discard", () ->
                        BountyNetwork.sendRequest(BoardRequest.order(
                                BoardRequest.Action.DISCARD_ORDER, order.board(), order.slot(),
                                order.offerId()))).ghost().small());
            } else if ("TAKEN".equals(order.status())) {
                actions.add(Ui.button("Contracts", () -> tab.set("Generated")).primary().small());
            }
            UIComponent footer = actions.isEmpty() ? Ui.text("Auto-restocking")
                    : centeredRow(actions.toArray(UIComponent[]::new)).gap(3);
            return Ui.card(Ui.column(
                    Ui.scroll(Ui.column(content.toArray(UIComponent[]::new)).gap(5))
                            .height(Math.max(62, height - 37)),
                    Ui.divider(),
                    footer
            ).gap(4)).padding(5).height(height);
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

        /** Accepted system orders only; players cannot mint new system offers. */
        private UIComponent generatedPanel() {
            return Ui.column(
                    Ui.heading("Your contracts"),
                    Ui.list(generated, this::bountyCard).itemHeight(156).gap(5)
                            .height(Math.max(130, uiHeight() - 108))
            ).gap(5);
        }

        private UIComponent postedPanel() {
            return Ui.column(
                    centeredRow(Ui.heading("Player-posted bounties"), Ui.spacer(),
                            Ui.button("Create", () -> tab.set("Create")).primary().small()).gap(6),
                    Ui.list(posted, this::bountyCard).itemHeight(156).gap(5).height(235)
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
