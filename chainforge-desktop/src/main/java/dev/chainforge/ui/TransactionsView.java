package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import static dev.chainforge.ui.NetworkSupport.networkByKey;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.TransactionRowsView.networkNameOrKey;
import static dev.chainforge.ui.TransactionRowsView.txAssetLabel;
import static dev.chainforge.ui.ViewSupport.csv;
import static dev.chainforge.ui.ViewSupport.fieldCard;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.pageShell;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.primary;
import static dev.chainforge.ui.ViewSupport.rootMessage;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.section;

final class TransactionsView {
    private final MainView app;
    private final TransactionHistory history;
    private final TransactionRowsView rowsView;

    TransactionsView(MainView app) {
        this.app = app;
        this.history = new TransactionHistory(app::adapterFor);
        this.rowsView = new TransactionRowsView(app);
    }

    private Timeline transactionPolling;
    private final AtomicBoolean txPollInFlight = new AtomicBoolean(false);
    private VBox transactionList;
    private boolean txTableView;
    private String txFilter = "ALL"; // ALL | IN | OUT
    // Transactions page search / filters (kept while you move between pages). null = no restriction.
    private String txSearch = "";
    private String txNetworkFilter, txAssetFilter, txStatusFilter;
    private java.time.LocalDate txFrom, txTo;
    private Runnable txFilterOptionsRefresh;
    private static final int TX_RENDER_LIMIT = 200;
    private final AtomicBoolean incomingSyncInFlight = new AtomicBoolean(false);
    private Timeline incomingSyncTimer;
    private Label incomingSyncLabel;

    VBox build() {
        VBox v = pageShell("Transactions", "Browse received and sent transfers across your wallets.");
        ToggleGroup filterGroup = new ToggleGroup();
        HBox filters = new HBox(4);
        filters.getStyleClass().add("tx-direction-switch");
        for (String[] f : new String[][]{{"ALL", "All"}, {"IN", "↓ Incoming"}, {"OUT", "↑ Outgoing"}}) {
            ToggleButton b = new ToggleButton(f[1]);
            b.setToggleGroup(filterGroup);
            b.getStyleClass().add("filter-toggle");
            b.setSelected(f[0].equals(txFilter));
            b.setOnAction(e -> {
                if (!b.isSelected()) b.setSelected(true); // one filter is always active
                txFilter = f[0];
                renderTransactionList(transactionList);
            });
            filters.getChildren().add(b);
        }
        Button refresh = new Button("Refresh statuses");
        refresh.getStyleClass().add("secondary");
        refresh.setOnAction(e -> {
            renderTransactionList(transactionList);
            pollPendingTransactions();
        });
        ToggleGroup viewGroup = new ToggleGroup();
        HBox viewSwitch = new HBox(4);
        viewSwitch.getStyleClass().add("tx-direction-switch");
        for (String view : List.of("Cards", "Table")) {
            boolean table = "Table".equals(view);
            ToggleButton button = new ToggleButton(view);
            button.setToggleGroup(viewGroup);
            button.getStyleClass().add("filter-toggle");
            button.setSelected(txTableView == table);
            button.setOnAction(e -> {
                if (!button.isSelected()) button.setSelected(true);
                if (txTableView == table) return;
                txTableView = table;
                renderTransactionList(transactionList);
            });
            viewSwitch.getChildren().add(button);
        }
        Button lookup = new Button("Find by hash");
        lookup.getStyleClass().add("secondary");
        lookup.setOnAction(e -> lookupIncomingTransaction());
        FlowPane toolbar = new FlowPane(8, 8, primary("Sync incoming", () -> syncIncoming(true)), lookup, refresh, exportButton());
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        Label pollingLabel = muted("● Auto-check active · every 15 seconds");
        pollingLabel.getStyleClass().add("polling-indicator");
        incomingSyncLabel = muted("Incoming: not synced yet");
        Hyperlink details = new Hyperlink("Sync details");
        details.setOnAction(e -> showIncomingSyncDetails());
        FlowPane statusLabels = new FlowPane(14, 6, pollingLabel, incomingSyncLabel, details);
        statusLabels.setAlignment(Pos.CENTER_LEFT);
        VBox filterPanel = transactionFilterBar(filters);
        Region historySpacer = new Region();
        HBox.setHgrow(historySpacer, Priority.ALWAYS);
        HBox historyHeader = new HBox(12, section("TRANSACTION HISTORY"), historySpacer, viewSwitch);
        historyHeader.setAlignment(Pos.CENTER_LEFT);
        VBox historyPanel = panel();
        transactionList = new VBox(12);
        historyPanel.getChildren().addAll(historyHeader, transactionList);
        renderTransactionList(transactionList);
        v.getChildren().addAll(toolbar, statusLabels, filterPanel, historyPanel);
        if (transactionPolling != null) transactionPolling.stop();
        transactionPolling = new Timeline(new KeyFrame(Duration.seconds(15), e -> pollPendingTransactions()));
        transactionPolling.setCycleCount(Timeline.INDEFINITE);
        transactionPolling.play();
        pollPendingTransactions();
        // Received transfers cannot be pushed to us: read them from the chain / indexer now and then every 90 s.
        if (incomingSyncTimer != null) incomingSyncTimer.stop();
        incomingSyncTimer = new Timeline(new KeyFrame(Duration.seconds(90), e -> syncIncoming(false)));
        incomingSyncTimer.setCycleCount(Timeline.INDEFINITE);
        incomingSyncTimer.play();
        syncIncoming(false);
        return v;
    }

