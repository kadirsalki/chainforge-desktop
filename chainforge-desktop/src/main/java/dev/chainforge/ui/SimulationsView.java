package dev.chainforge.ui;

import dev.chainforge.bitcoin.BitcoinAdapter;
import dev.chainforge.core.ChainAdapter;
import dev.chainforge.core.SimulationLock;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.security.WalletVault;
import dev.chainforge.ui.SimulationPreflight.SimulationPlan;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static dev.chainforge.ui.NetworkSupport.assetLabel;
import static dev.chainforge.ui.NetworkSupport.nativeAssetFor;
import static dev.chainforge.ui.NetworkSupport.networkByKey;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.NetworkSupport.shortRecipient;
import static dev.chainforge.ui.NetworkSupport.walletMatchesNetwork;
import static dev.chainforge.ui.SimulationPreflight.simulationDestination;
import static dev.chainforge.ui.ViewSupport.fieldCard;
import static dev.chainforge.ui.ViewSupport.metricCard;
import static dev.chainforge.ui.ViewSupport.metricValue;
import static dev.chainforge.ui.ViewSupport.muted;
import static dev.chainforge.ui.ViewSupport.pageShell;
import static dev.chainforge.ui.ViewSupport.panel;
import static dev.chainforge.ui.ViewSupport.primary;
import static dev.chainforge.ui.ViewSupport.rootMessage;
import static dev.chainforge.ui.ViewSupport.safe;
import static dev.chainforge.ui.ViewSupport.section;
import static dev.chainforge.ui.ViewSupport.shortHash;

final class SimulationsView {
    private final MainView app;
    private final SimulationPreflight preflight;

    SimulationsView(MainView app) {
        this.app = app;
        this.summary = new SimulationSummaryView(app, () -> sim.jobId, this::reviewRetries);
        this.preflight = new SimulationPreflight(app::adapterFor);
    }

    private volatile boolean stopRequested = false;

    /**
     * Live state of the current / last simulation. It outlives the Simulations page: the page is rebuilt every
     * time it is opened, so the worker updates this state and the widgets of whichever page instance is on screen.
     */
    private static final class SimState {
        int total, done, confirmed, pending, failed;
        boolean running;
        String progress = "Ready — no simulation running";
        long jobId = -1; // simulation_jobs id of the current / last batch
        boolean finishedUnseen; // a batch finished while the user was on another page
        final java.util.ArrayList<String> lines = new java.util.ArrayList<>(); // newest first

        void reset(int total) {
            this.total = total;
            done = confirmed = pending = failed = 0;
            lines.clear();
        }

        void addLine(String line) {
            lines.addFirst(line);
            if (lines.size() > 500) lines.removeLast();
        }
    }

    private final SimState sim = new SimState();
    private Label simProgress, simSent, simOk, simPend, simBad;
    private ProgressBar simBar;
    private VBox simActivity;
    private Button simStart, simStop;
    private final SimulationSummaryView summary;
    private VBox simSideBox;
    private Label simSideTitle, simSideDetail;
    private ProgressBar simSideBar;
    private Timeline simStatusTimer;

    private final SimulationLock simulationLock = new SimulationLock();

    private enum DelayUnit {
        MILLISECONDS("Milliseconds (ms)", 1),
        SECONDS("Seconds (s)", 1000);

        private final String label;
        private final int milliseconds;

        DelayUnit(String label, int milliseconds) {
            this.label = label;
            this.milliseconds = milliseconds;
        }

