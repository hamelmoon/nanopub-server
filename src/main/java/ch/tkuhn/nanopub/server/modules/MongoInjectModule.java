package ch.tkuhn.nanopub.server.modules;

import ch.tkuhn.nanopub.server.storage.NanopubStorage;
import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;
import ch.tkuhn.nanopub.server.storage.mongodb.NanopubStorageMongoImpl;
import com.google.inject.AbstractModule;

public class MongoInjectModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(NanopubStorage.class).to(NanopubStorageMongoImpl.class).asEagerSingleton();
        requestStaticInjection(NanopubStorageFactory.class);
    }
}
