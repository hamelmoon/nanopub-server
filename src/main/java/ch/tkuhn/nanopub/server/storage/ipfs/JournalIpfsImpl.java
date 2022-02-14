package ch.tkuhn.nanopub.server.storage.ipfs;

import ch.tkuhn.nanopub.server.NanopubServerUtils;
import ch.tkuhn.nanopub.server.ServerConf;
import ch.tkuhn.nanopub.server.storage.Journal;
import ch.tkuhn.nanopub.server.storage.ipfs.entities.JournalT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

public class JournalIpfsImpl implements Journal {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private IpfsCollection collection;
    public JournalIpfsImpl(IpfsCollection collection) {
        this.collection = collection;
        init();
    }

    private void init() {
        if (collection.getJournalCollection().isEmpty()) {
            logger.info("No journal found: Create new one");
            collection.setJournalCollection("journal-version", new JournalT("journal-version", NanopubServerUtils.journalVersion));
            collection.setJournalCollection("journal-id", new JournalT("journal-id", Math.abs(new Random().nextLong()) + ""));
            collection.setJournalCollection("next-nanopub-no", new JournalT("next-nanopub-no", "0"));
            collection.setJournalCollection("page-size", new JournalT("page-size", ServerConf.get().getInitPageSize() + ""));
            collection.setJournalCollection("uri-pattern", new JournalT("uri-pattern", ServerConf.get().getUriPattern()));
            collection.setJournalCollection("hash-pattern", new JournalT("hash-pattern", ServerConf.get().getHashPattern()));
        }

        int v = getVersionValue();
        if (v == NanopubServerUtils.journalVersionValue) {
            logger.info("Journal version is up-to-date: " + v);
            return;
        }
        if (v > NanopubServerUtils.journalVersionValue) {
            logger.error("Unknown (too new) journal version found");
            throw new RuntimeException("Unknown (too new) journal version found");
        }
        logger.info("Journal version is not up-to-date: " + v);
        logger.info("Journal version is not up-to-date: Try to upgrade...");
        if (v < 2) {
            // Found journal version is too old: Abort
            logger.error("Old database found in MongoDB: " + ServerConf.get().getMongoDbName() +
                    ". Erase or rename this DB and restart the nanopub server.");
            throw new RuntimeException("Old database found in MongoDB");
        }
        if (v == 2) {
            collection.setJournalCollection("uri-pattern", new JournalT("uri-pattern", ""));
            collection.setJournalCollection("hash-pattern", new JournalT("hash-pattern", ""));
        }
        collection.setJournalCollection("journal-version", new JournalT("journal-version", NanopubServerUtils.journalVersion));
        logger.info("Journal upgraded to version " + NanopubServerUtils.journalVersion);

        long j = getJournalId();
        if (j == 0) {
            // Prebuilt DB from a downloaded package that doesn't have a journal ID yet
            j = Math.abs(new Random().nextLong());
            collection.setJournalCollection("journal-id", new JournalT("journal-id", j + ""));
        }
    }

    @Override
    public long getJournalId() {
        return Long.parseLong(collection.getJournalCollection().get("journal-id").getValue());
    }

    @Override
    public int getPageSize() {
        return Integer.parseInt(collection.getJournalCollection().get("page-size").getValue());
    }

    @Override
    public long getNextNanopubNo() {

        return Long.parseLong(collection.getJournalCollection().get("next-nanopub-no").getValue());
    }

    @Override
    public long getCurrentPageNo() {
        return getNextNanopubNo() / getPageSize() + 1;
    }

    @Override
    public String getUriPattern() {
        return collection.getJournalCollection().get("uri-pattern").getValue();
    }

    @Override
    public String getHashPattern() {
        return collection.getJournalCollection().get("hash-pattern").getValue();
    }

    @Override
    public String getPageContent(long pageNo) {
        String pageName = "page" + pageNo;
        String pageContent = null;
        if (collection.getJournalCollection().containsKey(pageName)) {
            pageContent = collection.getJournalCollection().get(pageName).getValue();
        }
        if (pageContent == null) {
            if (getNextNanopubNo() % getPageSize() > 0) {
                throw new RuntimeException("Cannot find journal page: " + pageName);
            }
            // Make new page
            pageContent = "";
        }

        return pageContent;
    }

    @Override
    public synchronized String getStateId() {
        return getJournalId() + "/" + getNextNanopubNo();
    }

    @Override
    public synchronized int getVersionValue() {
        try {
            return NanopubServerUtils.getVersionValue(collection.getJournalCollection().get("journal-version").getValue());
        } catch (Exception ex) {
            logger.error("getVersionValue :" + ex.getMessage());
            return 0;
        }
    }

    @Override
    // Raise error if there is evidence of two parallel processes accessing the database:
    public synchronized void checkNextNanopubNo() {
        long loadedNextNanopubNo = Long.parseLong(collection.getJournalCollection().get("next-nanopub-no").getValue());
        if (loadedNextNanopubNo != getNextNanopubNo()) {
            if (loadedNextNanopubNo > getNextNanopubNo()) { collection.setJournalCollection("next-nanopub-no", new JournalT("next-nanopub-no", loadedNextNanopubNo + "")); }
            throw new RuntimeException("ERROR. Mismatch of nanopub count from MongoDB: several parallel processes?");
        }
    }

    synchronized void increaseNextNanopubNo() {
        collection.setJournalCollection("next-nanopub-no", new JournalT("next-nanopub-no", String.valueOf(getNextNanopubNo() + 1)));
    }

    synchronized void setPageContent(long pageNo, String pageContent) {
        collection.setJournalCollection("page" + pageNo, new JournalT("page" + pageNo, pageContent));
    }

}