    private Map<String, String> tokenSymbolsFor(List<Repository.Tx> txs) {
        Map<String, String> symbols = new HashMap<>();
        for (String nk : txs.stream().map(Repository.Tx::network).distinct().toList()) {
            try {
                for (var tk : Repository.tokens(nk))
                    symbols.put(nk + "|" + tk.contract().toLowerCase(Locale.ROOT), tk.symbol());
            } catch (Exception ignored) {
            }
        }
        return symbols;
    }

    /**
     * Asset as the user knows it: ETH / AVAX / BTC..., a tracked token's symbol, or "ERC20 · 0x12…ab" for unknown tokens.
     */
    private static java.time.LocalDate txLocalDate(String created) {
        try {
            return java.time.LocalDateTime.parse(created.trim().replace(' ', 'T')).atOffset(java.time.ZoneOffset.UTC)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesTxFilters(Repository.Tx t, Map<String, String> tokenSymbols) {
        if (txNetworkFilter != null && !txNetworkFilter.equals(t.network())) return false;
        String asset = txAssetLabel(t, tokenSymbols);
        if (txAssetFilter != null && !txAssetFilter.equals(asset)) return false;
        if (txStatusFilter != null) {
            String st = safe(t.status()).toUpperCase(Locale.ROOT);
            boolean ok = switch (txStatusFilter) {
                case "CONFIRMED" -> st.equals("CONFIRMED") || st.equals("SUCCESS");
                case "PENDING" -> st.equals("PENDING") || st.equals("SUBMITTED");
                case "FAILED" -> st.equals("FAILED");
                case "NEEDS_REVIEW" -> st.equals("UNKNOWN") || st.equals("SUBMITTING");
                default -> true;
            };
            if (!ok) return false;
        }
        if (txFrom != null || txTo != null) {
            java.time.LocalDate d = txLocalDate(t.created());
            if (d == null || (txFrom != null && d.isBefore(txFrom)) || (txTo != null && d.isAfter(txTo))) return false;
        }
        if (!txSearch.isBlank()) {
            String hay = String.join("\n", safe(t.hash()), safe(t.from()), safe(t.to()), recipientDisplay(safe(t.to())), safe(t.amount()), asset, safe(t.asset()),
                    networkNameOrKey(t.network()), safe(t.error())).toLowerCase(Locale.ROOT);
            for (String word : txSearch.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                if (!hay.contains(word)) return false; // every word must match somewhere
        }
        return true;
    }

    /**
     * Search box + network / asset / status / date-range filters for the Transactions page.
     */
    private VBox transactionFilterBar(HBox directions) {
        TextField search = new TextField(txSearch);
        search.setPromptText("Search hash, address, amount, asset, error…");
        search.setPrefWidth(290);
        javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> renderTransactionList(transactionList));
        search.textProperty().addListener((o, a, b) -> {
            txSearch = b == null ? "" : b;
            debounce.playFromStart(); // wait for a pause in typing before re-rendering
        });

        ComboBox<String> network = new ComboBox<>();
        ComboBox<String> asset = new ComboBox<>();
        ComboBox<String> status = new ComboBox<>();
        for (ComboBox<String> c : List.of(network, asset, status)) c.setPrefWidth(155);
        network.setCellFactory(lv -> filterCell(v -> "ALL".equals(v) ? "All networks" : networkNameOrKey(v)));
        network.setButtonCell(filterCell(v -> "ALL".equals(v) ? "All networks" : networkNameOrKey(v)));
        asset.setCellFactory(lv -> filterCell(v -> "ALL".equals(v) ? "All assets" : v));
        asset.setButtonCell(filterCell(v -> "ALL".equals(v) ? "All assets" : v));
        java.util.function.Function<String, String> statusText = v -> switch (v) {
            case "CONFIRMED" -> "Confirmed";
            case "PENDING" -> "Pending";
            case "FAILED" -> "Failed";
            case "NEEDS_REVIEW" -> "Needs review";
            default -> "Any status";
        };
        status.getItems().addAll("ALL", "CONFIRMED", "PENDING", "FAILED", "NEEDS_REVIEW");
        status.setCellFactory(lv -> filterCell(statusText));
        status.setButtonCell(filterCell(statusText));
        status.setValue(txStatusFilter == null ? "ALL" : txStatusFilter);

        boolean[] updating = {false};
        txFilterOptionsRefresh = () -> {
            try {
                var all = Repository.transactions();
                var symbols = tokenSymbolsFor(all);
                updating[0] = true;
                List<String> nets = new ArrayList<>(List.of("ALL"));
                all.stream().map(Repository.Tx::network).distinct().sorted(Comparator.comparing(TransactionRowsView::networkNameOrKey)).forEach(nets::add);
                List<String> assets = new ArrayList<>(List.of("ALL"));
                all.stream().map(t -> txAssetLabel(t, symbols)).distinct().sorted().forEach(assets::add);
                if (txNetworkFilter != null && !nets.contains(txNetworkFilter)) nets.add(txNetworkFilter);
                if (txAssetFilter != null && !assets.contains(txAssetFilter)) assets.add(txAssetFilter);
                network.getItems().setAll(nets);
                asset.getItems().setAll(assets);
                network.setValue(txNetworkFilter == null ? "ALL" : txNetworkFilter);
                asset.setValue(txAssetFilter == null ? "ALL" : txAssetFilter);
            } catch (Exception ignored) {
            } finally {
                updating[0] = false;
            }
        };
        txFilterOptionsRefresh.run();
        network.setOnAction(e -> {
            if (updating[0]) return;
            txNetworkFilter = "ALL".equals(network.getValue()) ? null : network.getValue();
            renderTransactionList(transactionList);
        });
        asset.setOnAction(e -> {
            if (updating[0]) return;
            txAssetFilter = "ALL".equals(asset.getValue()) ? null : asset.getValue();
            renderTransactionList(transactionList);
        });
        status.setOnAction(e -> {
            txStatusFilter = "ALL".equals(status.getValue()) ? null : status.getValue();
            renderTransactionList(transactionList);
        });

        DatePicker from = new DatePicker(txFrom), to = new DatePicker(txTo);
        from.setPromptText("From date");
        to.setPromptText("To date");
        from.setPrefWidth(150);
        to.setPrefWidth(150);
        from.setOnAction(e -> {
            txFrom = from.getValue();
            renderTransactionList(transactionList);
        });
        to.setOnAction(e -> {
            txTo = to.getValue();
            renderTransactionList(transactionList);
        });

        Button clear = new Button("Reset filters");
        clear.getStyleClass().addAll("secondary", "tx-filter-reset");
        clear.setDisable(!hasTransactionFilters());
        Runnable updateReset = () -> clear.setDisable(!hasTransactionFilters());
        search.textProperty().addListener((o, a, b) -> updateReset.run());
        for (Control control : List.of(network, asset, status, from, to))
            control.addEventHandler(javafx.event.ActionEvent.ACTION, e -> updateReset.run());
        clear.setOnAction(e -> {
            txSearch = "";
            txNetworkFilter = txAssetFilter = txStatusFilter = null;
            txFrom = txTo = null;
            search.setText("");
            status.setValue("ALL");
            from.setValue(null);
            to.setValue(null);
            txFilterOptionsRefresh.run();
            renderTransactionList(transactionList);
            updateReset.run();
        });

        FlowPane searchRow = new FlowPane(10, 8, directions, search, clear);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        FlowPane fields = new FlowPane(10, 8,
                fieldCard("Network", network), fieldCard("Asset", asset), fieldCard("Status", status),
                fieldCard("From date", from), fieldCard("To date", to));
        VBox panel = panel();
        panel.getStyleClass().add("tx-filter-panel");
        panel.getChildren().addAll(searchRow, fields);
        return panel;
    }

    private boolean hasTransactionFilters() {
        return !txSearch.isBlank() || txNetworkFilter != null || txAssetFilter != null ||
                txStatusFilter != null || txFrom != null || txTo != null;
    }

    private static ListCell<String> filterCell(java.util.function.Function<String, String> text) {
        return new ListCell<>() {
            protected void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : text.apply(v));
            }
        };
    }

