package com.nesp.android.clingdemo;

import fi.iki.elonen.NanoHTTPD;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

public class LocalHttpServer extends NanoHTTPD {

    private Context context;
    private String rootPath;

    public LocalHttpServer(Context context, int port, String rootPath) {
        super(port);
        this.context = context;
        this.rootPath = rootPath;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            // 构建完整的文件路径
            File file = new File(rootPath, uri);
            if (file.exists() && !file.isDirectory()) {
                String mimeType = getMimeType(uri);
                InputStream inputStream = new FileInputStream(file);
                return newChunkedResponse(Response.Status.OK, mimeType, inputStream);
            } else if (file.isDirectory()) {
                return listDirectory(file);
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found");
            }
        } catch (FileNotFoundException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "File error");
        }
    }

    private Response listDirectory(File dir) {
        String[] files = dir.list();
        if (files == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found");
        }
        StringBuilder response = new StringBuilder("<html><body>");
        response.append("<h1>Directory: ").append(dir.getName()).append("</h1>");
        response.append("<hr>");
        for (String file : files) {
            response.append("<a href=\"").append(file).append("\">").append(file).append("</a><br>");
        }
        response.append("<hr></body></html>");
        return newFixedLengthResponse(response.toString());
    }

    private String getMimeType(String fileName) {
        if (fileName.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else {
            return "application/octet-stream";
        }
    }
}