package ch.tkuhn.nanopub.server;

import ch.tkuhn.nanopub.server.storage.NanopubStorageFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class PeerListPage extends Page {

    public static final String PAGE_NAME = "peers";

    private boolean asHtml;

    public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
        PeerListPage obj = new PeerListPage(req, httpResp);
        obj.show();
    }

    public PeerListPage(ServerRequest req, HttpServletResponse httpResp) {
        super(req, httpResp);
        String rf = getReq().getPresentationFormat();
        if (rf == null) {
            String suppFormats = "text/plain,text/html";
            asHtml = "text/html".equals(Utils.getMimeType(getHttpReq(), suppFormats));
        } else {
            asHtml = "text/html".equals(getReq().getPresentationFormat());
        }
        setCanonicalLink("/" + PAGE_NAME);
    }

    public void show() throws IOException {
        int c = 0;
        printStart();
        for (String item : NanopubStorageFactory.getInstance().getPeerUris()) {
            c++;
            printElement(item);
        }
        if (c == 0 && asHtml) {
            println("<li><em>(no known peers)</em></li>");
        }
        printEnd();
        if (asHtml) {
            getResp().setContentType("text/html");
        } else {
            getResp().setContentType("text/plain");
        }
    }

    private void printStart() throws IOException {
        if (asHtml) {
            printHtmlHeader("Nanopub Server: List of peers");
            println("<h1>List of peers</h1>");
            println("<p>");
            println("[ <a href=\"peers.txt\" rel=\"alternate\" type=\"text/plain\">as plain text</a> | ");
            println("<a href=\".\" rel=\"home\">home</a> ]");
            println("</p>");
            println("<ul>");
        }
    }

    private void printElement(String peerUrl) throws IOException {
        if (asHtml) {
            println("<li><a href=\"" + peerUrl + "\">" + peerUrl + "</a></li>");
        } else {
            println(peerUrl);
        }
    }

    private void printEnd() throws IOException {
        if (asHtml) {
            println("</ul>");
            printHtmlFooter();
        }
    }

}
