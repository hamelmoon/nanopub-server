package ch.tkuhn.nanopub.server;

import ch.tkuhn.nanopub.server.shared.io.ipfs.api.IPFS;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.MerkleNode;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cid.Cid;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multiaddr.MultiAddress;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multihash.Multihash;
import ch.tkuhn.nanopub.server.shared.peergos.shared.hamt.Champ;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.ByteArrayWrapper;
import ch.tkuhn.nanopub.server.shared.peergos.shared.util.Pair;
import ch.tkuhn.nanopub.server.storage.ipfs.ContentAddressedStorage;
import ch.tkuhn.nanopub.server.storage.ipfs.IPFSStorageImpl;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.JournalT;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

public class DagPerformanceTest {
    private final IPFS ipfs = new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5002"));
    private final int CONST_TEST_COUNT = 100;

    @Test
    public void dagIPLDPerformanceTest() throws IOException {
        FileWriter csvWriter = new FileWriter("dagIPLDPerformanceTest.csv");
        csvWriter.write("Tries,Processing_time" + System.lineSeparator());
        //Generate sample DagCbor object
        Map<String, Cborable> tmp = new LinkedHashMap<>();
        String value = "TEST";
        tmp.put("KEY_SAMPLE", new JournalT("1", value));
        CborObject original = CborObject.CborMap.build(tmp);
        byte[] object = original.toByteArray();
        MerkleNode oldRoot = ipfs.dag.put("dag-cbor", object);
        Cid rootCid = (Cid) oldRoot.hash;

        for (int i = 0; i < CONST_TEST_COUNT; i++) {
            long start = System.currentTimeMillis();
            tmp.put("KEY_SAMPLE" + String.valueOf(i), new JournalT(String.valueOf(i), value));
            MerkleNode newRoot = ipfs.dag.put("dag-cbor", CborObject.CborMap.build(tmp).toByteArray());
            Cid newRootCid = (Cid) newRoot.hash;
            long finish = System.currentTimeMillis();
            long timeElapsed = finish - start;

            csvWriter.write(i + "," + timeElapsed + System.lineSeparator());
            System.out.println("Current Status : " + String.valueOf(timeElapsed) + " ms");
        }


    }

