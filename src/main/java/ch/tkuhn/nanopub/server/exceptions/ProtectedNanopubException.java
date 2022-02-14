package ch.tkuhn.nanopub.server.exceptions;

import org.nanopub.Nanopub;

public class ProtectedNanopubException extends Exception {

    private static final long serialVersionUID = 3978608156725990918L;

    public ProtectedNanopubException(Nanopub np) {
        super(np.getUri().toString());
    }

}
