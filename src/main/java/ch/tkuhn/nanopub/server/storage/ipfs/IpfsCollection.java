package ch.tkuhn.nanopub.server.storage.ipfs;

import ch.tkuhn.nanopub.server.ServerConf;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.IPFS;
import ch.tkuhn.nanopub.server.shared.io.ipfs.api.MerkleNode;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cid.Cid;
import ch.tkuhn.nanopub.server.storage.CollectionTypeEnum;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.JournalT;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.PeersT;
import ch.tkuhn.nanopub.server.utils.CaselessProperties;
import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

//inspired by https://github.com/aditsachde/hamt-rs/tree/5365d8e36ad20d7ff2d466001731322587b03e73
//TODO: IPLD Modeling
public class IpfsCollection {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    //TODO: It should improve other elegant ways
    static CollectionMap<String, PeersT> peerList;
    static CollectionMap<String, JournalT> journalList;
    static CollectionMap<String, CborObject.CborString> packagedList;
    static NanopubMappingCollection nanopubs;
    private IPFS ipfs;
    private boolean noPin;
    private Properties properties;

    public IpfsCollection(IPFSStorageImpl ipfsImpl, boolean noPin) {
        Objects.requireNonNull(ipfsImpl);
        this.ipfs = ipfsImpl.getIPFS();
        this.noPin = noPin;
        this.initRootCidFile();
        peerList = this.<PeersT>init("peers", PeersT.class);
        journalList = this.<JournalT>init("journal", JournalT.class);
        packagedList = this.<CborObject.CborString>init("packagedFile", CborObject.CborString.class);
        nanopubs = new NanopubMappingCollection(0, ipfsImpl);
    }


    private void initRootCidFile() {
        if (Files.notExists(Paths.get(ServerConf.get().getIpfsRootCidPropertiesPath()))) {
            File file = new File(ServerConf.get().getIpfsRootCidPropertiesPath());
            try {
                file.getParentFile().mkdirs();
                logger.info("dir genereted");
                file.createNewFile();
                logger.info("file genereted");
            } catch (IOException e) {
                logger.error(e.getMessage());
            }

        }
    }


    synchronized public CollectionMap<String, PeersT> getPeerCollection() {
        if (peerList == null) {
            peerList = new CollectionMap<>();
        }
        return peerList;
    }

    synchronized public CollectionMap<String, JournalT> getJournalCollection() {
        if (journalList == null) {
            journalList = new CollectionMap<>();
        }
        return journalList;
    }

    synchronized public CollectionMap<String, CborObject.CborString> getPackagedListCollection() {
        if (packagedList == null) {
            packagedList = new CollectionMap<>();
        }
        return packagedList;
    }

    synchronized public String getNanopubCollection(String key) {
        return nanopubs.get(key);
    }

    synchronized public Boolean containsNanopubCollection(String key) {
        try {
            return StringUtils.isNotEmpty(nanopubs.get(key));
        } catch (Exception e) {
            return false;
        }

    }

    public String getNanopubCollectionByIPLD(String artifactsCode) {
        String cidString = getRootCid(CollectionTypeEnum.Nanopubs.toString());

        if (!Strings.isNullOrEmpty(cidString)) {
            try {
                return CharMatcher.is('\"').trimFrom(new String(ipfs.dag.get(Cid.decode(cidString), "/" + artifactsCode)));
            } catch (IOException e) {
                logger.error("getNanopubCollectionByIPLD::" + e.getMessage());
            }
        }
        return null;
    }


    public PeersT setPeerCollection(String key, PeersT value) {
        peerList.put(key, value);
        resolve(CollectionTypeEnum.Peers.toString(), peerList);
        return value;
    }

    public JournalT setJournalCollection(String key, JournalT value) {
        journalList.put(key, value);
        resolve(CollectionTypeEnum.Journal.toString(), journalList);
        return value;
    }

    public String setPackagedListCollection(String key, String value) {
        packagedList.put(key, new CborObject.CborString(value));
        resolve(CollectionTypeEnum.PackagedFile.toString(), packagedList);
        return value;
    }

    public String setNanopubCollection(String key, String value) {
        String hash = nanopubs.put(key, new CborObject.CborString(value)).toBase58();
        updatePin(properties.getProperty(CollectionTypeEnum.Nanopubs.toString()), hash, true);
        setRootCid(CollectionTypeEnum.Nanopubs.toString(), hash);
        return value;
    }

    private <V> CollectionMap<String, V> init(String key, Class<V> clazz) {
        try {
            String cidString = getRootCid(key);
            if (Strings.isNullOrEmpty(cidString)) {
                return new CollectionMap<String, V>();
            }
            //SET KV to IPLD
            String body = new String(ipfs.dag.get(Cid.decode(cidString)));
            if (Strings.isNullOrEmpty(cidString) || Strings.isNullOrEmpty(body)) {
                return new CollectionMap<String, V>();
            }
            return new Gson().fromJson(body, TypeToken.getParameterized(CollectionMap.class, String.class, clazz).getType());
        } catch (Exception e) {
            logger.error("init error :" + e.getMessage());
        }
        return new CollectionMap<String, V>();
    }

    private void resolve(String collectionType, Object obj) {
        try {
            //TODO: Implement CRDT based KV Store
            //Thus, rootcid.properties file will be manage the root cid of the collection
            CborObject original = CborObject.CborMap.build((Map<String, Cborable>) obj);
            MerkleNode merkleNode = ipfs.dag.put("dag-cbor", original.toByteArray());
            updatePin(properties.getProperty(collectionType), merkleNode.hash.toString(), true);
            setRootCid(collectionType, merkleNode.hash.toString());
            //ipfs.repo.gc();
        } catch (Exception e) {
            logger.error("resolve error: " + e.getMessage());
        }
    }

    private void updatePin(String exsisting, String modified, boolean unpin) {
        try {
            if (!Strings.isNullOrEmpty(exsisting) && !Strings.isNullOrEmpty(modified)) {
                this.ipfs.pin.update(Cid.decode(exsisting), Cid.decode(modified), unpin);

                //TODO: ipfs daemon --enable-gc
                //ipfs.repo.gc();
            }
        } catch (Exception e) {
            logger.debug("update CID {} to {} on IPFS", exsisting.toString(), modified.toString());
        }
    }


    private synchronized String getRootCid(String key) {

        try {
            if (properties == null) {
                try (InputStream in = new FileInputStream(ServerConf.get().getIpfsRootCidPropertiesPath())) {
                    properties = new CaselessProperties();
                    properties.load(in);

                }
            }
            return properties.getProperty(key);

        } catch (IOException e) {
            logger.error("getRootCid:" + e.getMessage());
        }
        return null;
    }

    private synchronized void setRootCid(String propertyName, String rootCid) {
        properties.setProperty(propertyName, rootCid);

        try (FileOutputStream fileOutputStream = new FileOutputStream(ServerConf.get().getIpfsRootCidPropertiesPath())) {
            properties.forEach((key, value) -> {
                try {
                    fileOutputStream.write(String.format("%s=%s%s", key, value, System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    logger.error("properties forEach" + e.getMessage());
                }
            });

        } catch (FileNotFoundException fileNotFoundException) {
            logger.error("setRootCid - FileNotFoundException:" + fileNotFoundException.getMessage());
        } catch (IOException ioException) {
            logger.error("setRootCid - IOException:" + ioException.getMessage());
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }
    }

}