    private Button exportButton() {
        Button b = primary("Export CSV", this::exportTransactions);
        b.setTooltip(new Tooltip("Exports the transactions currently shown (All / Incoming / Outgoing, search and filters applied)."));
        return b;
    }

    private void renderTransactionList(VBox list) {
        if (list == null) return;
        list.getChildren().clear();
        try {
            var all = Repository.transactions();
            long inCount = all.stream().filter(t -> "IN".equals(t.direction())).count();
            // symbol lookup for ERC-20 contracts the user tracks (so cards show USDT instead of just "ERC20")
            Map<String, String> tokenSymbols = tokenSymbolsFor(all);
            if (txFilterOptionsRefresh != null) txFilterOptionsRefresh.run();
            var rows = all.stream().filter(t -> "ALL".equals(txFilter) || txFilter.equals(t.direction())).filter(t -> matchesTxFilters(t, tokenSymbols)).toList();
            boolean filtered = !txSearch.isBlank() || txNetworkFilter != null || txAssetFilter != null || txStatusFilter != null || txFrom != null || txTo != null;
            long pendingCount = rows.stream()
                    .filter(t -> "PENDING".equalsIgnoreCase(safe(t.status()))
                            || "SUBMITTED".equalsIgnoreCase(safe(t.status())))
                    .count();
            list.getChildren().add(muted(rows.size() + (filtered || !"ALL".equals(txFilter) ? " of " + all.size() + " match" : " shown") + "  ·  " + (all.size() - inCount) + " outgoing  ·  " + inCount + " incoming  ·  "
                    + pendingCount + " awaiting confirmation  ·  statuses refresh every 15 seconds"));

            if (rows.isEmpty()) {
                list.getChildren().add(muted(filtered ? "No transactions match the current search and filters. Use \"Reset filters\" to see everything." : "IN".equals(txFilter) ? "No incoming transfers found yet. Use \"Sync incoming\" to read them from the network."
                        : "OUT".equals(txFilter) ? "No outgoing transactions yet." : "No transactions yet."));
                return;
            }

            if (rows.size() > TX_RENDER_LIMIT)
                list.getChildren().add(muted("Showing the newest " + TX_RENDER_LIMIT + " of " + rows.size() + " matches — narrow the search or the dates to see the rest."));
            var visibleRows = rows.subList(0, Math.min(rows.size(), TX_RENDER_LIMIT));
            if (txTableView) {
                list.getChildren().add(rowsView.table(visibleRows, tokenSymbols));
                return;
            }

            list.getChildren().add(rowsView.cards(visibleRows, tokenSymbols));
        } catch (Exception e) {
            list.getChildren().add(muted("Could not load transactions: " + rootMessage(e)));
        }
    }

