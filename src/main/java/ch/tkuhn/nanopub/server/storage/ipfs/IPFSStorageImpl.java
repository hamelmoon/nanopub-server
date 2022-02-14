package ch.tkuhn.nanopub.server.storage.ipfs;

import ch.tkuhn.nanopub.server.ServerConf;
import com.google.common.base.Strings;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.IPFS;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.MerkleNode;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.NamedStreamable;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cid.Cid;
import ch.tkuhn.nanopub.server.shared.io.ipfs.multihash.Multihash;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class IPFSStorageImpl implements ContentAddressedStorage {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    private IPFS ipfs;
    private RetryPolicy<Object> retryPolicy;
    private ServerConf serverConf;
    public IPFSStorageImpl() {
        this.serverConf = new ServerConf();
        this.ipfs = this.initIPFS();
        this.retryPolicy = new RetryPolicy<>()
                .withDelay(Duration.ofSeconds(ServerConf.get().getIpfsRetryDelay()))
                .withMaxRetries(ServerConf.get().getIpfsRetryMaxRetry());
    }

    public IPFSStorageImpl(IPFS ipfs) {
        this.serverConf = new ServerConf();
        this.ipfs = ipfs;
        this.retryPolicy = new RetryPolicy<>()
                .withDelay(Duration.ofSeconds(ServerConf.get().getIpfsRetryDelay()))
                .withMaxRetries(ServerConf.get().getIpfsRetryMaxRetry());
    }

    private IPFS initIPFS() {
        try {
            logger.info("IPFSStorageImpl::initIPFS");
            return new IPFS(ServerConf.get().getIpfsHost(), ServerConf.get().getIpfsPort());

        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }
        return null;
    }

    public IPFS getIPFS() {
        if (this.ipfs == null) {
            return initIPFS();
        } else return this.ipfs;
    }

    public void pin(String cid){
        try {
            if (!Strings.isNullOrEmpty(cid)) {
                Multihash hash = Multihash.fromBase58(cid);
                this.ipfs.pin.add(hash);
            }

        } catch (Exception ex) {
            logger.error("Exception pinning cid " + cid + " on IPFS", ex);
        }
    }

    @Override
    public byte[] put(byte[] value) {
        return Failsafe.with(retryPolicy)
                .onFailure(event -> logger.error("Exception writing file on IPFS after {} attemps.", event.getAttemptCount()))
                .onSuccess(event -> logger.debug("File written on IPFS: [id: {}] ", event.getResult()))
                .get(() -> {
                    try {
//                        List<NamedStreamable> children = new ArrayList<>();
//                        children.add(new NamedStreamable.ByteArrayWrapper(content));
//                        NamedStreamable dir = new NamedStreamable.DirWrapper("nanopublications", children);
                        MerkleNode response = this.getIPFS().add(new NamedStreamable.ByteArrayWrapper(value), false).get(0);
                        this.getIPFS().pin.add(response.hash);
                        return response.hash.toBase58().getBytes(StandardCharsets.UTF_8);
                    } catch (RuntimeException ex) {
                        if (ex.getMessage().contains("timeout")) { //TODO find something more elegant
                            throw new RuntimeException("Exception while writing file on IPFS", ex);
                        } else {
                            throw ex;
                        }
                    } catch (IOException ex) {
                        throw new RuntimeException("Exception while writing file on IPFS", ex);
                    }
                });
    }

    @Override
    public byte[] get(byte[] hash) {
        return Failsafe.with(retryPolicy)
                .onFailure(event -> logger.error("Exception reading file [id: {}] on IPFS after {} attempts.", hash, event.getAttemptCount(), event.getFailure()))
                .onSuccess(event -> logger.debug("File read on IPFS: [id: {}] ", hash))
                .get(() -> {
                    try {
                        byte[] content = this.getIPFS().cat(Cid.decode(new String(hash, StandardCharsets.UTF_8)));
                        return content;
                    } catch (RuntimeException ex) {
                        if (ex.getMessage().contains("timeout")) { //TODO find something more elegant
                            throw new RuntimeException("Exception while fetching file from IPFS [id: {}]", ex);
                        } else {
                            throw ex;
                        }
                    } catch (IOException ex) {
                        throw new RuntimeException("Exception while fetching file from IPFS " + hash, ex);
                    }
                });
    }

    @Override
    public void remove(byte[] key) {
        try {
            if (key.length <= 0) {
                Multihash hash = Multihash.fromBase58(key.toString());
                this.getIPFS().pin.rm(hash);
            }

        } catch (Exception ex) {
            logger.error("Exception unpinning cid " + key + " on IPFS", ex);
        }
    }

    public void clear() {
        throw new NotImplementedException();
    }

    public int size() {
        throw new NotImplementedException();
    }

}
