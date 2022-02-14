package ch.tkuhn.nanopub.server;

import ch.tkuhn.nanopub.server.modules.IpfsInjectModule;
import ch.tkuhn.nanopub.server.modules.MongoInjectModule;
import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.commons.lang.NotImplementedException;

import java.io.IOException;

public class PopulatePackageCache {
	static Injector injector;


	public static void main(String[] args) throws IOException {


		if (ServerConf.get().getStorageType().equalsIgnoreCase("mongodb")) {
			injector = Guice
					.createInjector(new MongoInjectModule());
		} else if (ServerConf.get().getStorageType().equalsIgnoreCase("ipfs")) {
			injector = Guice
					.createInjector(new IpfsInjectModule());
		} else {
			throw new NotImplementedException();
		}

		NanopubStorageFactory.getInstance().populatePackageCache();
	}
}
