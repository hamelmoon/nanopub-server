package ch.tkuhn.nanopub.server.storage.ipfs;

import ch.tkuhn.nanopub.server.ServerConf;
import ch.tkuhn.nanopub.server.Utils;
import ch.tkuhn.nanopub.server.exceptions.NanopubDbException;
import ch.tkuhn.nanopub.server.exceptions.NotTrustyNanopubException;
import ch.tkuhn.nanopub.server.exceptions.OversizedNanopubException;
import ch.tkuhn.nanopub.server.exceptions.ProtectedNanopubException;
import ch.tkuhn.nanopub.server.storage.Journal;
import ch.tkuhn.nanopub.server.storage.NanopubStorage;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.PeersT;
import com.google.common.base.Strings;
import com.google.inject.Singleton;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import net.trustyuri.TrustyUriUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.rdf4j.RDF4JException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.nanopub.*;
import org.nanopub.extra.server.ServerInfo;
import org.nanopub.trusty.TrustyNanopubUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;


@Singleton
public class NanopubStorageIpfsImpl implements NanopubStorage {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    // Use trig internally to keep namespaces:
    private static RDFFormat internalFormat = RDFFormat.JSONLD;
    private JournalIpfsImpl journalIpfsImpl;
    private IPFSStorageImpl ipfs;
    private IpfsCollection collection;

    public NanopubStorageIpfsImpl() {
        ipfs = new IPFSStorageImpl();
        collection = new IpfsCollection(this.ipfs, false);
        for (String s : ServerConf.get().getInitialPeers()) {
            addPeerToCollection(s);
        }
        journalIpfsImpl = new JournalIpfsImpl(collection);
    }


    @Override
    public void loadNanopub(Nanopub np) throws NotTrustyNanopubException, OversizedNanopubException, NanopubDbException, ProtectedNanopubException {
        if (np instanceof NanopubWithNs) {
            ((NanopubWithNs) np).removeUnusedPrefixes();
        }
        if (!TrustyNanopubUtils.isValidTrustyNanopub(np)) {
            throw new NotTrustyNanopubException(np);
        }
        if (!ServerConf.get().isRunAsLocalServerEnabled() && Utils.isProtectedNanopub(np)) {
            throw new ProtectedNanopubException(np);
        }
        ch.tkuhn.nanopub.server.ServerInfo info = ServerConf.getInfo();
        if (info.getMaxNanopubTriples() != null && np.getTripleCount() > info.getMaxNanopubTriples()) {
            throw new OversizedNanopubException(np);
        }
        if (info.getMaxNanopubBytes() != null && np.getByteCount() > info.getMaxNanopubBytes()) {
            throw new OversizedNanopubException(np);
        }
        if (isFull()) {
            throw new NanopubDbException("Server is full (maximum number of nanopubs reached)");
        }

        String artifactCode = TrustyUriUtils.getArtifactCode(np.getUri().toString());
        String npString = null;
        try {
            npString = NanopubUtils.writeToString(np, internalFormat);
        } catch (RDFHandlerException ex) {
            throw new RuntimeException("Unexpected exception when processing nanopub", ex);
        }

        if (!collection.containsNanopubCollection(artifactCode)) {
            journalIpfsImpl.checkNextNanopubNo();
            long currentPageNo = journalIpfsImpl.getCurrentPageNo();
            String pageContent = journalIpfsImpl.getPageContent(currentPageNo);
            pageContent += np.getUri() + "\n";
            // TODO Implement proper transactions, rollback, etc.
            // The following three lines of code are critical. If Java gets interrupted
            // in between, the data will remain in a slightly inconsistent state (but, I
            // think, without serious consequences).
            journalIpfsImpl.increaseNextNanopubNo();
            // If interrupted here, the current page of the journal will miss one entry
            // (e.g. contain only 999 instead of 1000 entries).
            journalIpfsImpl.setPageContent(currentPageNo, pageContent);
            // If interrupted here, journal will contain an entry that cannot be found in
            // the database. This entry might be loaded later and then appear twice in the
            // journal.
//            String document = new Gson().(new NanoPubT(artifactCode, npString, np.getUri().toString()));
            String hash = write(npString.getBytes(StandardCharsets.UTF_8), false);
            if(!Strings.isNullOrEmpty(hash)) {
                collection.setNanopubCollection(artifactCode, hash);
                this.ipfs.pin(hash);
            }
        }
        String[] postUrls = ServerConf.get().getPostUrls();
        for (String postUrl : postUrls) {
            if (!Strings.isNullOrEmpty(postUrl)) {
                int failedTries = 0;
                boolean success = false;
                while (!success && failedTries < 3) {
                    try {
                        HttpPost post = new HttpPost(postUrl);
                        post.setHeader("Content-Type", internalFormat.getDefaultMIMEType());
                        post.setEntity(new StringEntity(npString, "UTF-8"));
                        HttpResponse response = HttpClientBuilder.create().build().execute(post);
                        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                            success = true;
                        } else {
                            logger.error("Failed to post nanopub to " + postUrl + ": " + response.getStatusLine().getReasonPhrase());
                            failedTries++;
                        }
                    } catch (Exception ex) {
                        logger.error("Error while posting nanopub", ex);
                        failedTries++;
                    }
                    if (!success) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            }
        }
        if (ServerConf.get().isLogNanopubLoadingEnabled()) {
            logger.info("Nanopub loaded: " + np.getUri());
        }
    }

