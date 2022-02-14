package ch.tkuhn.nanopub.server.storage;

import com.google.inject.Inject;
import com.google.inject.Provider;

public class NanopubStorageFactory {
    @Inject
    static Provider<NanopubStorage> storageProvider;

//    @Deprecated
    public static NanopubStorage getInstance() {
        return storageProvider.get();
    }
}
