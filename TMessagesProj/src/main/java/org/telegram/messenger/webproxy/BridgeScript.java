/*
 * Document-start script injected into the WEB proxy bridge page.
 *
 * Defines the window.TelegramWebProxy object contract used by the
 * relay bridge page (identical to Telegram Desktop's bridge script);
 * the native transport (base64 binary 'b', JSON control 'c', write
 * acknowledgements 'a' and heartbeats 'h') goes through the
 * redogramWebProxyBridge JavascriptInterface.
 */
package org.telegram.messenger.webproxy;

public final class BridgeScript {

    public static final String SCRIPT =
        "((()=>{" +
        "if(window!==window.top||Object.prototype.hasOwnProperty.call(window,'TelegramWebProxy'))return;" +
        "let receiver=null;" +
        "const send=value=>redogramWebProxyBridge.invoke(value);" +
        "const encode=value=>{" +
        " const bytes=new Uint8Array(value),parts=[];" +
        " for(let i=0;i<bytes.length;i+=32768)parts.push(String.fromCharCode.apply(null,bytes.subarray(i,i+32768)));" +
        " return btoa(parts.join(''));" +
        "};" +
        "const decode=value=>{" +
        " const binary=atob(value),bytes=new Uint8Array(binary.length);" +
        " for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);" +
        " return bytes.buffer;" +
        "};" +
        "const bridge={" +
        " postMessage(value){" +
        "  if(value instanceof ArrayBuffer)send('b'+encode(value));" +
        "  else if(typeof value==='string')send('c'+value);" +
        "  else send('f');" +
        " }," +
        " receive(sequence,value){" +
        "  try{" +
        "   if(typeof receiver!=='function')throw new Error();" +
        "   receiver({data:decode(value)});" +
        "   send('a'+sequence);" +
        "  }catch(error){send('f')}" +
        " }," +
        " receiveControl(sequence,value){" +
        "  try{" +
        "   if(typeof receiver!=='function')throw new Error();" +
        "   receiver({data:value});" +
        "   send('a'+sequence);" +
        "  }catch(error){send('f')}" +
        " }," +
        " get onmessage(){return receiver}," +
        " set onmessage(value){receiver=typeof value==='function'?value:null}" +
        "};" +
        "Object.defineProperty(window,'TelegramWebProxy',{" +
        " value:Object.freeze(bridge),configurable:false,writable:false" +
        "});" +
        "send('h');" +
        "})())";

    private BridgeScript() {}
}
