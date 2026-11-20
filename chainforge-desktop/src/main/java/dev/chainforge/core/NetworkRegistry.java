package dev.chainforge.core;

import dev.chainforge.evm.EvmSepoliaAdapter;

import java.util.*;

public final class NetworkRegistry {
    private final Map<String, ChainAdapter> adapters = new LinkedHashMap<>();

    public NetworkRegistry() {
        register(new EvmSepoliaAdapter());
    }

    public void register(ChainAdapter a) {
        adapters.put(a.networkKey(), a);
    }

    public Optional<ChainAdapter> get(String key) {
        return Optional.ofNullable(adapters.get(key));
    }

    public Collection<ChainAdapter> all() {
        return Collections.unmodifiableCollection(adapters.values());
    }
}