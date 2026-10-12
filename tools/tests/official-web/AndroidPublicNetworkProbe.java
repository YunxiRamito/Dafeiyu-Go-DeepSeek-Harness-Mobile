package com.dafeiyu.mobile.qa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.NodeList;

/** Standalone Android shell probe, without an app install, credentials or model calls. */
public final class AndroidPublicNetworkProbe {
    public static void main(String[] arguments) {
        boolean hostProxy = arguments.length > 0 && arguments[0].equals("--host-proxy");
        System.out.println("ENVIRONMENT Android app_process shell; not application UI");
        System.out.println("TRANSPORT " + (hostProxy ? "explicit emulator host proxy 10.0.2.2:7890" : "direct HTTPS"));
        probe("BingRss", "https://www.bing.com/search?format=rss&q=Kotlin%20official%20documentation", true, hostProxy);
        probe("DdgHtml", "https://html.duckduckgo.com/html/?q=Kotlin%20official%20documentation", false, hostProxy);
        probe("DdgLite", "https://lite.duckduckgo.com/lite/?q=Kotlin%20official%20documentation", false, hostProxy);
        probe("Page", "https://kotlinlang.org/docs/home.html", false, hostProxy);
    }

    private static void probe(String name, String address, boolean rss, boolean hostProxy) {
        long start = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            Proxy proxy = hostProxy
                ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("10.0.2.2", 7890)) : Proxy.NO_PROXY;
            connection = (HttpURLConnection) url.openConnection(proxy);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Dafeiyu-Go-Android-Network-QA/1.0");
            int status = connection.getResponseCode();
            System.out.println("HTTP " + name + " " + status + " " + connection.getContentType());
            if (status >= 300 && status <= 399)
                System.out.println("REDIRECT " + name + " " + connection.getHeaderField("Location"));
            if (status != 200) throw new IllegalStateException("non-success HTTP status");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 512 * 1024) throw new IllegalStateException("response byte cap");
                    bytes.write(buffer, 0, count);
                }
            }
            String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (rss) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                String upper = text.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
                    throw new IllegalStateException("unsafe XML declaration");
                factory.setExpandEntityReferences(false);
                NodeList items = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(bytes.toByteArray())).getElementsByTagName("item");
                if (items.getLength() == 0) throw new IllegalStateException("no RSS results");
                for (int index = 0; index < Math.min(3, items.getLength()); index++) {
                    org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(index);
                    System.out.println("SOURCE " + item.getElementsByTagName("title").item(0).getTextContent()
                        + " | " + item.getElementsByTagName("link").item(0).getTextContent());
                }
            } else if (name.equals("Page")) {
                if (!text.contains("Kotlin") || !text.contains("<title>"))
                    throw new IllegalStateException("unexpected documentation content");
                System.out.println("SOURCE Kotlin documentation | " + address);
            } else {
                boolean results = text.contains("result__a") || text.contains("result-link");
                if (!results) throw new IllegalStateException("no structured DDG links; possible challenge");
                System.out.println("RESULT_MARKUP " + name + " present");
            }
            System.out.println("PASS " + name + " bytes=" + bytes.size());
        } catch (Exception failure) {
            System.out.println("FAIL " + name + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            System.out.println("ELAPSED " + name + " " + ((System.nanoTime() - start) / 1_000_000) + "ms");
        }
    }
}
