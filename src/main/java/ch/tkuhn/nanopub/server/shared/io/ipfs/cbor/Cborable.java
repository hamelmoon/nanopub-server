package ch.tkuhn.nanopub.server.shared.io.ipfs.cbor;

import java.util.function.Function;

public interface Cborable {

    CborObject toCbor();

    default byte[] serialize() {
        return toCbor().toByteArray();
    }

    static <T> Function<byte[], T> parser(Function<Cborable, T> parser) {
        return arr -> parser.apply(CborObject.fromByteArray(arr));
    }
}
