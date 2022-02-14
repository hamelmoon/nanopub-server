package ch.tkuhn.nanopub.server.modules;

import ch.tkuhn.nanopub.server.NanopubServlet;
import com.google.inject.servlet.ServletModule;

public class NanopubServletModule extends ServletModule{
    @Override
    protected void configureServlets() {
        super.configureServlets();
        serve("/*").with(NanopubServlet.class);
    }
}
