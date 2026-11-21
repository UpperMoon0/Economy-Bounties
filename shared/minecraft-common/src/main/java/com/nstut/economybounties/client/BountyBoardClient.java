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
        // An explicit viewport is essential: OpenUI's VStack measures a
        // SignalSwitcher using preferred (intrinsic) height, which is only one
        // VirtualGrid row. Without a height on the switcher *and* its browser
        // row, the outer card stays tall while all content stops halfway down.
        private int contentHeight() { return Math.max(70, mainHeight() - 45); }
        private int orderBrowserHeight() { return Math.max(50, contentHeight() - 56); }
        private int bountyBrowserHeight() { return Math.max(50, contentHeight() - 29); }

        @Override protected UIComponent buildUI() {
            int side = sideWidth(), contentWidth = mainWidth(), height = mainHeight();
            UIComponent sidebar = Ui.scroll(Ui.column(
                    Ui.text("BOUNTIES").nowrap(),
                    Ui.divider(),
                    navButton("Orders", "Orders"),
                    navButton("Contracts", "Generated"),
                    navButton("Posted", "Posted"),
                    navButton("Create", "Create")
            ).gap(5)).width(side).height(height);
            UIComponent main = Ui.column(
                    centeredRow(
                            Ui.component(() -> {
                                String current = tab.get();
                                String heading = switch (current) {
                                    case "Generated" -> "Contracts";
                                    case "Posted" -> "Posted bounties";
                                    case "Create" -> "Create bounty";
                                    default -> "Orders";
                                };
                                return List.of(Ui.node("active-tab-heading", current,
                                        () -> Ui.heading(heading)));
                            }),
                            Ui.spacer(),
                            Ui.button("Refresh", () -> BountyNetwork.sendRequest(BoardRequest.refresh())).small(),
                            Ui.button("Close", this::onClose).ghost().small()
                    ).gap(4).height(24),
                    Ui.text(notice).height(11),
                    Ui.switcher(tab)
                            .when("Orders", this::ordersPanel)
                            .when("Generated", this::generatedPanel)
                            .when("Posted", this::postedPanel)
                            .when("Create", this::createPanel)
                            .height(contentHeight())
            ).gap(5).width(contentWidth).height(height);
            return Ui.card(centeredRow(sidebar, main).gap(7))
                    .padding(7).elevated(true).height(uiHeight());
        }

        /** Reactive, narrow, independently scrollable Economy-style navigation. */
        private UIComponent navButton(String caption, String destination) {
            return Ui.component(() -> {
                String selected = tab.get();
                return List.of(Ui.node("nav", destination + selected, () -> {
                    int color = destination.equals(selected) ? 0x76EDCB : 0xBDB9C4;
                    String icon = switch (destination) {
                        case "Orders" -> "1";
                        case "Generated" -> "2";
                        case "Posted" -> "3";
                        default -> "+";
                    };
                    return Ui.card(centeredRow(
                            Ui.icon(icon, color),
                            Ui.text(caption).nowrap().ellipsis(true).flex()
                    ).gap(4)).padding(4).elevated(false)
                            .selected(destination.equals(selected))
                            .onClick(() -> tab.set(destination));
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
            int panelHeight = orderBrowserHeight();
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
                    ? centeredRow(browserBody, rightBody).gap(6).height(panelHeight)
                    : Ui.scroll(Ui.column(browserBody.height(93), rightBody.height(160)).gap(5))
                            .height(panelHeight);

            return Ui.column(
                    centeredRow(
                            Ui.textField(orderSearch).placeholder("Search item or order...")
                                    .width(Math.max(80, mainWidth() - 206)),
                            Ui.select(orderScope).option("All scopes", "All").option("Personal", "Personal")
                                    .option("Global", "Global").width(92),
                            Ui.select(orderType).option("All types", "All").option("Normal", "Normal")
                                    .option("Special", "Special").width(92)
                    ).gap(4).height(24),
                    centeredRow(
                            Ui.select(orderStatus).option("Any status", "All").option("Ready", "Ready")
                                    .option("Available", "Available").option("Taken", "Taken")
                                    .option("Restocking", "Restocking").width(106),
                            Ui.select(orderSort).option("Ready first", "Ready").option("Reward", "Reward")
                                    .option("Soonest", "Time").option("Item A-Z", "Item").width(106),
                            Ui.switcher(readyOnly)
                            .when(true, () -> Ui.button("Ready ✓",
                                    () -> readyOnly.set(false)).primary().small())
                            .when(false, () -> Ui.button("Ready",
                                    () -> readyOnly.set(true)).small()),
                            viewToggle(orderView)
                    ).gap(3).height(24),
                    main
            ).gap(4).height(contentHeight());
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
                            .height(Math.max(54, height - 52)),
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
        /** Contracts are tracking/claiming only; manual system rolls stay prohibited. */
        private UIComponent generatedPanel() {
            int panelHeight = bountyBrowserHeight();
            return Ui.column(
                    bountyToolbar(false),
                    bountyBrowser(visibleContracts, contractRows,
                            contractSelection, contractView, panelHeight, false)
            ).gap(5).height(contentHeight());
        }

        private UIComponent postedPanel() {
            int panelHeight = bountyBrowserHeight();
            return Ui.column(
                    bountyToolbar(true),
                    bountyBrowser(visiblePosted, postedRows,
                            postedSelection, postedView, panelHeight, true)
            ).gap(5).height(contentHeight());
        }

        private UIComponent bountyToolbar(boolean postedTab) {
            Signal<String> search = postedTab ? postedSearch : contractSearch;
            Signal<String> status = postedTab ? postedStatus : contractStatus;
            Signal<String> sort = postedTab ? postedSort : contractSort;
            Signal<String> view = postedTab ? postedView : contractView;
            UIComponent statusFilter = postedTab
                    ? Ui.select(status).option("All", "All").option("Open", "Open")
                            .option("Active", "Active").option("Completed", "Completed")
                            .option("Expired", "Expired").option("Mine", "Mine").width(75)
                    : Ui.select(status).option("All", "All").option("Active", "Active")
                            .option("Claimable", "Claimable").option("Completed", "Completed")
                            .option("Expired", "Expired").width(75);
            return centeredRow(
                    Ui.textField(search).placeholder(postedTab
                            ? "Search posted..." : "Search contracts...")
                            .width(Math.max(87, mainWidth() - 268)),
                    statusFilter,
                    Ui.select(sort).option("Expiry", "Expiry").option("Reward", "Reward")
                            .option("Progress", "Progress").width(70),
                    viewToggle(view),
                    postedTab ? Ui.button("+", () -> tab.set("Create")).primary().small() : Ui.spacer()
            ).gap(4).height(24);
        }

        private UIComponent viewToggle(Signal<String> view) {
            return Ui.switcher(view)
                    .when("Grid", () -> centeredRow(
                            Ui.button("Grid", () -> view.set("Grid")).primary().small(),
                            Ui.button("List", () -> view.set("List")).small()
                    ).gap(2))
                    .when("List", () -> centeredRow(
                            Ui.button("Grid", () -> view.set("Grid")).small(),
                            Ui.button("List", () -> view.set("List")).primary().small()
                    ).gap(2));
        }

        private UIComponent bountyBrowser(
                Computed<List<BoardSnapshot.BountyEntry>> filtered,
                Computed<List<BountyRow>> rows,
                Signal<String> selectedId, Signal<String> view,
                int height, boolean postedTab) {
            int availableWidth = Math.max(142, mainWidth() - 8);
            int browserWidth = Math.max(82, (availableWidth * 51) / 100);
            int detailWidth = Math.max(85, availableWidth - browserWidth - 6);
            UIComponent browse = Ui.switcher(view)
                    .when("Grid", () -> Ui.virtualGrid(rows, this::bountyGridCard)
                            .key(row -> row.bounty().id()).minCellWidth(96)
                            .cellHeight(79).gap(4).height(height).width(browserWidth))
                    .when("List", () -> Ui.list(rows, this::bountyListRow)
                            .key(row -> row.bounty().id()).itemHeight(47).gap(4)
                            .height(height).width(browserWidth));

            UIComponent detail = Ui.component(() -> {
                List<BoardSnapshot.BountyEntry> filteredItems = filtered.get();
                String chosen = selectedId.get();
                if (filteredItems.isEmpty()) return List.of(Ui.node("bounty-empty", "empty",
                        () -> Ui.card(Ui.column(
                                Ui.heading("Nothing to show"),
                                Ui.text("Try another status or search term.")
                        ).gap(5)).padding(7).height(height)));
                BoardSnapshot.BountyEntry b = filteredItems.stream()
                        .filter(entry -> entry.id().equals(chosen))
                        .findFirst().orElse(filteredItems.get(0));
                return List.of(Ui.node("bounty-detail", b.id() + ":" + b.hashCode(),
                        () -> bountyDetailPanel(b, height)));
            }).height(height).width(detailWidth);
            return mainWidth() >= 215
                    ? centeredRow(Ui.card(browse).padding(2).width(browserWidth).height(height),
                            detail).gap(6).height(height)
                    : Ui.scroll(Ui.column(browse.height(98), detail.height(160)).gap(5))
                            .height(height);
        }

        private UIComponent bountyGridCard(BountyRow row) {
            BoardSnapshot.BountyEntry b = row.bounty();
            return Ui.card(Ui.column(
                    centeredRow(OrderItemIcons.icon(bountyIcon(b)),
                            Ui.text(readableItem(b.title())).nowrap().ellipsis(true).flex()).gap(4),
                    Ui.heading("$" + b.reward()).nowrap().ellipsis(true),
                    centeredRow(
                            Ui.text(b.status()).nowrap().ellipsis(true),
                            Ui.spacer(),
                            Ui.text(b.ownedByPlayer() ? "Mine" : " ").nowrap()
                    ).gap(2),
                    Ui.progress(Signals.of(bountyProgress(b))).height(6),
                    Ui.text("Due " + countdown(b.expiresAtEpochSecond()))
                            .nowrap().ellipsis(true)
            ).gap(3)).padding(4).elevated(false).selected(row.selected())
                    .onClick(() -> selectBounty(b));
        }

        private UIComponent bountyListRow(BountyRow row) {
            BoardSnapshot.BountyEntry b = row.bounty();
            return Ui.card(centeredRow(
                    OrderItemIcons.icon(bountyIcon(b)),
                    Ui.column(
                            Ui.text(readableItem(b.title())).nowrap().ellipsis(true),
                            Ui.text("$" + b.reward() + "  " + b.status())
                                    .nowrap().ellipsis(true)
                    ).gap(4).flex()
            ).gap(5)).padding(4).elevated(false).selected(row.selected())
                    .onClick(() -> selectBounty(b));
        }

        private void selectBounty(BoardSnapshot.BountyEntry bounty) {
            if ("posted".equals(bounty.source())) postedSelection.set(bounty.id());
            else contractSelection.set(bounty.id());
        }

        private UIComponent bountyDetailPanel(BoardSnapshot.BountyEntry bounty, int height) {
            List<UIComponent> content = new ArrayList<>();
            content.add(centeredRow(
                    OrderItemIcons.icon(bountyIcon(bounty)).size(25),
                    Ui.column(
                            Ui.heading(readableItem(bounty.title())).nowrap().ellipsis(true),
                            Ui.text(bounty.source().equals("posted")
                                    ? "Player-funded" : "System order").nowrap()
                    ).gap(3).flex()
            ).gap(6));
            content.add(centeredRow(
                    Ui.badge(Component.literal(bounty.status()),
                            bounty.canClaim() ? Badge.Variant.SUCCESS : Badge.Variant.NEUTRAL),
                    bounty.ownedByPlayer()
                            ? Ui.badge(Component.literal("Mine"), Badge.Variant.PRIMARY)
                            : Ui.spacer()
            ).gap(3));
            if (!bounty.subtitle().isBlank()) content.add(Ui.text(bounty.subtitle()));
            if (!bounty.description().isBlank()) content.add(Ui.text(bounty.description()));
            content.add(Ui.divider());
            content.add(Ui.heading("Objectives"));
            for (BoardSnapshot.ObjectiveEntry objective : bounty.objectives()) {
                List<UIComponent> pieces = new ArrayList<>();
                pieces.add(centeredRow(
                        objective.type().contains("kill_entity")
                                ? Ui.icon("☠", 0xE0B3A0)
                                : OrderItemIcons.icon(objective.target()),
                        Ui.text(readableItem(objective.target()) + " "
                                + objective.progress() + "/" + objective.targetAmount())
                                .nowrap().ellipsis(true).flex()
                ).gap(5));
                double ratio = objective.targetAmount() <= 0 ? 0
                        : Math.max(0, Math.min(1, objective.progress() /
                                (double) objective.targetAmount()));
                pieces.add(Ui.progress(Signals.of(ratio)).height(6));
                if (objective.deliverable() && bounty.objectives().size() > 1) {
                    pieces.add(Ui.button("Deliver this", () -> deliverObjective(bounty, objective))
                            .success().small());
                }
                content.add(Ui.card(Ui.column(pieces.toArray(UIComponent[]::new))
                        .gap(3)).padding(5));
            }
            content.add(Ui.divider());
            content.add(centeredRow(Ui.icon("$", 0x5BE0BC),
                    Ui.heading("$" + bounty.reward()), Ui.spacer()).gap(4));
            content.add(Ui.text("Expires in " + countdown(bounty.expiresAtEpochSecond())));
            List<UIComponent> actions = new ArrayList<>();
            if (bounty.objectives().size() == 1 && bounty.objectives().get(0).deliverable()) {
                BoardSnapshot.ObjectiveEntry objective = bounty.objectives().get(0);
                actions.add(Ui.button("Deliver", () -> deliverObjective(bounty, objective))
                        .success().small());
            }
            if (bounty.canAccept()) actions.add(Ui.button("Accept", () ->
                    sendBountyAction(bounty, true, false, false)).primary().small());
            if (bounty.canClaim()) actions.add(Ui.button("Claim", () ->
                    sendBountyAction(bounty, false, false, true)).success().small());
            if (bounty.canCancel()) actions.add(Ui.button("Cancel", () ->
                    sendBountyAction(bounty, false, true, false)).danger().small());
            UIComponent footer = actions.isEmpty() ? Ui.text("No actions available")
                    : centeredRow(actions.toArray(UIComponent[]::new)).gap(4);
            return Ui.card(Ui.column(
                    Ui.scroll(Ui.column(content.toArray(UIComponent[]::new)).gap(5))
                            .height(Math.max(54, height - 52)),
                    Ui.divider(),
                    footer
            ).gap(4)).padding(5).height(height);
        }

        private void deliverObjective(BoardSnapshot.BountyEntry b, BoardSnapshot.ObjectiveEntry objective) {
            BountyNetwork.sendRequest(BoardRequest.deliver(b.source(), b.id(),
                    objective.index(), objective.type(), objective.target()));
        }

        private static String countdown(long epochSeconds) {
            return formatTime(Math.max(0, epochSeconds - Instant.now().getEpochSecond()));
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
            int panelHeight = contentHeight();
            int availableWidth = Math.max(145, mainWidth() - 8);
            int editorWidth = Math.max(94, (availableWidth * 55) / 100);
            int previewWidth = Math.max(90, availableWidth - editorWidth - 6);
            int inputWidth = Math.max(74, editorWidth - 22);

            List<UIComponent> editor = new ArrayList<>();
            editor.add(Ui.heading("Player-funded bounty"));
            editor.add(verticalField("Title", title, inputWidth));
            editor.add(verticalField("Description", description, inputWidth));
            editor.add(verticalField("Icon ID", icon, inputWidth));
            editor.add(centeredRow(
                    verticalField("Reward ($)", reward, Math.max(43, inputWidth / 2 - 4)),
                    verticalField("Lifetime (min)", lifetime, Math.max(43, inputWidth / 2 - 4))
            ).gap(4));
            editor.add(Ui.divider());
            editor.add(centeredRow(Ui.heading("Objectives"), Ui.spacer(),
                    Ui.button("+ Add", this::addObjectiveForm).small()).gap(3));
            editor.add(Ui.list(objectiveForms, this::objectiveRow)
                    .itemHeight(52).gap(4).height(132));
            editor.add(Ui.text("Choose a type, target item/entity and quantity."));
            editor.add(Ui.divider());
            editor.add(Ui.switcher(advancedCreate)
                    .when(true, () -> Ui.button("Hide advanced",
                            () -> advancedCreate.set(false)).primary().small())
                    .when(false, () -> Ui.button("Audience & progression",
                            () -> advancedCreate.set(true)).small()));
            editor.add(Ui.component(() -> {
                boolean expanded = advancedCreate.get();
                return List.of(Ui.node("advanced-create", String.valueOf(expanded), () ->
                        expanded ? advancedAudienceForm(inputWidth)
                                : Ui.text("Public by default; expand for restrictions.")));
            }));

            UIComponent form = Ui.card(
                    Ui.scroll(Ui.column(editor.toArray(UIComponent[]::new)).gap(6))
                            .height(panelHeight - 8)
            ).padding(4).height(panelHeight).width(editorWidth);
            UIComponent preview = Ui.component(() -> {
                // Depend on each live field, so typing updates the preview.
                String name = title.get(), desc = description.get(), item = icon.get(),
                        payment = reward.get(), minutes = lifetime.get();
                boolean isPublic = publicAccess.get();
                List<ObjectiveForm> objectives = objectiveForms.get();
                StringBuilder key = new StringBuilder(name).append('|').append(desc).append('|')
                        .append(item).append('|').append(payment).append('|').append(minutes)
                        .append('|').append(isPublic);
                for (ObjectiveForm objective : objectives) key.append('|')
                        .append(objective.type.get()).append(':').append(objective.target.get())
                        .append(':').append(objective.amount.get());
                return List.of(Ui.node("create-preview", key.toString(),
                        () -> createPreview(panelHeight)));
            }).height(panelHeight).width(previewWidth);

            return availableWidth >= 215
                    ? centeredRow(form, preview).gap(6).height(panelHeight)
                    : Ui.scroll(Ui.column(form.height(170), preview.height(150)).gap(5))
                            .height(panelHeight);
        }

        private UIComponent createPreview(int height) {
            List<UIComponent> content = new ArrayList<>();
            content.add(Ui.heading("Live preview"));
            content.add(centeredRow(
                    OrderItemIcons.icon(icon.get()).size(25),
                    Ui.column(
                            Ui.heading(title.get().isBlank() ? "Untitled bounty" : title.get())
                                    .nowrap().ellipsis(true),
                            Ui.text(publicAccess.get() ? "Public contract" : "Restricted audience")
                                    .nowrap().ellipsis(true)
                    ).gap(3).flex()
            ).gap(5));
            if (!description.get().isBlank()) content.add(Ui.text(description.get()));
            content.add(Ui.divider());
            content.add(centeredRow(Ui.icon("$", 0x5BE0BC),
                    Ui.heading("$" + reward.get()), Ui.spacer()).gap(4));
            content.add(Ui.text("Lifetime: " + lifetime.get() + " minutes"));
            content.add(Ui.heading("Objectives"));
            for (ObjectiveForm obj : objectiveForms.get()) {
                content.add(Ui.card(Ui.column(
                        Ui.text(readableItem(obj.type.get())).nowrap().ellipsis(true),
                        centeredRow(OrderItemIcons.icon(obj.target.get()),
                                Ui.text(obj.amount.get() + " " + readableItem(obj.target.get()))
                                        .nowrap().ellipsis(true).flex()).gap(4)
                ).gap(3)).padding(4));
            }
            content.add(Ui.text("The server verifies payout funding and permissions on submission."));
            return Ui.card(Ui.column(
                    Ui.scroll(Ui.column(content.toArray(UIComponent[]::new)).gap(5))
                            .height(Math.max(54, height - 54)),
                    Ui.divider(),
                    centeredRow(
                            Ui.button("Back", () -> tab.set("Posted")).ghost().small(),
                            Ui.button("Preview", this::validateCreateDraft).small(),
                            Ui.button("Create & fund", this::submitCreate).primary().small()
                    ).gap(3)
            ).gap(4)).padding(4).height(height);
        }

        private UIComponent advancedAudienceForm(int width) {
            return Ui.column(
                    Ui.checkbox("Public", publicAccess),
                    verticalField("Allowed players (names/UUID)", allowedPlayers, width),
                    verticalField("Allowed groups", allowedGroups, width),
                    verticalField("Denied players", deniedPlayers, width),
                    verticalField("Progression group", progressionGroup, width),
                    centeredRow(
                            verticalField("Min lvl", minLevel, Math.max(44, width / 2 - 4)),
                            verticalField("Max lvl", maxLevel, Math.max(44, width / 2 - 4))
                    ).gap(3)
            ).gap(5);
        }

        private UIComponent verticalField(String label, Signal<String> value, int width) {
            return Ui.column(Ui.text(label).nowrap().ellipsis(true),
                    Ui.textField(value).width(width)).gap(3);
        }

        private UIComponent objectiveRow(ObjectiveForm form) {
            int half = Math.max(49, (Math.max(96, (mainWidth() * 55) / 100) - 28) / 2);
            return Ui.card(Ui.column(
                    centeredRow(
                            Ui.select(form.type).option("Deliver item", "economy_bounties:deliver_item")
                                    .option("Deliver fluid", "economy_bounties:deliver_fluid")
                                    .option("Kill entity", "economy_bounties:kill_entity")
                                    .option("Craft item", "economy_bounties:craft_item")
                                    .option("Mine block", "economy_bounties:mine_block")
                                    .option("Visit location", "economy_bounties:visit_location")
                                    .width(Math.max(82, half + 20)),
                            Ui.spacer(),
                            Ui.button("×", () -> removeObjectiveForm(form)).danger().small()
                    ).gap(3),
                    centeredRow(
                            Ui.textField(form.target).placeholder("target ID").width(Math.max(70, half + 17)),
                            Ui.textField(form.amount).placeholder("qty").numeric().width(43)
                    ).gap(3)
            ).gap(2)).padding(3);
        }

        private void validateCreateDraft() {
            if (title.get().isBlank()) { notice.set("Enter a title before creating"); return; }
            if (moneyValue(reward.get()).signum() <= 0) {
                notice.set("Reward must be greater than zero"); return;
            }
            if (parseLong(lifetime.get(), 0) <= 0) {
                notice.set("Lifetime must be at least one minute"); return;
            }
            for (ObjectiveForm objective : objectiveForms.get()) {
                if (objective.type.get().isBlank() || objective.target.get().isBlank()
                        || parseLong(objective.amount.get(), 0) <= 0) {
                    notice.set("Every objective needs a type, target and quantity"); return;
                }
            }
            notice.set("Preview valid. Server will verify funds and audience on creation.");
        }

        private void addObjectiveForm() {
            List<ObjectiveForm> current = objectiveForms.get();
            if (current.size() >= MAX_OBJECTIVES) {
                notice.set("A posted bounty can have at most " + MAX_OBJECTIVES + " objectives");
                return;
            }
            List<ObjectiveForm> next = new ArrayList<>(current);
            next.add(new ObjectiveForm("economy_bounties:deliver_item", "minecraft:paper", "1"));
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
