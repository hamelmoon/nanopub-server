package ch.tkuhn.nanopub.server.storage.ipfs.entities;




import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;

import java.util.Map;
import java.util.TreeMap;

public class JournalT implements Cborable {
    private String _id;
    private String value;

    public JournalT(String _id, String value) {
        this._id = _id;
        this.value = value;
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> res = new TreeMap<>();
        res.put("id", new CborObject.CborString(_id));
        res.put("value", new CborObject.CborString(value));
        return CborObject.CborMap.build(res);
    }

    @Override
    public byte[] serialize() {
        return Cborable.super.serialize();
    }

    public static JournalT fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for JournalT: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new JournalT(m.getString("id"), m.getString("value"));
    }
}