    private void pollPendingTransactions() {
        pollPendingTransactions(false);
    }

    /**
     * @param anyPage also run while another page (the Simulations summary) needs fresh statuses
     */
    void pollPendingTransactions(boolean anyPage) {
        if ((!anyPage && !app.isCurrentPage("Transactions")) || !txPollInFlight.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            boolean refresh = history.pollPending();
            Platform.runLater(() -> {
                txPollInFlight.set(false);
                if (refresh && app.isCurrentPage("Transactions")) renderTransactionList(transactionList);
                if (refresh && app.isCurrentPage("Simulations")) app.refreshSimulationSummary();
            });
        });
    }

    /**
     * Reads the transfers each wallet RECEIVED from the chain (Esplora / rippled / Solana RPC / EVM history API)
     * and stores them as direction IN. Sequential with small gaps so public APIs are not hammered.
     */
    private void syncIncoming(boolean manual) {
        if (!incomingSyncInFlight.compareAndSet(false, true)) {
            if (manual) app.notifyStatus("Incoming sync is already running…");
            return;
        }
        if (manual) app.notifyStatus("Syncing incoming transfers…");
        if (incomingSyncLabel != null) incomingSyncLabel.setText("Incoming: syncing…");
        CompletableFuture.runAsync(() -> {
            var result = history.syncIncoming();
            int changedCount = result.updated(), nets = result.checked();
            List<String> errs = result.problems();
            Platform.runLater(() -> {
                incomingSyncInFlight.set(false);
                String time = java.time.LocalTime.now().withNano(0).toString();
                if (incomingSyncLabel != null && app.isCurrentPage("Transactions"))
                    incomingSyncLabel.setText("Incoming: checked " + time + " · " + nets + " wallet/network pairs · "
                            + changedCount + " updated" + (errs.isEmpty() ? "" : " · " + errs.size() + " source(s) failed"));
                if (changedCount > 0 && app.isCurrentPage("Transactions")) renderTransactionList(transactionList);
                if (!errs.isEmpty() && manual)
                    app.error("Some incoming sources could not be read:\n" + String.join("\n", errs));
                else if (manual)
                    app.notifyStatus("Incoming sync done: " + changedCount + " updated from " + nets + " wallet/network pair(s).");
            });
        });
    }

    /**
     * The transactions the page currently shows: All/Incoming/Outgoing switch plus search and filters.
     */
    private List<Repository.Tx> visibleTransactions() throws Exception {
        var all = Repository.transactions();
        var symbols = tokenSymbolsFor(all);
        return all.stream().filter(t -> "ALL".equals(txFilter) || txFilter.equals(t.direction())).filter(t -> matchesTxFilters(t, symbols)).toList();
    }

    /**
     * Exports exactly the filtered result (not the whole history), newest first like the page.
     */
    private void exportTransactions() {
        List<Repository.Tx> rows;
        int total;
        try {
            rows = visibleTransactions();
            total = Repository.transactions().size();
        } catch (Exception e) {
            app.error(rootMessage(e));
            return;
        }
        if (rows.isEmpty()) {
            app.error("Nothing to export: no transactions match the current search and filters.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export " + rows.size() + " of " + total + " transactions");
        fc.setInitialFileName("chainforge-transactions" + (rows.size() < total ? "-filtered" : "") + ".csv");
        File f = fc.showSaveDialog(app.window());
        if (f == null) return;
        try {
            StringBuilder b = new StringBuilder("id,direction,network,hash,from,to,amount,asset,fee,status,error,created\n");
            for (var t : rows)
                b.append(csv(t.id() + "")).append(',').append(csv(t.direction())).append(',').append(csv(t.network())).append(',').append(csv(t.hash())).append(',').append(csv(t.from())).append(',').append(csv(t.to())).append(',').append(csv(t.amount())).append(',').append(csv(t.asset())).append(',').append(csv(t.fee())).append(',').append(csv(t.status())).append(',').append(csv(t.error())).append(',').append(csv(t.created())).append('\n');
            Files.writeString(f.toPath(), b.toString());
            app.alert("Export complete", rows.size() + " of " + total + " transactions exported" + (rows.size() < total ? " (current search and filters applied)" : "") + ".\n\n" + f.getAbsolutePath());
        } catch (Exception e) {
            app.error(e.getMessage());
        }
    }

    private void showIncomingSyncDetails() {
        try {
            var lines = history.syncDetails();
            app.alert("Incoming sync details", lines.isEmpty() ? "No wallet and network pairs available." : String.join("\n", lines));
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
    }

    private void lookupIncomingTransaction() {
        try {
            var networks = Repository.networks().stream().filter(n -> "EVM".equalsIgnoreCase(n.family())).toList();
            if (networks.isEmpty()) throw new IllegalStateException("Configure an EVM network first.");
            ComboBox<String> network = new ComboBox<>();
            network.getItems().setAll(networks.stream().map(Repository.Network::key).toList());
            network.setCellFactory(list -> filterCell(TransactionRowsView::networkNameOrKey));
            network.setButtonCell(filterCell(TransactionRowsView::networkNameOrKey));
            network.setValue(network.getItems().contains(txNetworkFilter) ? txNetworkFilter : network.getItems().getFirst());
            TextField hash = new TextField();
            hash.setPromptText("0x…");
            Dialog<ButtonType> dialog = new Dialog<>();
            app.styleDialog(dialog);
            dialog.setTitle("Find incoming EVM transaction");
            dialog.setHeaderText("Search by transaction hash");
            dialog.getDialogPane().setContent(new VBox(10, fieldCard("Network", network), fieldCard("Transaction hash", hash)));
            dialog.getDialogPane().setPrefWidth(560);
            ButtonType find = new ButtonType("Find transaction", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(find, ButtonType.CANCEL);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != find) return;
            var selected = networkByKey(network.getValue());
            String value = hash.getText().trim();
            app.notifyStatus("Looking up incoming transaction…");
            CompletableFuture.supplyAsync(() -> {
                try {
                    return history.lookupIncoming(selected, value);
                } catch (Exception ex) {
                    throw new java.util.concurrent.CompletionException(ex);
                }
            }).whenComplete((count, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    app.notifyStatus("Transaction lookup failed.");
                    app.error(rootMessage(failure));
                } else {
                    if (app.isCurrentPage("Transactions")) renderTransactionList(transactionList);
                    String message = count == 0 ? "Matching transfers are already in history." : count + " incoming transfer(s) imported.";
                    app.notifyStatus(message);
                    if (app.isCurrentPage("Transactions") && app.confirm("Incoming transfer found", message + "\nShow this hash in Transactions?")) {
                        txFilter = "IN";
                        txSearch = value;
                        txNetworkFilter = selected.key();
                        txAssetFilter = txStatusFilter = null;
                        txFrom = txTo = null;
                        app.showPage("Transactions");
                    }
                }
            }));
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
    }

    void onExit() {
        if (transactionPolling != null) {
            transactionPolling.stop();
            transactionPolling = null;
        }
        if (incomingSyncTimer != null) {
            incomingSyncTimer.stop();
            incomingSyncTimer = null;
        }
    }
}
