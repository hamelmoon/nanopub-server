package ch.tkuhn.nanopub.server.config;

import ch.tkuhn.nanopub.server.ServerConf;
import ch.tkuhn.nanopub.server.modules.IpfsInjectModule;
import ch.tkuhn.nanopub.server.modules.MongoInjectModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceServletContextListener;
import org.apache.commons.lang.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContextEvent;

//https://github.com/google/guice/wiki/ServletModule
public class GuiceServletConfig extends GuiceServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(GuiceServletConfig.class);

    @Override
    protected Injector getInjector() {
        Injector injector;
        if (ServerConf.get().getStorageType().equalsIgnoreCase("mongodb")) {
            injector = Guice
                    .createInjector(new MongoInjectModule());
        } else if (ServerConf.get().getStorageType().equalsIgnoreCase("ipfs")) {
            injector = Guice
                    .createInjector(new IpfsInjectModule());
        } else {
            throw new NotImplementedException();
        }
        return injector;
    }

    @Override
    public void contextInitialized(final ServletContextEvent sce) {
        super.contextInitialized(sce);
        logger.info("contextInitialized");
    }

    @Override
    public void contextDestroyed(final ServletContextEvent sce) {
        super.contextDestroyed(sce);
        logger.info("contextDestroyed");
    }
}