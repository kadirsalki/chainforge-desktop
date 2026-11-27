package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import static dev.chainforge.ui.NetworkSupport.assetLabel;
import static dev.chainforge.ui.NetworkSupport.explorerTxUrl;
import static dev.chainforge.ui.NetworkSupport.networkByKey;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.ViewSupport.localTime;
import static dev.chainforge.ui.ViewSupport.metricCard;
import static dev.chainforge.ui.ViewSupport.metricValue;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.rootMessage;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.section;
import static dev.chainforge.ui.ViewSupport.shortHash;
import static dev.chainforge.ui.ViewSupport.statusStyle;

final class SimulationSummaryView {
    private final MainView app;
    private final LongSupplier currentJobId;
    private final BiConsumer<Repository.SimJob, Set<Long>> reviewRetries;
    private Runnable reload;

    SimulationSummaryView(MainView app, LongSupplier currentJobId, BiConsumer<Repository.SimJob, Set<Long>> reviewRetries) {
        this.app = app;
        this.currentJobId = currentJobId;
        this.reviewRetries = reviewRetries;
    }

    void refresh() {
        if (reload != null) reload.run();
    }

    void onExit() {
        reload = null;
    }

    private record SimRow(long id, int n, String label, String address, String amount, String asset, String status, String hash,
                          String network, String error) {
    }

