package dev.chainforge.ui;

import dev.chainforge.data.Repository;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

import static dev.chainforge.ui.ViewSupport.*;

final class NetworksView {
    private final MainView app;

    NetworksView(MainView app) {
        this.app = app;
    }

    VBox build() {
        VBox v = pageShell("Network center", "Manage test environments at a glance. Compare network identity, configuration and availability before running a simulation.");
        List<Repository.Network> networks;
        try {
            networks = Repository.networks();
        } catch (Exception e) {
            networks = List.of();
        }
        long enabled = networks.stream().filter(Repository.Network::enabled).count();
        long evm = networks.stream().filter(n -> "EVM".equalsIgnoreCase(n.family())).count();
        long sol = networks.stream().filter(n -> "SOLANA".equalsIgnoreCase(n.family())).count();
        long btc = networks.stream().filter(n -> "BITCOIN".equalsIgnoreCase(n.family())).count();
        TilePane summary = new TilePane(12, 12, stat("CONFIGURED NETWORKS", String.valueOf(networks.size())),
                stat("ACTIVE NETWORKS", String.valueOf(enabled)), stat("EVM NETWORKS", String.valueOf(evm)), stat("SOLANA NETWORKS", String.valueOf(sol)), stat("BITCOIN NETWORKS", String.valueOf(btc)), stat("XRP NETWORKS", String.valueOf(networks.stream().filter(n -> "XRP".equalsIgnoreCase(n.family())).count())));
        summary.setPrefColumns(6);
        VBox configured = new VBox(14);
        HBox heading = new HBox(8);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getChildren().addAll(section("YOUR NETWORKS"), muted("  ·  " + networks.size() + " configured"));
        configured.getChildren().add(heading);
        if (networks.isEmpty())
            configured.getChildren().add(muted("No networks configured yet. Add an EVM test network below."));
        List<Region> cards = new ArrayList<>();
        for (var n : networks) {
            VBox card = new VBox(12);
            card.getStyleClass().add("network-card");
            card.setPrefWidth(330);
            card.setMinWidth(285);
            card.setMaxWidth(430);
            HBox top = new HBox(9);
            top.setAlignment(Pos.CENTER_LEFT);
            Label glyph = new Label("◎");
            glyph.getStyleClass().add("network-glyph");
            VBox identity = new VBox(3);
            Label name = new Label(n.name());
            name.getStyleClass().add("network-card-title");
            Label family = new Label(n.family() + " NETWORK" + (n.testnet() ? "" : "  ·  MAINNET (REAL FUNDS)"));
            family.getStyleClass().add("network-eyebrow");
            identity.getChildren().addAll(name, family);
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            Label state = new Label(n.enabled() ? "ACTIVE" : "DISABLED");
            state.getStyleClass().add(n.enabled() ? "network-state-active" : "network-state-disabled");
            top.getChildren().addAll(glyph, identity, push, state);
            GridPane details = new GridPane();
            details.setHgap(10);
            details.setVgap(9);
            ColumnConstraints left = new ColumnConstraints();
            left.setPercentWidth(32);
            ColumnConstraints right = new ColumnConstraints();
            right.setPercentWidth(68);
            details.getColumnConstraints().addAll(left, right);
            addNetworkDetail(details, 0, "NETWORK KEY", n.key());
            addNetworkDetail(details, 1, "CHAIN ID", safe(n.chainId()));
            Label rpcValue = new Label(safe(n.rpc()));
            rpcValue.getStyleClass().add("network-detail-value");
            rpcValue.setWrapText(true);
            rpcValue.setMaxWidth(245);
            rpcValue.setTooltip(new Tooltip(safe(n.rpc())));
            Label rpcLabel = new Label("RPC ENDPOINT");
            rpcLabel.getStyleClass().add("network-detail-label");
            details.add(rpcLabel, 0, 2);
            details.add(rpcValue, 1, 2);
            VBox.setVgrow(details, Priority.ALWAYS);
            Separator sep = new Separator();
            HBox actions = new HBox(8);
            Button toggle = new Button(n.enabled() ? "Disable network" : "Enable network");
            toggle.getStyleClass().add(n.enabled() ? "secondary" : "primary");
            toggle.setOnAction(e -> {
                try {
                    if (!n.enabled() && !n.testnet()) {
                        String typed = app.ask("⚠ Enable MAINNET", n.name() + " uses REAL funds. Transfers are irreversible and this app has not been audited.\nUse small amounts first and only with keys you control.\n\nType ENABLE MAINNET to continue:", false).orElse("");
                        if (!"ENABLE MAINNET".equals(typed.trim())) return;
                    }
                    Repository.setNetworkEnabled(n.key(), !n.enabled());
                    app.showPage("Networks");
                } catch (Exception ex) {
                    app.error(rootMessage(ex));
                }
            });
            Button copy = new Button("Copy RPC");
            copy.getStyleClass().add("secondary");
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(safe(n.rpc()));
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                app.notifyStatus("RPC endpoint copied.");
            });
            actions.getChildren().addAll(toggle, copy);
            card.getChildren().addAll(top, details, sep, actions);
            cards.add(card);
        }
        if (!cards.isEmpty()) configured.getChildren().add(alignedCardRows(cards, 330, 14));
        VBox form = panel();
        form.getChildren().addAll(section("ADD / UPDATE EVM TEST NETWORK"), muted("Connect an EVM-compatible testnet using its HTTPS RPC endpoint."));
        GridPane fields = new GridPane();
        fields.setHgap(12);
        fields.setVgap(10);
        ColumnConstraints fc1 = new ColumnConstraints();
        fc1.setPercentWidth(50);
        ColumnConstraints fc2 = new ColumnConstraints();
        fc2.setPercentWidth(50);
        fields.getColumnConstraints().addAll(fc1, fc2);
        TextField name = new TextField();
        name.setPromptText("e.g. Polygon Amoy");
        TextField key = new TextField();
        key.setPromptText("e.g. polygon-amoy");
        TextField chain = new TextField();
        chain.setPromptText("Numeric chain ID");
        TextField rpc = new TextField();
        rpc.setPromptText("https://...");
        TextField explorer = new TextField();
        explorer.setPromptText("https:// explorer base URL (optional)");
        fields.add(fieldCard("NETWORK NAME", name), 0, 0);
        fields.add(fieldCard("UNIQUE NETWORK KEY", key), 1, 0);
        fields.add(fieldCard("CHAIN ID", chain), 0, 1);
        fields.add(fieldCard("HTTPS RPC URL", rpc), 1, 1);
        TextField symbol = new TextField("ETH");
        symbol.setPromptText("e.g. ETH, AVAX, POL");
        fields.add(fieldCard("BLOCK EXPLORER URL (OPTIONAL)", explorer), 0, 2);
        fields.add(fieldCard("NATIVE COIN SYMBOL", symbol), 1, 2);
        Button save = primary("Save network", () -> {
            try {
                if (name.getText().isBlank() || key.getText().isBlank() || rpc.getText().isBlank())
                    throw new IllegalArgumentException("Name, key and RPC URL are required.");
                if (!rpc.getText().startsWith("https://"))
                    throw new IllegalArgumentException("Use an HTTPS RPC endpoint.");
                Repository.saveNetwork(name.getText().trim(), "EVM", key.getText().trim(), chain.getText().trim(), rpc.getText().trim(), explorer.getText().trim(), true, symbol.getText().trim());
                app.showPage("Networks");
                app.notifyStatus("Network saved.");
            } catch (Exception e) {
                app.error(rootMessage(e));
            }
        });
        form.getChildren().addAll(fields, save);
        VBox roadmap = panel();
        roadmap.getChildren().addAll(section("ADAPTER CAPABILITIES"),
                capabilityRow("EVM · Sepolia / Ethereum Mainnet", "Native ETH and ERC-20 balances / transfers · mainnet disabled by default", "SUPPORTED"),
                capabilityRow("Solana · Devnet / Mainnet", "Native SOL balances / transfers · SPL tokens not enabled · mainnet disabled by default", "PARTIAL"),
                capabilityRow("XRP Ledger · Testnet / Mainnet", "Native XRP balances / payments · reserve and fee checks · validated-ledger confirmation · issued currencies not enabled · mainnet disabled by default", "SUPPORTED"),
                capabilityRow("Bitcoin · Testnet / Mainnet", "Native BTC (P2WPKH) balances / transfers · UTXO selection and fee-rate estimation · Esplora API · mainnet disabled by default", "SUPPORTED"));
        v.getChildren().addAll(summary, configured, form, roadmap);
        return v;
    }

    private void addNetworkDetail(GridPane grid, int row, String title, String value) {
        Label l = new Label(title);
        l.getStyleClass().add("network-detail-label");
        Label val = new Label(value);
        val.getStyleClass().add("network-detail-value");
        val.setWrapText(true);
        grid.add(l, 0, row);
        grid.add(val, 1, row);
    }

    private HBox capabilityRow(String name, String detail, String status) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox info = new VBox(4);
        Label title = section(name);
        info.getChildren().addAll(title, muted(detail));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label tag = new Label(status);
        tag.getStyleClass().add("status-pill");
        row.getChildren().addAll(info, gap, tag);
        return row;
    }
}