    private void addPeerToCollection(String peerUrl) {
        if (peerUrl.equals(ServerConf.getInfo().getPublicUrl())) {
            return;
        }

        if (!collection.getPeerCollection().containsKey(peerUrl)) {
            addPeerCollection(new PeersT(peerUrl, null, null));
        }
    }

//    private List<PeersT> getPeerCollection(){
//        return peerList;
//    }

    private PeersT addPeerCollection(PeersT peer) {
        return collection.setPeerCollection(peer.get_id(), peer);
    }


    @Override
    public boolean hasNanopub(String artifactCode) {
        return collection.containsNanopubCollection(artifactCode);
    }

    @Override
    public String getCid(String artifactCode) {
        return collection.getNanopubCollection(artifactCode);
    }


    @Override
    public Nanopub getNanopub(String artifactCode) {
        String hash = collection.getNanopubCollection(artifactCode);
        if (Strings.isNullOrEmpty(hash)) {
            return null;
        }
        ByteArrayOutputStream stream = (ByteArrayOutputStream) read(hash);
        String nanopubString = stream.toString();
//        NanoPubT nanopub = new Gson().fromJson(nanopubString, NanoPubT.class);
        Nanopub np = null;
        try {
            np = new NanopubImpl(nanopubString, internalFormat);
        } catch (MalformedNanopubException ex) {
            throw new RuntimeException("Stored nanopub is not wellformed (this shouldn't happen)", ex);
        } catch (RDF4JException ex) {
            throw new RuntimeException("Stored nanopub is corrupted (this shouldn't happen)", ex);
        }
        if (ServerConf.get().isCheckNanopubsOnGetEnabled() && !TrustyNanopubUtils.isValidTrustyNanopub(np)) {
            throw new RuntimeException("Stored nanopub is not trusty (this shouldn't happen)");
        }
        return np;
    }

    @Override
    public long getNextNanopubNo() {
        return journalIpfsImpl.getNextNanopubNo();
    }

    @Override
    public List<String> getPeerUris() {
        return collection.getPeerCollection().values().stream().map(p -> p.get_id()).collect(Collectors.toList());
    }

    @Override
    public void addPeer(String peerUrl) throws ServerInfo.ServerInfoException {
        ch.tkuhn.nanopub.server.ServerInfo.load(peerUrl);  // throw exception if something is wrong
        addPeerToCollection(peerUrl);
    }

    @Override
    public void updatePeerState(ch.tkuhn.nanopub.server.ServerInfo peerInfo, long npno) {
        PeersT peer = new PeersT(peerInfo.getPublicUrl(), peerInfo.getJournalId(), npno);
        collection.setPeerCollection(peer.get_id(), peer);
    }

    @Override
    public Pair<Long, Long> getLastSeenPeerState(String peerUrl) {
        BasicDBObject q = new BasicDBObject("_id", peerUrl);
        PeersT peer = collection.getPeerCollection().get(peerUrl);
        if (peer == null) return null;
        if (peer.getJournalId() == null || peer.getNextNanopubId() == null) return null;
        return Pair.of(peer.getJournalId(), peer.getNextNanopubId());
    }

    @Override
    public void populatePackageCache() throws IOException {
        long c = journalIpfsImpl.getCurrentPageNo();
        for (long page = 1; page < c; page++) {
            if (!isPackageCached(page)) {
                writePackageToStream(page, false, new NullOutputStream());
            }
        }
        logger.info("done populatePackageCache");
    }

    private boolean isPackageCached(long pageNo) {
        return collection.getPackagedListCollection().get(pageNo + "") != null;
    }


