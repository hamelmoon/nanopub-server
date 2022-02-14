package ch.tkuhn.nanopub.server.storage.ipfs;

import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multihash.Multihash;
import ch.tkuhn.nanopub.server.shared.peergos.shared.hamt.Champ;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.ByteArrayWrapper;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.Pair;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class NanopubMappingCollection {
    public static final int BIT_WIDTH = 5;
    public static final int MAX_HASH_COLLISIONS_PER_LEVEL = 3;
    private final ContentAddressedStorage storage;
    private Champ<CborObject.CborString> current;
    private Pair<Champ<CborObject.CborString>, Multihash> updated;
    private Multihash currentHash;

    public NanopubMappingCollection(long count, ContentAddressedStorage storage) {
        this.storage = storage;
        current = Champ.empty(c -> (CborObject.CborString) c);
        currentHash = Multihash.fromBase58(new String(storage.put(current.serialize())));
        updated = new Pair<>(current, currentHash);
    }

    public Multihash getRoot() {
        return currentHash;
    }


    public String get(String key) {
        ByteArrayWrapper byteKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));
        return updated.left.get(byteKey, Hashing.sha256()
                .hashString(byteKey.toString(), StandardCharsets.UTF_8).asBytes(), 0, BIT_WIDTH, storage).join().get().value;

    }

    public Multihash put(String key, CborObject.CborString value) {
        ByteArrayWrapper bKey = new ByteArrayWrapper(key.getBytes(StandardCharsets.UTF_8));

        updated = current.put(bKey, Hashing.sha256()
                .hashString(bKey.toString(), StandardCharsets.UTF_8).asBytes(), 0, Optional.of(value), BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, storage, currentHash).join();
        current = updated.left;
        currentHash = updated.right;
        return updated.right;
    }


}