        long parseMillis(String value) {
            try {
                return Math.multiplyExact(Long.parseLong(value.trim()), milliseconds);
            } catch (NumberFormatException | ArithmeticException e) {
                throw new IllegalArgumentException("Enter a whole number within range for each delay.");
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Sidebar badge: visible while a simulation runs (and after it finished until the Simulations page is opened).
     */
    private void updateSimSidebar() {
        if (simSideBox == null) return;
        boolean show = sim.running || sim.finishedUnseen;
        simSideBox.setVisible(show);
        simSideBox.setManaged(show);
        if (!show) return;
        int pct = sim.total == 0 ? 0 : (int) Math.round(100.0 * sim.done / sim.total);
        String failed = sim.failed > 0 ? " · " + sim.failed + " failed" : "";
        if (sim.running) {
            simSideTitle.setText("●  SIMULATION RUNNING");
            simSideTitle.getStyleClass().setAll("label", "sim-side-title", "text-success");
            simSideDetail.setText(sim.done + " / " + sim.total + " sent · " + pct + "%" + failed);
            simSideBar.setProgress(sim.total == 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) sim.done / sim.total);
        } else {
            simSideTitle.setText(sim.failed > 0 ? "!  SIMULATION FINISHED" : "✓  SIMULATION FINISHED");
            simSideTitle.getStyleClass().setAll("label", "sim-side-title", sim.failed > 0 ? "text-warning" : "text-success");
            simSideDetail.setText(sim.done + " / " + sim.total + " sent" + failed);
            simSideBar.setProgress(1);
        }
    }

    /**
     * Pushes {@link #sim} into the widgets currently shown (no-op until the Simulations page has been built). Call on the FX thread.
     */
    private void applySimState() {
        updateSimSidebar();
        if (simProgress == null) return;
        simSent.setText(sim.done + " / " + sim.total);
        simOk.setText(String.valueOf(sim.confirmed));
        simPend.setText(String.valueOf(sim.pending));
        simBad.setText(String.valueOf(sim.failed));
        simBar.setProgress(sim.total == 0 ? 0 : (double) sim.done / sim.total);
        simProgress.setText(sim.progress);
        simActivity.getChildren().clear();
        if (sim.lines.isEmpty()) simActivity.getChildren().add(muted("Every transfer result will be listed here."));
        else for (String l : sim.lines) simActivity.getChildren().add(muted(l));
        simStart.setDisable(sim.running || simulationLock.isRunning());
        simStop.setDisable(!sim.running);
        summary.refresh();
    }

    VBox build() {
        VBox v = pageShell("Simulations", "Prepare a batch, review amounts and network fees, then run it.");
        ComboBox<Repository.Network> network = new ComboBox<>();
        try {
            network.getItems().addAll(Repository.networks().stream().filter(Repository.Network::enabled).toList());
        } catch (Exception ignored) {
        }
        network.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.Network n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty || n == null ? null : n.name());
            }
        });
        network.setButtonCell(new ListCell<>() {
            protected void updateItem(Repository.Network n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty || n == null ? "Select network" : n.name());
            }
        });
        if (!network.getItems().isEmpty()) network.setValue(network.getItems().getFirst());
        ComboBox<Repository.Wallet> wallet = new ComboBox<>();
        wallet.setMaxWidth(Double.MAX_VALUE);
        wallet.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.Wallet w, boolean empty) {
                super.updateItem(w, empty);
                setText(empty || w == null ? null : w.name() + " · " + shortHash(w.address()));
            }
        });
        wallet.setButtonCell(new ListCell<>() {
            protected void updateItem(Repository.Wallet w, boolean empty) {
                super.updateItem(w, empty);
                setText(empty || w == null ? "Select sender wallet" : w.name() + " · " + shortHash(w.address()));
            }
        });
        ComboBox<Repository.RecipientGroup> group = new ComboBox<>();
        group.setMaxWidth(Double.MAX_VALUE);
        ListView<Repository.Recipient> recips = new ListView<>();
        recips.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        recips.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.Recipient r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : safe(r.label()) + " · " + recipientDisplay(r.address()));
            }
        });
        TextField min = new TextField("0.001"), max = new TextField("0.003");
        TextField minDelay = new TextField("5"), maxDelay = new TextField("20");
        ComboBox<DelayUnit> minDelayUnit = delayUnitSelector();
        ComboBox<DelayUnit> maxDelayUnit = delayUnitSelector();
        ComboBox<String> asset = new ComboBox<>();
        asset.setMaxWidth(Double.MAX_VALUE);
        asset.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(String a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : assetLabel(a));
            }
        });
        asset.setButtonCell(new ListCell<>() {
            protected void updateItem(String a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? "Select asset" : assetLabel(a));
            }
        });
        Label tokenMeta = muted("Choose a tracked token to view its verified contract details.");
        tokenMeta.setVisible(false);
        tokenMeta.setManaged(false);
        asset.setOnAction(e -> {
            String selected = asset.getValue();
            boolean token = EvmAdapter.isToken(selected);
            tokenMeta.setVisible(token);
            tokenMeta.setManaged(token);
            if (token) {
                try {
                    String contract = EvmAdapter.tokenContract(selected);
                    var meta = Repository.tokens(network.getValue().key()).stream().filter(t -> t.contract().equalsIgnoreCase(contract)).findFirst().orElseThrow();
                    tokenMeta.setText(meta.name() + " · " + meta.symbol() + " · " + meta.decimals() + " decimals\n" + meta.contract());
                } catch (Exception ex) {
                    tokenMeta.setText("Saved token metadata unavailable: " + rootMessage(ex));
                }
            }
        });
        Runnable refreshNetwork = () -> {
            try {
                Repository.Network n = network.getValue();
                if (n == null) return;
                String family = n.family().toUpperCase(Locale.ROOT);
                wallet.getItems().setAll(Repository.wallets().stream().filter(w -> walletMatchesNetwork(w, n)).toList());
                if (!wallet.getItems().isEmpty()) wallet.setValue(wallet.getItems().getFirst());
                group.getItems().setAll(Repository.recipientGroups(n.key()));
                if (!group.getItems().isEmpty()) group.setValue(group.getItems().getFirst());
                asset.getItems().clear();
                switch (family) {
                    case "SOLANA" -> asset.getItems().add("SOL");
                    case "BITCOIN" -> asset.getItems().add("BTC");
                    case "XRP" -> asset.getItems().add("XRP");
                    case "EVM" -> {
                        asset.getItems().add(nativeAssetFor(n));
                        for (var t : Repository.tokens(n.key())) asset.getItems().add("ERC20:" + t.contract());
                    }
                }
                if (!asset.getItems().isEmpty()) asset.setValue(asset.getItems().getFirst());
                tokenMeta.setVisible(false);
                tokenMeta.setManaged(false);
                refreshRecipients(group, recips);
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        };
        network.setOnAction(e -> refreshNetwork.run());
        group.setOnAction(e -> refreshRecipients(group, recips));
        refreshNetwork.run();
        ComboBox<Repository.SimulationTemplate> templates = new ComboBox<>();
        templates.setPromptText("Select a template");
        templates.setPrefWidth(200);
        try {
            templates.getItems().setAll(Repository.simulationTemplates());
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
        Button saveTemplate = new Button("Save template");
        Button loadTemplate = new Button("Load");
        Button deleteTemplate = new Button("Delete template");
        for (Button button : List.of(saveTemplate, loadTemplate, deleteTemplate)) button.getStyleClass().add("secondary");
        loadTemplate.disableProperty().bind(templates.valueProperty().isNull());
        deleteTemplate.disableProperty().bind(templates.valueProperty().isNull());
        saveTemplate.setOnAction(e -> {
            try {
                if (network.getValue() == null || group.getValue() == null || asset.getValue() == null)
                    throw new IllegalArgumentException("Choose a network, asset and recipient group first.");
                String name = app.ask("Save template", "Template name:", false).orElse("").trim();
                if (name.isEmpty()) return;
                var low = new BigDecimal(min.getText().trim());
                var high = new BigDecimal(max.getText().trim());
                long delayLow = minDelayUnit.getValue().parseMillis(minDelay.getText());
                long delayHigh = maxDelayUnit.getValue().parseMillis(maxDelay.getText());
                if (low.signum() <= 0 || high.compareTo(low) < 0 || delayLow < 0 || delayHigh < delayLow)
                    throw new IllegalArgumentException("Check the amount and delay ranges before saving.");
                var template = new Repository.SimulationTemplate(name, network.getValue().key(), asset.getValue(),
                        group.getValue().id(), min.getText(), max.getText(), minDelay.getText(), minDelayUnit.getValue().name(),
                        maxDelay.getText(), maxDelayUnit.getValue().name());
                Repository.saveSimulationTemplate(template);
                templates.getItems().setAll(Repository.simulationTemplates());
                templates.setValue(template);
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        });
        loadTemplate.setOnAction(e -> {
            var template = templates.getValue();
            try {
                var savedNetwork = network.getItems().stream().filter(n -> n.key().equals(template.network())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Enable the template's network first."));
                var savedGroup = Repository.recipientGroups(savedNetwork.key()).stream()
                        .filter(g -> g.id() == template.groupId()).findFirst()
                        .orElseThrow(() -> new IllegalStateException("The template's recipient group no longer exists."));
                if (!nativeAssetFor(savedNetwork).equalsIgnoreCase(template.asset())
                        && Repository.tokens(savedNetwork.key()).stream().noneMatch(t -> ("ERC20:" + t.contract()).equalsIgnoreCase(template.asset())))
                    throw new IllegalStateException("Add the template's tracked token first.");
                if (!savedNetwork.equals(network.getValue())) network.setValue(savedNetwork);
                group.setValue(savedGroup);
                asset.setValue(asset.getItems().stream().filter(a -> a.equalsIgnoreCase(template.asset())).findFirst().orElseThrow());
                min.setText(template.minAmount());
                max.setText(template.maxAmount());
                minDelay.setText(template.minDelay());
                maxDelay.setText(template.maxDelay());
                minDelayUnit.setValue(DelayUnit.valueOf(template.minUnit()));
                maxDelayUnit.setValue(DelayUnit.valueOf(template.maxUnit()));
                recips.getSelectionModel().clearSelection();
                app.notifyStatus("Template loaded. Select recipients before review.");
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        });
        deleteTemplate.setOnAction(e -> {
            var template = templates.getValue();
            try {
                if (!app.confirm("Delete template?", "Remove the saved template “" + template.name() + "”?")) return;
                Repository.deleteSimulationTemplate(template.name());
                templates.getItems().setAll(Repository.simulationTemplates());
                templates.setValue(null);
            } catch (Exception ex) {
                app.error(rootMessage(ex));
            }
        });
        VBox config = panel();
        FlowPane templateActions = new FlowPane(8, 8, templates, loadTemplate, saveTemplate, deleteTemplate);
        config.getChildren().addAll(section("1 · PREPARE SIMULATION"), templateActions);
        GridPane configGrid = new GridPane();
        configGrid.setHgap(12);
        configGrid.setVgap(10);
        ColumnConstraints cfgCol1 = new ColumnConstraints();
        cfgCol1.setPercentWidth(50);
        ColumnConstraints cfgCol2 = new ColumnConstraints();
        cfgCol2.setPercentWidth(50);
        configGrid.getColumnConstraints().addAll(cfgCol1, cfgCol2);
        configGrid.add(fieldCard("Network", network), 0, 0);
        configGrid.add(fieldCard("Sender wallet", wallet), 1, 0);
        configGrid.add(fieldCard("Recipient group", group), 0, 1);
        configGrid.add(fieldCard("Asset", asset), 1, 1);
        configGrid.add(fieldCard("Minimum amount", min), 0, 2);
        configGrid.add(fieldCard("Maximum amount", max), 1, 2);
        configGrid.add(fieldCard("Minimum delay", minDelayUnit, minDelay), 0, 3);
        configGrid.add(fieldCard("Maximum delay", maxDelayUnit, maxDelay), 1, 3);
        configGrid.add(tokenMeta, 0, 4, 2, 1);
        config.getChildren().add(configGrid);
        VBox targets = panel();
        targets.getChildren().addAll(section("RECIPIENT BATCH"), muted("Select recipients from the chosen network and group."), recips);
        recips.setPrefHeight(265);
        recips.setMinHeight(220);
        recips.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(recips, Priority.ALWAYS);
        HBox setupCards = new HBox(14, config, targets);
        HBox.setHgrow(config, Priority.ALWAYS);
        HBox.setHgrow(targets, Priority.ALWAYS);
        config.setMaxWidth(Double.MAX_VALUE);
        targets.setMaxWidth(Double.MAX_VALUE);
        VBox monitor = panel();
        HBox cards = new HBox(12);
        Label sent = metricValue("0 / 0"), ok = metricValue("0"), pend = metricValue("0"), bad = metricValue("0");
        cards.getChildren().addAll(metricCard("SENT", sent), metricCard("CONFIRMED", ok), metricCard("PENDING", pend), metricCard("FAILED", bad));
        Label progress = muted("");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("monitor-progress");
        VBox activity = new VBox(7);
        monitor.getChildren().addAll(section("3 · EXECUTION"), cards, progress, bar, activity);
        Button start = primary("Review simulation", () -> reviewSimulation(
                wallet.getValue(), network.getValue(), group.getValue(), min.getText(), max.getText(),
                minDelay.getText(), minDelayUnit.getValue(), maxDelay.getText(), maxDelayUnit.getValue(),
                asset.getValue(), new ArrayList<>(recips.getSelectionModel().getSelectedItems())));
        Button stop = new Button("■ Stop after current transfer");
        stop.getStyleClass().add("secondary");
        stop.setOnAction(e -> {
            stopRequested = true;
            sim.progress = "Stop requested — current transfer will finish.";
            applySimState();
        });
        // Bind this page instance to the running (or finished) simulation so progress survives page switches.
        summary.onExit();
        if (simStatusTimer != null) simStatusTimer.stop();
        simProgress = progress;
        simSent = sent;
        simOk = ok;
        simPend = pend;
        simBad = bad;
        simBar = bar;
        simActivity = activity;
        simStart = start;
        simStop = stop;
        applySimState();
        HBox controls = new HBox(10, start, stop);
        VBox summaryPanel = summary.build();
        // Statuses (e.g. Bitcoin PENDING -> CONFIRMED) only change on-chain; re-check them while this page is open.
        simStatusTimer = new Timeline(new KeyFrame(Duration.seconds(20), e -> app.pollPendingTransactions()));
        simStatusTimer.setCycleCount(Timeline.INDEFINITE);
        simStatusTimer.play();
        v.getChildren().addAll(setupCards, controls, monitor, summaryPanel, muted("EVM: native ETH + tracked ERC-20 tokens. Solana: native SOL. XRP: native XRP (a new recipient account needs at least the 1 XRP base reserve, otherwise the payment fails and the fee is still charged). Bitcoin: native BTC (back-to-back transfers chain unconfirmed change; keep batches small). Only contracts verified and saved in Wallets appear in this asset selector. Mainnet networks spend real funds and are disabled until you enable them in Networks."));
        return v;
    }

    private void reviewRetries(Repository.SimJob job, Set<Long> ids) {
        try {
            if (job == null || ids.isEmpty()) return;
            var eligible = Repository.retryableTransfers(job.id()).stream().filter(t -> ids.contains(t.id())).toList();
            if (eligible.size() != ids.size())
                throw new IllegalArgumentException("Choose only unsent planned rows or failed rows with an on-chain hash that have not already been retried.");
            var first = eligible.getFirst();
            if (eligible.stream().anyMatch(t -> !Objects.equals(t.asset(), first.asset()) || !Objects.equals(t.from(), first.from())))
                throw new IllegalArgumentException("Retry rows must share an asset and sender.");
            var network = networkByKey(job.network());
            var wallet = Repository.wallets().stream().filter(w -> walletMatchesNetwork(w, network))
                    .filter(w -> "EVM".equalsIgnoreCase(w.family()) ? w.address().equalsIgnoreCase(first.from()) : w.address().equals(first.from()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("The original sender wallet is unavailable."));
            long delayLow = 5000, delayHigh = 20000;
            for (String option : safe(job.config()).split(";")) {
                if (!option.startsWith("delayMs=")) continue;
                String[] range = option.substring(8).split(java.util.regex.Pattern.quote(".."));
                if (range.length == 2) {
                    delayLow = Long.parseLong(range[0]);
                    delayHigh = Long.parseLong(range[1]);
                }
            }
            prepareSimulation(wallet, network, first.asset(), delayLow, delayHigh, List.of(), null, null, eligible);
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void refreshRecipients(ComboBox<Repository.RecipientGroup> group, ListView<Repository.Recipient> recips) {
        try {
            var g = group.getValue();
            recips.getItems().setAll(g == null ? List.of() : Repository.recipients(g.network(), g.id()));
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
    }

    private ComboBox<DelayUnit> delayUnitSelector() {
        ComboBox<DelayUnit> selector = new ComboBox<>();
        selector.getItems().setAll(DelayUnit.values());
        selector.setValue(DelayUnit.SECONDS);
        return selector;
    }

    private void reviewSimulation(Repository.Wallet wallet, Repository.Network network, Repository.RecipientGroup group,
                                  String min, String max, String minDelay, DelayUnit minUnit,
                                  String maxDelay, DelayUnit maxUnit, String asset, List<Repository.Recipient> recipients) {
        try {
            if (wallet == null || network == null || group == null || asset == null)
                throw new IllegalArgumentException("Select a network, sender wallet, recipient group and asset.");
            if (!network.key().equals(group.network()) || recipients.isEmpty())
                throw new IllegalArgumentException("Select one or more recipients from this network and group.");
            for (var recipient : recipients)
                if (!network.key().equals(recipient.network()) || group.id() != recipient.groupId())
                    throw new IllegalArgumentException("One or more recipients belong to a different network or group.");
            BigDecimal low = new BigDecimal(min.trim()), high = new BigDecimal(max.trim());
            long delayLow = minUnit.parseMillis(minDelay), delayHigh = maxUnit.parseMillis(maxDelay);
            if (low.signum() <= 0 || high.compareTo(low) < 0 || delayLow < 0 || delayHigh < delayLow)
                throw new IllegalArgumentException("Check the amount and delay ranges.");
            prepareSimulation(wallet, network, asset, delayLow, delayHigh, recipients, low, high, List.of());
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void prepareSimulation(Repository.Wallet wallet, Repository.Network network, String asset,
                                   long minDelay, long maxDelay, List<Repository.Recipient> recipients,
                                   BigDecimal low, BigDecimal high, List<Repository.Tx> retryRows) {
        if (!simulationLock.tryAcquire()) {
            app.error("A simulation is already being reviewed or executed.");
            return;
        }
        if (simStart != null) simStart.setDisable(true);
        var pageAtRequest = app.contentView();
        app.notifyStatus("Checking balances, recipients and fees…");
        CompletableFuture.supplyAsync(() -> {
            try {
                return preflight.prepare(wallet, network, asset, minDelay, maxDelay, recipients, low, high, retryRows);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((plan, failure) -> Platform.runLater(() -> {
            boolean started = false;
            try {
                if (failure != null) throw new IllegalStateException(rootMessage(failure));
                if (app.contentView() != pageAtRequest) {
                    app.notifyStatus("Simulation review canceled.");
                    return;
                }
                started = approveSimulation(plan);
                if (!started) app.notifyStatus("Simulation review canceled.");
            } catch (Exception e) {
                app.notifyStatus("Simulation review stopped.");
                app.error(rootMessage(e));
            } finally {
                if (!started) {
                    simulationLock.release();
                    if (simStart != null) simStart.setDisable(false);
                }
            }
        }));
    }

    private boolean approveSimulation(SimulationPlan plan) throws Exception {
        var network = plan.network();
        var wallet = plan.wallet();
        String nativeAsset = nativeAssetFor(network);
        Dialog<Boolean> review = new Dialog<>();
        app.styleDialog(review);
        review.setTitle("2 · Review simulation");
        review.setHeaderText(network.name() + (network.testnet() ? " · Testnet" : " · Mainnet / real funds"));
        CheckBox pilot = new CheckBox("Send to the first recipient and wait for confirmation before continuing");
        pilot.setDisable(plan.transfers().size() < 2);
        var rows = new ListView<String>();
        for (var transfer : plan.transfers())
            rows.getItems().add(recipientDisplay(transfer.address()) + "   ·   " + formatAmount(transfer.amount()));
        rows.setPrefHeight(220);
        String fee = plan.fee() == null ? "Fee estimate unavailable. Leave a balance for network fees."
                : "Estimated network fees: " + formatAmount(plan.fee()) + " " + nativeAsset;
        Label details = muted(wallet.name() + " · " + wallet.address() + "\nAsset: " + assetLabel(plan.asset())
                + (EvmAdapter.isToken(plan.asset()) ? "\nContract: " + EvmAdapter.tokenContract(plan.asset()) : "")
                + "\n" + plan.transfers().size() + " recipients · Total " + formatAmount(plan.total())
                + "\nAsset balance: " + formatAmount(plan.assetBalance())
                + " · Fee balance: " + formatAmount(plan.nativeBalance()) + " " + nativeAsset
                + "\n" + fee + "\nDelay: " + plan.minDelay() + "–" + plan.maxDelay() + " ms");
        details.setWrapText(true);
        String warning = "Network fees can change before submission.";
        if (plan.adapter() instanceof BitcoinAdapter && plan.transfers().size() > 25)
            warning += " Unconfirmed change may limit how many Bitcoin transfers can be sent before a block confirms.";
        Label warningLabel = new Label(warning);
        warningLabel.getStyleClass().add("text-warning");
        warningLabel.setWrapText(true);
        review.getDialogPane().setContent(new VBox(12, details, warningLabel, rows, pilot));
        review.getDialogPane().setPrefWidth(650);
        ButtonType start = new ButtonType("Start simulation", ButtonBar.ButtonData.OK_DONE);
        review.getDialogPane().getButtonTypes().setAll(start, ButtonType.CANCEL);
        review.setResultConverter(selected -> selected == start ? pilot.isSelected() : null);
        Optional<Boolean> approved = review.showAndWait();
        if (approved.isEmpty()) return false;
        if (!network.testnet() && !"MAINNET".equals(app.ask("Mainnet · real funds",
                "Type MAINNET to authorize these transfers of real funds:", false).orElse("").trim())) return false;
        if (!networkByKey(network.key()).equals(network)
                || Repository.wallets().stream().noneMatch(candidate -> candidate.id() == wallet.id()))
            throw new IllegalStateException("The sender or network changed. Review again.");

        String key = app.unlockedKey(wallet.id());
        if (key == null) {
            String pass = app.ask("Unlock sender wallet", "Enter vault password for " + wallet.name() + ":", true).orElse("");
            if (pass.isEmpty()) return false;
            byte[] raw = WalletVault.open(pass.toCharArray(), wallet.encrypted(), wallet.salt());
            try {
                key = new String(raw, StandardCharsets.UTF_8);
                app.rememberKey(wallet.id(), key);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
        stopRequested = false;
        sim.finishedUnseen = false;
        sim.reset(plan.transfers().size());
        sim.running = true;
        sim.progress = "Starting on " + network.name();
        applySimState();
        startSimulationWorker(plan, key, approved.get());
        return true;
    }

    private static String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private void startSimulationWorker(SimulationPlan plan, String key, boolean pilot) {
        Thread worker = new Thread(() -> {
            int confirmed = 0, failed = 0, pending = 0, processed = 0;
            long jobId = -1;
            String stopped = null;
            try {
                jobId = Repository.createSimulationJob(plan.network().key(), plan.wallet().address(), plan.asset(),
                        plan.transfers(), "delayMs=" + plan.minDelay() + ".." + plan.maxDelay() + ";pilot=" + pilot);
                var rows = Repository.txsForJob(jobId);
                if (rows.size() != plan.transfers().size())
                    throw new IllegalStateException("Saved plan is incomplete.");
                long job = jobId;
                Platform.runLater(() -> {
                    sim.jobId = job;
                    applySimState();
                });
                Random random = new SecureRandom();
                for (var row : rows) {
                    if (stopRequested) break;
                    if (!networkByKey(plan.network().key()).equals(plan.network()))
                        throw new IllegalStateException("The network changed. Review the remaining transfers before sending.");
                    if (!Repository.claimPlannedTransfer(row.id()))
                        throw new IllegalStateException("A planned transfer has already been claimed or retried.");
                    int waiting = pending + 1;
                    Platform.runLater(() -> {
                        sim.pending = waiting;
                        sim.progress = "Submitting " + row.amount() + " to " + shortRecipient(row.to());
                        applySimState();
                    });
                    ChainAdapter.TransferResult result;
                    try {
                        result = plan.adapter().transfer(key, simulationDestination(plan.network(), row.to()),
                                new BigDecimal(row.amount()), plan.asset()).join();
                    } catch (Exception e) {
                        Repository.updateTx(row.id(), null, null, "UNKNOWN", rootMessage(e));
                        pending++;
                        processed++;
                        stopped = "Submission outcome unknown. Check the wallet history before retrying.";
                        break;
                    }
                    Repository.updateTx(row.id(), result.hash(), result.fee(), result.status(), result.error());
                    processed++;
                    String status = result.status().toUpperCase(Locale.ROOT);
                    if (pilot && processed == 1) {
                        status = awaitPilotConfirmation(plan.adapter(), row.id(), result);
                    }
                    switch (status) {
                        case "CONFIRMED", "SUCCESS" -> confirmed++;
                        case "FAILED" -> failed++;
                        default -> pending++;
                    }
                    int c = confirmed, f = failed, p = pending, done = processed;
                    String finalStatus = status;
                    Platform.runLater(() -> {
                        sim.done = done;
                        sim.confirmed = c;
                        sim.pending = p;
                        sim.failed = f;
                        sim.progress = "Processed " + done + " / " + rows.size();
                        sim.addLine(finalStatus + " · " + shortRecipient(row.to()) + " · " + row.amount() + " " + plan.asset());
                        applySimState();
                    });
                    if (pilot && processed == 1 && !stopRequested) {
                        if (!"CONFIRMED".equals(status) && !"SUCCESS".equals(status)) {
                            stopped = "Pilot did not confirm. Remaining transfers are saved as planned.";
                            break;
                        }
                        if (!approveRemaining(result.hash(), rows.size() - 1)) {
                            stopped = "Pilot confirmed. Remaining transfers are saved as planned.";
                            break;
                        }
                    }
                    if (!stopRequested && processed < rows.size()) {
                        long delay = plan.minDelay() == plan.maxDelay() ? plan.minDelay()
                                : random.nextLong(plan.minDelay(), plan.maxDelay());
                        while (!stopRequested && delay > 0) {
                            long step = Math.min(delay, 250);
                            Thread.sleep(step);
                            delay -= step;
                        }
                    }
                }
            } catch (Exception e) {
                stopped = rootMessage(e);
            } finally {
                boolean incomplete = stopRequested || stopped != null;
                String message = stopped != null ? stopped : stopRequested ? "Stopped. Unsent transfers are saved as planned." : "Simulation finished.";
                try {
                    if (jobId > 0)
                        Repository.updateSimulationJob(jobId, incomplete ? "STOPPED" : failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED", confirmed, failed);
                } catch (Exception ignored) {
                }
                int c = confirmed, f = failed, p = pending, done = processed;
                Platform.runLater(() -> {
                    simulationLock.release();
                    sim.running = false;
                    sim.finishedUnseen = !app.isCurrentPage("Simulations");
                    sim.done = done;
                    sim.confirmed = c;
                    sim.pending = p;
                    sim.failed = f;
                    sim.progress = message;
                    applySimState();
                    app.notifyStatus(message);
                    app.alert(incomplete ? "Simulation stopped" : "Simulation finished", message + "\n\nProcessed: " + done
                            + " / " + plan.transfers().size() + "\nConfirmed: " + c + "\nPending or unknown: " + p + "\nFailed: " + f);
                });
            }
        }, "chainforge-simulation");
        worker.setDaemon(true);
        worker.start();
    }

    private String awaitPilotConfirmation(ChainAdapter adapter, long id, ChainAdapter.TransferResult result) throws Exception {
        String status = result.status().toUpperCase(Locale.ROOT);
        if (result.hash() == null || result.hash().isBlank() || !Set.of("PENDING", "SUBMITTED").contains(status)) return status;
        Platform.runLater(() -> {
            sim.progress = "Waiting for pilot confirmation · " + result.hash();
            applySimState();
        });
        int maxChecks = adapter instanceof BitcoinAdapter ? 450 : 30;
        for (int check = 0; !stopRequested && check < maxChecks && Set.of("PENDING", "SUBMITTED").contains(status); check++) {
            Thread.sleep(4000);
            if (stopRequested) break;
            ChainAdapter.StatusResult current;
            try {
                current = adapter.checkTransactionStatus(result.hash()).join();
            } catch (Exception e) {
                continue;
            }
            status = current.status().toUpperCase(Locale.ROOT);
            if (!Set.of("PENDING", "SUBMITTED").contains(status))
                Repository.updateTx(id, result.hash(), current.fee() == null ? result.fee() : current.fee(), status, current.error());
        }
        return status;
    }

    private boolean approveRemaining(String hash, int count) {
        var answer = new CompletableFuture<Boolean>();
        Platform.runLater(() -> {
            try {
                answer.complete(!stopRequested && app.confirm("Pilot confirmed", "Transaction: " + hash
                        + "\n\nSend the remaining " + count + " planned transfers?"));
            } catch (Exception e) {
                answer.completeExceptionally(e);
            }
        });
        return answer.join();
    }

    VBox sidebarMonitor() {
        simSideBox = new VBox(6);
        simSideBox.getStyleClass().add("sim-side");
        simSideTitle = new Label();
        simSideDetail = new Label();
        simSideDetail.getStyleClass().add("muted");
        simSideBar = new ProgressBar(0);
        simSideBar.setMaxWidth(Double.MAX_VALUE);
        simSideBar.getStyleClass().add("monitor-progress");
        simSideBox.getChildren().addAll(simSideTitle, simSideDetail, simSideBar);
        simSideBox.setCursor(javafx.scene.Cursor.HAND);
        simSideBox.setFocusTraversable(true);
        simSideBox.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        simSideBox.setAccessibleText("Open simulation monitor");
        simSideBox.setOnMouseClicked(e -> app.showPage("Simulations"));
        simSideBox.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                app.showPage("Simulations");
                e.consume();
            }
        });
        updateSimSidebar();
        return simSideBox;
    }

    void onEnter() {
        sim.finishedUnseen = false;
        updateSimSidebar();
    }

    void onExit() {
        if (simStatusTimer != null) simStatusTimer.stop();
        simStatusTimer = null;
        summary.onExit();
    }

    void refreshSummary() {
        summary.refresh();
    }
}
