package ch.tkuhn.nanopub.server.shared.io.ipfs.api;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class Multipart {
    private final String boundary;
    private static final String LINE_FEED = "\r\n";
    private HttpURLConnection httpConn;
    private String charset;
    private OutputStream out;
    private PrintWriter writer;

    public Multipart(String requestURL, String charset) throws IOException {
        this(requestURL, charset, Collections.emptyMap());
    }
    public Multipart(String requestURL, String charset, Map<String, String> headers) throws IOException {
        this.charset = charset;

        boundary = createBoundary();

        URL url = new URL(requestURL);
        httpConn = (HttpURLConnection) url.openConnection();
        httpConn.setUseCaches(false);
        httpConn.setDoOutput(true);
        httpConn.setDoInput(true);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            httpConn.setRequestProperty(e.getKey(), e.getValue());
        }
        httpConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        httpConn.setRequestProperty("User-Agent", "Java IPFS Client");
        out = httpConn.getOutputStream();
        writer = new PrintWriter(new OutputStreamWriter(out, charset), true);
    }

    public static String createBoundary() {
        Random r = new Random();
        String allowed = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder b = new StringBuilder();
        for (int i=0; i < 32; i++)
            b.append(allowed.charAt(r.nextInt(allowed.length())));
        return b.toString();
    }

    public void addFormField(String name, String value) {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
                .append(LINE_FEED);
        writer.append("Content-Type: text/plain; charset=" + charset).append(
                LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }

    /** Recursive call to add a subtree to this post
     *
     * @param parentPath
     * @param dir
     * @throws IOException
     */
    public void addSubtree(Path parentPath, NamedStreamable dir) throws IOException {
        Path dirPath = parentPath.resolve(dir.getName().get());
        addDirectoryPart(dirPath);
        for (NamedStreamable f: dir.getChildren()) {
            if (f.isDirectory())
                addSubtree(dirPath, f);
            else
                addFilePart("file", dirPath, f);
        }
    }

    public void addDirectoryPart(Path path) {
        try {
            writer.append("--" + boundary).append(LINE_FEED);
            writer.append("Content-Disposition: file; filename=\"" + URLEncoder.encode(path.toString(), "UTF-8") + "\"").append(LINE_FEED);
            writer.append("Content-Type: application/x-directory").append(LINE_FEED);
            writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.append(LINE_FEED);
            writer.flush();
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String encode(String in) {
        try {
            return URLEncoder.encode(in, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void addFilePart(String fieldName, Path parent, NamedStreamable uploadFile) throws IOException {
        //Optional<String> fileName = uploadFile.getName().map(n -> encode(parent.resolve(n).toString().replace('\\','/')));
        Optional<String> fileName = uploadFile.getName();
        writer.append("--" + boundary).append(LINE_FEED);
        if (!fileName.isPresent())
            writer.append("Content-Disposition: file; name=\"" + fieldName + "\";").append(LINE_FEED);
        else
            writer.append("Content-Disposition: file; filename=\"" + fileName.get() + "\"").append(LINE_FEED);
        writer.append("Content-Type: application/octet-stream").append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        InputStream inputStream = uploadFile.getInputStream();
        byte[] buffer = new byte[4096];
        int r;
        while ((r = inputStream.read(buffer)) != -1)
            out.write(buffer, 0, r);
        out.flush();
        inputStream.close();

        writer.append(LINE_FEED);
        writer.flush();
    }

    public void addHeaderField(String name, String value) {
        writer.append(name + ": " + value).append(LINE_FEED);
        writer.flush();
    }
    //TODO: 500 with body: {"Message":"committing batch to datastore at /blocks: write /data/ipfs/blocks/.temp/temp-148445058: no space left on device","Code":0,"Type":"error"}
    // and Trailer header: [X-Stream-Error]
    public String finish() throws IOException {
        StringBuilder b = new StringBuilder();

        writer.append("--" + boundary + "--").append(LINE_FEED);
        writer.close();

        int status = httpConn.getResponseCode();
        if (status == HttpURLConnection.HTTP_OK) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    httpConn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                b.append(line);
            }
            reader.close();
            httpConn.disconnect();
        } else if (status == HttpURLConnection.HTTP_NO_CONTENT) {
            httpConn.disconnect();
            return "";
        } else {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        httpConn.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    b.append(line);
                }
                reader.close();
            } catch (Throwable t) {}
            InputStream errorStream = httpConn.getErrorStream();
            if (errorStream != null) {
                String errBody = new String(readFully(errorStream));
                b.append(errBody);
            }
            throw new IOException("Server returned status: " + status + " with body: "+b.toString() + " and Trailer header: "+httpConn.getHeaderFields().get("Trailer"));
        }

        return b.toString();
    }

    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bout =  new ByteArrayOutputStream();
        byte[] b =  new  byte[0x1000];
        int nRead;
        while ((nRead = in.read(b, 0, b.length)) != -1 )
            bout.write(b, 0, nRead);
        in.close();
        return bout.toByteArray();
    }
}
