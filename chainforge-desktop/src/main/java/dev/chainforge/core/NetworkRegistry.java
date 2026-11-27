package dev.chainforge.core;

import java.util.*;

public final class NetworkRegistry {
    private final Map<String, ChainAdapter> adapters = new LinkedHashMap<>();

    /** Adapters are created per configured network by {@link AdapterFactory}; register extras here if needed. */
    public NetworkRegistry() {
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