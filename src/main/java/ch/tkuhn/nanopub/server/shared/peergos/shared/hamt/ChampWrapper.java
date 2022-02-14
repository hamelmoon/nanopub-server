package ch.tkuhn.nanopub.server.shared.peergos.shared.hamt;

import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cid.Cid;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multihash.Multihash;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.ByteArrayWrapper;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.MaybeMultihash;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.Pair;
import ch.tkuhn.nanopub.server.storage.ipfs.ContentAddressedStorage;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ChampWrapper<V extends Cborable>
{
    public static final int BIT_WIDTH = 3;
    public static final int MAX_HASH_COLLISIONS_PER_LEVEL = 4;

    public final ContentAddressedStorage storage;
    public final int bitWidth;
    private Pair<Champ<V>, Multihash> root;

    public ChampWrapper(Champ<V> root,
                        Multihash rootHash,
                        ContentAddressedStorage storage,
                        int bitWidth) {
        this.storage = storage;
        this.root = new Pair<>(root, rootHash);
        this.bitWidth = bitWidth;
    }

    public Multihash getRoot() {
        return root.right;
    }

    public static <V extends Cborable> CompletableFuture<ChampWrapper<V>> create(Cid rootHash,
                                                                                 ContentAddressedStorage storage,
                                                                                 Function<Cborable, V> fromCbor) {
        return CompletableFuture.completedFuture(MaybeMultihash.of(Multihash.fromBase58(new String(storage.get(rootHash.toBase58().getBytes(StandardCharsets.UTF_8))))))
                .thenApply(rawOpt -> {
            if (! rawOpt.isPresent())
                throw new IllegalStateException("Champ root not present: " + rootHash);
            return new ChampWrapper<>(Champ.fromCbor(rawOpt, fromCbor), rootHash, storage, BIT_WIDTH);
        });
    }

    public static <V extends Cborable> CompletableFuture<ChampWrapper<V>> create(
                                                                                 ContentAddressedStorage storage,
                                                                                 Function<Cborable, V> fromCbor) {
        Champ<V> newRoot = Champ.empty(fromCbor);
        byte[] raw = newRoot.serialize();
        return CompletableFuture.completedFuture(Hashing.sha256()
                .hashString(new String(raw), StandardCharsets.UTF_8))
                .thenApply(hash -> storage.put(raw))
                .thenApply(put -> new ChampWrapper<V>(newRoot, Multihash.fromBase58(new String(put)), storage, BIT_WIDTH));
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    public CompletableFuture<Optional<V>> get(byte[] rawKey) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return CompletableFuture.completedFuture(Hashing.sha256()
                        .hashString(new String(key.data), StandardCharsets.UTF_8).toString())
                .thenCompose(keyHash -> root.left.get(key, keyHash.getBytes(StandardCharsets.UTF_8), 0, BIT_WIDTH, storage));
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> put(
                                            byte[] rawKey,
                                            Optional<V> existing,
                                            V value) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return CompletableFuture.completedFuture(Hashing.sha256()
                        .hashString(new String(key.data), StandardCharsets.UTF_8).toString())
                .thenCompose(keyHash -> root.left.put(key, keyHash.getBytes(StandardCharsets.UTF_8), 0, Optional.of(value),
                        BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, storage, root.right))
                .thenCompose(newRoot -> CompletableFuture.completedFuture(newRoot.right));
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> remove(
                                               byte[] rawKey,
                                               Optional<V> existing) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return CompletableFuture.completedFuture(Hashing.sha256()
                        .hashString(new String(key.data), StandardCharsets.UTF_8).toString())
                .thenCompose(keyHash -> root.left.put(key, keyHash.getBytes(StandardCharsets.UTF_8), 0, Optional.empty(),
                BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, storage, root.right))
                .thenCompose(newRoot -> CompletableFuture.completedFuture(newRoot.right));
    }

    /**
     *
     * @return number of keys stored in tree
     * @throws IOException
     */
    public CompletableFuture<Long> size() {
        return root.left.size(0, storage);
    }

    /**
     *
     * @return true
     * @throws IOException
     */
    public <T> CompletableFuture<T> applyToAllMappings(T identity,
                                                       BiFunction<T, Pair<ByteArrayWrapper, Optional<V>>, CompletableFuture<T>> consumer) {
        return root.left.applyToAllMappings(identity, consumer, storage);
    }
}
