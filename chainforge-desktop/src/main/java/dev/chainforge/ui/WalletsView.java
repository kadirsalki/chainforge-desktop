package dev.chainforge.ui;

import dev.chainforge.bitcoin.BitcoinKeys;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.security.WalletVault;
import dev.chainforge.solana.SolanaKeys;
import dev.chainforge.xrp.XrpKeys;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static dev.chainforge.ui.NetworkSupport.*;
import static dev.chainforge.ui.ViewSupport.*;

final class WalletsView {
    private final MainView app;

    WalletsView(MainView app) {
        this.app = app;
    }

    VBox build() {
        VBox v = pageShell("Wallet portfolio", "Network-aware wallet balances and tracked assets. Add verified token contracts once; they will be available in your simulations.");
        MenuButton create = new MenuButton("Create wallet", null,
                walletMenuItem("EVM", this::createWallet),
                walletMenuItem("Solana", this::createSolanaWallet),
                walletMenuItem("Bitcoin", this::createBitcoinWallet),
                walletMenuItem("XRP", this::createXrpWallet));
        create.getStyleClass().addAll("wallet-menu", "primary");
        MenuButton importKey = new MenuButton("Import wallet", null,
                walletMenuItem("EVM private key", this::importWallet),
                walletMenuItem("Solana private key", this::importSolanaWallet),
                walletMenuItem("Bitcoin private key", this::importBitcoinWallet),
                walletMenuItem("XRP seed", this::importXrpWallet));
        importKey.getStyleClass().addAll("wallet-menu", "secondary");
        Button addToken = new Button("Add EVM token");
        addToken.getStyleClass().add("secondary");
        addToken.setOnAction(e -> addTrackedToken());
        Button refreshPortfolio = new Button("↻ Refresh portfolio");
        refreshPortfolio.getStyleClass().add("secondary");
        refreshPortfolio.setOnAction(e -> app.showPage("Wallets"));
        FlowPane actions = new FlowPane(8, 8, create, importKey, addToken, refreshPortfolio);
        actions.setAlignment(Pos.CENTER_LEFT);
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
                badge.getStyleClass().add(anyMainnet ? "tx-status-pending" : "status-pill");
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
                nativeCard.setMaxHeight(Double.MAX_VALUE);
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
                            app.adapterFor(net).getBalance(w.address(), netSymbol).whenComplete((val, err) -> Platform.runLater(() -> netValue.setText(err == null ? val + " " + netSymbol : "Unavailable")));
                        } catch (Exception ex) {
                            netValue.setText("Unavailable");
                        }
                    });
                }
                balances.add(nativeCard, 0, 0);
                VBox tokenCard = new VBox(5);
                tokenCard.getStyleClass().add("balance-tile");
                tokenCard.setMaxHeight(Double.MAX_VALUE);
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
                                        app.adapterFor(net).getBalance(w.address(), "ERC20:" + t.contract())
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
                Button refreshBalance = new Button("↻ Refresh balance");
                refreshBalance.getStyleClass().add("secondary");
                refreshBalance.setOnAction(e -> {
                    loaders.forEach(Runnable::run);
                    app.notifyStatus("Refreshing balance: " + w.name());
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
                    app.notifyStatus("Address copied.");
                });
                Button del = new Button("Delete wallet");
                del.getStyleClass().addAll("secondary", "wallet-delete");
                del.setOnAction(e -> {
                    if (app.confirm("Delete wallet?", "Encrypted key data will be removed from this local database.")) {
                        try {
                            Repository.deleteWallet(w.id());
                            app.forgetKey(w.id());
                            app.showPage("Wallets");
                        } catch (Exception ex) {
                            app.error(rootMessage(ex));
                        }
                    }
                });
                FlowPane walletActions = new FlowPane(8, 8, unlock, copy, refreshBalance);
                walletActions.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(walletActions, Priority.ALWAYS);
                HBox controls = new HBox(12, walletActions, del);
                controls.setAlignment(Pos.CENTER_RIGHT);
                card.getChildren().addAll(head, balances, controls);
                cards.getChildren().add(card);
            }
        } catch (Exception e) {
            cards.getChildren().add(muted(rootMessage(e)));
        }
        v.getChildren().addAll(actions, cards, muted("Portfolio balances show native coin plus tokens in your tracked-token list. Standard RPC cannot enumerate every arbitrary token held by an address; use an indexer or add token contracts to track them."));
        return v;
    }

    private static MenuItem walletMenuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.getStyleClass().add("wallet-menu-item");
        item.setOnAction(e -> action.run());
        return item;
    }

    private void addTrackedToken() {
        try {
            var evmNets = Repository.networks().stream().filter(x -> x.enabled() && "EVM".equalsIgnoreCase(x.family())).toList();
            if (evmNets.isEmpty()) {
                app.error("No active EVM network. Enable one in Networks first.");
                return;
            }
            Repository.Network picked = evmNets.getFirst();
            if (evmNets.size() > 1) {
                List<String> names = evmNets.stream().map(Repository.Network::name).toList();
                ChoiceDialog<String> cd = new ChoiceDialog<>(names.getFirst(), names);
                app.styleDialog(cd);
                cd.setTitle("Add EVM token");
                cd.setHeaderText("Which network is this token contract deployed on?");
                var choice = cd.showAndWait();
                if (choice.isEmpty()) return;
                picked = evmNets.get(names.indexOf(choice.get()));
            }
            final Repository.Network net = picked;
            String contract = app.ask("Add ERC-20 · " + net.name(), "Paste the verified token contract address for " + net.name() + ":", false).orElse("").trim();
            if (contract.isBlank()) return;
            validateEvmAddress(contract);
            ((EvmAdapter) app.adapterFor(net)).tokenInfo(contract).whenComplete((meta, err) -> Platform.runLater(() -> {
                if (err != null) {
                    app.error(rootMessage(err));
                    return;
                }
                if (app.confirm("Verify token", "Network: " + net.name() + (net.testnet() ? "" : "  (MAINNET — real funds)") + "\nName: " + meta.name() + "\nSymbol: " + meta.symbol() + "\nDecimals: " + meta.decimals() + "\nContract: " + meta.contract() + "\n\nAdd this token to your tracked assets?")) {
                    try {
                        Repository.saveToken(net.key(), meta.contract(), meta.symbol(), meta.name(), meta.decimals());
                        app.showPage("Wallets");
                    } catch (Exception ex) {
                        app.error(rootMessage(ex));
                    }
                }
            }));
        } catch (Exception ex) {
            app.error(rootMessage(ex));
        }
    }

    private void createWallet() {
        try {
            String name = app.ask("New wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = app.ask("Encrypt wallet", "Set a strong vault password (you will need it to unlock this key):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            ECKeyPair pair = Keys.createEcKeyPair();
            String pk = Numeric.toHexStringNoPrefix(pair.getPrivateKey());
            Credentials c = Credentials.create(pk);
            var sealed = WalletVault.seal(pass.toCharArray(), pk.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "EVM", c.getAddress(), sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("Wallet created", "Address:\n" + c.getAddress() + "\n\nThe same address works on every EVM network (Sepolia, Avalanche Fuji, Ethereum Mainnet, ...). Fund it with test coins from a faucet for testnets (Sepolia ETH, Fuji AVAX); mainnets need real funds. The private key is encrypted in the local database.");
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void importWallet() {
        try {
            String name = app.ask("Import wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pk = app.ask("Import private key", "Paste an EVM private key. The same key controls this address on every EVM network, including mainnet (real funds):", true).orElse("").trim();
            if (pk.isEmpty()) return;
            pk = Numeric.cleanHexPrefix(pk);
            Credentials c = Credentials.create(pk);
            String pass = app.ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), pk.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "EVM", c.getAddress(), sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("Imported", "Wallet address:\n" + c.getAddress());
        } catch (Exception e) {
            app.error("Invalid key or duplicate wallet. " + rootMessage(e));
        }
    }

    private void unlockWallet(Repository.Wallet w) {
        try {
            String pass = app.ask("Unlock wallet", "Password for " + w.name() + ":", true).orElse("");
            if (pass.isEmpty()) return;
            byte[] raw = WalletVault.open(pass.toCharArray(), w.encrypted(), w.salt());
            String key = new String(raw, StandardCharsets.UTF_8);
            Arrays.fill(raw, (byte) 0);
            app.rememberKey(w.id(), key);
            app.notifyStatus("Unlocked: " + w.name());
            app.alert("Wallet unlocked", "Key is held in application memory until you clear unlocked keys or close the app.");
        } catch (Exception e) {
            app.error("Password incorrect or encrypted key is damaged.");
        }
    }

    private void createBitcoinWallet() {
        try {
            String name = app.ask("New Bitcoin wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            ChoiceDialog<String> cd = new ChoiceDialog<>("Testnet", "Testnet", "Mainnet");
            app.styleDialog(cd);
            cd.setTitle("Bitcoin network");
            cd.setHeaderText("Which Bitcoin network is this wallet for? Mainnet wallets hold REAL funds.");
            var choice = cd.showAndWait();
            if (choice.isEmpty()) return;
            var net = BitcoinKeys.network(!"Mainnet".equals(choice.get()));
            String pass = app.ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var key = BitcoinKeys.generate();
            String wif = BitcoinKeys.toWif(key, net);
            String address = BitcoinKeys.address(key, net);
            var sealed = WalletVault.seal(pass.toCharArray(), wif.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "BITCOIN", address, sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("Bitcoin " + BitcoinKeys.label(net) + " wallet created", BitcoinKeys.label(net) + " address (native SegWit):\n" + address + "\n\n" + (net == org.bitcoinj.base.BitcoinNetwork.MAINNET ? "This is a MAINNET wallet: anything sent here is real Bitcoin. Back up access to this database and your vault password — there is no recovery." : "Fund it with Testnet BTC from a faucet.") + " The WIF private key is encrypted in the local database.");
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void importBitcoinWallet() {
        try {
            String name = app.ask("Import Bitcoin wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String wif = app.ask("Import Bitcoin key", "Paste a compressed WIF private key (testnet 'c…' or mainnet 'K…/L…'). The network is detected from the key:", true).orElse("").trim();
            if (wif.isEmpty()) return;
            var parsed = BitcoinKeys.parseWif(wif);
            String address = BitcoinKeys.address(parsed.key(), parsed.network());
            if (parsed.network() == org.bitcoinj.base.BitcoinNetwork.MAINNET
                    && !app.confirm("Mainnet key", "This is a MAINNET key controlling address:\n" + address + "\n\nIt holds real funds. Import it anyway?"))
                return;
            String pass = app.ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), wif.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "BITCOIN", address, sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("Imported", "Bitcoin " + BitcoinKeys.label(parsed.network()) + " address:\n" + address);
        } catch (Exception e) {
            app.error("Invalid Bitcoin key or duplicate wallet. " + rootMessage(e));
        }
    }

    private void createXrpWallet() {
        try {
            String name = app.ask("New XRP wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = app.ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var g = XrpKeys.generate();
            var sealed = WalletVault.seal(pass.toCharArray(), g.seed().getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "XRP", g.address(), sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("XRP wallet created", "Classic address:\n" + g.address() + "\n\nThe same address works on the XRPL Testnet and Mainnet. A new XRPL account only exists after it receives at least the base reserve (1 XRP); on the testnet use the XRP Testnet Faucet (xrpl.org/resources/dev-tools/xrp-faucets). The family seed is encrypted in the local database — keep your vault password safe, there is no recovery.");
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void importXrpWallet() {
        try {
            String name = app.ask("Import XRP wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String seed = app.ask("Import XRP seed", "Paste the XRP family seed (starts with 's', e.g. sEd… or s…). The same seed controls this address on mainnet (real funds):", true).orElse("").trim();
            if (seed.isEmpty()) return;
            String address = XrpKeys.addressOfSeed(seed);
            String pass = app.ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), seed.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "XRP", address, sealed.ciphertext(), sealed.salt());
            app.showPage("Wallets");
            app.alert("Imported", "XRP address:\n" + address);
        } catch (Exception e) {
            app.error("Invalid XRP seed or duplicate wallet. " + rootMessage(e));
        }
    }

    private void createSolanaWallet() {
        try {
            String name = app.ask("New Solana wallet", "Wallet name:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String pass = app.ask("Encrypt wallet", "Set a strong vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            org.p2p.solanaj.core.Account account = new org.p2p.solanaj.core.Account();
            byte[] secret = account.getSecretKey();
            String encoded = SolanaKeys.encode(secret);
            String address = account.getPublicKey().toBase58();
            var sealed = WalletVault.seal(pass.toCharArray(), encoded.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "SOLANA", address, sealed.ciphertext(), sealed.salt());
            Arrays.fill(secret, (byte) 0);
            app.showPage("Wallets");
            app.alert("Solana wallet created", "Address:\n" + address + "\n\nFund this wallet with Devnet SOL. The 64-byte keypair is encrypted locally.");
        } catch (Exception e) {
            app.error(rootMessage(e));
        }
    }

    private void importSolanaWallet() {
        try {
            String name = app.ask("Import Solana wallet", "Wallet label:", false).orElse("").trim();
            if (name.isEmpty()) return;
            String encoded = app.ask("Import Solana keypair", "Paste the Base58-encoded 64-byte Solana secret keypair. Never use a mainnet wallet:", true).orElse("").trim();
            if (encoded.isEmpty()) return;
            byte[] secret = SolanaKeys.decode(encoded);
            if (secret.length != 64) throw new IllegalArgumentException("Solana secret key must decode to 64 bytes.");
            org.p2p.solanaj.core.Account account = new org.p2p.solanaj.core.Account(secret);
            String address = account.getPublicKey().toBase58();
            String pass = app.ask("Encrypt imported wallet", "Set a vault password (minimum 10 characters):", true).orElse("");
            if (pass.length() < 10) {
                Arrays.fill(secret, (byte) 0);
                app.error("Use a vault password with at least 10 characters.");
                return;
            }
            var sealed = WalletVault.seal(pass.toCharArray(), encoded.getBytes(StandardCharsets.UTF_8));
            Repository.addWallet(name, "SOLANA", address, sealed.ciphertext(), sealed.salt());
            Arrays.fill(secret, (byte) 0);
            app.showPage("Wallets");
            app.alert("Imported", "Solana address:\n" + address);
        } catch (Exception e) {
            app.error("Invalid Solana keypair or duplicate wallet. " + rootMessage(e));
        }
    }
}
