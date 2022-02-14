package ch.tkuhn.nanopub.server;

import ch.tkuhn.nanopub.server.exceptions.NanopubDbException;
import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;
import ch.tkuhn.nanopub.server.exceptions.NotTrustyNanopubException;
import ch.tkuhn.nanopub.server.exceptions.OversizedNanopubException;
import ch.tkuhn.nanopub.server.exceptions.ProtectedNanopubException;
import com.google.common.base.Strings;
import com.google.inject.Singleton;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import net.trustyuri.TrustyUriUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.ServerInfo.ServerInfoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.time.Instant;

@Singleton
public class NanopubServlet extends HttpServlet {

    private static final long serialVersionUID = -4542560440919522982L;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            setGeneralHeaders(resp);
            ServerRequest r = new ServerRequest(req);




            if (r.hasArtifactCode()) {
                if (r.getIsIPFSGatewayRequest()) {
                    String cid = NanopubStorageFactory.getInstance().getCid(r.getArtifactCode());
                    if (!Strings.isNullOrEmpty(cid)) {
                        resp.sendRedirect("https://ipfs.io/ipfs/" + cid);
                    } else {
                        resp.sendError(400, "Can not retrieve ipfs urls: " + r.getFullRequest());
                    }
                } else {
                    NanopubPage.show(r, resp);
                }
            }else if (r.getRequestString().equals("publish_test")) {
                String testTrig = "@prefix this: <http://krauthammerlab.med.yale.edu/nanopub/GeneRIF189637.RAP6unEbj-RX1SwGzTbIP_4ztqhlDQIbB7Dk3H3wHnJCc> .\n" +
                        "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n" +
                        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n" +
                        "@prefix rdfg: <http://www.w3.org/2004/03/trix/rdfg-1/> .\n" +
                        "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n" +
                        "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n" +
                        "@prefix dct: <http://purl.org/dc/terms/> .\n" +
                        "@prefix dce: <http://purl.org/dc/elements/1.1/> .\n" +
                        "@prefix pav: <http://purl.org/pav/> .\n" +
                        "@prefix prov: <http://www.w3.org/ns/prov#> .\n" +
                        "@prefix np: <http://www.nanopub.org/nschema#> .\n" +
                        "\n" +
                        "this:\\#Head {\n" +
                        "  this: np:hasAssertion this:\\#assertion;\n" +
                        "    np:hasProvenance this:\\#provenance;\n" +
                        "    np:hasPublicationInfo this:\\#pubinfo;\n" +
                        "    a np:Nanopublication .\n" +
                        "}\n" +
                        "\n" +
                        "this:\\#assertion {\n" +
                        "  this:\\#assertion <http://purl.org/nanopub/x/asSentence> <http://purl.org/aida/Cell-type+specific+glucocorticoid+receptor+isoforms+generate+specificity+in+glucocorticoid+control+of+transcription+in+different+tissues.>;\n" +
                        "    rdf:about <http://purl.uniprot.org/taxonomy/9606>, <http://www.ncbi.nlm.nih.gov/gene/2908>;\n" +
                        "    a <http://purl.org/nanopub/x/UnderspecifiedAssertion> .\n" +
                        "}\n" +
                        "\n" +
                        "this:\\#provenance {\n" +
                        "  this:\\#assertion prov:hadPrimarySource <http://www.ncbi.nlm.nih.gov/pubmed/15866175>;\n" +
                        "    prov:wasDerivedFrom <ftp://ftp.ncbi.nih.gov/gene/GeneRIF/generifs_basic.gz> .\n" +
                        "}\n" +
                        "\n" +
                        "this:\\#pubinfo {\n" +
                        "  this: dct:created \"2014-06-16T13:47:00Z\"^^xsd:dateTime;\n" +
                        "    dct:isPartOf <http://krauthammerlab.med.yale.edu/nanopub/NanopubsFromGeneRIF>;\n" +
                        "    pav:createdBy <http://orcid.org/0000-0002-1267-0234>;\n" +
                        "    pav:version \"1.3." + Instant.now().getEpochSecond() + "\";\n" +
                        "    rdfs:seeAlso <http://dx.doi.org/10.1007/978-3-642-38288-8_33> .\n" +
                        "}";
                String ret = "";
                try {
                    ret = NanopubStorageFactory.getInstance().testPublish(new NanopubImpl(testTrig, RDFFormat.TRIG));
                } catch (Exception e) {
                    logger.error(e.getMessage());
                }

                resp.setStatus(200);
                OutputStream os = resp.getOutputStream();
                os.write(ret.getBytes());
                os.close();
            }
            else if (r.getRequestString().equals("metric")) {
                final PrometheusMeterRegistry meterRegistry = (PrometheusMeterRegistry) this.getServletContext().getAttribute("meterRegistry");
                String response = meterRegistry.scrape();
                resp.setStatus(200);
                OutputStream os = resp.getOutputStream();
                os.write(response.getBytes());
                os.close();
            } else if (!NanopubStorageFactory.getInstance().isAccessible()) {
                // The above (single nanopub) gives a nice 500 error code if Storage is not running,
                // but the pages below don't. That's why we need this check here.
                resp.sendError(500, "Storage is not accessible");
            } else if (r.isEmpty()) {
                MainPage.show(r, resp);
            } else if (r.getRequestString().equals(NanopubListPage.PAGE_NAME)) {
                NanopubListPage.show(r, resp);
            } else if (r.getRequestString().equals(PeerListPage.PAGE_NAME)) {
                PeerListPage.show(r, resp);
            } else if (r.getRequestString().equals(PackagePage.PAGE_NAME)) {
                PackagePage.show(r, resp);
            } else if (r.getFullRequest().equals("/style/plain.css")) {
                ResourcePage.show(r, resp, "style.css", "text/css");
            } else if (r.getFullRequest().equals("/style/favicon.ico")) {
                ResourcePage.show(r, resp, "favicon.ico", "image/x-icon");
            } else {
                resp.sendError(400, "Invalid GET request: " + r.getFullRequest());
            }
        } finally {
            resp.getOutputStream().close();
            req.getInputStream().close();
        }

        check();

    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            setGeneralHeaders(resp);
            ServerRequest r = new ServerRequest(req);
            if (r.isEmpty()) {
                if (!ServerConf.getInfo().isPostNanopubsEnabled()) {
                    resp.sendError(405, "Posting nanopubs is not supported by this nanopub server");
                    return;
                }
                Nanopub np = null;
                try {
                    np = new NanopubImpl(req.getInputStream(), Rio.getParserFormatForMIMEType(req.getContentType()).orElse(RDFFormat.TRIG));
                } catch (Exception ex) {
                    resp.sendError(400, "Error reading nanopub: " + ex.getMessage());
                }
                if (np != null) {
                    if (ServerConf.getInfo().getNanopubSurfacePattern().matchesUri(np.getUri().toString())) {
                        String code = TrustyUriUtils.getArtifactCode(np.getUri().toString());
                        try {
                            if (NanopubStorageFactory.getInstance().getNanopub(code) == null) {
                                NanopubStorageFactory.getInstance().loadNanopub(np);
                            }
                            resp.setHeader("Location", TrustyUriUtils.getArtifactCode(np.getUri().toString()));
                            resp.setStatus(201);
                        } catch (NotTrustyNanopubException ex) {
                            resp.sendError(400, "Nanopub is not trusty: " + ex.getMessage());
                        } catch (OversizedNanopubException ex) {
                            resp.sendError(400, "Nanopub is too large: " + ex.getMessage());
                        } catch (ProtectedNanopubException ex) {
                            resp.sendError(400, "Nanopub is protected: " + ex.getMessage());
                        } catch (Exception ex) {
                            resp.sendError(500, "Error storing nanopub: " + ex.getMessage());
                        }
                    } else {
                        resp.sendError(500, "Nanopub doesn't match pattern for this server: " + np.getUri());
                    }
                }
            } else if (r.getRequestString().equals(PeerListPage.PAGE_NAME)) {
                if (!ServerConf.getInfo().isPostPeersEnabled()) {
                    resp.sendError(405, "Posting peers is not supported by this nanopub server");
                    return;
                }
                try {
                    StringWriter sw = new StringWriter();
                    IOUtils.copy(new InputStreamReader(req.getInputStream(), Charset.forName("UTF-8")), sw);
                    NanopubStorageFactory.getInstance().addPeer(sw.toString().trim());
                    resp.setStatus(201);
                } catch (ServerInfoException ex) {
                    resp.sendError(400, "Invalid peer URL: " + ex.getMessage());
                } catch (IOException ex) {
                    resp.sendError(500, "Error adding peer: " + ex.getMessage());
                }
            } else {
                resp.sendError(400, "Invalid POST request: " + r.getFullRequest());
            }
        } finally {
            resp.getOutputStream().close();
            req.getInputStream().close();
        }
        check();
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        super.doOptions(req, resp);
        setGeneralHeaders(resp);
    }

    @Override
    public void init() throws ServletException {
        logger.info("Init");
        check();
    }

    private void setGeneralHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
    }

    private void check() {
        ScanPeers.check();
        LoadFiles.check();
    }

}
