package dev.chainforge.ui;

import dev.chainforge.bitcoin.BitcoinKeys;
import dev.chainforge.core.AdapterFactory;
import dev.chainforge.core.ChainAdapter;
import dev.chainforge.core.SimulationLock;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.security.WalletVault;
import dev.chainforge.solana.SolanaKeys;
import dev.chainforge.xrp.XrpKeys;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Functional local-first desktop UI. Chain-specific behavior is delegated to adapters.
 */
public final class MainView {
    private final BorderPane root = new BorderPane();
    private Label pageTitle;
    private String currentPage = "Overview";
    private final Map<Long, String> unlockedKeys = new HashMap<>();
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
            lines.add(0, line);
            if (lines.size() > 500) lines.remove(lines.size() - 1);
        }
    }

    private final SimState sim = new SimState();
    private Label simProgress, simSent, simOk, simPend, simBad;
    private ProgressBar simBar;
    private VBox simActivity;
    private Button simStart, simStop;
    private Runnable simSummaryReload;
    private VBox simSideBox;
    private Label simSideTitle, simSideDetail;
    private ProgressBar simSideBar;
    private Timeline simStatusTimer;

    /** Sidebar badge: visible while a simulation runs (and after it finished until the Simulations page is opened). */
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
            simSideTitle.setStyle("-fx-text-fill:#57d6ad;-fx-font-size:10px;-fx-font-weight:bold;");
            simSideDetail.setText(sim.done + " / " + sim.total + " sent · " + pct + "%" + failed);
            simSideBar.setProgress(sim.total == 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) sim.done / sim.total);
        } else {
            simSideTitle.setText(sim.failed > 0 ? "!  SIMULATION FINISHED" : "✓  SIMULATION FINISHED");
            simSideTitle.setStyle("-fx-text-fill:" + (sim.failed > 0 ? "#f4cb76" : "#57d6ad") + ";-fx-font-size:10px;-fx-font-weight:bold;");
            simSideDetail.setText(sim.done + " / " + sim.total + " sent" + failed);
            simSideBar.setProgress(1);
        }
    }

    /** Pushes {@link #sim} into the widgets currently shown (no-op until the Simulations page has been built). Call on the FX thread. */
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
        simStart.setDisable(sim.running);
        simStop.setDisable(!sim.running);
        if (simSummaryReload != null) simSummaryReload.run();
    }
    private final SimulationLock simulationLock = new SimulationLock();
    private final AdapterFactory adapters = new AdapterFactory();
    private Label statusLabel;
    private Timeline transactionPolling;
    private final AtomicBoolean txPollInFlight = new AtomicBoolean(false);
    private VBox transactionList;
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

    public Parent build() {
        root.getStyleClass().add("app");
        root.setLeft(sidebar());
        root.setTop(topbar());
        showPage("Overview");
        return root;
    }

    private VBox sidebar() {
        VBox s = new VBox(18);
        s.getStyleClass().add("sidebar");
        s.setPadding(new Insets(24, 16, 20, 16));
        s.setPrefWidth(238);
        HBox brand = new HBox(11);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("C");
        mark.getStyleClass().add("mark");
        VBox bt = new VBox(2);
        Label n = new Label("CHAINFORGE");
        n.getStyleClass().add("brand");
        Label sub = new Label("BLOCKCHAIN TEST STUDIO");
        sub.getStyleClass().add("caption");
        bt.getChildren().addAll(n, sub);
        brand.getChildren().addAll(mark, bt);
        Label cap = new Label("WORKSPACE");
        cap.getStyleClass().add("caption");
        VBox nav = new VBox(5);
        for (String x : new String[]{"Overview", "Wallets", "Networks", "Recipients", "Transactions", "Simulations", "Settings"}) {
            Button b = new Button(icon(x) + "   " + x);
            b.getStyleClass().add("nav");
            b.setMaxWidth(Double.MAX_VALUE);
            if (x.equals(currentPage)) b.getStyleClass().add("active");
            b.setOnAction(e -> showPage(x));
            nav.getChildren().add(b);
        }
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
        simSideBox.setOnMouseClicked(e -> showPage("Simulations"));
        updateSimSidebar();
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        VBox net = new VBox(7);
        net.getStyleClass().add("network");
        Label a = new Label("●  TEST NETWORKS");
        a.setTextFill(Color.web("#57d6ad"));
        Label id = new Label("EVM · Solana · Bitcoin · XRP");
        id.getStyleClass().add("muted");
        net.getChildren().addAll(a, id);
        s.getChildren().addAll(brand, new Separator(), cap, nav, simSideBox, gap, net);
        return s;
    }

    private String icon(String p) {
        return switch (p) {
            case "Overview" -> "◫";
            case "Wallets" -> "▣";
            case "Networks" -> "◎";
            case "Recipients" -> "⇢";
            case "Transactions" -> "≋";
            case "Simulations" -> "◷";
            default -> "⚙";
        };
    }

    private HBox topbar() {
        HBox h = new HBox(14);
        h.getStyleClass().add("topbar");
        h.setPadding(new Insets(17, 28, 17, 28));
        h.setAlignment(Pos.CENTER_LEFT);
        pageTitle = new Label("Overview");
        pageTitle.getStyleClass().add("page-title");
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        Label local = new Label("● LOCAL SQLITE");
        local.getStyleClass().add("pill");
        Button lock = new Button("⌑  Clear unlocked keys");
        lock.getStyleClass().add("secondary");
        lock.setOnAction(e -> {
            unlockedKeys.clear();
            notifyStatus("In-memory wallet keys cleared.");
        });
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("muted");
        h.getChildren().addAll(pageTitle, r, statusLabel, local, lock);
        return h;
    }

    private void showPage(String p) {
        if (!"Transactions".equals(p) && transactionPolling != null) {
            transactionPolling.stop();
            transactionPolling = null;
        }
        if (!"Simulations".equals(p)) {
            if (simStatusTimer != null) simStatusTimer.stop();
            simStatusTimer = null;
            simSummaryReload = null;
        }
        if (!"Transactions".equals(p) && incomingSyncTimer != null) {
            incomingSyncTimer.stop();
            incomingSyncTimer = null;
        }
        if ("Simulations".equals(p)) sim.finishedUnseen = false;
        currentPage = p;
        pageTitle.setText(p);
        root.setLeft(sidebar());
        Parent content = switch (p) {
            case "Overview" -> dashboard();
            case "Wallets" -> walletsPage();
            case "Networks" -> networksPage();
            case "Recipients" -> recipientsPage();
            case "Transactions" -> transactionsPage();
            case "Simulations" -> simulationsPage();
            default -> settingsPage();
        };
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        root.setCenter(scroll);
    }

    private VBox pageShell(String title, String subtitle) {
        VBox v = new VBox(18);
        v.setPadding(new Insets(28, 30, 30, 30));
        v.getStyleClass().add("content");
        Label h = new Label(title);
        h.getStyleClass().add("hero");
        Label d = new Label(subtitle);
        d.getStyleClass().add("muted");
        v.getChildren().addAll(h, d);
        return v;
    }

    private VBox panel() {
        VBox b = new VBox(12);
        b.getStyleClass().add("panel");
        b.setPadding(new Insets(18));
        return b;
    }

    private Label section(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("section");
        return l;
    }

    private Label muted(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("muted");
        return l;
    }

    private Button primary(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("primary");
        b.setOnAction(e -> action.run());
        return b;
    }

    private void notifyStatus(String s) {
        if (statusLabel != null) statusLabel.setText(s);
    }

    private void styleDialog(Dialog<?> d) {
        String css = getClass().getResource("/styles.css").toExternalForm();
        if (!d.getDialogPane().getStylesheets().contains(css)) d.getDialogPane().getStylesheets().add(css);
        d.getDialogPane().getStyleClass().add("app-dialog");
    }

    private void alert(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        styleDialog(a);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private void error(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        styleDialog(a);
        a.setTitle("ChainForge");
        a.setHeaderText("İşlem tamamlanamadı");
        a.setContentText(msg);
        a.showAndWait();
    }

    private Optional<String> ask(String title, String prompt, boolean password) {
        Dialog<String> d = new Dialog<>();
        styleDialog(d);
        d.setTitle(title);
        d.setHeaderText(prompt);
        ButtonType ok = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Control field = password ? new PasswordField() : new TextField();
        field.setPrefWidth(340);
        d.getDialogPane().setContent(field);
        d.setResultConverter(bt -> bt == ok ? (password ? ((PasswordField) field).getText() : ((TextField) field).getText()) : null);
        return d.showAndWait();
    }

    private VBox dashboard() {
        VBox v = pageShell("Your testing workspace", "Live transaction overview for your local simulations.");
        int recipients = 0, confirmed = 0, pending = 0, failed = 0, total = 0;
        try {
            recipients = Repository.count("recipients");
            for (var tx : Repository.transactions()) {
                if ("IN".equals(tx.direction())) continue; // received transfers are not part of the simulation stats
                total++;
                String st = safe(tx.status()).toUpperCase(Locale.ROOT);
                switch (st) {
                    case "CONFIRMED", "SUCCESS" -> confirmed++;
                    case "PENDING", "SUBMITTED" -> pending++;
                    case "FAILED" -> failed++;
                }
            }
        } catch (Exception ignored) {
        }
        HBox stats = new HBox(12);
        stats.getChildren().addAll(stat("WALLETS", count("wallets")), stat("ACTIVE NETWORKS", activeNetworks()), stat("RECIPIENTS", String.valueOf(recipients)), stat("CONFIRMED", String.valueOf(confirmed)), stat("PENDING", String.valueOf(pending)), stat("FAILED", String.valueOf(failed)));
        VBox monitor = panel();
        HBox heading = new HBox(10);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox titleBox = new VBox(4);
        titleBox.getChildren().addAll(section("LIVE MONITOR"), new Label("Transaction activity"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label(pending > 0 ? "● IN PROGRESS" : "● COMPLETED");
        state.getStyleClass().add("monitor-pill");
        heading.getChildren().addAll(titleBox, spacer, state);
        int finished = confirmed + failed;
        double ratio = total == 0 ? 0 : (double) finished / total;
        ProgressBar bar = new ProgressBar(ratio);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("monitor-progress");
        HBox labels = new HBox();
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        labels.getChildren().addAll(muted(confirmed + " confirmed · " + pending + " pending · " + failed + " failed"), gap, muted(total + " total"));
        VBox table = panel();
        table.getChildren().add(section("RECENT ACTIVITY"));
        try {
            var txs = Repository.transactions();
            if (txs.isEmpty())
                table.getChildren().add(muted("No transactions recorded yet. Start a simulation to see activity here."));
            else for (var tx : txs.stream().limit(8).toList()) {
                HBox row = new HBox(12);
                row.setAlignment(Pos.CENTER_LEFT);
                VBox details = new VBox(3);
                boolean incoming = "IN".equals(tx.direction());
                details.getChildren().addAll(new Label((incoming ? "↓ from " + shortHash(tx.from()) : "↑ " + shortHash(tx.to()))), muted((incoming ? "+" : "") + safe(tx.amount()) + " " + safe(tx.asset())));
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                Label status = new Label(safe(tx.status()));
                status.getStyleClass().add("status-pill");
                row.getChildren().addAll(details, push, status, muted(shortHash(tx.hash())));
                table.getChildren().addAll(row, new Separator());
            }
        } catch (Exception ex) {
            table.getChildren().add(muted(ex.getMessage()));
        }
        HBox actions = new HBox(10, primary("+ Create wallet", () -> showPage("Wallets")), primary("+ Add recipients", () -> showPage("Recipients")), primary("+ New simulation", () -> showPage("Simulations")));
        monitor.getChildren().addAll(heading, muted("Execution progress"), bar, labels);
        v.getChildren().addAll(stats, monitor, actions, table, muted("MAINNET DISABLED BY DEFAULT  •  LOCAL SQLITE  •  AES-256-GCM ENCRYPTED WALLET STORAGE"));
        return v;
    }

    private VBox stat(String t, String value) {
        VBox b = new VBox(8);
        b.getStyleClass().add("stat");
        Label l = new Label(t);
        l.getStyleClass().add("panel-label");
        Label n = new Label(value);
        n.getStyleClass().add("stat-value");
        b.getChildren().addAll(l, n);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private String count(String table) {
        try {
            return String.valueOf(Repository.count(table));
        } catch (Exception e) {
            return "—";
        }
    }

    private String activeNetworks() {
        try {
            return String.valueOf(Repository.networks().stream().filter(Repository.Network::enabled).count());
        } catch (Exception e) {
            return "—";
        }
    }

    private String safe(String s) {
        return s == null ? "—" : s;
    }

    private String shortHash(String s) {
        return s == null ? "Pending" : s.length() > 18 ? s.substring(0, 10) + "…" + s.substring(s.length() - 6) : s;
    }

    private VBox walletsPage() {
        VBox v = pageShell("Wallet portfolio", "Network-aware wallet balances and tracked assets. Add verified token contracts once; they will be available in your simulations.");
        FlowPane actions = new FlowPane(8, 8);
        actions.getChildren().addAll(primary("+ Generate EVM wallet", this::createWallet), primary("+ Generate Solana wallet", this::createSolanaWallet), primary("+ Generate Bitcoin wallet", this::createBitcoinWallet), primary("+ Generate XRP wallet", this::createXrpWallet), primary("Import EVM key", this::importWallet), primary("Import Solana key", this::importSolanaWallet), primary("Import Bitcoin key", this::importBitcoinWallet), primary("Import XRP seed", this::importXrpWallet), primary("+ Add EVM token", this::addTrackedToken), primary("↻ Refresh", () -> showPage("Wallets")));
        VBox cards = new VBox(14);
        try {
            var rows = Repository.wallets();
            if (rows.isEmpty())
                cards.getChildren().add(muted("No wallets yet. Create a wallet to get started."));
            for (var w : rows) {
                List<Repository.Network> nets = walletNetworks(w);
                boolean anyMainnet = nets.stream().anyMatch(x -> !x.testnet());
                VBox card = panel();
                HBox head = new HBox(10);
                head.setAlignment(Pos.CENTER_LEFT);
                VBox identity = new VBox(4);
                Label name = new Label(w.name());
                name.getStyleClass().add("wallet-name");
                Label network = muted(nets.isEmpty() ? w.family().toUpperCase(Locale.ROOT) + " · NO ACTIVE NETWORK" : nets.stream().map(x -> x.name().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.joining("  ·  ")));
                network.getStyleClass().add("network-eyebrow");
                Label address = muted(w.address());
                identity.getChildren().addAll(name, network, address);
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                Label badge = new Label(anyMainnet ? "MAINNET" : "TESTNET");
                badge.getStyleClass().add("status-pill");
                if (anyMainnet) badge.setStyle("-fx-background-color:#173b35;-fx-text-fill:#61dfb7;");
                head.getChildren().addAll(identity, push, badge);
                GridPane balances = new GridPane();
                balances.setHgap(12);
                balances.setVgap(10);
                ColumnConstraints c1 = new ColumnConstraints();
                c1.setPercentWidth(50);
                ColumnConstraints c2 = new ColumnConstraints();
                c2.setPercentWidth(50);
                balances.getColumnConstraints().addAll(c1, c2);
                // Per-wallet balance loaders: each entry fetches one balance and writes it to its label,
                // so the card's own Refresh button can re-run just this wallet.
                List<Runnable> loaders = new ArrayList<>();
                VBox nativeCard = new VBox(5);
                nativeCard.getStyleClass().add("balance-tile");
                nativeCard.getChildren().add(muted("NATIVE BALANCE"));
                if (nets.isEmpty()) nativeCard.getChildren().add(muted("Enable a matching network in Networks"));
                for (var net : nets) {
                    Label netName = muted(net.name());
                    Label netValue = new Label("Loading…");
                    netValue.getStyleClass().add("balance-value");
                    nativeCard.getChildren().addAll(netName, netValue);
                    loaders.add(() -> {
                        netValue.setText("Loading…");
                        try {
                            String netSymbol = nativeAssetFor(net);
                            adapterFor(net).getBalance(w.address(), netSymbol).whenComplete((val, err) -> Platform.runLater(() -> netValue.setText(err == null ? val + " " + netSymbol : "Unavailable")));
                        } catch (Exception ex) {
                            netValue.setText("Unavailable");
                        }
                    });
                }
                balances.add(nativeCard, 0, 0);
                VBox tokenCard = new VBox(5);
                tokenCard.getStyleClass().add("balance-tile");
                tokenCard.getChildren().add(muted("TRACKED TOKENS"));
                if ("BITCOIN".equalsIgnoreCase(w.family())) {
                    tokenCard.getChildren().add(muted("Native BTC only · no token catalogue"));
                } else if ("XRP".equalsIgnoreCase(w.family())) {
                    tokenCard.getChildren().add(muted("Native XRP only · issued currencies (trust lines) not enabled"));
                } else if ("SOLANA".equalsIgnoreCase(w.family())) {
                    tokenCard.getChildren().add(muted("SPL token discovery not enabled"));
                } else {
                    VBox tokenLines = new VBox(5);
                    int tokenCount = 0;
                    for (var net : nets) {
                        try {
                            for (var t : Repository.tokens(net.key())) {
                                tokenCount++;
                                Label line = muted(t.symbol() + " (" + net.name() + ")  ·  loading…");
                                tokenLines.getChildren().add(line);
                                loaders.add(() -> {
                                    line.setText(t.symbol() + " (" + net.name() + ")  ·  loading…");
                                    try {
                                        adapterFor(net).getBalance(w.address(), "ERC20:" + t.contract())
                                                .whenComplete((val, err) -> Platform.runLater(() -> line.setText(err == null ? t.symbol() + " (" + net.name() + ")  ·  " + val : t.symbol() + " (" + net.name() + ")  ·  unavailable")));
                                    } catch (Exception ex) {
                                        line.setText(t.symbol() + " (" + net.name() + ")  ·  unavailable");
                                    }
                                });
                            }
                        } catch (Exception ex) {
                            tokenLines.getChildren().add(muted("Token list unavailable"));
                        }
                    }
                    if (tokenCount == 0) tokenLines.getChildren().add(muted("No tracked tokens yet"));
                    tokenCard.getChildren().add(tokenLines);
                }
                balances.add(tokenCard, 1, 0);
                loaders.forEach(Runnable::run);
                HBox controls = new HBox(8);
                Button refreshBalance = new Button("↻ Refresh balance");
                refreshBalance.getStyleClass().add("secondary");
                refreshBalance.setOnAction(e -> {
                    loaders.forEach(Runnable::run);
                    notifyStatus("Refreshing balance: " + w.name());
                });
                Button unlock = new Button("Unlock wallet");
                unlock.getStyleClass().add("secondary");
                unlock.setOnAction(e -> unlockWallet(w));
                Button copy = new Button("Copy address");
                copy.getStyleClass().add("secondary");
                copy.setOnAction(e -> {
                    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                    cc.putString(w.address());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                    notifyStatus("Address copied.");
                });
                Button del = new Button("Delete");
                del.getStyleClass().add("secondary");
                del.setOnAction(e -> {
                    if (confirm("Delete wallet?", "Encrypted key data will be removed from this local database.")) {
                        try {
                            Repository.deleteWallet(w.id());
                            unlockedKeys.remove(w.id());
                            showPage("Wallets");
                        } catch (Exception ex) {
                            error(rootMessage(ex));
                        }
                    }
                });
                controls.getChildren().addAll(unlock, copy, refreshBalance, del);
                card.getChildren().addAll(head, balances, controls);
                cards.getChildren().add(card);
            }
        } catch (Exception e) {
            cards.getChildren().add(muted(rootMessage(e)));
        }
        v.getChildren().addAll(actions, cards, muted("Portfolio balances show native coin plus tokens in your tracked-token list. Standard RPC cannot enumerate every arbitrary token held by an address; use an indexer or add token contracts to track them."));
        return v;
    }

    private void addTrackedToken() {
        try {
            var evmNets = Repository.networks().stream().filter(x -> x.enabled() && "EVM".equalsIgnoreCase(x.family())).toList();
            if (evmNets.isEmpty()) {
                error("No active EVM network. Enable one in Networks first.");
                return;
            }
            Repository.Network picked = evmNets.get(0);
            if (evmNets.size() > 1) {
                List<String> names = evmNets.stream().map(Repository.Network::name).toList();
                ChoiceDialog<String> cd = new ChoiceDialog<>(names.get(0), names);
                styleDialog(cd);
                cd.setTitle("Add EVM token");
                cd.setHeaderText("Which network is this token contract deployed on?");
                var choice = cd.showAndWait();
                if (choice.isEmpty()) return;
                picked = evmNets.get(names.indexOf(choice.get()));
            }
            final Repository.Network net = picked;
            String contract = ask("Add ERC-20 · " + net.name(), "Paste the verified token contract address for " + net.name() + ":", false).orElse("").trim();
            if (contract.isBlank()) return;
            validateEvmAddress(contract);
            ((EvmAdapter) adapterFor(net)).tokenInfo(contract).whenComplete((meta, err) -> Platform.runLater(() -> {
                if (err != null) {
                    error(rootMessage(err));
                    return;
                }
                if (confirm("Verify token", "Network: " + net.name() + (net.testnet() ? "" : "  (MAINNET — real funds)") + "\nName: " + meta.name() + "\nSymbol: " + meta.symbol() + "\nDecimals: " + meta.decimals() + "\nContract: " + meta.contract() + "\n\nAdd this token to your tracked assets?")) {
                    try {
                        Repository.saveToken(net.key(), meta.contract(), meta.symbol(), meta.name(), meta.decimals());
                        showPage("Wallets");
                    } catch (Exception ex) {
                        error(rootMessage(ex));
                    }
                }
            }));
        } catch (Exception ex) {
            error(rootMessage(ex));
        }
    }

    private boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        styleDialog(a);
        a.setTitle(title);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void createWallet() {
        try {
            String name = ask("New wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = ask("Encrypt wallet", "Set a strong vault password (you will need it to unlock this key):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            ECKeyPair pair = Keys.createEcKeyPair();
            String pk = Numeric.toHexStringNoPrefix(pair.getPrivateKey());
            Credentials c = Credentials.create(pk);
            var sealed = WalletVault.seal(pass.toCharArray(), pk.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "EVM", c.getAddress(), sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("Wallet created", "Address:\n" + c.getAddress() + "\n\nThe same address works on every EVM network (Sepolia, Avalanche Fuji, Ethereum Mainnet, ...). Fund it with test coins from a faucet for testnets (Sepolia ETH, Fuji AVAX); mainnets need real funds. The private key is encrypted in the local database.");
        } catch (Exception e) {
            error(rootMessage(e));
        }
    }

    private void importWallet() {
        try {
            String name = ask("Import wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pk = ask("Import private key", "Paste an EVM private key. The same key controls this address on every EVM network, including mainnet (real funds):", true).orElse("").trim();
            if (pk.isEmpty()) return;
            pk = Numeric.cleanHexPrefix(pk);
            Credentials c = Credentials.create(pk);
            String pass = ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), pk.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "EVM", c.getAddress(), sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("Imported", "Wallet address:\n" + c.getAddress());
        } catch (Exception e) {
            error("Invalid key or duplicate wallet. " + rootMessage(e));
        }
    }

    private void unlockWallet(Repository.Wallet w) {
        try {
            String pass = ask("Unlock wallet", "Password for " + w.name() + ":", true).orElse("");
            if (pass.isEmpty()) return;
            byte[] raw = WalletVault.open(pass.toCharArray(), w.encrypted(), w.salt());
            String key = new String(raw, StandardCharsets.UTF_8);
            Arrays.fill(raw, (byte) 0);
            unlockedKeys.put(w.id(), key);
            notifyStatus("Unlocked: " + w.name());
            alert("Wallet unlocked", "Key is held in application memory until you clear unlocked keys or close the app.");
        } catch (Exception e) {
            error("Password incorrect or encrypted key is damaged.");
        }
    }

    private ChainAdapter adapterFor(Repository.Network n) {
        return adapters.forNetwork(n);
    }

    private Repository.Network networkByKey(String key) throws Exception {
        for (var n : Repository.networks()) if (n.key().equals(key)) return n;
        throw new IllegalArgumentException("Unknown network: " + key);
    }

    /** Enabled networks this wallet can operate on (Bitcoin wallets only match their own network by address prefix). */
    private List<Repository.Network> walletNetworks(Repository.Wallet w) {
        try {
            return Repository.networks().stream().filter(x -> x.enabled() && walletMatchesNetwork(w, x)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean walletMatchesNetwork(Repository.Wallet w, Repository.Network n) {
        if (!w.family().equalsIgnoreCase(n.family())) return false;
        if ("BITCOIN".equalsIgnoreCase(n.family()))
            return (BitcoinKeys.networkOfAddress(w.address()) == org.bitcoinj.base.BitcoinNetwork.MAINNET) == !n.testnet();
        return true;
    }

    private Optional<Repository.Token> findToken(String contract) {
        try {
            for (var n : Repository.networks())
                for (var t : Repository.tokens(n.key()))
                    if (t.contract().equalsIgnoreCase(contract)) return Optional.of(t);
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private String explorerTxUrl(String networkKey, String hash) {
        return switch (networkKey) {
            case "bitcoin-testnet" -> "https://mempool.space/testnet/tx/" + hash;
            case "bitcoin-mainnet" -> "https://mempool.space/tx/" + hash;
            case "solana-devnet" -> "https://explorer.solana.com/tx/" + hash + "?cluster=devnet";
            case "solana-mainnet" -> "https://explorer.solana.com/tx/" + hash;
            case "ethereum-mainnet" -> "https://etherscan.io/tx/" + hash;
            case "xrp-testnet" -> "https://testnet.xrpl.org/transactions/" + hash;
            case "xrp-mainnet" -> "https://livenet.xrpl.org/transactions/" + hash;
            case "ethereum-sepolia" -> "https://sepolia.etherscan.io/tx/" + hash;
            default -> {
                // any other EVM network (Avalanche Fuji, custom networks): use the explorer URL saved with the network
                String base = null;
                try {
                    base = networkByKey(networkKey).explorer();
                } catch (Exception ignored) {
                }
                yield base == null || base.isBlank() ? "https://sepolia.etherscan.io/tx/" + hash : base.trim().replaceAll("/+$", "") + "/tx/" + hash;
            }
        };
    }

    private String explorerName(String networkKey) {
        return switch (networkKey) {
            case "bitcoin-testnet" -> "mempool.space (Testnet)";
            case "bitcoin-mainnet" -> "mempool.space";
            case "solana-devnet" -> "Solana Explorer (Devnet)";
            case "solana-mainnet" -> "Solana Explorer";
            case "ethereum-mainnet" -> "Etherscan";
            case "xrp-testnet" -> "XRPL Explorer (Testnet)";
            case "xrp-mainnet" -> "XRPL Explorer";
            case "ethereum-sepolia" -> "Sepolia Etherscan";
            default -> {
                String label = null;
                try {
                    label = networkByKey(networkKey).name();
                } catch (Exception ignored) {
                }
                yield label == null ? "block explorer" : label + " explorer";
            }
        };
    }

    /** Ticker of a network's native coin (AVAX on Avalanche, ETH on Ethereum/Sepolia, ...). */
    private String nativeAssetFor(Repository.Network n) {
        return n.nativeSymbol() == null || n.nativeSymbol().isBlank() ? nativeAssetFor(n.family()) : n.nativeSymbol();
    }

    private String nativeAssetFor(String family) {
        if ("SOLANA".equalsIgnoreCase(family)) return "SOL";
        if ("BITCOIN".equalsIgnoreCase(family)) return "BTC";
        if ("XRP".equalsIgnoreCase(family)) return "XRP";
        return "ETH";
    }

    private void createBitcoinWallet() {
        try {
            String name = ask("New Bitcoin wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            ChoiceDialog<String> cd = new ChoiceDialog<>("Testnet", "Testnet", "Mainnet");
            styleDialog(cd);
            cd.setTitle("Bitcoin network");
            cd.setHeaderText("Which Bitcoin network is this wallet for? Mainnet wallets hold REAL funds.");
            var choice = cd.showAndWait();
            if (choice.isEmpty()) return;
            var net = BitcoinKeys.network(!"Mainnet".equals(choice.get()));
            String pass = ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var key = BitcoinKeys.generate();
            String wif = BitcoinKeys.toWif(key, net);
            String address = BitcoinKeys.address(key, net);
            var sealed = WalletVault.seal(pass.toCharArray(), wif.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "BITCOIN", address, sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("Bitcoin " + BitcoinKeys.label(net) + " wallet created", BitcoinKeys.label(net) + " address (native SegWit):\n" + address + "\n\n" + (net == org.bitcoinj.base.BitcoinNetwork.MAINNET ? "This is a MAINNET wallet: anything sent here is real Bitcoin. Back up access to this database and your vault password — there is no recovery." : "Fund it with Testnet BTC from a faucet.") + " The WIF private key is encrypted in the local database.");
        } catch (Exception e) {
            error(rootMessage(e));
        }
    }

    private void importBitcoinWallet() {
        try {
            String name = ask("Import Bitcoin wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String wif = ask("Import Bitcoin key", "Paste a compressed WIF private key (testnet 'c…' or mainnet 'K…/L…'). The network is detected from the key:", true).orElse("").trim();
            if (wif.isEmpty()) return;
            var parsed = BitcoinKeys.parseWif(wif);
            String address = BitcoinKeys.address(parsed.key(), parsed.network());
            if (parsed.network() == org.bitcoinj.base.BitcoinNetwork.MAINNET
                    && !confirm("Mainnet key", "This is a MAINNET key controlling address:\n" + address + "\n\nIt holds real funds. Import it anyway?")) return;
            String pass = ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), wif.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "BITCOIN", address, sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("Imported", "Bitcoin " + BitcoinKeys.label(parsed.network()) + " address:\n" + address);
        } catch (Exception e) {
            error("Invalid Bitcoin key or duplicate wallet. " + rootMessage(e));
        }
    }

    private void createXrpWallet() {
        try {
            String name = ask("New XRP wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var g = XrpKeys.generate();
            var sealed = WalletVault.seal(pass.toCharArray(), g.seed().getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "XRP", g.address(), sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("XRP wallet created", "Classic address:\n" + g.address() + "\n\nThe same address works on the XRPL Testnet and Mainnet. A new XRPL account only exists after it receives at least the base reserve (1 XRP); on the testnet use the XRP Testnet Faucet (xrpl.org/resources/dev-tools/xrp-faucets). The family seed is encrypted in the local database — keep your vault password safe, there is no recovery.");
        } catch (Exception e) {
            error(rootMessage(e));
        }
    }

    private void importXrpWallet() {
        try {
            String name = ask("Import XRP wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String seed = ask("Import XRP seed", "Paste the XRP family seed (starts with 's', e.g. sEd… or s…). The same seed controls this address on mainnet (real funds):", true).orElse("").trim();
            if (seed.isEmpty()) return;
            String address = XrpKeys.addressOfSeed(seed);
            String pass = ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), seed.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "XRP", address, sealed.ciphertext(), sealed.salt());
            showPage("Wallets");
            alert("Imported", "XRP address:\n" + address);
        } catch (Exception e) {
            error("Invalid XRP seed or duplicate wallet. " + rootMessage(e));
        }
    }

    private void createSolanaWallet() {
        try {
            String name = ask("New Solana wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                error("Use a vault password with at least 10 characters.");
                return;
            }
            org.p2p.solanaj.core.Account account = new org.p2p.solanaj.core.Account();
            byte[] secret = account.getSecretKey();
            String encoded = SolanaKeys.encode(secret);
            String address = account.getPublicKey().toBase58();
            var sealed = WalletVault.seal(pass.toCharArray(), encoded.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "SOLANA", address, sealed.ciphertext(), sealed.salt());
            Arrays.fill(secret, (byte) 0);
            showPage("Wallets");
            alert("Solana wallet created", "Address:\n" + address + "\n\nFund this wallet with Devnet SOL. The 64-byte keypair is encrypted locally.");
        } catch (Exception e) {
            error(rootMessage(e));
        }
    }

    private void importSolanaWallet() {
        try {
            String name = ask("Import Solana wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String encoded = ask("Import Solana keypair", "Paste the Base58-encoded 64-byte Solana secret keypair. Never use a mainnet wallet:", true).orElse("").trim();
            if (encoded.isEmpty()) return;
            byte[] secret = SolanaKeys.decode(encoded);
            if (secret.length != 64) throw new IllegalArgumentException("Solana secret key must decode to 64 bytes.");
            org.p2p.solanaj.core.Account account = new org.p2p.solanaj.core.Account(secret);
            String address = account.getPublicKey().toBase58();
            String pass = ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                Arrays.fill(secret, (byte) 0);
                error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), encoded.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "SOLANA", address, sealed.ciphertext(), sealed.salt());
            Arrays.fill(secret, (byte) 0);
            showPage("Wallets");
            alert("Imported", "Solana address:\n" + address);
        } catch (Exception e) {
            error("Invalid Solana keypair or duplicate wallet. " + rootMessage(e));
        }
    }

    private void checkBalance(String address) {
        try {
            var w = Repository.wallets().stream().filter(x -> x.address().equals(address)).findFirst().orElse(null);
            if (w == null) throw new IllegalArgumentException("Wallet not found.");
            var nets = walletNetworks(w);
            if (nets.isEmpty()) throw new IllegalArgumentException("No active network for this wallet. Enable one in Networks.");
            ChainAdapter adapter = adapterFor(nets.get(0));
            String asset = nativeAssetFor(nets.get(0));
            notifyStatus("Checking " + asset + " balance…");
            adapter.getBalance(address, asset).whenComplete((b, e) -> Platform.runLater(() -> {
                if (e != null) error(rootMessage(e));
                else alert(asset + " balance", b + " " + asset);
            }));
        } catch (Exception ex) {
            error(rootMessage(ex));
        }
    }

    private VBox recipientsPage() {
        VBox v = pageShell("Recipients", "Create separate address books for each network. A simulation can only use recipients from its selected network and group.");
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
        if (!network.getItems().isEmpty()) network.setValue(network.getItems().get(0));
        ComboBox<Repository.RecipientGroup> group = new ComboBox<>();
        Runnable reload = () -> {
            try {
                group.getItems().setAll(Repository.recipientGroups(network.getValue().key()));
                if (!group.getItems().isEmpty()) group.setValue(group.getItems().get(0));
            } catch (Exception ex) {
                error(rootMessage(ex));
            }
        };
        reload.run();
        network.setOnAction(e -> reload.run());
        Button newGroup = primary("+ New group", () -> {
            String n = ask("Create recipient group", "Group name:", false).orElse("").trim();
            if (n.isBlank()) return;
            try {
                Repository.createRecipientGroup(network.getValue().key(), n);
                reload.run();
            } catch (Exception ex) {
                error("Could not create group: " + rootMessage(ex));
            }
        });
        Button deleteGroup = new Button("Delete group");
        deleteGroup.getStyleClass().add("secondary");
        deleteGroup.setOnAction(e -> {
            Repository.RecipientGroup g = group.getValue();
            if (g == null) {
                error("Select a recipient group first.");
                return;
            }
            if ("Default".equalsIgnoreCase(g.name())) {
                error("The Default group cannot be deleted (it is recreated automatically). Remove its recipients instead.");
                return;
            }
            if (!confirm("Delete group \"" + g.name() + "\"?", "This permanently removes the group and its " + g.count()
                    + " recipient address(es) from the local address book. Past transactions are not affected.")) return;
            try {
                int removed = Repository.deleteRecipientGroup(g.id());
                showPage("Recipients");
                alert("Group deleted", "\"" + g.name() + "\" and " + removed + " recipient(s) were removed.");
            } catch (Exception ex) {
                error("Could not delete group: " + rootMessage(ex));
            }
        });
        TextField label = new TextField();
        label.setPromptText("Label (optional)");
        TextField address = new TextField();
        address.setPromptText("Recipient address");
        address.setPrefWidth(360);
        TextField tag = new TextField();
        tag.setPromptText("Destination tag (XRP, optional)");
        tag.setPrefWidth(200);
        Button add = primary("Add address", () -> {
            try {
                String stored = storedRecipient(network.getValue(), address.getText().trim(), tag.getText());
                if (group.getValue() == null)
                    throw new IllegalArgumentException("Create or select a recipient group first.");
                Repository.addRecipient(network.getValue().key(), group.getValue().id(), label.getText().trim(), stored);
                showPage("Recipients");
            } catch (Exception ex) {
                error(rootMessage(ex));
            }
        });
        network.setPrefWidth(175);
        group.setPrefWidth(175);
        label.setPrefWidth(170);
        address.setPrefWidth(280);
        Runnable syncTag = () -> {
            boolean xrp = network.getValue() != null && "XRP".equalsIgnoreCase(network.getValue().family());
            tag.setVisible(xrp);
            tag.setManaged(xrp);
            if (!xrp) tag.clear();
        };
        syncTag.run();
        FlowPane controls = new FlowPane(10, 10);
        controls.setPrefWrapLength(880);
        controls.getChildren().addAll(network, group, newGroup, deleteGroup, label, address, tag, add);
        TextArea bulk = new TextArea();
        bulk.setPromptText("Paste addresses, one per line (optionally \"label,address\"). XRP: \"address,tag\" or \"label,address,tag\". YAML import accepts a list or { recipients: [...] } with optional destination_tag.");
        bulk.setPrefRowCount(5);
        HBox imports = new HBox(10, primary("Import pasted list", () -> importPastedToGroup(network.getValue(), group.getValue(), bulk.getText())), primary("Import YAML file…", () -> importYamlToGroup(network.getValue(), group.getValue())));
        VBox list = panel();
        populateRecipientList(list, network.getValue(), group.getValue());
        group.setOnAction(e -> populateRecipientList(list, network.getValue(), group.getValue()));
        network.setOnAction(e -> {
            reload.run();
            syncTag.run();
            populateRecipientList(list, network.getValue(), group.getValue());
        });
        VBox importPanel = panel();
        importPanel.getChildren().addAll(section("BULK IMPORT"), muted("Paste one address per line, or import a YAML recipient file."), bulk, imports);
        v.getChildren().addAll(controls, importPanel, list);
        return v;
    }

    private void populateRecipientList(VBox list, Repository.Network network, Repository.RecipientGroup group) {
        list.getChildren().clear();
        list.getChildren().add(section("SAVED ADDRESSES"));
        try {
            if (group == null) {
                list.getChildren().add(muted("Create or select a group."));
                return;
            }
            var rows = Repository.recipients(network.key(), group.id());
            if (rows.isEmpty()) list.getChildren().add(muted("This group is empty."));
            FlowPane recipientCards = new FlowPane(10, 10);
            recipientCards.setPrefWrapLength(850);
            for (var r : rows) {
                VBox row = new VBox(8);
                row.getStyleClass().add("recipient-card");
                row.setPrefWidth(320);
                Label title = new Label(safe(r.label()).isBlank() ? "Unnamed recipient" : r.label());
                title.getStyleClass().add("network-card-title");
                Label address = new Label(recipientDisplay(r.address()));
                address.getStyleClass().add("network-detail-value");
                address.setWrapText(true);
                address.setTooltip(new Tooltip(recipientDisplay(r.address())));
                Button del = new Button("Remove recipient");
                del.getStyleClass().add("secondary");
                del.setOnAction(e -> {
                    try {
                        Repository.deleteRecipient(r.id());
                        populateRecipientList(list, network, group);
                    } catch (Exception ex) {
                        error(ex.getMessage());
                    }
                });
                row.getChildren().addAll(title, address, del);
                recipientCards.getChildren().add(row);
            }
            list.getChildren().add(recipientCards);
        } catch (Exception ex) {
            list.getChildren().add(muted(rootMessage(ex)));
        }
    }

    private void validateAddressForNetwork(Repository.Network n, String a) {
        if (n == null) throw new IllegalArgumentException("Select a network.");
        if ("EVM".equalsIgnoreCase(n.family())) validateEvmAddress(a);
        else if ("SOLANA".equalsIgnoreCase(n.family())) SolanaKeys.validateAddress(a);
        else if ("BITCOIN".equalsIgnoreCase(n.family())) BitcoinKeys.validateAddress(a, BitcoinKeys.network(n.testnet()));
        else if ("XRP".equalsIgnoreCase(n.family())) XrpKeys.normalizeForNetwork(a, n.testnet());
        else throw new IllegalArgumentException("This network family is not enabled yet.");
    }

    /**
     * Validates a recipient for a network and returns the value to store. On XRP networks an optional destination tag
     * is folded into an X-address (the standard single-string "address + tag" encoding); other networks reject a tag.
     */
    private String storedRecipient(Repository.Network n, String address, String tag) {
        if (n == null) throw new IllegalArgumentException("Select a network.");
        String a = address == null ? "" : address.trim();
        boolean hasTag = tag != null && !tag.isBlank();
        if ("XRP".equalsIgnoreCase(n.family())) {
            if (hasTag) {
                if (XrpKeys.isXAddress(a))
                    throw new IllegalArgumentException("This X-address already contains a destination tag; leave the tag field empty.");
                return XrpKeys.encode(a, XrpKeys.parseTag(tag), n.testnet());
            }
            return XrpKeys.normalizeForNetwork(a, n.testnet());
        }
        if (hasTag) throw new IllegalArgumentException("Destination tags are only used on XRP Ledger networks.");
        validateAddressForNetwork(n, a);
        return a;
    }

    /** Readable recipient text: XRP X-addresses are shown as "rXXXX · tag N", everything else unchanged. */
    private String recipientDisplay(String address) {
        return XrpKeys.isXAddress(address) ? XrpKeys.display(address) : address;
    }

    private String shortRecipient(String address) {
        if (!XrpKeys.isXAddress(address)) return shortHash(address);
        try {
            var d = XrpKeys.parseDestination(address);
            return shortHash(d.classic()) + (d.tag() == null ? "" : " #" + d.tag());
        } catch (RuntimeException e) {
            return shortHash(address);
        }
    }

    private void importPastedToGroup(Repository.Network n, Repository.RecipientGroup g, String raw) {
        try {
            if (n == null || g == null) throw new IllegalArgumentException("Select network and recipient group.");
            int added = 0;
            for (String line : raw.split("\\R")) {
                String a = line.trim();
                if (a.isEmpty() || a.startsWith("#")) continue;
                if (a.startsWith("- ")) a = a.substring(2).trim();
                String label = "Imported " + (added + 1);
                String tagPart = null;
                if (a.contains(",")) {
                    if ("XRP".equalsIgnoreCase(n.family())) {
                        String[] parts = a.split(",", -1);
                        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
                        if (parts.length == 3) {
                            label = parts[0];
                            a = parts[1];
                            tagPart = parts[2];
                        } else if (parts.length == 2 && parts[1].matches("\\d+")) {
                            a = parts[0];
                            tagPart = parts[1];
                        } else if (parts.length == 2) {
                            label = parts[0];
                            a = parts[1];
                        } else {
                            throw new IllegalArgumentException("Unrecognised line: " + a);
                        }
                    } else {
                        String[] pair = a.split(",", 2);
                        label = pair[0].trim();
                        a = pair[1].trim();
                    }
                }
                Repository.addRecipient(n.key(), g.id(), label, storedRecipient(n, a, tagPart));
                added++;
            }
            showPage("Recipients");
            alert("Import complete", added + " address(es) processed into " + g.name() + ".");
        } catch (Exception ex) {
            error(rootMessage(ex));
        }
    }

    private void importYamlToGroup(Repository.Network n, Repository.RecipientGroup g) {
        if (n == null || g == null) {
            error("Select network and recipient group.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Select recipients YAML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML files", "*.yaml", "*.yml"));
        File f = fc.showOpenDialog(root.getScene().getWindow());
        if (f == null) return;
        try {
            Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(f.toPath()));
            if (data instanceof Map<?, ?> m && m.get("recipients") instanceof List<?> list) data = list;
            if (!(data instanceof List<?> items))
                throw new IllegalArgumentException("YAML root must be a list or contain a recipients list.");
            int added = 0;
            for (Object item : items) {
                String a = null, l = "Imported " + (added + 1), tagValue = null;
                if (item instanceof Map<?, ?> m) {
                    a = Objects.toString(m.get("address"), null);
                    l = Objects.toString(m.get("label"), l);
                    Object t = m.containsKey("destination_tag") ? m.get("destination_tag") : m.get("tag");
                    tagValue = t == null ? null : t.toString();
                } else if (item != null) a = item.toString();
                if (a != null) {
                    Repository.addRecipient(n.key(), g.id(), l, storedRecipient(n, a, tagValue));
                    added++;
                }
            }
            showPage("Recipients");
            alert("YAML imported", added + " address(es) added to " + g.name() + ".");
        } catch (Exception ex) {
            error("YAML import failed: " + rootMessage(ex));
        }
    }

    private void validateEvmAddress(String a) {
        if (a == null || !a.matches("(?i)^0x[0-9a-f]{40}$"))
            throw new IllegalArgumentException("Invalid EVM address. Expected 0x followed by 40 hexadecimal characters.");
    }

    private VBox networksPage() {
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
        HBox summary = new HBox(12, stat("CONFIGURED NETWORKS", String.valueOf(networks.size())),
                stat("ACTIVE NETWORKS", String.valueOf(enabled)), stat("EVM NETWORKS", String.valueOf(evm)), stat("SOLANA NETWORKS", String.valueOf(sol)), stat("BITCOIN NETWORKS", String.valueOf(btc)), stat("XRP NETWORKS", String.valueOf(networks.stream().filter(n -> "XRP".equalsIgnoreCase(n.family())).count())));
        VBox configured = new VBox(14);
        HBox heading = new HBox(8);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getChildren().addAll(section("YOUR NETWORKS"), muted("  ·  " + networks.size() + " configured"));
        configured.getChildren().add(heading);
        if (networks.isEmpty())
            configured.getChildren().add(muted("No networks configured yet. Add an EVM test network below."));
        FlowPane cards = new FlowPane(14, 14);
        cards.setPrefWrapLength(900);
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
            Separator sep = new Separator();
            HBox actions = new HBox(8);
            Button toggle = new Button(n.enabled() ? "Disable network" : "Enable network");
            toggle.getStyleClass().add(n.enabled() ? "secondary" : "primary");
            toggle.setOnAction(e -> {
                try {
                    if (!n.enabled() && !n.testnet()) {
                        String typed = ask("⚠ Enable MAINNET", n.name() + " uses REAL funds. Transfers are irreversible and this app has not been audited.\nUse small amounts first and only with keys you control.\n\nType ENABLE MAINNET to continue:", false).orElse("");
                        if (!"ENABLE MAINNET".equals(typed.trim())) return;
                    }
                    Repository.setNetworkEnabled(n.key(), !n.enabled());
                    showPage("Networks");
                } catch (Exception ex) {
                    error(rootMessage(ex));
                }
            });
            Button copy = new Button("Copy RPC");
            copy.getStyleClass().add("secondary");
            copy.setOnAction(e -> {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(safe(n.rpc()));
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
                notifyStatus("RPC endpoint copied.");
            });
            actions.getChildren().addAll(toggle, copy);
            card.getChildren().addAll(top, details, sep, actions);
            cards.getChildren().add(card);
        }
        configured.getChildren().add(cards);
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
                showPage("Networks");
                notifyStatus("Network saved.");
            } catch (Exception e) {
                error(rootMessage(e));
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
        Label title = new Label(name);
        title.getStyleClass().add("section");
        info.getChildren().addAll(title, muted(detail));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label tag = new Label(status);
        tag.getStyleClass().add("status-pill");
        row.getChildren().addAll(info, gap, tag);
        return row;
    }

    private VBox transactionsPage() {
        VBox v = pageShell("Transactions", "Transaction statuses are refreshed from the network every 15 seconds while this screen is open.");
        ToggleGroup filterGroup = new ToggleGroup();
        HBox filters = new HBox(6);
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
        HBox bar = new HBox(10, filters, primary("Refresh", () -> {
            renderTransactionList(transactionList);
            pollPendingTransactions();
            syncIncoming(true);
        }), primary("⇣ Sync incoming", () -> syncIncoming(true)), exportButton());
        Label pollingLabel = muted("● Auto-check active · every 15 seconds");
        pollingLabel.getStyleClass().add("polling-indicator");
        incomingSyncLabel = muted("Incoming: not synced yet");
        VBox statusLabels = new VBox(2, pollingLabel, incomingSyncLabel);
        HBox toolbar = new HBox(12, bar, statusLabels);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        transactionList = panel();
        renderTransactionList(transactionList);
        v.getChildren().addAll(toolbar, transactionFilterBar(), transactionList);
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
                for (var tk : Repository.tokens(nk)) symbols.put(nk + "|" + tk.contract().toLowerCase(Locale.ROOT), tk.symbol());
            } catch (Exception ignored) {
            }
        }
        return symbols;
    }

    /** Asset as the user knows it: ETH / AVAX / BTC..., a tracked token's symbol, or "ERC20 · 0x12…ab" for unknown tokens. */
    private String txAssetLabel(Repository.Tx t, Map<String, String> tokenSymbols) {
        String asset = safe(t.asset());
        if (!asset.toUpperCase(Locale.ROOT).startsWith("ERC20:")) return asset;
        String contract = asset.substring(asset.indexOf(':') + 1).trim();
        String sym = tokenSymbols.get(t.network() + "|" + contract.toLowerCase(Locale.ROOT));
        return sym != null ? sym : "ERC20 · " + shortHash(contract);
    }

    private String networkNameOrKey(String key) {
        try {
            return networkByKey(key).name();
        } catch (Exception e) {
            return key;
        }
    }

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
                default -> true;
            };
            if (!ok) return false;
        }
        if (txFrom != null || txTo != null) {
            java.time.LocalDate d = txLocalDate(t.created());
            if (d == null || (txFrom != null && d.isBefore(txFrom)) || (txTo != null && d.isAfter(txTo))) return false;
        }
        if (!txSearch.isBlank()) {
            String hay = String.join("\n", safe(t.hash()), safe(t.from()), safe(t.to()), recipientDisplay(safe(t.to())), safe(t.amount()), asset,
                    networkNameOrKey(t.network()), safe(t.error())).toLowerCase(Locale.ROOT);
            for (String word : txSearch.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                if (!hay.contains(word)) return false; // every word must match somewhere
        }
        return true;
    }

    /** Search box + network / asset / status / date-range filters for the Transactions page. */
    private FlowPane transactionFilterBar() {
        TextField search = new TextField(txSearch);
        search.setPromptText("Search hash, address, amount, asset, error…");
        search.setPrefWidth(330);
        javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> renderTransactionList(transactionList));
        search.textProperty().addListener((o, a, b) -> {
            txSearch = b == null ? "" : b;
            debounce.playFromStart(); // wait for a pause in typing before re-rendering
        });

        ComboBox<String> network = new ComboBox<>();
        ComboBox<String> asset = new ComboBox<>();
        ComboBox<String> status = new ComboBox<>();
        for (ComboBox<String> c : List.of(network, asset, status)) c.setPrefWidth(165);
        network.setCellFactory(lv -> filterCell(v -> "ALL".equals(v) ? "All networks" : networkNameOrKey(v)));
        network.setButtonCell(filterCell(v -> "ALL".equals(v) ? "All networks" : networkNameOrKey(v)));
        asset.setCellFactory(lv -> filterCell(v -> "ALL".equals(v) ? "All assets" : v));
        asset.setButtonCell(filterCell(v -> "ALL".equals(v) ? "All assets" : v));
        java.util.function.Function<String, String> statusText = v -> switch (v) {
            case "CONFIRMED" -> "Confirmed";
            case "PENDING" -> "Pending";
            case "FAILED" -> "Failed";
            default -> "Any status";
        };
        status.getItems().addAll("ALL", "CONFIRMED", "PENDING", "FAILED");
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
                all.stream().map(Repository.Tx::network).distinct().sorted(Comparator.comparing(this::networkNameOrKey)).forEach(nets::add);
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
        from.setPrefWidth(140);
        to.setPrefWidth(140);
        from.setOnAction(e -> {
            txFrom = from.getValue();
            renderTransactionList(transactionList);
        });
        to.setOnAction(e -> {
            txTo = to.getValue();
            renderTransactionList(transactionList);
        });

        Button clear = new Button("✕ Clear filters");
        clear.getStyleClass().add("secondary");
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
        });

        FlowPane bar = new FlowPane(10, 10, search, network, asset, status, from, to, clear);
        bar.setPrefWrapLength(900);
        return bar;
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
        list.getChildren().add(section("TRANSACTION HISTORY"));
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
                list.getChildren().add(muted(filtered ? "No transactions match the current search and filters. Use \"Clear filters\" to see everything." : "IN".equals(txFilter) ? "No incoming transfers found yet. Use \"Sync incoming\" to read them from the network."
                        : "OUT".equals(txFilter) ? "No outgoing transactions yet." : "No transactions yet."));
                return;
            }

            FlowPane cards = new FlowPane(14, 14);
            cards.setPrefWrapLength(900);
            cards.setMaxWidth(Double.MAX_VALUE);

            if (rows.size() > TX_RENDER_LIMIT)
                list.getChildren().add(muted("Showing the newest " + TX_RENDER_LIMIT + " of " + rows.size() + " matches — narrow the search or the dates to see the rest."));
            for (var t : rows.size() > TX_RENDER_LIMIT ? rows.subList(0, TX_RENDER_LIMIT) : rows) {
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
                String tokenSymbol = erc20 ? tokenSymbols.get(t.network() + "|" + asset.substring(asset.indexOf(':') + 1).trim().toLowerCase(Locale.ROOT)) : null;
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
                    link.setOnAction(e -> getHostServicesOpen(explorer));
                    card.getChildren().add(link);
                }
                cards.getChildren().add(card);
            }
            list.getChildren().add(cards);
        } catch (Exception e) {
            list.getChildren().add(muted("Could not load transactions: " + rootMessage(e)));
        }
    }

    /**
     * A bounded, wrapping transaction detail row with optional one-click copy.
     */
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
            copy.setOnAction(e -> copyToClipboard(value, title + " copied."));
            valueRow.getChildren().add(copy);
        }

        field.getChildren().addAll(caption, valueRow);
        return field;
    }

    private void copyToClipboard(String value, String message) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(value);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        notifyStatus(message);
    }

    private String statusStyle(String status) {
        String s = safe(status).toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CONFIRMED", "SUCCESS" -> "tx-status-success";
            case "FAILED" -> "tx-status-failed";
            case "PENDING", "SUBMITTED" -> "tx-status-pending";
            default -> "status-pill";
        };
    }

    private void pollPendingTransactions() {
        pollPendingTransactions(false);
    }

    /** @param anyPage also run while another page (the Simulations summary) needs fresh statuses */
    private void pollPendingTransactions(boolean anyPage) {
        if ((!anyPage && !"Transactions".equals(currentPage)) || !txPollInFlight.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            boolean changed = false;
            try {
                Map<String, ChainAdapter> byKey = new HashMap<>();
                for (var net : Repository.networks()) {
                    try {
                        if (AdapterFactory.isSupported(net)) byKey.put(net.key(), adapterFor(net));
                    } catch (Exception ignored) {
                    }
                }
                for (var tx : Repository.transactions()) {
                    String current = safe(tx.status()).toUpperCase(Locale.ROOT);
                    if (!"PENDING".equals(current) && !"SUBMITTED".equals(current)) continue;
                    if (tx.hash() == null || tx.hash().isBlank()) continue;
                    ChainAdapter adapter = byKey.get(tx.network());
                    if (adapter == null) continue;
                    try {
                        ChainAdapter.StatusResult result = adapter.checkTransactionStatus(tx.hash()).join();
                        String next = safe(result.status()).toUpperCase(Locale.ROOT);
                        if (!next.equals(current) || !Objects.equals(result.fee(), tx.fee()) || !Objects.equals(result.error(), tx.error())) {
                            Repository.updateTx(tx.id(), tx.hash(), result.fee() != null ? result.fee() : tx.fee(), next, result.error());
                            changed = true;
                        }
                    } catch (Exception ignored) {/* transient RPC failure: keep the last known status */}
                }
            } catch (Exception ignored) {
            }
            final boolean refresh = changed;
            Platform.runLater(() -> {
                txPollInFlight.set(false);
                if (refresh && "Transactions".equals(currentPage)) renderTransactionList(transactionList);
                if (refresh && "Simulations".equals(currentPage) && simSummaryReload != null) simSummaryReload.run();
            });
        });
    }

    /**
     * Reads the transfers each wallet RECEIVED from the chain (Esplora / rippled / Solana RPC / EVM history API)
     * and stores them as direction IN. Sequential with small gaps so public APIs are not hammered.
     */
    private void syncIncoming(boolean manual) {
        if (!incomingSyncInFlight.compareAndSet(false, true)) {
            if (manual) notifyStatus("Incoming sync is already running…");
            return;
        }
        if (manual) notifyStatus("Syncing incoming transfers…");
        if (incomingSyncLabel != null) incomingSyncLabel.setText("Incoming: syncing…");
        CompletableFuture.runAsync(() -> {
            int added = 0, checked = 0;
            List<String> problems = new ArrayList<>();
            try {
                for (var w : Repository.wallets()) {
                    for (var net : walletNetworks(w)) {
                        if (!AdapterFactory.isSupported(net)) continue;
                        checked++;
                        try {
                            var known = Repository.knownIncomingHashes(net.key(), w.address());
                            for (var t : adapterFor(net).fetchIncoming(w.address(), known).join()) {
                                if (t.from() != null && t.from().equalsIgnoreCase(w.address())) continue; // sent to itself
                                String asset = ChainAdapter.NATIVE.equals(t.asset()) ? nativeAssetFor(net) : t.asset();
                                if (Repository.addIncomingTx(net.key(), t.hash(), t.from(), t.to(), t.amount(), asset, t.fee(), t.status(), t.timestamp())) added++;
                            }
                        } catch (Exception ex) {
                            problems.add(w.name() + " · " + net.name() + ": " + rootMessage(ex));
                        }
                        Thread.sleep(400);
                    }
                }
            } catch (Exception ex) {
                problems.add(rootMessage(ex));
            }
            final int newCount = added, nets = checked;
            final List<String> errs = problems;
            Platform.runLater(() -> {
                incomingSyncInFlight.set(false);
                String time = java.time.LocalTime.now().withNano(0).toString();
                if (incomingSyncLabel != null && "Transactions".equals(currentPage))
                    incomingSyncLabel.setText("Incoming: synced " + time + " · " + newCount + " new" + (errs.isEmpty() ? "" : " · " + errs.size() + " source(s) failed"));
                if (newCount > 0 && "Transactions".equals(currentPage)) renderTransactionList(transactionList);
                if (!errs.isEmpty() && manual) error("Some incoming sources could not be read:\n" + String.join("\n", errs));
                else if (manual) notifyStatus("Incoming sync done: " + newCount + " new from " + nets + " wallet/network pair(s).");
            });
        });
    }

    private void getHostServicesOpen(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            error("Could not open browser: " + e.getMessage());
        }
    }

    /** The transactions the page currently shows: All/Incoming/Outgoing switch plus search and filters. */
    private List<Repository.Tx> visibleTransactions() throws Exception {
        var all = Repository.transactions();
        var symbols = tokenSymbolsFor(all);
        return all.stream().filter(t -> "ALL".equals(txFilter) || txFilter.equals(t.direction())).filter(t -> matchesTxFilters(t, symbols)).toList();
    }

    /** Exports exactly the filtered result (not the whole history), newest first like the page. */
    private void exportTransactions() {
        List<Repository.Tx> rows;
        int total;
        try {
            rows = visibleTransactions();
            total = Repository.transactions().size();
        } catch (Exception e) {
            error(rootMessage(e));
            return;
        }
        if (rows.isEmpty()) {
            error("Nothing to export: no transactions match the current search and filters.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export " + rows.size() + " of " + total + " transactions");
        fc.setInitialFileName("chainforge-transactions" + (rows.size() < total ? "-filtered" : "") + ".csv");
        File f = fc.showSaveDialog(root.getScene().getWindow());
        if (f == null) return;
        try {
            StringBuilder b = new StringBuilder("id,direction,network,hash,from,to,amount,asset,fee,status,error,created\n");
            for (var t : rows)
                b.append(csv(t.id() + "")).append(',').append(csv(t.direction())).append(',').append(csv(t.network())).append(',').append(csv(t.hash())).append(',').append(csv(t.from())).append(',').append(csv(t.to())).append(',').append(csv(t.amount())).append(',').append(csv(t.asset())).append(',').append(csv(t.fee())).append(',').append(csv(t.status())).append(',').append(csv(t.error())).append(',').append(csv(t.created())).append('\n');
            Files.writeString(f.toPath(), b.toString());
            alert("Export complete", rows.size() + " of " + total + " transactions exported" + (rows.size() < total ? " (current search and filters applied)" : "") + ".\n\n" + f.getAbsolutePath());
        } catch (Exception e) {
            error(e.getMessage());
        }
    }

    private String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private VBox simulationsPage() {
        VBox v = pageShell("Simulations", "Run one batch at a time. The next simulation stays locked until every transfer in the current batch reaches a final result.");
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
        if (!network.getItems().isEmpty()) network.setValue(network.getItems().get(0));
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
        recips.setPrefHeight(210);
        recips.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        recips.setCellFactory(lv -> new ListCell<>() {
            protected void updateItem(Repository.Recipient r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : safe(r.label()) + " · " + recipientDisplay(r.address()));
            }
        });
        TextField min = new TextField("0.001"), max = new TextField("0.003"), minDelay = new TextField("5"), maxDelay = new TextField("20");
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
                if (!wallet.getItems().isEmpty()) wallet.setValue(wallet.getItems().get(0));
                group.getItems().setAll(Repository.recipientGroups(n.key()));
                if (!group.getItems().isEmpty()) group.setValue(group.getItems().get(0));
                asset.getItems().clear();
                if (family.equals("SOLANA")) asset.getItems().add("SOL");
                else if (family.equals("BITCOIN")) asset.getItems().add("BTC");
                else if (family.equals("XRP")) asset.getItems().add("XRP");
                else if (family.equals("EVM")) {
                    asset.getItems().add(nativeAssetFor(n));
                    for (var t : Repository.tokens(n.key())) asset.getItems().add("ERC20:" + t.contract());
                }
                if (!asset.getItems().isEmpty()) asset.setValue(asset.getItems().get(0));
                tokenMeta.setVisible(false);
                tokenMeta.setManaged(false);
                refreshRecipients(group, recips);
            } catch (Exception ex) {
                error(rootMessage(ex));
            }
        };
        network.setOnAction(e -> refreshNetwork.run());
        group.setOnAction(e -> refreshRecipients(group, recips));
        refreshNetwork.run();
        VBox config = panel();
        config.getChildren().add(section("SIMULATION CONFIGURATION"));
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
        configGrid.add(fieldCard("Minimum delay · sec", minDelay), 0, 3);
        configGrid.add(fieldCard("Maximum delay · sec", maxDelay), 1, 3);
        configGrid.add(tokenMeta, 0, 4, 2, 1);
        config.getChildren().add(configGrid);
        VBox targets = panel();
        targets.getChildren().addAll(section("RECIPIENT BATCH"), muted("Select recipients from the chosen network and group."), recips);
        recips.setPrefHeight(265);
        recips.setMinHeight(220);
        recips.setMaxHeight(300);
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
        monitor.getChildren().addAll(section("LIVE SIMULATION MONITOR"), cards, progress, bar, activity);
        Button start = primary("▶ Start simulation", () -> startSimulation(wallet.getValue(), network.getValue(), group.getValue(), min.getText(), max.getText(), minDelay.getText(), maxDelay.getText(), asset.getValue(), new ArrayList<>(recips.getSelectionModel().getSelectedItems())));
        Button stop = new Button("■ Stop after current transfer");
        stop.getStyleClass().add("secondary");
        stop.setOnAction(e -> {
            stopRequested = true;
            sim.progress = "Stop requested — current transfer will finish.";
            applySimState();
        });
        // Bind this page instance to the running (or finished) simulation so progress survives page switches.
        simSummaryReload = null;
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
        sim.running = simulationLock.isRunning();
        applySimState();
        HBox controls = new HBox(10, start, stop);
        VBox summary = simulationSummaryPanel();
        // Statuses (e.g. Bitcoin PENDING -> CONFIRMED) only change on-chain; re-check them while this page is open.
        simStatusTimer = new Timeline(new KeyFrame(Duration.seconds(20), e -> pollPendingTransactions(true)));
        simStatusTimer.setCycleCount(Timeline.INDEFINITE);
        simStatusTimer.play();
        v.getChildren().addAll(setupCards, monitor, controls, summary, muted("EVM: native ETH + tracked ERC-20 tokens. Solana: native SOL. XRP: native XRP (a new recipient account needs at least the 1 XRP base reserve, otherwise the payment fails and the fee is still charged). Bitcoin: native BTC (back-to-back transfers chain unconfirmed change; keep batches small). Only contracts verified and saved in Wallets appear in this asset selector. Mainnet networks spend real funds and are disabled until you enable them in Networks."));
        return v;
    }

    private record SimRow(int n, String label, String address, String amount, String asset, String status, String hash, String network, String error) {
    }

    private static String localTime(String utc) {
        try {
            return java.time.LocalDateTime.parse(utc.replace(' ', 'T')).atOffset(java.time.ZoneOffset.UTC)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return utc == null ? "" : utc;
        }
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

    /**
     * Per-recipient breakdown of a simulation batch: who received how much, status and transaction hash,
     * with totals. Reads the stored transfers, so it also works for earlier batches and after a restart.
     */
    private VBox simulationSummaryPanel() {
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
            table.getItems().clear();
            if (job == null) {
                totals.setText("");
                return;
            }
            try {
                Map<String, String> labels = new HashMap<>();
                for (var r : Repository.recipients(job.network())) labels.put(recipientDisplay(r.address()), safe(r.label()));
                List<SimRow> rows = new ArrayList<>();
                int n = 0;
                BigDecimal sentAmt = BigDecimal.ZERO, confAmt = BigDecimal.ZERO, failAmt = BigDecimal.ZERO;
                int conf = 0, pend = 0, fail = 0;
                String asset = "";
                for (var t : Repository.txsForJob(job.id())) {
                    rows.add(new SimRow(++n, labels.getOrDefault(t.to(), ""), safe(t.to()), safe(t.amount()), safe(t.asset()), t.status(), t.hash(), t.network(), t.error()));
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
                        default -> {
                            pend++;
                            sentAmt = sentAmt.add(a);
                        }
                    }
                }
                table.getItems().setAll(rows);
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
        simSummaryReload = () -> {
            try {
                var all = Repository.simulationJobs();
                long keep = jobs.getValue() == null ? -1 : jobs.getValue().id();
                if (sim.jobId > 0 && sim.jobId != followed[0]) { // a new batch started: follow it
                    followed[0] = sim.jobId;
                    keep = sim.jobId;
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
            java.util.function.Function<String, String> q = csv ? this::csv : x -> x == null ? "" : x.replace('\t', ' ').replace('\n', ' ');
            b.append(String.join(sep, "#", "recipient", "address", "amount", "asset", "status", "tx_hash", "error")).append('\n');
            for (var r : table.getItems())
                b.append(String.join(sep, q.apply(String.valueOf(r.n())), q.apply(r.label()), q.apply(r.address()), q.apply(r.amount()), q.apply(assetLabel(r.asset()).split(" · ")[0]), q.apply(r.status()), q.apply(r.hash()), q.apply(r.error()))).append('\n');
            return b.toString();
        };
        Button refresh = new Button("↻ Refresh status");
        refresh.getStyleClass().add("secondary");
        refresh.setOnAction(e -> {
            pollPendingTransactions(true);
            simSummaryReload.run();
            notifyStatus("Refreshing transfer statuses…");
        });
        Button copyTable = new Button("Copy table");
        copyTable.getStyleClass().add("secondary");
        copyTable.setOnAction(e -> {
            if (table.getItems().isEmpty()) {
                error("There is nothing to copy yet.");
                return;
            }
            copyToClipboard(render.apply(false) + "\n" + totals.getText(), "Summary copied — paste it into Excel or a document.");
        });
        Button export = new Button("Export CSV…");
        export.getStyleClass().add("secondary");
        export.setOnAction(e -> {
            if (table.getItems().isEmpty() || jobs.getValue() == null) {
                error("There is nothing to export yet.");
                return;
            }
            FileChooser fc = new FileChooser();
            fc.setTitle("Export simulation summary");
            fc.setInitialFileName("simulation-" + jobs.getValue().id() + "-summary.csv");
            File f = fc.showSaveDialog(root.getScene().getWindow());
            if (f == null) return;
            try {
                Files.writeString(f.toPath(), render.apply(true));
                alert("Export complete", f.getAbsolutePath());
            } catch (Exception ex) {
                error(rootMessage(ex));
            }
        });
        Button open = new Button("Open in explorer");
        open.getStyleClass().add("secondary");
        open.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row == null || row.hash() == null || row.hash().isBlank()) {
                error("Select a row that has a transaction hash.");
                return;
            }
            getHostServicesOpen(explorerTxUrl(row.network(), row.hash()));
        });
        ContextMenu menu = new ContextMenu();
        MenuItem copyAddr = new MenuItem("Copy address");
        copyAddr.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row != null) copyToClipboard(row.address(), "Address copied.");
        });
        MenuItem copyHash = new MenuItem("Copy transaction hash");
        copyHash.setOnAction(e -> {
            SimRow row = table.getSelectionModel().getSelectedItem();
            if (row != null && row.hash() != null) copyToClipboard(row.hash(), "Transaction hash copied.");
        });
        menu.getItems().addAll(copyAddr, copyHash);
        table.setContextMenu(menu);

        HBox actions = new HBox(10, refresh, copyTable, export, open);
        panel.getChildren().addAll(section("SIMULATION SUMMARY"), muted("Who received how much in a batch. Pick an earlier batch from the list; click a column header to sort, right-click a row to copy."),
                jobs, metrics, totals, table, actions);
        simSummaryReload.run();
        return panel;
    }

    private String assetLabel(String asset) {
        if (!EvmAdapter.isToken(asset)) return asset;
        String contract = EvmAdapter.tokenContract(asset);
        try {
            return findToken(contract).map(t -> t.symbol() + " · " + t.name()).orElse("ERC-20 · " + shortHash(contract));
        } catch (Exception e) {
            return "ERC-20 · " + shortHash(contract);
        }
    }

    private void refreshRecipients(ComboBox<Repository.RecipientGroup> group, ListView<Repository.Recipient> recips) {
        try {
            var g = group.getValue();
            recips.getItems().setAll(g == null ? List.of() : Repository.recipients(g.network(), g.id()));
        } catch (Exception ex) {
            error(rootMessage(ex));
        }
    }

    private VBox fieldCard(String title, Control control) {
        VBox box = new VBox(5);
        Label label = muted(title);
        control.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().addAll(label, control);
        return box;
    }

    private Label metricValue(String value) {
        Label l = new Label(value);
        l.getStyleClass().add("metric-value");
        return l;
    }

    private VBox metricCard(String title, Label value) {
        VBox c = new VBox(5);
        c.getStyleClass().add("metric-card");
        Label t = new Label(title);
        t.getStyleClass().add("panel-label");
        c.getChildren().addAll(t, value);
        HBox.setHgrow(c, Priority.ALWAYS);
        return c;
    }

    private void startSimulation(Repository.Wallet w, Repository.Network network, Repository.RecipientGroup group, String minS, String maxS, String minD, String maxD, String asset, List<Repository.Recipient> recipients) {
        if (!simulationLock.tryAcquire()) {
            error("A simulation is already running. Wait until every transfer in that batch has finished.");
            return;
        }
        try {
            if (w == null || network == null || group == null)
                throw new IllegalArgumentException("Select network, sender wallet and recipient group.");
            if (!AdapterFactory.isSupported(network))
                throw new IllegalArgumentException("This network has no live adapter yet. Only EVM, Solana and Bitcoin networks can send transfers.");
            if (!network.enabled())
                throw new IllegalArgumentException("This network is disabled. Enable it in Networks first.");
            if (!walletMatchesNetwork(w, network))
                throw new IllegalArgumentException("Sender wallet does not belong to the selected network.");
            if (!group.network().equals(network.key()))
                throw new IllegalArgumentException("Recipient group belongs to a different network.");
            if (recipients.isEmpty()) throw new IllegalArgumentException("Select at least one recipient.");
            for (var r : recipients)
                if (!r.network().equals(network.key()) || r.groupId() != group.id())
                    throw new IllegalArgumentException("Recipient selection contains an address from another network or group.");
            BigDecimal lo = new BigDecimal(minS), hi = new BigDecimal(maxS);
            int dlo = Integer.parseInt(minD), dhi = Integer.parseInt(maxD);
            if (lo.signum() <= 0 || hi.compareTo(lo) < 0 || dlo < 0 || dhi < dlo)
                throw new IllegalArgumentException("Check amount and delay ranges.");
            boolean evmToken = EvmAdapter.isToken(asset);
            String expectedAsset = nativeAssetFor(network);
            if (network.family().equalsIgnoreCase("SOLANA") && !expectedAsset.equalsIgnoreCase(asset))
                throw new IllegalArgumentException("Solana currently supports native SOL only.");
            if (network.family().equalsIgnoreCase("BITCOIN") && !expectedAsset.equalsIgnoreCase(asset))
                throw new IllegalArgumentException("Bitcoin currently supports native BTC only.");
            if (network.family().equalsIgnoreCase("XRP") && !expectedAsset.equalsIgnoreCase(asset))
                throw new IllegalArgumentException("XRP Ledger currently supports native XRP only.");
            if (network.family().equalsIgnoreCase("EVM") && !expectedAsset.equalsIgnoreCase(asset) && !evmToken)
                throw new IllegalArgumentException("Select " + expectedAsset + " or an ERC-20 contract.");
            if (evmToken) {
                String contract = EvmAdapter.tokenContract(asset);
                validateEvmAddress(contract);
                ((EvmAdapter) adapterFor(network)).tokenInfo(contract).join();
            }
            String key = unlockedKeys.get(w.id());
            if (key == null) {
                String pass = ask("Unlock sender wallet", "Enter vault password for " + w.name() + ":", true).orElse("");
                if (pass.isEmpty()) {
                    simulationLock.release();
                    return;
                }
                byte[] raw = WalletVault.open(pass.toCharArray(), w.encrypted(), w.salt());
                key = new String(raw, StandardCharsets.UTF_8);
                Arrays.fill(raw, (byte) 0);
                unlockedKeys.put(w.id(), key);
            }
            final String privateKey = key;
            ChainAdapter adapter = adapterFor(network);
            String summary = "Network: " + network.name() + "\nSender: " + w.address() + "\nGroup: " + group.name() + "\nRecipients: " + recipients.size() + "\nAmount: " + lo + "–" + hi + " " + asset;
            boolean approved;
            if (network.testnet()) {
                approved = confirm("Start " + network.name() + " simulation?", summary + "\n\nTransfers will be sent sequentially. Testnet only.");
            } else {
                BigDecimal worst = hi.multiply(BigDecimal.valueOf(recipients.size()));
                String typed = ask("⚠ MAINNET — REAL FUNDS", summary + "\n\nWorst-case total (excluding network fees): " + worst.toPlainString() + " " + assetLabel(asset) + "\nEvery transfer is irreversible. Check the sender, recipients and amounts above.\n\nType MAINNET to start:", false).orElse("");
                approved = "MAINNET".equals(typed.trim());
            }
            if (!approved) {
                simulationLock.release();
                return;
            }
            stopRequested = false;
            sim.finishedUnseen = false;
            sim.reset(recipients.size());
            sim.running = true;
            sim.progress = "Starting batch: " + recipients.size() + " recipient(s) on " + network.name();
            applySimState();
            startSimulationWorker(w, network, adapter, privateKey, recipients, lo, hi, dlo, dhi, asset);
        } catch (Exception e) {
            simulationLock.release();
            sim.running = false;
            applySimState();
            error(rootMessage(e));
        }
    }

    private void startSimulationWorker(Repository.Wallet wallet, Repository.Network network, ChainAdapter adapter, String key, List<Repository.Recipient> recipients, BigDecimal lo, BigDecimal hi, int dlo, int dhi, String asset) {
        Thread worker = new Thread(() -> {
            int confirmed = 0, failed = 0, pending = 0, processed = 0;
            long jobId = -1;
            try {
                jobId = Repository.createSimulationJob(network.key(), recipients.size(), "amount=" + lo + ".." + hi + ";delay=" + dlo + ".." + dhi + ";asset=" + asset);
            } catch (Exception ignored) {
            }
            final long job = jobId;
            Platform.runLater(() -> {
                sim.jobId = job;
                applySimState();
            });
            Random random = new SecureRandom();
            int amountScale = "SOLANA".equalsIgnoreCase(adapter.family()) ? 9 : "XRP".equalsIgnoreCase(adapter.family()) ? 6 : 8;
            if (adapter instanceof EvmAdapter evm && EvmAdapter.isToken(asset)) {
                try {
                    amountScale = evm.tokenInfo(EvmAdapter.tokenContract(asset)).join().decimals();
                } catch (Exception ignored) {
                    amountScale = 8;
                }
            }
            final int transferScale = amountScale;
            try {
                for (var r : recipients) {
                    if (stopRequested) break;
                    BigDecimal amount = lo.add(hi.subtract(lo).multiply(BigDecimal.valueOf(random.nextDouble()))).setScale(transferScale, java.math.RoundingMode.DOWN);
                    long txId = -1;
                    try {
                        txId = Repository.addTx(network.key(), null, wallet.address(), recipientDisplay(r.address()), amount.toPlainString(), asset, null, "PENDING", null, job);
                        pending++;
                    } catch (Exception ignored) {
                    }
                    final int p0 = pending;
                    Platform.runLater(() -> {
                        sim.pending = p0;
                        sim.progress = "Submitting to " + shortRecipient(r.address()) + " · " + amount + " " + asset;
                        sim.addLine("PENDING · " + shortRecipient(r.address()) + " · " + amount + " " + asset);
                        applySimState();
                    });
                    String rowStatus = "FAILED";
                    try {
                        ChainAdapter.TransferResult result = adapter.transfer(key, r.address(), amount, asset).join();
                        if (txId > 0) Repository.updateTx(txId, result.hash(), result.fee(), result.status(), result.error());
                        if ("CONFIRMED".equalsIgnoreCase(result.status())) {
                            pending--;
                            confirmed++;
                            rowStatus = "CONFIRMED";
                        } else if ("PENDING".equalsIgnoreCase(result.status()) || "SUBMITTED".equalsIgnoreCase(result.status())) {
                            rowStatus = "PENDING";
                        } else {
                            pending--;
                            failed++;
                            rowStatus = "FAILED";
                        }
                    } catch (Exception ex) {
                        pending--;
                        failed++;
                        String message = rootMessage(ex);
                        try {
                            if (txId > 0) Repository.updateTx(txId, null, null, "FAILED", message);
                            else
                                Repository.addTx(network.key(), null, wallet.address(), recipientDisplay(r.address()), amount.toPlainString(), asset, null, "FAILED", message, job);
                        } catch (Exception ignored) {
                        }
                    }
                    processed++;
                    final int c = confirmed, f = failed, p = pending, done = processed;
                    final String status = rowStatus;
                    Platform.runLater(() -> {
                        sim.done = done;
                        sim.confirmed = c;
                        sim.pending = p;
                        sim.failed = f;
                        sim.progress = "Execution " + Math.round(100.0 * done / recipients.size()) + "% · " + c + " confirmed · " + p + " pending · " + f + " failed";
                        if (!sim.lines.isEmpty()) sim.lines.set(0, status + " · " + shortRecipient(r.address()) + " · " + amount + " " + asset);
                        applySimState();
                    });
                    if (!stopRequested && processed < recipients.size()) try {
                        Thread.sleep((long) (dlo + (dhi - dlo) * random.nextDouble()) * 1000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                final int c = confirmed, f = failed, p = pending, done = processed;
                String finalState = stopRequested ? "STOPPED" : (failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED");
                try {
                    if (jobId > 0) Repository.updateSimulationJob(jobId, finalState, confirmed, failed);
                } catch (Exception ignored) {
                }
                simulationLock.release();
                Platform.runLater(() -> {
                    sim.running = false;
                    sim.finishedUnseen = !"Simulations".equals(currentPage);
                    sim.done = done;
                    sim.confirmed = c;
                    sim.pending = p;
                    sim.failed = f;
                    sim.progress = "Finished · sent " + done + " / " + recipients.size() + " · " + c + " confirmed · " + p + " pending · " + f + " failed" + (stopRequested ? " · stopped" : "");
                    applySimState();
                    notifyStatus("Simulation finished");
                    alert("Simulation finished", "Network: " + network.name() + "\nSent: " + done + " / " + recipients.size() + "\nConfirmed: " + c + "\nPending: " + p + "\nFailed: " + f + "\n\nCheck Transactions for hashes and final status.");
                });
            }
        }, "chainforge-simulation");
        worker.setDaemon(true);
        worker.start();
    }

    private VBox settingsPage() {
        VBox v = pageShell("Settings", "Local application settings and security controls.");
        VBox vault = panel();
        vault.getChildren().addAll(section("WALLET SECURITY"), muted("Private keys are encrypted using AES-256-GCM. Passwords are derived with PBKDF2-HMAC-SHA256 (310,000 iterations). The master password is not stored."), primary("Clear all unlocked keys", () -> {
            unlockedKeys.clear();
            notifyStatus("All in-memory keys cleared.");
            alert("Vault locked", "All decrypted private keys were cleared from memory.");
        }));
        VBox db = panel();
        db.getChildren().addAll(section("LOCAL DATA"), muted("SQLite database: " + System.getProperty("user.home") + "/.chainforge/chainforge.db"), muted("Wallets, recipients, network definitions and transaction history remain on this device."));
        VBox safety = panel();
        safety.getChildren().addAll(section("NETWORK SAFETY"), muted("Testnets are enabled by default. Mainnet networks (real funds) are disabled until you enable them in Networks and confirm by typing. Only import keys you control, and start with small amounts."), muted("Never share your vault password, private key or seed phrase."));
        v.getChildren().addAll(vault, db, safety);
        return v;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

}
