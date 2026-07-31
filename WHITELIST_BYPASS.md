# Embedded Whitelist Bypass Joiner

The **Whitelists** screen under **Settings → Data and Storage** embeds the
headless Joiner from [kulikov0/whitelist-bypass](https://github.com/kulikov0/whitelist-bypass).

The bundled relay executables were built from commit
`d470aaee9b88c93791f5129826eabcef642bd05b` for Android ABIs `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64`. The source project is MIT licensed; its
license is included in `TMessagesProj/src/main/assets/whitelist-bypass-LICENSE.txt`.

To refresh the binaries from a sibling checkout:

```sh
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go -C ../whitelist-bypass/relay build -trimpath -ldflags="-s -w" -o ../../Telegram/TMessagesProj/jni/arm64-v8a/libwhitelist_relay.so .
CGO_ENABLED=0 GOOS=linux GOARCH=arm go -C ../whitelist-bypass/relay build -trimpath -ldflags="-s -w" -o ../../Telegram/TMessagesProj/jni/armeabi-v7a/libwhitelist_relay.so .
CGO_ENABLED=0 GOOS=linux GOARCH=386 go -C ../whitelist-bypass/relay build -trimpath -ldflags="-s -w" -o ../../Telegram/TMessagesProj/jni/x86/libwhitelist_relay.so .
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go -C ../whitelist-bypass/relay build -trimpath -ldflags="-s -w" -o ../../Telegram/TMessagesProj/jni/x86_64/libwhitelist_relay.so .
```

Creator supplies one invite link or room identifier per Joiner. The app starts
the embedded relay, points Telegram MTProto connections and in-process WebViews
at its loopback SOCKS5 listener, then restores the user's prior Telegram proxy
and clears the WebView override when the session stops.
