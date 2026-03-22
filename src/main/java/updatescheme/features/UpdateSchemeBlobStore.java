package updatescheme.features;

import arc.func.Cons;
import arc.util.Http;
import arc.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class UpdateSchemeBlobStore {

    private static final String uploadEndpoint = "https://0x0.st";
    private static final String userAgent = "UpdateScheme/2.0.0";

    private UpdateSchemeBlobStore() {
    }

    static void uploadText(String text, Cons<String> onUrl, Cons<String> onError) {
        new Thread(() -> {
            try {
                String uploaded = uploadTextNow(text == null ? "" : text);
                if (onUrl != null) onUrl.get(uploaded);
            } catch (Throwable t) {
                Log.warn("UpdateScheme: blob upload failed", t);
                if (onError != null) onError.get(errorName(t));
            }
        }, "UpdateScheme-blob-upload").start();
    }

    static void downloadText(String url, Cons<String> onText, Cons<String> onError) {
        if (url == null || url.trim().isEmpty()) {
            if (onError != null) onError.get("invalid-url");
            return;
        }

        Http.get(url.trim())
            .timeout(25_000)
            .header("User-Agent", userAgent)
            .header("Accept", "text/plain,*/*")
            .error(t -> {
                if (onError != null) onError.get(errorName(t));
            })
            .submit(res -> {
                if (onText != null) onText.get(res.getResultAsString());
            });
    }

    private static String uploadTextNow(String text) throws Exception {
        String boundary = "----UpdateScheme" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(uploadEndpoint).openConnection();
        conn.setConnectTimeout(25_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept", "text/plain,*/*");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream out = conn.getOutputStream()) {
            writeFormField(out, boundary, "secret", "1");
            writeFileField(out, boundary, "file", "updatescheme.txt", text);
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String body = readBody(code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new RuntimeException("blob-upload-http-" + code + ":" + body);
        }

        String uploaded = body == null ? "" : body.trim();
        if (uploaded.isEmpty() || !(uploaded.startsWith("http://") || uploaded.startsWith("https://"))) {
            throw new RuntimeException("blob-upload-invalid-response");
        }
        return uploaded;
    }

    private static void writeFormField(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileField(OutputStream out, String boundary, String name, String filename, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = stream.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String errorName(Throwable error) {
        if (error == null) return "error";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message == null ? "error" : message.trim();
    }
}
