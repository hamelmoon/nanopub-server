package ch.tkuhn.nanopub.server.exceptions;

import org.nanopub.Nanopub;

public class OversizedNanopubException extends Exception {

    private static final long serialVersionUID = -8828914376012234462L;

    public OversizedNanopubException(Nanopub np) {
        super(np.getUri().toString());
    }

}