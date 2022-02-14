package ch.tkuhn.nanopub.server.modules;

import ch.tkuhn.nanopub.server.storage.NanopubStorage;
import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;
import ch.tkuhn.nanopub.server.storage.ipfs.NanopubStorageIpfsImpl;
import com.google.inject.AbstractModule;

public class IpfsInjectModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(NanopubStorage.class).to(NanopubStorageIpfsImpl.class).asEagerSingleton();
        requestStaticInjection(NanopubStorageFactory.class);
    }

}
