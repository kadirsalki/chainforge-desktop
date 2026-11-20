package dev.chainforge.ui;

import dev.chainforge.core.ChainAdapter;
import dev.chainforge.core.SimulationLock;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmSepoliaAdapter;
import dev.chainforge.security.WalletVault;
import dev.chainforge.solana.SolanaAdapter;
import dev.chainforge.solana.SolanaKeys;
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
    private final SimulationLock simulationLock = new SimulationLock();
    private final ChainAdapter sepolia = new EvmSepoliaAdapter();
    private final ChainAdapter solana = new SolanaAdapter();
    private Label statusLabel;
    private Timeline transactionPolling;
    private final AtomicBoolean txPollInFlight = new AtomicBoolean(false);
    private VBox transactionList;

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
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        VBox net = new VBox(7);
        net.getStyleClass().add("network");
        Label a = new Label("●  TEST NETWORKS");
        a.setTextFill(Color.web("#57d6ad"));
        Label id = new Label("EVM · Solana Devnet");
        id.getStyleClass().add("muted");
        net.getChildren().addAll(a, id);
        s.getChildren().addAll(brand, new Separator(), cap, nav, gap, net);
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
        VBox v = pageShell("Your testing workspace", "Live transaction overview for your local testnet simulations.");
        int recipients = 0, confirmed = 0, pending = 0, failed = 0, total = 0;
        try {
            recipients = Repository.count("recipients");
            for (var tx : Repository.transactions()) {
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
                details.getChildren().addAll(new Label(shortHash(tx.to())), muted(safe(tx.amount()) + " " + safe(tx.asset())));
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
        v.getChildren().addAll(stats, monitor, actions, table, muted("TESTNET ONLY  •  LOCAL SQLITE  •  AES-256-GCM ENCRYPTED WALLET STORAGE"));
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
        actions.getChildren().addAll(primary("+ Generate EVM wallet", this::createWallet), primary("+ Generate Solana wallet", this::createSolanaWallet), primary("Import EVM key", this::importWallet), primary("Import Solana key", this::importSolanaWallet), primary("+ Add EVM token", this::addTrackedToken), primary("↻ Refresh", () -> showPage("Wallets")));
        VBox cards = new VBox(14);
        try {
            var rows = Repository.wallets();
            if (rows.isEmpty())
                cards.getChildren().add(muted("No wallets yet. Create a testnet wallet to get started."));
            for (var w : rows) {
                boolean isSol = "SOLANA".equalsIgnoreCase(w.family());
                String nativeAsset = isSol ? "SOL" : "ETH";
                ChainAdapter adapter = isSol ? solana : sepolia;
                VBox card = panel();
                HBox head = new HBox(10);
                head.setAlignment(Pos.CENTER_LEFT);
                VBox identity = new VBox(4);
                Label name = new Label(w.name());
                name.getStyleClass().add("wallet-name");
                Label network = muted(isSol ? "SOLANA DEVNET" : "ETHEREUM SEPOLIA");
                network.getStyleClass().add("network-eyebrow");
                Label address = muted(w.address());
                identity.getChildren().addAll(name, network, address);
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                Label badge = new Label("TESTNET");
                badge.getStyleClass().add("status-pill");
                head.getChildren().addAll(identity, push, badge);
                GridPane balances = new GridPane();
                balances.setHgap(12);
                balances.setVgap(10);
                ColumnConstraints c1 = new ColumnConstraints();
                c1.setPercentWidth(50);
                ColumnConstraints c2 = new ColumnConstraints();
                c2.setPercentWidth(50);
                balances.getColumnConstraints().addAll(c1, c2);
                VBox nativeCard = new VBox(5);
                nativeCard.getStyleClass().add("balance-tile");
                Label nativeLabel = muted("NATIVE BALANCE · " + nativeAsset);
                Label nativeValue = new Label("Loading…");
                nativeValue.getStyleClass().add("balance-value");
                nativeCard.getChildren().addAll(nativeLabel, nativeValue);
                balances.add(nativeCard, 0, 0);
                VBox tokenCard = new VBox(5);
                tokenCard.getStyleClass().add("balance-tile");
                tokenCard.getChildren().addAll(muted("TRACKED TOKENS"), muted(isSol ? "SPL token discovery not enabled" : "Loading token balances…"));
                balances.add(tokenCard, 1, 0);
                adapter.getBalance(w.address(), nativeAsset).whenComplete((value, err) -> Platform.runLater(() -> nativeValue.setText(err == null ? value + " " + nativeAsset : "Unavailable")));
                if (!isSol) {
                    try {
                        List<Repository.Token> tokens = Repository.tokens("ethereum-sepolia");
                        VBox tokenLines = new VBox(5);
                        if (tokens.isEmpty()) tokenLines.getChildren().add(muted("No tracked tokens yet"));
                        for (var t : tokens) {
                            Label line = muted(t.symbol() + "  ·  loading…");
                            tokenLines.getChildren().add(line);
                            sepolia.getBalance(w.address(), "ERC20:" + t.contract())
                                    .whenComplete((value, err) -> Platform.runLater(() -> line.setText(err == null ? t.symbol() + "  ·  " + value : " " + t.symbol() + "  ·  unavailable")));
                        }
                        tokenCard.getChildren().setAll(muted("TRACKED TOKENS"), tokenLines);
                    } catch (Exception ex) {
                        tokenCard.getChildren().add(muted("Token list unavailable"));
                    }
                }
                HBox controls = new HBox(8);
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
                controls.getChildren().addAll(unlock, copy, del);
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
            String contract = ask("Add Sepolia ERC-20", "Paste the verified token contract address:", false).orElse("").trim();
            if (contract.isBlank()) return;
            validateEvmAddress(contract);
            ((EvmSepoliaAdapter) sepolia).tokenInfo(contract).whenComplete((meta, err) -> Platform.runLater(() -> {
                if (err != null) {
                    error(rootMessage(err));
                    return;
                }
                if (confirm("Verify token", "Network: Ethereum Sepolia\nName: " + meta.name() + "\nSymbol: " + meta.symbol() + "\nDecimals: " + meta.decimals() + "\nContract: " + meta.contract() + "\n\nAdd this token to your tracked assets?")) {
                    try {
                        Repository.saveToken("ethereum-sepolia", meta.contract(), meta.symbol(), meta.name(), meta.decimals());
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
            alert("Wallet created", "Address:\n" + c.getAddress() + "\n\nFund it with Sepolia test ETH from a faucet. The private key is encrypted in the local database.");
        } catch (Exception e) {
            error(rootMessage(e));
        }
    }

    private void importWallet() {
        try {
            String name = ask("Import wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pk = ask("Import private key", "Paste a testnet-only EVM private key. Never enter a mainnet wallet key:", true).orElse("").trim();
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
            ChainAdapter adapter = w != null && "SOLANA".equalsIgnoreCase(w.family()) ? solana : sepolia;
            String asset = adapter == solana ? "SOL" : "ETH";
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
        TextField label = new TextField();
        label.setPromptText("Label (optional)");
        TextField address = new TextField();
        address.setPromptText("Recipient address");
        address.setPrefWidth(360);
        Button add = primary("Add address", () -> {
            try {
                String a = address.getText().trim();
                validateAddressForNetwork(network.getValue(), a);
                if (group.getValue() == null)
                    throw new IllegalArgumentException("Create or select a recipient group first.");
                Repository.addRecipient(network.getValue().key(), group.getValue().id(), label.getText().trim(), a);
                showPage("Recipients");
            } catch (Exception ex) {
                error(rootMessage(ex));
            }
        });
        network.setPrefWidth(175);
        group.setPrefWidth(175);
        label.setPrefWidth(170);
        address.setPrefWidth(280);
        FlowPane controls = new FlowPane(10, 10);
        controls.setPrefWrapLength(880);
        controls.getChildren().addAll(network, group, newGroup, label, address, add);
        TextArea bulk = new TextArea();
        bulk.setPromptText("Paste addresses, one per line. YAML import accepts a list or { recipients: [...] }.");
        bulk.setPrefRowCount(5);
        HBox imports = new HBox(10, primary("Import pasted list", () -> importPastedToGroup(network.getValue(), group.getValue(), bulk.getText())), primary("Import YAML file…", () -> importYamlToGroup(network.getValue(), group.getValue())));
        VBox list = panel();
        populateRecipientList(list, network.getValue(), group.getValue());
        group.setOnAction(e -> populateRecipientList(list, network.getValue(), group.getValue()));
        network.setOnAction(e -> {
            reload.run();
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
                Label address = new Label(r.address());
                address.getStyleClass().add("network-detail-value");
                address.setWrapText(true);
                address.setTooltip(new Tooltip(r.address()));
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
        else throw new IllegalArgumentException("This network family is not enabled yet.");
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
                if (a.contains(",")) {
                    String[] pair = a.split(",", 2);
                    label = pair[0].trim();
                    a = pair[1].trim();
                }
                validateAddressForNetwork(n, a);
                Repository.addRecipient(n.key(), g.id(), label, a);
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
                String a = null, l = "Imported " + (added + 1);
                if (item instanceof Map<?, ?> m) {
                    a = Objects.toString(m.get("address"), null);
                    l = Objects.toString(m.get("label"), l);
                } else if (item != null) a = item.toString();
                if (a != null) {
                    validateAddressForNetwork(n, a);
                    Repository.addRecipient(n.key(), g.id(), l, a);
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
        HBox summary = new HBox(12, stat("CONFIGURED NETWORKS", String.valueOf(networks.size())),
                stat("ACTIVE NETWORKS", String.valueOf(enabled)), stat("EVM NETWORKS", String.valueOf(evm)), stat("SOLANA NETWORKS", String.valueOf(sol)));
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
            Label family = new Label(n.family() + " NETWORK");
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
        fields.add(fieldCard("BLOCK EXPLORER URL (OPTIONAL)", explorer), 0, 2, 2, 1);
        Button save = primary("Save network", () -> {
            try {
                if (name.getText().isBlank() || key.getText().isBlank() || rpc.getText().isBlank())
                    throw new IllegalArgumentException("Name, key and RPC URL are required.");
                if (!rpc.getText().startsWith("https://"))
                    throw new IllegalArgumentException("Use an HTTPS RPC endpoint.");
                Repository.saveNetwork(name.getText().trim(), "EVM", key.getText().trim(), chain.getText().trim(), rpc.getText().trim(), explorer.getText().trim(), true);
                showPage("Networks");
                notifyStatus("Network saved.");
            } catch (Exception e) {
                error(rootMessage(e));
            }
        });
        form.getChildren().addAll(fields, save);
        VBox roadmap = panel();
        roadmap.getChildren().addAll(section("ADAPTER CAPABILITIES"),
                capabilityRow("EVM · Ethereum Sepolia", "Native ETH and ERC-20 balances / transfers", "SUPPORTED"),
                capabilityRow("Solana · Devnet", "Native SOL balances / transfers · SPL tokens not enabled", "PARTIAL"),
                capabilityRow("Bitcoin · Signet", "Adapter extension point only · no UTXO or fee engine", "PLANNED"));
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
        HBox bar = new HBox(10, primary("Refresh", () -> {
            renderTransactionList(transactionList);
            pollPendingTransactions();
        }), primary("Export CSV", this::exportTransactions));
        Label pollingLabel = muted("● Auto-check active · every 15 seconds");
        pollingLabel.getStyleClass().add("polling-indicator");
        HBox toolbar = new HBox(12, bar, pollingLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        transactionList = panel();
        renderTransactionList(transactionList);
        v.getChildren().addAll(toolbar, transactionList);
        if (transactionPolling != null) transactionPolling.stop();
        transactionPolling = new Timeline(new KeyFrame(Duration.seconds(15), e -> pollPendingTransactions()));
        transactionPolling.setCycleCount(Timeline.INDEFINITE);
        transactionPolling.play();
        pollPendingTransactions();
        return v;
    }

    private void renderTransactionList(VBox list) {
        if (list == null) return;
        list.getChildren().clear();
        list.getChildren().add(section("TRANSACTION HISTORY"));
        try {
            var rows = Repository.transactions();
            long pendingCount = rows.stream()
                    .filter(t -> "PENDING".equalsIgnoreCase(safe(t.status()))
                            || "SUBMITTED".equalsIgnoreCase(safe(t.status())))
                    .count();
            list.getChildren().add(muted(rows.size() + " total transactions  ·  "
                    + pendingCount + " awaiting confirmation  ·  updated automatically every 15 seconds"));

            if (rows.isEmpty()) {
                list.getChildren().add(muted("No transactions yet."));
                return;
            }

            FlowPane cards = new FlowPane(14, 14);
            cards.setPrefWrapLength(900);
            cards.setMaxWidth(Double.MAX_VALUE);

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
                Label status = new Label(safe(t.status()).toUpperCase(Locale.ROOT));
                status.getStyleClass().add(statusStyle(t.status()));
                status.setMinWidth(Region.USE_PREF_SIZE);
                status.setMaxWidth(Region.USE_PREF_SIZE);
                status.setWrapText(false);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                String asset = safe(t.asset());
                boolean erc20 = asset.toUpperCase(Locale.ROOT).startsWith("ERC20:");
                String amountAsset = erc20 ? "ERC20" : asset;
                Label amount = new Label(safe(t.amount()) + " " + amountAsset);
                amount.getStyleClass().add("transaction-amount");
                amount.setWrapText(true);
                amount.setMaxWidth(190);
                amount.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
                heading.getChildren().addAll(status, spacer, amount);

                card.getChildren().add(heading);
                card.getChildren().add(transactionField("NETWORK", safe(t.network()), false));

                if (erc20) {
                    String contract = asset.substring(asset.indexOf(':') + 1).trim();
                    card.getChildren().add(transactionField("TOKEN CONTRACT ADDRESS", contract, true));
                } else {
                    card.getChildren().add(transactionField("ASSET", asset, false));
                }

                card.getChildren().addAll(
                        transactionField("FROM", safe(t.from()), true),
                        transactionField("TO", safe(t.to()), true),
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
                    String explorer = "solana-devnet".equals(t.network())
                            ? "https://explorer.solana.com/tx/" + t.hash() + "?cluster=devnet"
                            : "https://sepolia.etherscan.io/tx/" + t.hash();
                    Hyperlink link = new Hyperlink("Open in "
                            + ("solana-devnet".equals(t.network()) ? "Solana Explorer" : "Sepolia Etherscan"));
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
        if (!"Transactions".equals(currentPage) || !txPollInFlight.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            boolean changed = false;
            try {
                for (var tx : Repository.transactions()) {
                    String current = safe(tx.status()).toUpperCase(Locale.ROOT);
                    if (!"PENDING".equals(current) && !"SUBMITTED".equals(current)) continue;
                    if (tx.hash() == null || tx.hash().isBlank()) continue;
                    ChainAdapter adapter = switch (tx.network()) {
                        case "ethereum-sepolia" -> sepolia;
                        case "solana-devnet" -> solana;
                        default -> null;
                    };
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

    private void exportTransactions() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export transactions");
        fc.setInitialFileName("chainforge-transactions.csv");
        File f = fc.showSaveDialog(root.getScene().getWindow());
        if (f == null) return;
        try {
            StringBuilder b = new StringBuilder("id,network,hash,from,to,amount,asset,fee,status,error,created\n");
            for (var t : Repository.transactions())
                b.append(csv(t.id() + "")).append(',').append(csv(t.network())).append(',').append(csv(t.hash())).append(',').append(csv(t.from())).append(',').append(csv(t.to())).append(',').append(csv(t.amount())).append(',').append(csv(t.asset())).append(',').append(csv(t.fee())).append(',').append(csv(t.status())).append(',').append(csv(t.error())).append(',').append(csv(t.created())).append('\n');
            Files.writeString(f.toPath(), b.toString());
            alert("Export complete", f.getAbsolutePath());
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
                setText(empty || r == null ? null : safe(r.label()) + " · " + r.address());
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
            boolean token = EvmSepoliaAdapter.isToken(selected);
            tokenMeta.setVisible(token);
            tokenMeta.setManaged(token);
            if (token) {
                try {
                    String contract = EvmSepoliaAdapter.tokenContract(selected);
                    var meta = Repository.tokens("ethereum-sepolia").stream().filter(t -> t.contract().equalsIgnoreCase(contract)).findFirst().orElseThrow();
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
                wallet.getItems().setAll(Repository.wallets().stream().filter(w -> w.family().equalsIgnoreCase(family)).toList());
                if (!wallet.getItems().isEmpty()) wallet.setValue(wallet.getItems().get(0));
                group.getItems().setAll(Repository.recipientGroups(n.key()));
                if (!group.getItems().isEmpty()) group.setValue(group.getItems().get(0));
                asset.getItems().clear();
                if (family.equals("SOLANA")) asset.getItems().add("SOL");
                else if (family.equals("EVM")) {
                    asset.getItems().add("ETH");
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
        Label progress = muted(simulationLock.isRunning() ? "Another simulation is running in the background." : "Ready — no simulation running");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("monitor-progress");
        VBox activity = new VBox(7);
        activity.getChildren().add(muted("Every transfer result will be listed here."));
        monitor.getChildren().addAll(section("LIVE SIMULATION MONITOR"), cards, progress, bar, activity);
        Button[] startRef = new Button[1], stopRef = new Button[1];
        Button start = primary("▶ Start simulation", () -> startSimulation(wallet.getValue(), network.getValue(), group.getValue(), min.getText(), max.getText(), minDelay.getText(), maxDelay.getText(), asset.getValue(), new ArrayList<>(recips.getSelectionModel().getSelectedItems()), progress, sent, ok, pend, bad, bar, activity, startRef[0], stopRef[0]));
        startRef[0] = start;
        start.setDisable(simulationLock.isRunning());
        Button stop = new Button("■ Stop after current transfer");
        stopRef[0] = stop;
        stop.getStyleClass().add("secondary");
        stop.setDisable(!simulationLock.isRunning());
        stop.setOnAction(e -> {
            stopRequested = true;
            progress.setText("Stop requested — current transfer will finish.");
        });
        HBox controls = new HBox(10, start, stop);
        v.getChildren().addAll(setupCards, monitor, controls, muted("EVM: native ETH + tracked ERC-20 tokens. Solana: native SOL. Only contracts verified and saved in Wallets appear in this asset selector."));
        return v;
    }

    private String assetLabel(String asset) {
        if (!EvmSepoliaAdapter.isToken(asset)) return asset;
        String contract = EvmSepoliaAdapter.tokenContract(asset);
        try {
            return Repository.tokens("ethereum-sepolia").stream().filter(t -> t.contract().equalsIgnoreCase(contract)).findFirst().map(t -> t.symbol() + " · " + t.name()).orElse("ERC-20 · " + shortHash(contract));
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

    private void startSimulation(Repository.Wallet w, Repository.Network network, Repository.RecipientGroup group, String minS, String maxS, String minD, String maxD, String asset, List<Repository.Recipient> recipients, Label progress, Label sent, Label ok, Label pend, Label bad, ProgressBar bar, VBox activity, Button startButton, Button stopButton) {
        if (!simulationLock.tryAcquire()) {
            error("A simulation is already running. Wait until every transfer in that batch has finished.");
            return;
        }
        try {
            if (w == null || network == null || group == null)
                throw new IllegalArgumentException("Select network, sender wallet and recipient group.");
            if (!network.key().equals("ethereum-sepolia") && !network.key().equals("solana-devnet"))
                throw new IllegalArgumentException("This network has no live adapter yet. Only Ethereum Sepolia and Solana Devnet can send transfers.");
            if (!w.family().equalsIgnoreCase(network.family()))
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
            boolean evmToken = EvmSepoliaAdapter.isToken(asset);
            String expectedAsset = network.family().equalsIgnoreCase("SOLANA") ? "SOL" : "ETH";
            if (network.family().equalsIgnoreCase("SOLANA") && !expectedAsset.equalsIgnoreCase(asset))
                throw new IllegalArgumentException("Solana currently supports native SOL only.");
            if (network.family().equalsIgnoreCase("EVM") && !expectedAsset.equalsIgnoreCase(asset) && !evmToken)
                throw new IllegalArgumentException("Select ETH or an ERC-20 contract.");
            if (evmToken) {
                String contract = EvmSepoliaAdapter.tokenContract(asset);
                validateEvmAddress(contract);
                ((EvmSepoliaAdapter) sepolia).tokenInfo(contract).join();
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
            ChainAdapter adapter = network.key().equals("solana-devnet") ? solana : sepolia;
            if (!confirm("Start " + network.name() + " simulation?", "Network: " + network.name() + "\nSender: " + w.address() + "\nGroup: " + group.name() + "\nRecipients: " + recipients.size() + "\nAmount: " + lo + "–" + hi + " " + asset + "\n\nTransfers will be sent sequentially. Testnet only.")) {
                simulationLock.release();
                return;
            }
            stopRequested = false;
            if (startButton != null) startButton.setDisable(true);
            if (stopButton != null) stopButton.setDisable(false);
            progress.setText("Starting batch: " + recipients.size() + " recipient(s)");
            sent.setText("0 / " + recipients.size());
            ok.setText("0");
            pend.setText("0");
            bad.setText("0");
            bar.setProgress(0);
            activity.getChildren().clear();
            startSimulationWorker(w, network, adapter, privateKey, recipients, lo, hi, dlo, dhi, asset, progress, sent, ok, pend, bad, bar, activity, startButton, stopButton);
        } catch (Exception e) {
            simulationLock.release();
            if (startButton != null) startButton.setDisable(false);
            if (stopButton != null) stopButton.setDisable(true);
            error(rootMessage(e));
        }
    }

    private void startSimulationWorker(Repository.Wallet wallet, Repository.Network network, ChainAdapter adapter, String key, List<Repository.Recipient> recipients, BigDecimal lo, BigDecimal hi, int dlo, int dhi, String asset, Label progress, Label sent, Label ok, Label pend, Label bad, ProgressBar bar, VBox activity, Button startButton, Button stopButton) {
        Thread worker = new Thread(() -> {
            int confirmed = 0, failed = 0, pending = 0, processed = 0;
            long jobId = -1;
            try {
                jobId = Repository.createSimulationJob(network.key(), recipients.size(), "amount=" + lo + ".." + hi + ";delay=" + dlo + ".." + dhi + ";asset=" + asset);
            } catch (Exception ignored) {
            }
            Random random = new SecureRandom();
            int amountScale = adapter == solana ? 9 : 8;
            if (adapter instanceof EvmSepoliaAdapter evm && EvmSepoliaAdapter.isToken(asset)) {
                try {
                    amountScale = evm.tokenInfo(EvmSepoliaAdapter.tokenContract(asset)).join().decimals();
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
                        txId = Repository.addTx(network.key(), null, wallet.address(), r.address(), amount.toPlainString(), asset, null, "PENDING", null);
                        pending++;
                    } catch (Exception ignored) {
                    }
                    final int p0 = pending;
                    Platform.runLater(() -> {
                        progress.setText("Submitting to " + shortHash(r.address()) + " · " + amount + " " + asset);
                        pend.setText(String.valueOf(p0));
                        Label line = muted("PENDING · " + shortHash(r.address()) + " · " + amount + " " + asset);
                        activity.getChildren().add(0, line);
                    });
                    String rowStatus = "FAILED";
                    try {
                        ChainAdapter.TransferResult result = adapter.transfer(key, r.address(), amount, asset).join();
                        if (txId > 0) Repository.updateTx(txId, result.hash(), result.fee(), result.status(), null);
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
                                Repository.addTx(network.key(), null, wallet.address(), r.address(), amount.toPlainString(), asset, null, "FAILED", message);
                        } catch (Exception ignored) {
                        }
                    }
                    processed++;
                    final int c = confirmed, f = failed, p = pending, done = processed;
                    final String status = rowStatus;
                    Platform.runLater(() -> {
                        sent.setText(done + " / " + recipients.size());
                        ok.setText(String.valueOf(c));
                        pend.setText(String.valueOf(p));
                        bad.setText(String.valueOf(f));
                        bar.setProgress((double) done / recipients.size());
                        progress.setText("Execution " + Math.round(100.0 * done / recipients.size()) + "% · " + c + " confirmed · " + p + " pending · " + f + " failed");
                        if (!activity.getChildren().isEmpty() && activity.getChildren().get(0) instanceof Label l)
                            l.setText(status + " · " + shortHash(r.address()) + " · " + amount + " " + asset);
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
                    if (startButton != null) startButton.setDisable(false);
                    if (stopButton != null) stopButton.setDisable(true);
                    notifyStatus("Simulation finished");
                    progress.setText("Finished · sent " + done + " / " + recipients.size() + " · " + c + " confirmed · " + p + " pending · " + f + " failed" + (stopRequested ? " · stopped" : ""));
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
        safety.getChildren().addAll(section("NETWORK SAFETY"), muted("This build is configured for test networks. Do not import mainnet private keys."), muted("Never share your vault password, private key or seed phrase."));
        v.getChildren().addAll(vault, db, safety);
        return v;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

}
