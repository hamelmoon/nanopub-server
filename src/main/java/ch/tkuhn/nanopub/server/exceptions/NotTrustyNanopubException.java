package ch.tkuhn.nanopub.server.exceptions;

import org.nanopub.Nanopub;

public class NotTrustyNanopubException extends Exception {

    private static final long serialVersionUID = -3782872539656552144L;

    public NotTrustyNanopubException(Nanopub np) {
        super(np.getUri().toString());
    }

}