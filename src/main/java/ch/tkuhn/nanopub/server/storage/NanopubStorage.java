package ch.tkuhn.nanopub.server.storage;

import ch.tkuhn.nanopub.server.ServerInfo;
import ch.tkuhn.nanopub.server.exceptions.NanopubDbException;
import ch.tkuhn.nanopub.server.exceptions.NotTrustyNanopubException;
import ch.tkuhn.nanopub.server.exceptions.OversizedNanopubException;
import ch.tkuhn.nanopub.server.exceptions.ProtectedNanopubException;
import org.apache.commons.lang3.tuple.Pair;
import org.nanopub.Nanopub;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public interface NanopubStorage {
    boolean hasNanopub(String artifactCode);
    boolean isFull();
    boolean isAccessible();
    long getNextNanopubNo();
    void addPeer(String peerUrl) throws org.nanopub.extra.server.ServerInfo.ServerInfoException;
    void updatePeerState(ServerInfo peerInfo, long npno);
    void populatePackageCache() throws IOException;
    void writePackageToStream(long pageNo, boolean gzipped, OutputStream out) throws IOException;
    void loadNanopub(Nanopub np) throws NotTrustyNanopubException,
            OversizedNanopubException, NanopubDbException, ProtectedNanopubException;
    List<String> getPeerUris();
    Pair<Long,Long> getLastSeenPeerState(String peerUrl);
    Journal getJournal();
    Nanopub getNanopub(String artifactCode);
    String getCid(String artifactCode);
    String testPublish(Nanopub np);
}
