package ch.tkuhn.nanopub.server.storage.ipfs;

import java.util.HashMap;

public class CollectionMap<K, V> extends HashMap<K, V> {
    //TODO: For production use, you should consider implementing a Btree with indexes.
    public CollectionMap() {
        super(1024, 0.75f);
    }

    @Override
    public V put(K key, V value) {
        return super.put(key, value);
    }
}
