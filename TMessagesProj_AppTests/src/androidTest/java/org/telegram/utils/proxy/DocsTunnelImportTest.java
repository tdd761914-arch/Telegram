package org.telegram.utils.proxy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.ProxyListImporter;
import org.telegram.messenger.SharedConfig;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DocsTunnelImportTest {
    private ArrayList<SharedConfig.ProxyInfo> original;

    @Before public void saveList() {
        SharedConfig.loadProxyList();
        original = new ArrayList<>(SharedConfig.proxyList);
        SharedConfig.proxyList.clear();
    }

    @After public void restoreList() {
        SharedConfig.proxyList.clear();
        SharedConfig.proxyList.addAll(original);
        SharedConfig.saveProxyList();
    }

    private static String subscription() throws Exception {
        String key = "secret+%&0123456789";
        String yandex = "https://docs.yandex.ru/docs/view?url=abc%2Fdef&name=a+b";
        String mailru = "https://cloud.mail.ru/public/test/document";
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("type", "socks5").put("server", "localhost").put("port", 1080))
                .put(new JSONObject().put("type", "mtproto").put("server", "localhost").put("secret", "0123456789abcdef"))
                .put(new JSONObject().put("type", "web").put("server", "proxy.example.org").put("secret", "0123456789abcdef0123456789abcdef"))
                .put(new JSONObject().put("type", "YandexDocs Tunnel").put("server", yandex).put("secret", key))
                .put(new JSONObject().put("protocol", "mailru-docs").put("url", mailru).put("key", key));
        rows.put(ProxySettings.builder().setType(ProxySettings.Type.YANDEX_DOCS).setAddress(yandex).setSecret(key).build().getLink());
        rows.put(new JSONObject().put("link", ProxySettings.builder().setType(ProxySettings.Type.MAILRU_DOCS).setAddress(mailru).setSecret(key).build().getLink()));
        rows.put(new JSONObject().put("type", "yandexdocs").put("server", yandex).put("secret", "short"));
        rows.put("tg://proxy?type=mailrudocs&server=https%3A%2F%2Fcloud.mail.ru%2Ftest&secret=short");
        return new JSONObject().put("proxies", rows).toString();
    }

    private static void verify(ProxyListImporter.Result result) {
        assertNotNull(result);
        assertEquals(5, result.proxiesAdded);
        assertEquals(2, result.proxiesSkipped);
        assertEquals(2, result.proxiesInvalid);
        assertEquals(0, result.linksAdded);
        assertEquals(5, SharedConfig.proxyList.size());
        assertEquals(1, SharedConfig.proxyList.stream().filter(p -> p.settings.getType() == ProxySettings.Type.YANDEX_DOCS).count());
        assertEquals(1, SharedConfig.proxyList.stream().filter(p -> p.settings.getType() == ProxySettings.Type.MAILRU_DOCS).count());
    }

    @Test public void mixedJsonPreservesTypesAndSkipsDuplicatesAndInvalidRows() throws Exception {
        verify(ProxyListImporter.parseAndImport(subscription()));
    }

    @Test public void remoteSubscriptionUsesTheSameMixedProtocolImporter() throws Exception {
        byte[] body = subscription().getBytes(StandardCharsets.UTF_8);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ProxyListImporter.Result> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            Thread responder = new Thread(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(10000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);
                } catch (Exception e) { error.set(e.toString()); done.countDown(); }
            });
            responder.start();
            ProxyListImporter.importFromUrl("http://127.0.0.1:" + server.getLocalPort() + "/proxies.json", (r, e) -> {
                result.set(r); if (e != null) error.set(e); done.countDown();
            });
            assertTrue("subscription callback", done.await(20, TimeUnit.SECONDS));
            responder.join(10000);
            assertNull(error.get());
            verify(result.get());
        }
    }
}
