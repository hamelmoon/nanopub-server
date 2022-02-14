package ch.tkuhn.nanopub.server.storage.ipfs;

public interface ContentAddressedStorage {

    /**
     *
     * @param value
     * @return a hash of the value
     */
    byte[] put(byte[] value);

    /**
     *
     * @param key the hash of a value previously stored
     * @return
     */
    byte[] get(byte[] key);

    /**
     *
     * @param key the hash of a value previously stored
     */
    void remove(byte[] key);
}