    private String jobLabel(Repository.SimJob j) {
        String netName = j.network();
        try {
            var net = networkByKey(j.network());
            if (net != null) netName = net.name();
        } catch (Exception ignored) {
            // fall back to the network key
        }
        return "#" + j.id() + " · " + netName + " · " + localTime(j.created()) + " · " + j.recipients() + " recipients · " + safe(j.status()).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    VBox build() {
        VBox panel = panel();
        ComboBox<Repository.SimJob> jobs = new ComboBox<>();
        jobs.setMaxWidth(Double.MAX_VALUE);
        jobs.setPromptText("No simulation yet");
        jobs.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.SimJob j, boolean empty) {
                super.updateItem(j, empty);
                setText(empty || j == null ? null : jobLabel(j));
            }
        });
        jobs.setButtonCell(new ListCell<>() {
            protected void updateItem(Repository.SimJob j, boolean empty) {
                super.updateItem(j, empty);
                setText(empty || j == null ? "No simulation yet" : jobLabel(j));
            }
        });
        Label totals = new Label("");
        totals.getStyleClass().add("transaction-amount");
        totals.setWrapText(true);
        Label recipientsV = metricValue("0"), sentV = metricValue("0"), confirmedV = metricValue("0"), pendingV = metricValue("0"), failedV = metricValue("0");
        HBox metrics = new HBox(12, metricCard("RECIPIENTS", recipientsV), metricCard("SENT", sentV), metricCard("CONFIRMED", confirmedV), metricCard("PENDING", pendingV), metricCard("FAILED", failedV));

        TableView<SimRow> table = new TableView<>();
        table.getStyleClass().add("sim-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(340);
        table.setMinHeight(200);
        table.setPlaceholder(muted("No transfers yet — start a simulation and the per-recipient breakdown appears here."));
        TableColumn<SimRow, String> cN = new TableColumn<>("#");
        cN.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().n())));
        cN.setComparator(Comparator.comparingInt(Integer::parseInt));
        cN.setMaxWidth(55);
        TableColumn<SimRow, String> cLabel = new TableColumn<>("Recipient");
        cLabel.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().label()));
        TableColumn<SimRow, String> cAddr = new TableColumn<>("Address");
        cAddr.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().address()));
        cAddr.setMinWidth(260);
        TableColumn<SimRow, String> cAmount = new TableColumn<>("Amount");
        cAmount.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().amount() + " " + assetLabel(c.getValue().asset()).split(" · ")[0]));
        cAmount.setComparator(Comparator.comparing(a -> new BigDecimal(a.substring(0, a.indexOf(' ')))));
        cAmount.setStyle("-fx-alignment: CENTER-RIGHT;");
        TableColumn<SimRow, String> cStatus = new TableColumn<>("Status");
        cStatus.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(safe(c.getValue().status()).toUpperCase(Locale.ROOT)));
        cStatus.setMaxWidth(120);
        cStatus.setCellFactory(col -> new TableCell<>() {
            protected void updateItem(String st, boolean empty) {
                super.updateItem(st, empty);
                if (empty || st == null) {
                    setGraphic(null);
                    return;
                }
                Label l = new Label(st);
                l.getStyleClass().add(statusStyle(st));
                setGraphic(l);
                SimRow row = getTableRow() == null ? null : getTableRow().getItem();
                setTooltip(row != null && row.error() != null && !row.error().isBlank() ? new Tooltip(row.error()) : null);
            }
        });
        TableColumn<SimRow, String> cHash = new TableColumn<>("Transaction hash");
        cHash.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(safe(c.getValue().hash())));
        cHash.setCellFactory(col -> new TableCell<>() {
            protected void updateItem(String h, boolean empty) {
                super.updateItem(h, empty);
                setText(empty || h == null || h.isBlank() ? (empty ? null : "—") : shortHash(h));
                setTooltip(empty || h == null || h.isBlank() ? null : new Tooltip(h));
            }
        });
        table.getColumns().addAll(List.of(cN, cLabel, cAddr, cAmount, cStatus, cHash));

        // Rebuilds the table from the stored transfers of the selected batch.
        Runnable loadRows = () -> {
            var job = jobs.getValue();
            var selectedIds = table.getSelectionModel().getSelectedItems().stream().map(SimRow::id)
                    .collect(java.util.stream.Collectors.toSet());
            table.getItems().clear();
            if (job == null) {
                totals.setText("");
                return;
            }
            try {
                Map<String, String> labels = new HashMap<>();
                for (var r : Repository.recipients(job.network()))
                    labels.put(recipientDisplay(r.address()), safe(r.label()));
                List<SimRow> rows = new ArrayList<>();
                int n = 0;
                BigDecimal sentAmt = BigDecimal.ZERO, confAmt = BigDecimal.ZERO, failAmt = BigDecimal.ZERO;
                int conf = 0, pend = 0, fail = 0;
                String asset = "";
                for (var t : Repository.txsForJob(job.id())) {
                    rows.add(new SimRow(t.id(), ++n, labels.getOrDefault(t.to(), ""), recipientDisplay(safe(t.to())), safe(t.amount()), safe(t.asset()), t.status(), t.hash(), t.network(), t.error()));
                    asset = safe(t.asset());
                    BigDecimal a;
                    try {
                        a = new BigDecimal(t.amount());
                    } catch (Exception e) {
                        a = BigDecimal.ZERO;
                    }
                    switch (safe(t.status()).toUpperCase(Locale.ROOT)) {
                        case "CONFIRMED", "SUCCESS" -> {
                            conf++;
                            confAmt = confAmt.add(a);
                            sentAmt = sentAmt.add(a);
                        }
                        case "FAILED" -> {
                            fail++;
                            failAmt = failAmt.add(a);
                        }
                        case "PENDING", "SUBMITTED" -> {
                            pend++;
                            sentAmt = sentAmt.add(a);
                        }
                        default -> {
                        }
                    }
                }
                table.getItems().setAll(rows);
                for (int index = 0; index < rows.size(); index++)
                    if (selectedIds.contains(rows.get(index).id())) table.getSelectionModel().select(index);
                recipientsV.setText(rows.size() + " / " + job.recipients());
                sentV.setText(String.valueOf(conf + pend));
                confirmedV.setText(String.valueOf(conf));
                pendingV.setText(String.valueOf(pend));
                failedV.setText(String.valueOf(fail));
                String sym = asset.isEmpty() ? "" : " " + assetLabel(asset).split(" · ")[0];
                totals.setText(rows.isEmpty() ? "" : "Total sent: " + sentAmt.stripTrailingZeros().toPlainString() + sym
                        + "   (confirmed " + confAmt.stripTrailingZeros().toPlainString() + " · pending " + sentAmt.subtract(confAmt).stripTrailingZeros().toPlainString()
                        + (fail > 0 ? " · not sent, failed " + failAmt.stripTrailingZeros().toPlainString() : "") + ")");
            } catch (Exception ex) {
                totals.setText("Could not load summary: " + rootMessage(ex));
            }
        };

        boolean[] updating = {false};
        long[] followed = {-1};
        reload = () -> {
            try {
                var all = Repository.simulationJobs();
                long keep = jobs.getValue() == null ? -1 : jobs.getValue().id();
                long activeJob = currentJobId.getAsLong();
                if (activeJob > 0 && activeJob != followed[0]) {
                    followed[0] = activeJob;
                    keep = activeJob;
                }
                updating[0] = true;
                jobs.getItems().setAll(all);
                final long sel = keep;
                var pick = all.stream().filter(j -> j.id() == sel).findFirst().or(() -> all.stream().findFirst());
                if (pick.isPresent()) jobs.setValue(pick.get());
                updating[0] = false;
                loadRows.run();
            } catch (Exception ex) {
                updating[0] = false;
                totals.setText("Could not load summary: " + rootMessage(ex));
            }
        };
        jobs.setOnAction(e -> {
            if (!updating[0]) loadRows.run();
        });

        java.util.function.Function<Boolean, String> render = csv -> {
            StringBuilder b = new StringBuilder();
            String sep = csv ? "," : "\t";
            java.util.function.Function<String, String> q = csv ? ViewSupport::csv : x -> x == null ? "" : x.replace('\t', ' ').replace('\n', ' ');
            b.append(String.join(sep, "#", "recipient", "address", "amount", "asset", "status", "tx_hash", "error")).append('\n');
            for (var r : table.getItems())
                b.append(String.join(sep, q.apply(String.valueOf(r.n())), q.apply(r.label()), q.apply(r.address()), q.apply(r.amount()), q.apply(assetLabel(r.asset()).split(" · ")[0]), q.apply(r.status()), q.apply(r.hash()), q.apply(r.error()))).append('\n');
            return b.toString();
        };
        Button refresh = new Button("↻ Refresh status");
        refresh.getStyleClass().add("secondary");
        refresh.setOnAction(e -> {
            app.pollPendingTransactions();
            reload.run();
            app.notifyStatus("Refreshing transfer statuses…");
        });
        Button copyTable = new Button("Copy table");
        copyTable.getStyleClass().add("secondary");
        copyTable.setOnAction(e -> {
            if (table.getItems().isEmpty()) {
                app.error("There is nothing to copy yet.");
                return;
            }
            app.copyToClipboard(render.apply(false) + "\n" + totals.getText(), "Summary copied — paste it into Excel or a document.");
        });
        Button export = new Button("Export CSV…");
        export.getStyleClass().add("secondary");
        export.setOnAction(e -> {
            if (table.getItems().isEmpty() || jobs.getValue() == null) {
                app.error("There is nothing to export yet.");
                return;
            }
            FileChooser fc = new FileChooser();
            fc.setTitle("Export simulation summary");
            fc.setInitialFileName("simulation-" + jobs.getValue().id() + "-summary.csv");
            File f = fc.showSaveDialog(app.window());
            if (f == null) return;
            try {
                Files.writeString(f.toPath(), render.apply(true));
                app.alert("Export complete", f.getAbsolutePath());
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        });
        Button open = new Button("Open in explorer");
        open.getStyleClass().add("secondary");
        open.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row == null || row.hash() == null || row.hash().isBlank()) {
                app.error("Select a row that has a transaction hash.");
                return;
            }
            app.openExplorer(explorerTxUrl(row.network(), row.hash()));
        });
        ContextMenu menu = new ContextMenu();
        MenuItem copyAddr = new MenuItem("Copy address");
        copyAddr.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row != null) app.copyToClipboard(row.address(), "Address copied.");
        });
        MenuItem copyHash = new MenuItem("Copy transaction hash");
        copyHash.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row != null && row.hash() != null) app.copyToClipboard(row.hash(), "Transaction hash copied.");
        });
        menu.getItems().addAll(copyAddr, copyHash);
        table.setContextMenu(menu);

        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Button retry = new Button("Review selected for retry");
        retry.getStyleClass().add("secondary");
        retry.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        retry.setOnAction(e -> reviewRetries.accept(jobs.getValue(), table.getSelectionModel().getSelectedItems().stream()
                .map(SimRow::id).collect(java.util.stream.Collectors.toSet())));
        FlowPane actions = new FlowPane(10, 8, refresh, copyTable, export, open, retry);
        panel.getChildren().addAll(section("SIMULATION SUMMARY"),
                muted("Select planned transfers or confirmed failures to review a retry. Pending and unknown submissions require investigation."),
                jobs, metrics, totals, table, actions);
        reload.run();
        return panel;
    }
}
