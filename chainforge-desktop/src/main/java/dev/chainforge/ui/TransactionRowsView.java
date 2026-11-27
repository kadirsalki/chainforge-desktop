package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static dev.chainforge.ui.NetworkSupport.explorerName;
import static dev.chainforge.ui.NetworkSupport.explorerTxUrl;
import static dev.chainforge.ui.NetworkSupport.networkByKey;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.ViewSupport.alignedCardRows;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.shortHash;
import static dev.chainforge.ui.ViewSupport.statusStyle;

final class TransactionRowsView {
    private final MainView app;

    TransactionRowsView(MainView app) {
        this.app = app;
    }

    GridPane cards(List<Repository.Tx> rows, Map<String, String> tokenSymbols) {
        List<Region> cards = new ArrayList<>();

        for (var t : rows) {
            VBox card = new VBox(12);
            card.getStyleClass().add("transaction-card");
            card.setPrefWidth(350);
            card.setMinWidth(260);
            card.setMaxWidth(420);

            // Header deliberately contains only status and amount. Token
            // contracts and hashes are rendered below, never beside amount.
            HBox heading = new HBox(10);
            heading.setAlignment(Pos.CENTER_LEFT);
            boolean incoming = "IN".equals(t.direction());
            Label dir = new Label(incoming ? "↓ IN" : "↑ OUT");
            dir.getStyleClass().add(incoming ? "tx-dir-in" : "tx-dir-out");
            dir.setMinWidth(Region.USE_PREF_SIZE);
            Label status = new Label(safe(t.status()).toUpperCase(Locale.ROOT));
            status.getStyleClass().add(statusStyle(t.status()));
            status.setMinWidth(Region.USE_PREF_SIZE);
            status.setMaxWidth(Region.USE_PREF_SIZE);
            status.setWrapText(false);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            String asset = safe(t.asset());
            boolean erc20 = asset.toUpperCase(Locale.ROOT).startsWith("ERC20:");
            String tokenSymbol = erc20 ? tokenSymbols.getOrDefault(t.network() + "|" + asset.substring(asset.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT), t.tokenSymbol()) : null;
            String amountAsset = erc20 ? (tokenSymbol != null ? tokenSymbol : "ERC20") : asset;
            Label amount = new Label((incoming ? "+" : "") + safe(t.amount()) + " " + amountAsset);
            amount.getStyleClass().add(incoming ? "transaction-amount-in" : "transaction-amount");
            amount.setWrapText(true);
            amount.setMaxWidth(190);
            amount.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
            heading.getChildren().addAll(dir, status, spacer, amount);

            card.getChildren().add(heading);
            card.getChildren().add(transactionField("NETWORK", safe(t.network()), false));

            if (erc20) {
                String contract = asset.substring(asset.indexOf(':') + 1).trim();
                if (tokenSymbol != null) card.getChildren().add(transactionField("ASSET", tokenSymbol, false));
                card.getChildren().add(transactionField("TOKEN CONTRACT ADDRESS", contract, true));
            } else {
                card.getChildren().add(transactionField("ASSET", asset, false));
            }

            card.getChildren().addAll(
                    transactionField("FROM", safe(t.from()), true),
                    transactionField("TO", recipientDisplay(safe(t.to())), true),
                    transactionField("TRANSACTION HASH", safe(t.hash()), true),
                    transactionField("FEE", safe(t.fee()), false),
                    transactionField("CREATED", safe(t.created()), false)
            );

            if (t.error() != null && !t.error().isBlank()) {
                Label errorDetails = muted("Details: " + t.error());
                errorDetails.setWrapText(true);
                errorDetails.setMaxWidth(Double.MAX_VALUE);
                card.getChildren().add(errorDetails);
            }

            if (t.hash() != null && !t.hash().isBlank()) {
                String explorer = explorerTxUrl(t.network(), t.hash());
                Hyperlink link = new Hyperlink("Open in " + explorerName(t.network()));
                link.setOnAction(e -> app.openExplorer(explorer));
                card.getChildren().add(link);
            }
            cards.add(card);
        }
        return alignedCardRows(cards, 350, 14);
    }

    /** Asset as the user knows it: ETH / AVAX / BTC..., a tracked token's symbol, or "ERC20 · 0x12…ab" for unknown tokens. */
    static String txAssetLabel(Repository.Tx t, Map<String, String> tokenSymbols) {
        String asset = safe(t.asset());
        if (!asset.toUpperCase(Locale.ROOT).startsWith("ERC20:")) return asset;
        String contract = asset.substring(asset.indexOf(':') + 1).trim();
        String sym = tokenSymbols.getOrDefault(t.network() + "|" + contract.toLowerCase(Locale.ROOT), t.tokenSymbol());
        return sym != null ? sym : "ERC20 · " + shortHash(contract);
    }

