package dev.chainforge.ui;

import dev.chainforge.core.AdapterFactory;
import dev.chainforge.core.ChainAdapter;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static dev.chainforge.ui.NetworkSupport.nativeAssetFor;
import static dev.chainforge.ui.NetworkSupport.walletMatchesNetwork;
import static dev.chainforge.ui.ViewSupport.localTime;
import static dev.chainforge.ui.ViewSupport.rootMessage;
import static dev.chainforge.ui.ViewSupport.safe;

final class TransactionHistory {
    private final Function<Repository.Network, ChainAdapter> adapterFor;

    TransactionHistory(Function<Repository.Network, ChainAdapter> adapterFor) {
        this.adapterFor = adapterFor;
    }

    record SyncResult(int updated, int checked, List<String> problems) {
    }

    boolean pollPending() {
        boolean changed = false;
        try {
            Map<String, ChainAdapter> byKey = new HashMap<>();
            for (var net : Repository.networks()) {
                try {
                    if (AdapterFactory.isSupported(net)) byKey.put(net.key(), adapterFor.apply(net));
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
                    String fee = result.fee() != null ? result.fee() : tx.fee();
                    if (!next.equals(current) || !Objects.equals(fee, tx.fee()) || !Objects.equals(result.error(), tx.error())) {
                        changed |= Repository.updatePolledTransaction(tx, next, fee, result.error());
                    }
                } catch (Exception ignored) {/* transient RPC failure: keep the last known status */}
            }
        } catch (Exception ignored) {
        }
        return changed;
    }

    SyncResult syncIncoming() {
        int changed = 0, checked = 0;
        boolean scanComplete = false;
        List<String> problems = new ArrayList<>();
        Set<String> successfulNetworks = new HashSet<>(), failedNetworks = new HashSet<>();
        try {
            var networks = Repository.networks();
            for (var w : Repository.wallets()) {
                for (var net : incomingHistoryNetworks(w, networks)) {
                    if (!AdapterFactory.isSupported(net)) continue;
                    checked++;
                    int previousErrors = problems.size();
                    try {
                        var known = Repository.knownIncomingHashes(net.key(), w.address());
                        ChainAdapter adapter = adapterFor.apply(net);
                        var incoming = adapter instanceof EvmAdapter evm
                                ? evm.fetchIncoming(w.address(), Repository.tokens(net.key()).stream()
                                .map(t -> new EvmAdapter.TokenInfo(t.contract(), t.symbol(), t.decimals(), t.name()))
                                .toList(), ex -> problems.add(w.name() + " · " + net.name() + ": " + ex.getMessage()))
                                : adapter.fetchIncoming(w.address(), known);
                        for (var t : incoming.join()) {
                            if (t.from() != null && t.from().equalsIgnoreCase(w.address()))
                                continue; // sent to itself
                            String asset = ChainAdapter.NATIVE.equals(t.asset()) ? nativeAssetFor(net) : t.asset();
                            if (Repository.addIncomingTx(net.key(), t.hash(), t.from(), t.to(), t.amount(), asset, t.fee(), t.status(), t.timestamp(), t.tokenSymbol()))
                                changed++;
                        }
                    } catch (Exception ex) {
                        problems.add(w.name() + " · " + net.name() + ": " + rootMessage(ex));
                    }
                    if (problems.size() == previousErrors) successfulNetworks.add(net.key());
                    else failedNetworks.add(net.key());
                    Thread.sleep(400);
                }
            }
            scanComplete = true;
        } catch (Exception ex) {
            problems.add(rootMessage(ex));
        }
        if (scanComplete) {
            successfulNetworks.removeAll(failedNetworks);
            try {
                Repository.recordIncomingSync(successfulNetworks);
            } catch (Exception ex) {
                problems.add("Could not save sync history: " + rootMessage(ex));
            }
        }
        return new SyncResult(changed, checked, List.copyOf(problems));
    }

    List<String> syncDetails() throws Exception {
        var wallets = Repository.wallets();
        var history = Repository.incomingSyncHistory();
        Set<String> eligible = new HashSet<>();
        var networks = Repository.networks();
        for (var wallet : wallets)
            for (var network : incomingHistoryNetworks(wallet, networks)) eligible.add(network.key());
        var lines = networks.stream().filter(n -> eligible.contains(n.key()))
                .map(n -> {
                    var when = history.get(n.key());
                    return n.name() + "  ·  " + (when == null ? "No successful sync yet" : localTime(when));
                }).toList();
        return lines;
    }

    int lookupIncoming(Repository.Network selected, String value) throws Exception {
        var wallets = Repository.wallets().stream().filter(w -> walletMatchesNetwork(w, selected))
                .map(Repository.Wallet::address).toList();
        var tokens = Repository.tokens(selected.key()).stream()
                .map(t -> new EvmAdapter.TokenInfo(t.contract(), t.symbol(), t.decimals(), t.name())).toList();
        var evm = (EvmAdapter) adapterFor.apply(selected);
        var found = evm.lookupIncoming(value, wallets, tokens);
        if (found.isEmpty()) throw new IllegalStateException(
                "No incoming native transfer or tracked token transfer matched a saved wallet on this network.");
        int changed = 0;
        for (var row : found) {
            String asset = ChainAdapter.NATIVE.equals(row.asset()) ? nativeAssetFor(selected) : row.asset();
            if (Repository.addIncomingTx(selected.key(), row.hash(), row.from(), row.to(), row.amount(),
                    asset, row.fee(), row.status(), row.timestamp(), row.tokenSymbol())) changed++;
        }
        return changed;
    }

    private List<Repository.Network> incomingHistoryNetworks(Repository.Wallet wallet, List<Repository.Network> networks) {
        return networks.stream()
                .filter(network -> network.enabled() || "ethereum-mainnet".equals(network.key()))
                .filter(network -> walletMatchesNetwork(wallet, network))
                .toList();
    }
}
