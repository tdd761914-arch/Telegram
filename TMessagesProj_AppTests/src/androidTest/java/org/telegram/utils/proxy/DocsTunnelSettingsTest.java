package org.telegram.utils.proxy;

import android.content.SharedPreferences;
import android.net.Uri;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DocsTunnelSettingsTest {
    private static final String DOCUMENT = "https://docs.example.org/document?id=a%2Fb&title=Привет+world#part";
    private static final String KEY = "Key +%&?#=0123456789";

    @Test public void bothTypesRoundTripThroughTelegramLinksWithoutDoubleDecoding() {
        for (ProxySettings.Type type : new ProxySettings.Type[]{ProxySettings.Type.YANDEX_DOCS, ProxySettings.Type.MAILRU_DOCS}) {
            ProxySettings original = ProxySettings.builder().setType(type).setAddress(DOCUMENT).setSecret(KEY).build();
            assertTrue(original.isValid());
            assertTrue(original.getLink().startsWith("tg://proxy?type="));
            ProxySettings parsed = ProxySettings.fromUri(Uri.parse(original.getLink()));
            assertEquals(original, parsed);
            assertEquals(DOCUMENT, parsed.getAddress());
            assertEquals(KEY, parsed.getSecret());
            assertEquals(0, parsed.getPort());
        }
    }

    @Test public void invalidTunnelLinksNeverBecomeMtprotoProxies() {
        assertNull(ProxySettings.fromUri(Uri.parse("tg://proxy?type=yandexdocs&server=https%3A%2F%2Fdocs.example.org&secret=short")));
        assertNull(ProxySettings.fromUri(Uri.parse("tg://proxy?type=mailrudocs&server=http%3A%2F%2Fdocs.example.org")));
        assertNull(ProxySettings.fromUri(Uri.parse("tg://proxy?type=unknown&server=example.org&port=443&secret=0123456789abcdef")));
        assertNull(ProxySettings.fromUri(Uri.parse("openflux://unsupported")));
    }

    @Test public void emptyAndSixteenCharacterKeysMatchCoreValidation() {
        for (String secret : new String[]{"", "0123456789abcdef"}) {
            assertTrue(ProxySettings.builder().setType(ProxySettings.Type.MAILRU_DOCS).setAddress(DOCUMENT).setSecret(secret).build().isValid());
        }
        assertFalse(ProxySettings.builder().setType(ProxySettings.Type.YANDEX_DOCS).setAddress(DOCUMENT).setSecret("0123456789abcde").build().isValid());
    }

    @Test public void persistentTypesAndSwitchingBackToSocksPreserveCredentials() {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (p, m, a) -> {
            if (m.getName().startsWith("put")) { values.put((String) a[0], a[1]); return p; }
            if (m.getName().equals("remove")) { values.remove(a[0]); return p; }
            if (m.getName().equals("commit")) return true;
            return null;
        });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (p, m, a) -> values.containsKey(a[0]) ? values.get(a[0]) : a[1]);
        for (ProxySettings.Type type : new ProxySettings.Type[]{ProxySettings.Type.YANDEX_DOCS, ProxySettings.Type.MAILRU_DOCS}) {
            ProxySettings settings = ProxySettings.builder().setType(type).setAddress(DOCUMENT).setSecret(KEY).build();
            settings.toSharedPreferences(editor);
            assertEquals(settings, ProxySettings.fromSharedPreferences(prefs));
            assertEquals(type, ProxySettings.intToType(ProxySettings.typeToInt(type)));
        }
        ProxySettings socks = ProxySettings.builder().setType(ProxySettings.Type.SOCKS5).setAddress("127.0.0.1").setPort(1080).setUser("user").setPassword("pass").build();
        socks.toSharedPreferences(editor);
        assertEquals(socks, ProxySettings.fromSharedPreferences(prefs));
        assertFalse(values.containsKey("proxy_secret"));
    }

    @Test public void existingSocksMtprotoAndWebLinksRemainReadable() {
        assertEquals(ProxySettings.Type.SOCKS5, ProxySettings.fromUri(Uri.parse("tg://socks?server=localhost&port=1080")).getType());
        assertEquals(ProxySettings.Type.MTPROTO, ProxySettings.fromUri(Uri.parse("tg://proxy?server=localhost&port=443&secret=0123456789abcdef")).getType());
        ProxySettings web = ProxySettings.builder().setType(ProxySettings.Type.WEB).setAddress("proxy.example.org").setSecret("0123456789abcdef0123456789abcdef").build();
        assertEquals(web, ProxySettings.fromUri(Uri.parse(web.getLink())));
    }
}