    @Override
    public void writePackageToStream(long pageNo, boolean gzipped, OutputStream out) throws IOException {
        if (pageNo < 1 || pageNo >= journalIpfsImpl.getCurrentPageNo()) {
            throw new IllegalArgumentException("Not a complete page: " + pageNo);
        }
        String hash = collection.getPackagedListCollection().get(pageNo + "").value;
        OutputStream packageOut = null;
        InputStream packageAsStream = null;
        try {
            if (hash == null) {
                if (gzipped) {
                    out = new GZIPOutputStream(out);
                }
                ByteArrayOutputStream bOut = new ByteArrayOutputStream();
                packageOut = new GZIPOutputStream(bOut);
                String pageContent = journalIpfsImpl.getPageContent(pageNo);
                for (String uri : pageContent.split("\\n")) {
                    Nanopub np = getNanopub(TrustyUriUtils.getArtifactCode(uri));
                    String s;
                    try {
                        s = NanopubUtils.writeToString(np, RDFFormat.TRIG);
                    } catch (RDFHandlerException ex) {
                        throw new RuntimeException("Unexpected RDF handler exception", ex);
                    }
                    byte[] bytes = (s + "\n").getBytes();
                    out.write(bytes);
                    packageOut.write(bytes);
                }
                packageOut.close();
                packageAsStream = new ByteArrayInputStream(bOut.toByteArray());
                //Original implements works like cache, gzip package on IPFS will not be pinned. Thus it will work like cache
                String ipfsHash = write(packageAsStream, true);
                if(!Strings.isNullOrEmpty(ipfsHash)) {
                    collection.setPackagedListCollection(pageNo + "", ipfsHash);
                    this.ipfs.remove(ipfsHash.getBytes(StandardCharsets.UTF_8));
                }

            } else {
                String packagedFileHash = collection.getPackagedListCollection().get(pageNo + "").value;
                if (gzipped) {
                    this.read(packagedFileHash, out);

                } else {
                    try (ByteArrayOutputStream stream = (ByteArrayOutputStream) read(packagedFileHash)) {
                        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(stream.toByteArray()))) {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                        }
                    }
                }
            }

        } finally {
            if (out != null) out.close();
            if (packageOut != null) packageOut.close();
            if (packageAsStream != null) packageAsStream.close();
        }
    }

    @Override
    public boolean isFull() {
        if (this.isServerSpaceFull()) {
            return true;
        }
        ch.tkuhn.nanopub.server.ServerInfo info = ServerConf.getInfo();
        return (info.getMaxNanopubs() != null && journalIpfsImpl.getNextNanopubNo() >= info.getMaxNanopubs());
    }

    public boolean isServerSpaceFull() {
        File file = new File(ServerConf.get().getIpfsRootCidPropertiesPath());
        if (file.getFreeSpace() < 1000000) //1MB
        {
            return true;
        }
        return false;
    }

    @Override
    public boolean isAccessible() {
        try {
            //Healthcheck for the container
            //QmUNLLsPACCz1vLxQVkXqqLX5R1X345qqfHbsf67hvA3Nn is the CID of empty folder
            //return this.ipfs.dag.get(Cid.decode("QmUNLLsPACCz1vLxQVkXqqLX5R1X345qqfHbsf67hvA3Nn")).length > 0;
            return true;
        } catch (Exception ex) {
            logger.error(ex.getMessage());
        }

        return false;
    }

    @Override
    public Journal getJournal() {
        return journalIpfsImpl;
    }

    public String write(InputStream content, boolean noPin) {

        try {
            return this.write(IOUtils.toByteArray(content), noPin);

        } catch (IOException ex) {
            logger.error("Exception converting Inputstream to byte array", ex);
            throw new RuntimeException("Exception converting Inputstream to byte array", ex);
        }
    }

    public String write(byte[] content, boolean noPin) {
        logger.debug("Write file on IPFS [noPin: {}]", noPin);
        return new String(this.ipfs.put(content));
    }


    public OutputStream read(String id) {
        return read(id, new ByteArrayOutputStream());
    }

    public OutputStream read(String hash, OutputStream output) {
        logger.debug("Read file on IPFS [id: {}]", hash);
        try {
             IOUtils.write(this.ipfs.get(hash.getBytes(StandardCharsets.UTF_8)), output);
             return output;
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
        return null;
    }

    @Override
    public String testPublish(Nanopub np) {
        String npString = null;
        try {
            npString = NanopubUtils.writeToString(np, internalFormat);
        } catch (RDFHandlerException ex) {
            throw new RuntimeException("Unexpected exception when processing nanopub", ex);
        }
        String ret = new String(ipfs.put(npString.getBytes(StandardCharsets.UTF_8)));

        return ret;
    }
}