    static String networkNameOrKey(String key) {
        try {
            return networkByKey(key).name();
        } catch (Exception e) {
            return key;
        }
    }

    TableView<Repository.Tx> table(List<Repository.Tx> rows, Map<String, String> tokenSymbols) {
        TableView<Repository.Tx> table = new TableView<>();
        table.getStyleClass().add("transaction-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(42);
        table.setPrefHeight(34 + 42 * Math.min(rows.size(), 12));
        Map<String, String> networkNames = new HashMap<>();
        for (var row : rows) networkNames.computeIfAbsent(row.network(), TransactionRowsView::networkNameOrKey);

        table.getColumns().addAll(List.of(
                transactionBadgeColumn("Direction", 86, Repository.Tx::direction,
                        value -> "IN".equals(value) ? "↓ IN" : "↑ OUT",
                        value -> "IN".equals(value) ? "tx-dir-in" : "tx-dir-out"),
                transactionColumn("Network", 130, t -> networkNames.get(t.network())),
                transactionColumn("Amount", 120, t -> safe(t.amount()) + " " + txAssetLabel(t, tokenSymbols)),
                transactionBadgeColumn("Status", 100, t -> safe(t.status()),
                        value -> value.toUpperCase(Locale.ROOT), ViewSupport::statusStyle),
                transactionColumn("From", 105, t -> safe(t.from())),
                transactionColumn("To", 105, t -> recipientDisplay(safe(t.to()))),
                transactionColumn("Created (UTC)", 145, t -> safe(t.created()))
        ));

        TableColumn<Repository.Tx, Repository.Tx> hash = new TableColumn<>("Transaction hash");
        hash.setPrefWidth(130);
        hash.setSortable(false);
        hash.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        hash.setCellFactory(column -> new TableCell<>() {
            private final Hyperlink link = new Hyperlink();
            private final Tooltip tooltip = new Tooltip();

            @Override
            protected void updateItem(Repository.Tx tx, boolean empty) {
                super.updateItem(tx, empty);
                setGraphic(null);
                link.setOnAction(null);
                if (empty || tx == null || tx.hash() == null || tx.hash().isBlank()) return;
                String value = tx.hash();
                link.setText(shortHash(value));
                tooltip.setText(value);
                link.setTooltip(tooltip);
                link.setOnAction(e -> app.openExplorer(explorerTxUrl(tx.network(), value)));
                setGraphic(link);
            }
        });
        table.getColumns().add(hash);
        table.getItems().setAll(rows);
        return table;
    }

    private static TableColumn<Repository.Tx, String> transactionBadgeColumn(
            String title, int width, java.util.function.Function<Repository.Tx, String> value,
            java.util.function.Function<String, String> text, java.util.function.Function<String, String> style) {
        TableColumn<Repository.Tx, String> column = transactionColumn(title, width, value);
        column.setCellFactory(c -> new TableCell<>() {
            private final Label badge = new Label();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(text.apply(item));
                badge.getStyleClass().setAll("label", style.apply(item));
                setGraphic(badge);
            }
        });
        return column;
    }

    private static TableColumn<Repository.Tx, String> transactionColumn(String title, int width,
                                                                        java.util.function.Function<Repository.Tx, String> value) {
        TableColumn<Repository.Tx, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setCellFactory(c -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                tooltip.setText(item);
                setTooltip(empty || item == null || item.isBlank() ? null : tooltip);
            }
        });
        return column;
    }

    private VBox transactionField(String title, String value, boolean copyable) {
        VBox field = new VBox(5);
        field.setMaxWidth(Double.MAX_VALUE);

        Label caption = new Label(title);
        caption.getStyleClass().add("network-detail-label");

        HBox valueRow = new HBox(8);
        valueRow.setAlignment(Pos.TOP_LEFT);
        Label content = new Label(value);
        content.getStyleClass().add("transaction-detail-value");
        content.setWrapText(true);
        content.setMaxWidth(Double.MAX_VALUE);
        content.setMinWidth(0);
        content.setTooltip(new Tooltip(value));
        HBox.setHgrow(content, Priority.ALWAYS);
        valueRow.getChildren().add(content);

        if (copyable && value != null && !value.isBlank() && !"—".equals(value)) {
            Button copy = new Button("Copy");
            copy.getStyleClass().add("secondary");
            copy.setMinWidth(Region.USE_PREF_SIZE);
            copy.setOnAction(e -> app.copyToClipboard(value, title + " copied."));
            valueRow.getChildren().add(copy);
        }

        field.getChildren().addAll(caption, valueRow);
        return field;
    }
}
