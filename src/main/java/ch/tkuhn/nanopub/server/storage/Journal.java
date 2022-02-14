package ch.tkuhn.nanopub.server.storage;

public interface Journal {
    long getJournalId();
    long getNextNanopubNo();
    long getCurrentPageNo();
    int getPageSize();
    int getVersionValue();
    String getUriPattern();
    String getHashPattern();
    String getPageContent(long pageNo);
    String getStateId();
    void checkNextNanopubNo();
}
