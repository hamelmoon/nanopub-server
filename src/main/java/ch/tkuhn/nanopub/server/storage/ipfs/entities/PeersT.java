package ch.tkuhn.nanopub.server.storage.ipfs.entities;


import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.CborObject;
import ch.tkuhn.nanopub.server.shared.io.ipfs.cbor.Cborable;

import java.util.Map;
import java.util.TreeMap;

public class PeersT implements Cborable {
    private String _id;
    private Long journalId;
    private Long nextNanopubId;

    public PeersT(String _id, Long journalId, Long nextNanopubId) {
        this._id = _id;
        this.journalId = journalId;
        this.nextNanopubId = nextNanopubId;
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public Long getJournalId() {
        return journalId;
    }

    public void setJournalId(Long journalId) {
        this.journalId = journalId;
    }

    public Long getNextNanopubId() {
        return nextNanopubId;
    }

    public void setNextNanopubId(Long nextNanopubId) {
        this.nextNanopubId = nextNanopubId;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> res = new TreeMap<>();
        res.put("id", new CborObject.CborString(_id));
        res.put("journalId", new CborObject.CborLong(journalId));
        res.put("nextNanopubId", new CborObject.CborLong(nextNanopubId));

        return CborObject.CborMap.build(res);
    }

    @Override
    public byte[] serialize() {
        return Cborable.super.serialize();
    }

    public static PeersT fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for PeersT: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new PeersT(m.getString("id"), m.getLong("journalId"),  m.getLong("nextNanopubId"));
    }
}
