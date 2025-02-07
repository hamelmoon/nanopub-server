package ch.tkuhn.nanopub.server;


import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.JournalT;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.*;


import ch.tkuhn.nanopub.server.shared.io.ipfs.cid.Cid;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multiaddr.MultiAddress;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multihash.Multihash;
import com.google.common.base.CharMatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IpfsTest {

    private final IPFS ipfs = new IPFS(new MultiAddress("/ip4/49.247.26.88/tcp/5001"));
    private final Random r = new Random(33550336); // perfect

    @Test
    public void dag() throws IOException {
        byte[] object = "{\"data\":1234}".getBytes();
        MerkleNode put = ipfs.dag.put("json", object);

        Cid expected = Cid.decode("zdpuB2CbdLrUK5AgZusm4hraisDDDC135ugdmZWvMHhhsSYTb");

        Multihash result = put.hash;
        assertTrue(result.equals(expected), "Correct cid returned");

        byte[] get = ipfs.dag.get(expected);
        assertTrue(Arrays.equals(object, get), "Raw data equal");
    }

    @Test
    public void dagIPLDwithRawFile() throws IOException {
        //Generate sample file
        NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper("nanopub.trig", "trig example".getBytes());
        List<MerkleNode> addParts = ipfs.add(file, true);
        MerkleNode filePart = addParts.get(0);

        //Generate sample DagCbor object
        Map<String, Cborable> tmp = new LinkedHashMap<>();
        String value = "This is JournalT";
        tmp.put("journal-version", new JournalT("1", value));

        //connect ipld and mock trig file
        tmp.put("file", new CborObject.CborMerkleLink(filePart.hash));
        CborObject original = CborObject.CborMap.build(tmp);
        byte[] object = original.toByteArray();
        MerkleNode put = ipfs.dag.put("dag-cbor", object);
//        ipfs.pin.add(put.hash);
        Cid cid = (Cid) put.hash;

        //Access exact value by path
        byte[] get = ipfs.dag.get(cid,"/journal-version/value/");
        assertTrue(CharMatcher.is('\"').trimFrom(new String(get)).contentEquals(value), "Raw data equal");
        Cid expected = Cid.decode("z4EBG9jDKgFzqkwRzj1YG8oq3cSj6NGc2fiESQGVZ98TR5EdFY6");
        assertTrue(cid.toBase58().equals(expected.toBase58()), "Correct cid returned");

        //Access file by path
        byte[] catResult = ipfs.cat(cid,"/file");

        if (!Arrays.equals(catResult, file.getContents()))
            throw new IllegalStateException("Different contents!");
    }

    @Test
    public void singleFileTest() throws IOException {
        NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper("hello.txt", "G'day world! IPFS rocks!".getBytes());
        fileTest(file);
    }

    @Test
    public void wrappedSingleFileTest() throws IOException {
        NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper("hello.txt", "G'day world! IPFS rocks!".getBytes());
        List<MerkleNode> addParts = ipfs.add(file, true);
        MerkleNode filePart = addParts.get(0);
        MerkleNode dirPart = addParts.get(1);
        byte[] catResult = ipfs.cat(filePart.hash);
        byte[] getResult = ipfs.get(filePart.hash);
        if (!Arrays.equals(catResult, file.getContents()))
            throw new IllegalStateException("Different contents!");
        List<Multihash> pinRm = ipfs.pin.rm(dirPart.hash, true);
        if (!pinRm.get(0).equals(dirPart.hash))
            throw new IllegalStateException("Didn't remove file!");
        Object gc = ipfs.repo.gc();
    }

    @Test
    public void dirTest() throws IOException {
        NamedStreamable.DirWrapper dir = new NamedStreamable.DirWrapper("root", Arrays.asList());
        MerkleNode addResult = ipfs.add(dir).get(0);
        List<MerkleNode> ls = ipfs.ls(addResult.hash);
        assertTrue(ls.size() > 0);
    }

    @Test
    public void directoryTest() throws IOException {
        Random rnd = new Random();
        String dirName = "folder" + rnd.nextInt(100);
        Path tmpDir = Files.createTempDirectory(dirName);

        String fileName = "afile" + rnd.nextInt(100);
        Path file = tmpDir.resolve(fileName);
        FileOutputStream fout = new FileOutputStream(file.toFile());
        byte[] fileContents = "IPFS rocks!".getBytes();
        fout.write(fileContents);
        fout.flush();
        fout.close();

        String subdirName = "subdir";
        tmpDir.resolve(subdirName).toFile().mkdir();

        String subfileName = "subdirfile" + rnd.nextInt(100);
        Path subdirfile = tmpDir.resolve(subdirName + "/" + subfileName);
        FileOutputStream fout2 = new FileOutputStream(subdirfile.toFile());
        byte[] file2Contents = "IPFS still rocks!".getBytes();
        fout2.write(file2Contents);
        fout2.flush();
        fout2.close();

        List<MerkleNode> addParts = ipfs.add(new NamedStreamable.FileWrapper(tmpDir.toFile()));
        MerkleNode addResult = addParts.get(addParts.size() - 1);
        List<MerkleNode> lsResult = ipfs.ls(addResult.hash);
        if (lsResult.size() != 1)
            throw new IllegalStateException("Incorrect number of objects in ls!");
        if (!lsResult.get(0).equals(addResult))
            throw new IllegalStateException("Object not returned in ls!");
        byte[] catResult = ipfs.cat(addResult.hash, "/" + fileName);
        if (!Arrays.equals(catResult, fileContents))
            throw new IllegalStateException("Different contents!");

        byte[] catResult2 = ipfs.cat(addResult.hash, "/" + subdirName + "/" + subfileName);
        if (!Arrays.equals(catResult2, file2Contents))
            throw new IllegalStateException("Different contents!");
    }

    //    @Test
    public void largeFileTest() throws IOException {
        byte[] largerData = new byte[100 * 1024 * 1024];
        new Random(1).nextBytes(largerData);
        NamedStreamable.ByteArrayWrapper largeFile = new NamedStreamable.ByteArrayWrapper("nontrivial.txt", largerData);
        fileTest(largeFile);
    }

    //    @Test
    public void hugeFileStreamTest() throws IOException {
        byte[] hugeData = new byte[1000 * 1024 * 1024];
        new Random(1).nextBytes(hugeData);
        NamedStreamable.ByteArrayWrapper largeFile = new NamedStreamable.ByteArrayWrapper("massive.txt", hugeData);
        MerkleNode addResult = ipfs.add(largeFile).get(0);
        InputStream in = ipfs.catStream(addResult.hash);

        byte[] res = new byte[hugeData.length];
        int offset = 0;
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) >= 0) {
            try {
                System.arraycopy(buf, 0, res, offset, r);
                offset += r;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (!Arrays.equals(res, hugeData))
            throw new IllegalStateException("Different contents!");
    }

    @Test
    public void hostFileTest() throws IOException {
        Path tempFile = Files.createTempFile("IPFS", "tmp");
        BufferedWriter w = new BufferedWriter(new FileWriter(tempFile.toFile()));
        w.append("Some data");
        w.flush();
        w.close();
        NamedStreamable hostFile = new NamedStreamable.FileWrapper(tempFile.toFile());
        fileTest(hostFile);
    }

    @Test
    public void hashOnly() throws IOException {
        byte[] data = randomBytes(4096);
        NamedStreamable file = new NamedStreamable.ByteArrayWrapper(data);
        MerkleNode addResult = ipfs.add(file, false, true).get(0);
        List<Multihash> local = ipfs.refs.local();
        if (local.contains(addResult.hash))
            throw new IllegalStateException("Object shouldn't be present!");
    }

    public void fileTest(NamedStreamable file) throws IOException {
        MerkleNode addResult = ipfs.add(file).get(0);
        byte[] catResult = ipfs.cat(addResult.hash);
        byte[] getResult = ipfs.get(addResult.hash);
        if (!Arrays.equals(catResult, file.getContents()))
            throw new IllegalStateException("Different contents!");
        List<Multihash> pinRm = ipfs.pin.rm(addResult.hash, true);
        if (!pinRm.get(0).equals(addResult.hash))
            throw new IllegalStateException("Didn't remove file!");
        Object gc = ipfs.repo.gc();
    }

    @Test
    public void pinTest() throws IOException {
        MerkleNode file = ipfs.add(new NamedStreamable.ByteArrayWrapper("some data".getBytes())).get(0);
        Multihash hash = file.hash;
        Map<Multihash, Object> ls1 = ipfs.pin.ls(IPFS.PinType.all);
        boolean pinned = ls1.containsKey(hash);
        List<Multihash> rm = ipfs.pin.rm(hash);
        // second rm should not throw a http 500, but return an empty list
//            List<Multihash> rm2 = ipfs.pin.rm(hash);
        List<Multihash> add2 = ipfs.pin.add(hash);
        // adding something already pinned should succeed
        List<Multihash> add3 = ipfs.pin.add(hash);
        Map<Multihash, Object> ls = ipfs.pin.ls(IPFS.PinType.recursive);
        ipfs.repo.gc();
        // object should still be present after gc
        Map<Multihash, Object> ls2 = ipfs.pin.ls(IPFS.PinType.recursive);
        boolean stillPinned = ls2.containsKey(hash);
        assertTrue(pinned && stillPinned, "Pinning works");
    }

    @Test
    public void pinUpdate() throws IOException {
        MerkleNode child1 = ipfs.add(new NamedStreamable.ByteArrayWrapper("some data".getBytes())).get(0);
        Multihash hashChild1 = child1.hash;
        System.out.println("child1: " + hashChild1);

        CborObject.CborMerkleLink root1 = new CborObject.CborMerkleLink(hashChild1);
        MerkleNode root1Res = ipfs.block.put(Collections.singletonList(root1.toByteArray()), Optional.of("cbor")).get(0);
        System.out.println("root1: " + root1Res.hash);
        ipfs.pin.add(root1Res.hash);

        CborObject.CborList root2 = new CborObject.CborList(Arrays.asList(new CborObject.CborMerkleLink(hashChild1), new CborObject.CborLong(42)));
        MerkleNode root2Res = ipfs.block.put(Collections.singletonList(root2.toByteArray()), Optional.of("cbor")).get(0);
        List<Multihash> update = ipfs.pin.update(root1Res.hash, root2Res.hash, true);

        Map<Multihash, Object> ls = ipfs.pin.ls(IPFS.PinType.all);
        boolean childPresent = ls.containsKey(hashChild1);
        if (!childPresent)
            throw new IllegalStateException("Child not present!");

        ipfs.repo.gc();
        Map<Multihash, Object> ls2 = ipfs.pin.ls(IPFS.PinType.all);
        boolean childPresentAfterGC = ls2.containsKey(hashChild1);
        if (!childPresentAfterGC)
            throw new IllegalStateException("Child not present!");
    }


    @Test
    public void indirectPinTest() throws IOException {
        Multihash EMPTY = ipfs.object._new(Optional.empty()).hash;
        MerkleNode data = ipfs.object.patch(EMPTY, "set-data", Optional.of("childdata".getBytes()), Optional.empty(), Optional.empty());
        Multihash child = data.hash;

        MerkleNode tmp1 = ipfs.object.patch(EMPTY, "set-data", Optional.of("parent1_data".getBytes()), Optional.empty(), Optional.empty());
        Multihash parent1 = ipfs.object.patch(tmp1.hash, "add-link", Optional.empty(), Optional.of(child.toString()), Optional.of(child)).hash;
        ipfs.pin.add(parent1);

        MerkleNode tmp2 = ipfs.object.patch(EMPTY, "set-data", Optional.of("parent2_data".getBytes()), Optional.empty(), Optional.empty());
        Multihash parent2 = ipfs.object.patch(tmp2.hash, "add-link", Optional.empty(), Optional.of(child.toString()), Optional.of(child)).hash;
        ipfs.pin.add(parent2);
        ipfs.pin.rm(parent1, true);

        Map<Multihash, Object> ls = ipfs.pin.ls(IPFS.PinType.all);
        boolean childPresent = ls.containsKey(child);
        if (!childPresent)
            throw new IllegalStateException("Child not present!");

        ipfs.repo.gc();
        Map<Multihash, Object> ls2 = ipfs.pin.ls(IPFS.PinType.all);
        boolean childPresentAfterGC = ls2.containsKey(child);
        if (!childPresentAfterGC)
            throw new IllegalStateException("Child not present!");
    }

    @Test
    public void objectPatch() throws IOException {
        MerkleNode obj = ipfs.object._new(Optional.empty());
        Multihash base = obj.hash;
        // link tests
        String linkName = "alink";
        MerkleNode addLink = ipfs.object.patch(base, "add-link", Optional.empty(), Optional.of(linkName), Optional.of(base));
        MerkleNode withLink = ipfs.object.get(addLink.hash);
        if (withLink.links.size() != 1 || !withLink.links.get(0).hash.equals(base) || !withLink.links.get(0).name.get().equals(linkName))
            throw new RuntimeException("Added link not correct!");
        MerkleNode rmLink = ipfs.object.patch(addLink.hash, "rm-link", Optional.empty(), Optional.of(linkName), Optional.empty());
        if (!rmLink.hash.equals(base))
            throw new RuntimeException("Adding not inverse of removing link!");

        // data tests
//            byte[] data = "some random textual data".getBytes();
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        MerkleNode patched = ipfs.object.patch(base, "set-data", Optional.of(data), Optional.empty(), Optional.empty());
        byte[] patchedResult = ipfs.object.data(patched.hash);
        if (!Arrays.equals(patchedResult, data))
            throw new RuntimeException("object.patch: returned data != stored data!");

        MerkleNode twicePatched = ipfs.object.patch(patched.hash, "append-data", Optional.of(data), Optional.empty(), Optional.empty());
        byte[] twicePatchedResult = ipfs.object.data(twicePatched.hash);
        byte[] twice = new byte[2 * data.length];
        for (int i = 0; i < 2; i++)
            System.arraycopy(data, 0, twice, i * data.length, data.length);
        if (!Arrays.equals(twicePatchedResult, twice))
            throw new RuntimeException("object.patch: returned data after append != stored data!");

    }

    @Test
    public void refsTest() throws IOException {
        List<Multihash> local = ipfs.refs.local();
        for (Multihash ref : local) {
            Object refs = ipfs.refs(ref, false);
        }
    }

    @Test
    public void objectTest() throws IOException {
        MerkleNode _new = ipfs.object._new(Optional.empty());
        Multihash pointer = Multihash.fromBase58("QmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB");
        MerkleNode object = ipfs.object.get(pointer);
        List<MerkleNode> newPointer = ipfs.object.put(Arrays.asList(object.toJSONString().getBytes()));
        List<MerkleNode> newPointer2 = ipfs.object.put("json", Arrays.asList(object.toJSONString().getBytes()));
        MerkleNode links = ipfs.object.links(pointer);
        byte[] data = ipfs.object.data(pointer);
        Map stat = ipfs.object.stat(pointer);
    }

    @Test
    public void blockTest() throws IOException {
        MerkleNode pointer = new MerkleNode("QmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB");
        Map stat = ipfs.block.stat(pointer.hash);
        byte[] object = ipfs.block.get(pointer.hash);
        List<MerkleNode> newPointer = ipfs.block.put(Arrays.asList("Some random data...".getBytes()));
    }

    @Test
    public void bulkBlockTest() throws IOException {
        CborObject cbor = new CborObject.CborString("G'day IPFS!");
        byte[] raw = cbor.toByteArray();
        List<MerkleNode> bulkPut = ipfs.block.put(Arrays.asList(raw, raw, raw, raw, raw), Optional.of("cbor"));
        List<Multihash> hashes = bulkPut.stream().map(m -> m.hash).collect(Collectors.toList());
        byte[] result = ipfs.block.get(hashes.get(0));
        System.out.println();
    }

    @Test
    public void pubsubSynchronous() throws Exception {
        String topic = "topic" + System.nanoTime();
        List<Map<String, Object>> res = Collections.synchronizedList(new ArrayList<>());
        new Thread(() -> {
            try {
                ipfs.pubsub.sub(topic, res::add, t -> t.printStackTrace());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).start();

        long start = System.currentTimeMillis();
        for (int i = 1; i < 100; ) {
            long t1 = System.currentTimeMillis();
            ipfs.pubsub.pub(topic, "Hello!");
            if (res.size() >= i) {
                long t2 = System.currentTimeMillis();
                System.out.println("pub => sub took " + (t2 - t1));
                i++;
            }
        }
        long duration = System.currentTimeMillis() - start;
        assertTrue(duration < 1000, "Fast synchronous pub-sub");
    }

    @Test
    public void pubsub() throws Exception {
        String topic = "topic" + System.nanoTime();
        Stream<Map<String, Object>> sub = ipfs.pubsub.sub(topic);
        Thread.sleep(100); // There's a race condition in ipfs
        String data = "Hello!";
        Object pub = ipfs.pubsub.pub(topic, data);
        Object pub2 = ipfs.pubsub.pub(topic, "G'day");
        List<Map> results = sub.limit(2).collect(Collectors.toList());
        assertTrue(!results.get(0).equals(Collections.emptyMap()));
    }

    private static String toEscapedHex(byte[] in) throws IOException {
        StringBuilder res = new StringBuilder();
        for (byte b : in) {
            res.append("\\x");
            res.append(String.format("%02x", b & 0xFF));
        }
        return res.toString();
    }


    /**
     * Test that merkle links as a root object are followed during recursive pins
     */
    @Test
    public void rootMerkleLink() throws IOException {
        Random r = new Random();
        CborObject.CborByteArray target = new CborObject.CborByteArray(("g'day IPFS!" + r.nextInt()).getBytes());
        byte[] rawTarget = target.toByteArray();
        MerkleNode block1 = ipfs.block.put(Arrays.asList(rawTarget), Optional.of("cbor")).get(0);
        Multihash block1Hash = block1.hash;
        byte[] retrievedObj1 = ipfs.block.get(block1Hash);
        assertTrue(Arrays.equals(retrievedObj1, rawTarget), "get inverse of put");

        CborObject.CborMerkleLink cbor2 = new CborObject.CborMerkleLink(block1.hash);
        byte[] obj2 = cbor2.toByteArray();
        MerkleNode block2 = ipfs.block.put(Arrays.asList(obj2), Optional.of("cbor")).get(0);
        byte[] retrievedObj2 = ipfs.block.get(block2.hash);
        assertTrue(Arrays.equals(retrievedObj2, obj2), "get inverse of put");

        List<Multihash> add = ipfs.pin.add(block2.hash);
        ipfs.repo.gc();
        ipfs.repo.gc();

        byte[] bytes = ipfs.block.get(block1.hash);
        assertTrue(Arrays.equals(bytes, rawTarget), "same contents after GC");
        // These commands can be used to reproduce this on the command line
        String reproCommand1 = "printf \"" + toEscapedHex(rawTarget) + "\" | ipfs block put --format=cbor";
        String reproCommand2 = "printf \"" + toEscapedHex(obj2) + "\" | ipfs block put --format=cbor";
        System.out.println();
    }

    /**
     * Test that a cbor null is allowed as an object root
     */
    @Test
    public void rootNull() throws IOException {
        CborObject.CborNull cbor = new CborObject.CborNull();
        byte[] obj = cbor.toByteArray();
        MerkleNode block = ipfs.block.put(Arrays.asList(obj), Optional.of("cbor")).get(0);
        byte[] retrievedObj = ipfs.block.get(block.hash);
        assertTrue(Arrays.equals(retrievedObj, obj), "get inverse of put");

        List<Multihash> add = ipfs.pin.add(block.hash);
        ipfs.repo.gc();
        ipfs.repo.gc();

        // These commands can be used to reproduce this on the command line
        String reproCommand1 = "printf \"" + toEscapedHex(obj) + "\" | ipfs block put --format=cbor";
        System.out.println();
    }

    /**
     * Test that merkle links in a cbor list are followed during recursive pins
     */
    @Test
    public void merkleLinkInList() throws IOException {
        Random r = new Random();
        CborObject.CborByteArray target = new CborObject.CborByteArray(("g'day IPFS!" + r.nextInt()).getBytes());
        byte[] rawTarget = target.toByteArray();
        MerkleNode targetRes = ipfs.block.put(Arrays.asList(rawTarget), Optional.of("cbor")).get(0);

        CborObject.CborMerkleLink link = new CborObject.CborMerkleLink(targetRes.hash);
        CborObject.CborList source = new CborObject.CborList(Arrays.asList(link));
        byte[] rawSource = source.toByteArray();
        MerkleNode sourceRes = ipfs.block.put(Arrays.asList(rawSource), Optional.of("cbor")).get(0);

        List<Multihash> add = ipfs.pin.add(sourceRes.hash);
        ipfs.repo.gc();
        ipfs.repo.gc();

        byte[] bytes = ipfs.block.get(targetRes.hash);
        assertTrue(Arrays.equals(bytes, rawTarget), "same contents after GC");
        // These commands can be used to reproduce this on the command line
        String reproCommand1 = "printf \"" + toEscapedHex(rawTarget) + "\" | ipfs block put --format=cbor";
        String reproCommand2 = "printf \"" + toEscapedHex(rawSource) + "\" | ipfs block put --format=cbor";
    }

    @Test
    public void fileContentsTest() throws IOException {
        ipfs.repo.gc();
        List<Multihash> local = ipfs.refs.local();
        for (Multihash hash : local) {
            try {
                Map ls = ipfs.file.ls(hash);
                return;
            } catch (Exception e) {
            } // non unixfs files will throw an exception here
        }
    }

    @Test
    public void nameTest() throws IOException {
        MerkleNode pointer = new MerkleNode("QmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB");
        Map pub = ipfs.name.publish(pointer.hash);
        String name = "key" + System.nanoTime();
        Object gen = ipfs.key.gen(name, Optional.of("rsa"), Optional.of("2048"));
        Map mykey = ipfs.name.publish(pointer.hash, Optional.of(name));
        String resolved = ipfs.name.resolve(Multihash.fromBase58((String) pub.get("Name")));
    }


    public void mountTest() throws IOException {
        Map mount = ipfs.mount(null, null);
    }

    @Test
    public void localId() throws Exception {
        Map id = ipfs.id();
        System.out.println();
    }

    @Test
    public void statsTest() throws IOException {
        Map stats = ipfs.stats.bw();
    }

    public void resolveTest() throws IOException {
        Multihash hash = Multihash.fromBase58("QmatmE9msSfkKxoffpHwNLNKgwZG8eT9Bud6YoPab52vpy");
        Map res = ipfs.resolve("ipns", hash, false);
    }

    @Test
    public void swarmTest() throws IOException {
        MultiAddress multiaddr = new MultiAddress("/ip4/127.0.0.1/tcp/4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ");
        Map connect = ipfs.swarm.connect(multiaddr);
        Map disconnect = ipfs.swarm.disconnect(multiaddr);
        Map<Multihash, List<MultiAddress>> addrs = ipfs.swarm.addrs();
        if (addrs.size() > 0) {
            boolean contacted = addrs.keySet().stream()
                    .anyMatch(target -> {
                        try {
                            Map id = ipfs.id(target);
                            Map ping = ipfs.ping(target);
                            return true;
                        } catch (Exception e) {
                            // not all nodes have to be contactable
                            return false;
                        }
                    });
            if (!contacted)
                throw new IllegalStateException("Couldn't contact any node!");
        }
        List<Peer> peers = ipfs.swarm.peers();
        System.out.println(peers);
    }

    @Test
    public void bootstrapTest() throws IOException {
        List<MultiAddress> bootstrap = ipfs.bootstrap.list();
        System.out.println(bootstrap);
        List<MultiAddress> rm = ipfs.bootstrap.rm(bootstrap.get(0), false);
        List<MultiAddress> add = ipfs.bootstrap.add(bootstrap.get(0));
        System.out.println();
    }

    @Test
    public void diagTest() throws IOException {
        Map config = ipfs.config.show();
        String val = (String) ipfs.config.get("Datastore.GCPeriod");
        Map setResult = ipfs.config.set("Datastore.GCPeriod", val);
        ipfs.config.replace(new NamedStreamable.ByteArrayWrapper(JSONParser.toString(config).getBytes()));
//            Object log = ipfs.log();
        String sys = ipfs.diag.sys();
        String cmds = ipfs.diag.cmds();
    }

    @Test
    public void toolsTest() throws IOException {
        String version = ipfs.version();
        int major = Integer.parseInt(version.split("\\.")[0]);
        int minor = Integer.parseInt(version.split("\\.")[1]);
        assertTrue(major >= 0 && minor >= 4);     // Requires at least 0.4.0
        Map commands = ipfs.commands();
    }

    // this api is disabled until deployment over IPFS is enabled
    public void updateTest() throws IOException {
        Object check = ipfs.update.check();
        Object update = ipfs.update();
    }

    private byte[] randomBytes(int len) {
        byte[] res = new byte[len];
        r.nextBytes(res);
        return res;
    }
}