    @Test
    public void HAMTinsertAndRetrieve() throws Exception {

        FileWriter csvWriter = new FileWriter("dagInsertAndRetrieve.csv");
        csvWriter.write("Tries,Processing_time" + System.lineSeparator());

        ContentAddressedStorage storage = new IPFSStorageImpl(ipfs);
        Random r = new Random(28);

        Supplier<Multihash> randomHash = () -> {
            byte[] hash = new byte[32];
            r.nextBytes(hash);
            return new Multihash(Multihash.Type.sha2_256, hash);
        };

        Map<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>> state = new HashMap<>();

        Champ<CborObject.CborMerkleLink> current = Champ.empty(c -> (CborObject.CborMerkleLink) c);
        Multihash currentHash = Multihash.fromBase58(new String(storage.put(current.serialize())));
        int bitWidth = 5;
        int maxCollisions = 3;
        // build a random tree and keep track of the state
        for (int i = 0; i < CONST_TEST_COUNT; i++) {
            long start = System.currentTimeMillis();

            ByteArrayWrapper key = new ByteArrayWrapper(randomHash.get().toBytes());
            Multihash value = randomHash.get();

            Pair<Champ<CborObject.CborMerkleLink>, Multihash> updated = current.put(key, Hashing.sha256()
                            .hashString(key.toString(), StandardCharsets.UTF_8).asBytes(), 0
                    , Optional.of(new CborObject.CborMerkleLink(value)), bitWidth, maxCollisions, storage, currentHash).get();
            Optional<CborObject.CborMerkleLink> result = updated.left.get(key, Hashing.sha256()
                    .hashString(key.toString(), StandardCharsets.UTF_8).asBytes(), 0, bitWidth, storage).get();
            if (!result.equals(Optional.of(new CborObject.CborMerkleLink(value))))
                throw new IllegalStateException("Incorrect result!");
            current = updated.left;
            currentHash = updated.right;
            state.put(key, Optional.of(new CborObject.CborMerkleLink(value)));
            long finish = System.currentTimeMillis();
            long timeElapsed = finish - start;

            csvWriter.write(i + "," + timeElapsed + System.lineSeparator());
            System.out.println("Current Status : " + String.valueOf(timeElapsed) + " ms");
        }

//        // check every mapping
//        for (Map.Entry<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>> e : state.entrySet()) {
//            Optional<CborObject.CborMerkleLink> res = current.get(e.getKey(), Hashing.sha256()
//                    .hashString(e.getKey().toString(), StandardCharsets.UTF_8).asBytes(), 0, bitWidth, storage).get();
//            if (! res.equals(e.getValue()))
//                throw new IllegalStateException("Incorrect state!");
//        }
//
//        long size = current.size(0, storage).get();
//        if (size != CONST_TEST_COUNT)
//            throw new IllegalStateException("Incorrect number of mappings! " + size);
//
//        // change the value for every key and check
//        for (Map.Entry<ByteArrayWrapper, Optional<CborObject.CborMerkleLink>> e : state.entrySet()) {
//            ByteArrayWrapper key = e.getKey();
//            Multihash value = randomHash.get();
//            Optional<CborObject.CborMerkleLink> currentValue = current.get(e.getKey(), Hashing.sha256()
//                    .hashString(e.getKey().toString(), StandardCharsets.UTF_8).asBytes(), 0, bitWidth, storage).get();
//            Pair<Champ<CborObject.CborMerkleLink>, Multihash> updated = current.put(key, Hashing.sha256()
//                            .hashString(e.getKey().toString(), StandardCharsets.UTF_8).asBytes(), 0, currentValue,
//                    Optional.of(new CborObject.CborMerkleLink(value)), bitWidth, maxCollisions, storage, currentHash).get();
//            Optional<CborObject.CborMerkleLink> result = updated.left.get(key, Hashing.sha256()
//                    .hashString(key.toString(), StandardCharsets.UTF_8).asBytes(), 0, bitWidth, storage).get();
//            if (! result.equals(Optional.of(new CborObject.CborMerkleLink(value))))
//                throw new IllegalStateException("Incorrect result!");
//            state.put(key, Optional.of(new CborObject.CborMerkleLink(value)));
//            current = updated.left;
//            currentHash = updated.right;
//        }
    }

    public void HAMTCborStringTest() throws Exception {

        ContentAddressedStorage storage = new IPFSStorageImpl(ipfs);
        Champ<CborObject.CborString> current = Champ.empty(c -> (CborObject.CborString) c);
        Multihash currentHash = Multihash.fromBase58(new String(storage.put(current.serialize())));
        int bitWidth = 5;
        int maxCollisions = 3;
        // build a random tree and keep track of the state
        for (int i = 0; i < CONST_TEST_COUNT; i++) {

            ByteArrayWrapper key = new ByteArrayWrapper(String.valueOf(i).getBytes(StandardCharsets.UTF_8));

            Pair<Champ<CborObject.CborString>, Multihash> updated = current.put(key, Hashing.sha256()
                    .hashString(key.toString(), StandardCharsets.UTF_8).asBytes(), 0, Optional.of(new CborObject.CborString("a")), bitWidth, maxCollisions, storage, currentHash).get();
            Optional<CborObject.CborString> result = updated.left.get(key, Hashing.sha256()
                    .hashString(key.toString(), StandardCharsets.UTF_8).asBytes(), 0, bitWidth, storage).get();
            if (!result.equals(Optional.of(new CborObject.CborString("a"))))
                throw new IllegalStateException("Incorrect result!");
            current = updated.left;
            currentHash = updated.right;
        }
        System.out.println("Done");

    }


}
